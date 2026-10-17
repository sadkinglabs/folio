-- SPDX-License-Identifier: AGPL-3.0-or-later
-- KOReader's reading time, sent to Librillo after a confirmed sync (docs/SYNC_PROTOCOL.md,
-- /folio/v2/statistics). Independently written; no KOReader source copied.
--
-- Version 1 sends totals: seconds read in all and per day. Version 2 sends reading sessions
-- (docs/STATS_DESIGN.md, a3 and a4), worked out here from KOReader's page rows, because a full
-- history of page rows is too large to send. The steps are a port of KoreaderSessions.derive
-- (core), checked against the same cases (scripts/check-plugin.py).
--
-- The statistics database is only read, in one read-only transaction, the way the first plugin's
-- export read it; it is never written. Only sessions leave the e-reader: a book's partial MD5,
-- times and page fractions. For a book the phone doesn't know, its title and authors too, so the
-- Stats tab can name it. Never book content. A missing or unreadable database, or a phone that
-- doesn't answer, sends nothing and never fails the sync.
local Stats = {
    FORMAT = "folio-statistics",
    VERSION = 1,
    SESSIONS_VERSION = 2,
    -- A year and a day, newest first: what Librillo keeps.
    MAX_DAYS = 366,
    -- Librillo's own limits, so one odd row never makes it refuse the rest.
    MAX_DAY_SECONDS = 10000000,
    MAX_SECONDS = 10000000000,
    -- Time on one page counts up to 2 minutes, as KOReader's own default.
    MAX_PAGE_SECONDS = 120,
    -- More than 5 minutes between one page's counted end and the next page ends a session.
    GAP_SECONDS = 300,
    -- Rows are re-read from this long before the phone's watermark, to finish sessions that were
    -- still growing at the last sync.
    LOOKBACK = 86400,
    -- Rows are read in windows of this many seconds, so a long history is never all in memory.
    WINDOW = 30 * 86400,
    -- Sessions per request, and requests per sync; the rest go at the next sync.
    PER_REQUEST = 500,
    MAX_REQUESTS = 40,
    -- The longest title or authors text the phone takes, in characters (UTF-16 units).
    MAX_NAME = 512,
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

-- The request body for Librillo (version 1).
function Stats.body(cfg, d, value)
    return d.encode({
        device = { id = cfg.device, name = cfg.name or "KOReader" },
        format = Stats.FORMAT, version = Stats.VERSION,
        seconds = value.seconds, exportedAt = d.now and d.now() or os.time(),
        days = d.array(value.days),
    })
end

-- ---------------------------------------------------------------------------------------------
-- Sessions (version 2). A port of KoreaderSessions.derive; the rules are in STATS_DESIGN.md, a3.
-- ---------------------------------------------------------------------------------------------

-- Clamped to 0..1 and rounded to 6 decimals: floor(x * 1e6 + 0.5) / 1e6, as in the Kotlin.
local function round6(x)
    return math.floor(math.max(0, math.min(1, x)) * 1e6 + 0.5) / 1e6
end
Stats.round6 = round6

-- A page row as { md5, page, start, duration, total } (a table with those names, or an array in
-- that order), checked and normalised; nil when it is unusable (rule 1). Every number goes
-- through tonumber, as the database's int64 values need.
local function clean(row)
    if type(row) ~= "table" then return nil end
    local md5 = row.md5 or row[1]
    local page = tonumber(row.page or row[2])
    local start = tonumber(row.start or row[3])
    local duration = tonumber(row.duration or row[4])
    local total = tonumber(row.total or row[5]) or 0
    if type(md5) ~= "string" then return nil end
    md5 = md5:lower()
    if #md5 ~= 32 or md5:find("[^0-9a-f]") then return nil end
    -- Not a number, or NaN, which is never equal to itself.
    if not page or not start or not duration or page ~= page or start ~= start or duration ~= duration then return nil end
    if total ~= total then total = 0 end
    page, start, duration, total = math.floor(page), math.floor(start), math.floor(duration), math.floor(total)
    if page < 1 or start <= 0 or duration <= 0 then return nil end
    return { md5 = md5, page = page, start = start, duration = duration, total = math.max(0, total) }
end

local Deriver = {}
Deriver.__index = Deriver
Stats.Deriver = Deriver

-- `before`: rows (as for add) from before the cut. A book's latest one tells whether its first
-- session here began before the cut.
function Deriver.new(before)
    local self = setmetatable({ books = {}, out = {} }, Deriver)
    local latest = {}
    for _, row in ipairs(before or {}) do
        local r = clean(row)
        if r and (not latest[r.md5] or r.start > latest[r.md5].start) then latest[r.md5] = r end
    end
    for md5, r in pairs(latest) do
        self.books[md5] = { beforeEnd = r.start + math.min(r.duration, Stats.MAX_PAGE_SECONDS) }
    end
    return self
end

-- The session as it is sent; nil for one that began before the cut or counted no time.
local function wire(md5, s)
    if s.truncated or s.seconds <= 0 then return nil end
    return {
        book = md5, start = s.start, seconds = s.seconds, pages = s.pageCount,
        from = s.firstTotal > 0 and round6((s.firstPage - 1) / s.firstTotal) or nil,
        to = s.lastTotal > 0 and round6(s.lastPage / s.lastTotal) or nil,
        read = s.anyTotal and round6(s.read) or nil,
    }
end

local function close(self, md5, state)
    local s = state.session
    state.session = nil
    local done = s and wire(md5, s)
    if done then self.out[#self.out + 1] = done end
end

-- Feeds one row. Rows come in the order start, page, md5, duration (longest first).
function Deriver:add(row)
    local r = clean(row)
    if not r then return end
    local state = self.books[r.md5]
    if not state then
        state = {}
        self.books[r.md5] = state
    end
    -- The same start and page as this book's last row: they are one row, the longest, which
    -- came first.
    if state.lastStart == r.start and state.lastPage == r.page then return end
    state.lastStart, state.lastPage = r.start, r.page
    local counted = math.min(r.duration, Stats.MAX_PAGE_SECONDS)
    local s = state.session
    if not s or r.start - s.finish > Stats.GAP_SECONDS then
        close(self, r.md5, state)
        s = {
            start = r.start, finish = r.start, seconds = 0, pageCount = 0, pages = {}, counted = {},
            read = 0, anyTotal = false, firstPage = r.page, firstTotal = r.total,
            truncated = state.beforeEnd ~= nil and r.start - state.beforeEnd <= Stats.GAP_SECONDS,
        }
        state.session = s
        state.beforeEnd = nil
    end
    local stop = r.start + counted
    local from = math.max(r.start, s.finish)
    if stop > from then s.seconds = s.seconds + (stop - from) end
    s.finish = math.max(s.finish, stop)
    s.lastPage, s.lastTotal = r.page, r.total
    if not s.pages[r.page] then
        s.pages[r.page] = true
        s.pageCount = s.pageCount + 1
    end
    if r.total > 0 then
        s.anyTotal = true
        if not s.counted[r.page] then
            s.counted[r.page] = true
            s.read = s.read + 1 / r.total
        end
    end
end

-- Ends the open sessions and returns every session, sorted by start, then book.
function Deriver:finish()
    for md5, state in pairs(self.books) do close(self, md5, state) end
    table.sort(self.out, function(a, b)
        if a.start ~= b.start then return a.start < b.start end
        return a.book < b.book
    end)
    local out = self.out
    self.out = {}
    return out
end

-- Sessions from all of `events` at once: the shared cases call this. `before` holds, per book,
-- the latest row before the cut.
function Stats.derive(events, before)
    local rows = {}
    for i, event in ipairs(events or {}) do
        local r = clean(event)
        if r then
            r.index = i
            rows[#rows + 1] = r
        end
    end
    table.sort(rows, function(a, b)
        if a.start ~= b.start then return a.start < b.start end
        if a.page ~= b.page then return a.page < b.page end
        if a.md5 ~= b.md5 then return a.md5 < b.md5 end
        if a.duration ~= b.duration then return a.duration > b.duration end
        return a.index < b.index
    end)
    local deriver = Deriver.new(before)
    for _, r in ipairs(rows) do deriver:add(r) end
    return deriver:finish()
end

-- A number for SQL text. Never a string from outside.
local function num(n) return string.format("%.0f", n) end

local SELECT_BEFORE = "SELECT b.md5, p.page, MAX(p.start_time), p.duration, 0 "
    .. "FROM page_stat_data p JOIN book b ON b.id = p.id_book "
    .. "WHERE p.start_time < %s AND p.duration > 0 AND b.md5 IS NOT NULL GROUP BY b.md5;"
local SELECT_FIRST = "SELECT MIN(start_time) FROM page_stat_data WHERE start_time >= %s AND duration > 0;"
local SELECT_EVENTS = "SELECT b.md5, p.page, p.start_time, p.duration, "
    .. "CASE WHEN p.total_pages > 0 THEN p.total_pages ELSE COALESCE(b.pages, 0) END "
    .. "FROM page_stat_data p JOIN book b ON b.id = p.id_book "
    .. "WHERE p.start_time >= %s AND p.start_time < %s AND p.duration > 0 AND b.md5 IS NOT NULL "
    .. "ORDER BY p.start_time, p.page, b.md5, p.duration DESC;"
local SELECT_NAMES = "SELECT md5, title, authors FROM book WHERE md5 IS NOT NULL;"

-- lua-ljsqlite3 answers a query with one array per column; nil when there are no rows.
local function each_row(rows, visit)
    if type(rows) ~= "table" or type(rows[1]) ~= "table" then return end
    for i = 1, #rows[1] do
        visit({ rows[1][i], rows[2] and rows[2][i], rows[3] and rows[3][i], rows[4] and rows[4][i],
            rows[5] and rows[5][i] })
    end
end

-- A title or authors text the phone takes: valid UTF-8, no control characters, at most
-- MAX_NAME characters (a character outside the Basic Multilingual Plane counts as two, as the
-- phone counts them). Anything invalid is dropped, never sent.
local function text_of(value)
    if type(value) ~= "string" then return "" end
    local out, units, i, n = {}, 0, 1, #value
    while i <= n do
        local c = value:byte(i)
        local len, cost = 0, 1
        if c < 128 then
            len = 1
        elseif c >= 194 and c <= 223 then
            len = 2
        elseif c >= 224 and c <= 239 then
            len = 3
        elseif c >= 240 and c <= 244 then
            len, cost = 4, 2
        end
        local ok = len > 0 and i + len - 1 <= n
        if ok then
            for k = 1, len - 1 do
                local b = value:byte(i + k)
                if b < 128 or b > 191 then ok = false end
            end
        end
        if ok and len == 3 then
            local b = value:byte(i + 1)
            -- No overlong forms, no surrogates.
            if (c == 224 and b < 160) or (c == 237 and b > 159) then ok = false end
        elseif ok and len == 4 then
            local b = value:byte(i + 1)
            if (c == 240 and b < 144) or (c == 244 and b > 143) then ok = false end
        end
        if not ok then
            i = i + 1
        else
            local piece = value:sub(i, i + len - 1)
            -- Control characters: 0-31, 127, and U+0080 to U+009F.
            local control = (len == 1 and (c < 32 or c == 127)) or (len == 2 and c == 194 and value:byte(i + 1) < 160)
            if not control then
                if units + cost > Stats.MAX_NAME then break end
                units = units + cost
                out[#out + 1] = piece
            end
            i = i + len
        end
    end
    -- Trimmed: a title of spaces is no title.
    return (table.concat(out):match("^%s*(.-)%s*$"))
end

-- KOReader keeps several authors in one text, one per line.
local function authors_of(value)
    if type(value) ~= "string" then return "" end
    return text_of((value:gsub("[\r\n]+", ", ")))
end

-- Every session from `cut` (epoch seconds; 0 for the whole history) to now, and the names of the
-- books (md5 -> { title, authors }). Read only: one read-only transaction, in windows of
-- Stats.WINDOW, fed to one Deriver, so the result is that of a single pass without holding every
-- row. Raises when the database can't be read; the caller rolls back and closes it.
function Stats.readSessions(db, cut, now)
    cut = tonumber(cut) or 0
    now = tonumber(now) or os.time()
    db:exec("PRAGMA query_only=ON;")
    db:exec("BEGIN;")
    local before = {}
    if cut > 0 then
        each_row(db:exec(string.format(SELECT_BEFORE, num(cut))), function(row)
            before[#before + 1] = { md5 = string.lower(row[1] or ""), page = tonumber(row[2]),
                start = tonumber(row[3]), duration = tonumber(row[4]), total = tonumber(row[5]) }
        end)
    end
    local deriver = Deriver.new(before)
    local first = tonumber(db:rowexec(string.format(SELECT_FIRST, num(cut))))
    if first then
        -- A row with no real start time is never used, and needs no window of its own.
        local from = math.max(first, 1)
        while from <= now + 86400 do
            local to = from + Stats.WINDOW
            each_row(db:exec(string.format(SELECT_EVENTS, num(from), num(to))), function(row)
                deriver:add({ md5 = string.lower(row[1] or ""), page = tonumber(row[2]),
                    start = tonumber(row[3]), duration = tonumber(row[4]), total = tonumber(row[5]) })
            end)
            from = to
        end
    end
    local sessions = deriver:finish()
    -- Names, read once. A database without them still gives its sessions.
    local names = {}
    if #sessions > 0 then
        local read, rows = pcall(db.exec, db, SELECT_NAMES)
        if read then
            each_row(rows, function(row)
                local md5 = type(row[1]) == "string" and row[1]:lower() or nil
                local title = text_of(row[2])
                if md5 and (not names[md5] or names[md5].title == "") then
                    names[md5] = { title = title, authors = authors_of(row[3]) }
                end
            end)
        end
    end
    db:exec("COMMIT;")
    return sessions, names
end

local function url_of(cfg) return (cfg.server or ""):gsub("/+$", "") .. "/folio/v2/statistics" end

-- Sends the reading sessions the phone doesn't have yet. Returns true when all were sent. The
-- phone says how far it has them (`until`) and which books it knows (`known`); titles and authors
-- go only for the others.
function Stats.sendSessions(cfg, d, headers)
    if not d.open_statistics then return false end
    local url = url_of(cfg)
    local asked, status, text = pcall(d.request, "GET", url, headers)
    if not asked or status ~= 200 then return false end
    local decoded, answer = pcall(d.decode, text)
    if not decoded or type(answer) ~= "table" or type(answer["until"]) ~= "number" then return false end
    local watermark = answer["until"]
    local known = {}
    if type(answer.known) == "table" then
        for _, digest in ipairs(answer.known) do
            if type(digest) == "string" then known[digest:lower()] = true end
        end
    end
    local cut = watermark > 0 and math.max(0, watermark - Stats.LOOKBACK) or 0
    local now = d.now and d.now() or os.time()

    local opened, db = pcall(d.open_statistics)
    if not opened or not db then return false end
    local read, sessions, names = pcall(Stats.readSessions, db, cut, now)
    if not read then pcall(db.exec, db, "ROLLBACK;") end
    pcall(db.close, db)
    if not read then return false end

    local sent, requests = 0, 0
    while sent < #sessions do
        if requests >= Stats.MAX_REQUESTS then return false end
        local chunk, books, count = {}, {}, 0
        for i = sent + 1, math.min(sent + Stats.PER_REQUEST, #sessions) do
            local s = sessions[i]
            chunk[#chunk + 1] = s
            local name = names[s.book]
            if not known[s.book] and name and name.title ~= "" and not books[s.book] then
                books[s.book] = { title = name.title, authors = name.authors }
                count = count + 1
            end
        end
        local body = {
            device = { id = cfg.device, name = cfg.name or "KOReader" },
            format = Stats.FORMAT, version = Stats.SESSIONS_VERSION, exportedAt = now,
            sessions = d.array(chunk),
            books = count > 0 and books or nil,
        }
        local posted, code = pcall(d.request, "POST", url, headers, d.encode(body))
        if not posted or code ~= 200 then return false end
        requests = requests + 1
        sent = sent + #chunk
    end
    return true
end

-- Sends the reading time to Librillo. Returns true when Librillo kept it. Never raises: reading
-- time is extra, and a sync that worked has worked.
function Stats.send(cfg, d, headers)
    local ok, sent = pcall(function()
        if not d.open_statistics then return false end
        local value = Stats.collect(d)
        if not value then return false end
        local status = d.request("POST", url_of(cfg), headers, Stats.body(cfg, d, value))
        -- The sessions follow the totals; a phone without them answers 404 and gets the totals only.
        if status == 200 then pcall(Stats.sendSessions, cfg, d, headers) end
        return status == 200
    end)
    return ok and sent == true
end

return Stats
