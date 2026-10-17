-- SPDX-License-Identifier: AGPL-3.0-or-later
-- KOReader's reading time, sent to Librillo after a confirmed sync (docs/SYNC_PROTOCOL.md,
-- POST /folio/v2/statistics). Independently written; no KOReader source copied.
--
-- The statistics database is only read, in one read-only transaction, the way the first plugin's
-- export read it. Only totals leave the e-reader: seconds read in all and per day. No titles, no
-- book content. A missing or unreadable database, or a phone that doesn't answer, sends nothing
-- and never fails the sync.
local Stats = {
    FORMAT = "folio-statistics",
    VERSION = 1,
    -- A year and a day, newest first: what Librillo keeps.
    MAX_DAYS = 366,
    -- Librillo's own limits, so one odd row never makes it refuse the rest.
    MAX_DAY_SECONDS = 10000000,
    MAX_SECONDS = 10000000000,
}

local TOTAL = "SELECT COALESCE(SUM(duration),0) FROM page_stat_data WHERE duration>=0;"
local DAYS = "SELECT strftime('%Y-%m-%d',start_time,'unixepoch','localtime') AS day, SUM(duration) AS seconds "
    .. "FROM page_stat_data WHERE duration>=0 GROUP BY day ORDER BY day DESC LIMIT " .. Stats.MAX_DAYS .. ";"

local function whole(value, max)
    value = math.floor(tonumber(value) or -1)
    if value < 0 or value > max then return nil end
    return value
end

-- Reads the totals from an open statistics database (KOReader's lua-ljsqlite3 connection, or any
-- object with the same exec and rowexec). Returns { seconds, days = { { date, seconds } } }.
-- Raises when the database can't be read; the caller closes it.
function Stats.read(db)
    db:exec("PRAGMA query_only=ON;")
    db:exec("BEGIN;")
    local seconds = whole(db:rowexec(TOTAL), Stats.MAX_SECONDS)
    assert(seconds, "Reading time out of range")
    local rows = db:exec(DAYS)
    db:exec("COMMIT;")
    local days = {}
    if rows then
        for i, day in ipairs(rows[1] or {}) do
            local value = whole(rows[2] and rows[2][i], Stats.MAX_DAY_SECONDS)
            -- A row without a usable date or time is left out, never sent as is.
            if type(day) == "string" and day:match("^%d%d%d%d%-%d%d%-%d%d$") and value then
                days[#days + 1] = { date = day, seconds = value }
            end
            if #days >= Stats.MAX_DAYS then break end
        end
    end
    return { seconds = seconds, days = days }
end

-- The statistics, or nil when there are none to send. `d.open_statistics()` opens the database,
-- or returns nil when it doesn't exist.
function Stats.collect(d)
    local ok, db = pcall(d.open_statistics)
    if not ok or not db then return nil end
    local read, value = pcall(Stats.read, db)
    if not read then pcall(db.exec, db, "ROLLBACK;") end
    pcall(db.close, db)
    if read then return value end
    return nil
end

-- The request body for Librillo.
function Stats.body(cfg, d, value)
    return d.encode({
        device = { id = cfg.device, name = cfg.name or "KOReader" },
        format = Stats.FORMAT, version = Stats.VERSION,
        seconds = value.seconds, exportedAt = d.now and d.now() or os.time(),
        days = d.array(value.days),
    })
end

-- Sends the reading time to Librillo. Returns true when Librillo kept it. Never raises: reading
-- time is extra, and a sync that worked has worked.
function Stats.send(cfg, d, headers)
    local ok, sent = pcall(function()
        if not d.open_statistics then return false end
        local value = Stats.collect(d)
        if not value then return false end
        local url = (cfg.server or ""):gsub("/+$", "") .. "/folio/v2/statistics"
        local status = d.request("POST", url, headers, Stats.body(cfg, d, value))
        return status == 200
    end)
    return ok and sent == true
end

return Stats
