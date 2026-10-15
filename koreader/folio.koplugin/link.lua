-- SPDX-License-Identifier: AGPL-3.0-or-later
-- Folio Link from the e-reader side: fetch the books Folio queued for this device.
-- Network and file access are passed in, so the logic runs (and is tested) outside KOReader.
local Link = { API = 1 }

local function strip(server) return (server or ""):gsub("/+$", "") end

function Link.url(server, path) return strip(server) .. path end

-- The same login KOReader's Progress sync uses: Folio Link serves both.
function Link.headers(cfg)
    return {
        ["Accept"] = "application/json",
        ["x-auth-user"] = cfg.username,
        ["x-auth-key"] = cfg.key,
        ["x-folio-device"] = cfg.device,
    }
end

-- A name that is free in the folder: "Name.epub", then "Name (2).epub", ...
function Link.unique(folder, filename, exists)
    local path = folder .. "/" .. filename
    if not exists(path) then return path end
    local stem = filename:gsub("%.epub$", "")
    for n = 2, 99 do
        path = folder .. "/" .. stem .. " (" .. n .. ").epub"
        if not exists(path) then return path end
    end
    error("Too many books with this name")
end

local function valid(book)
    return type(book) == "table"
        and type(book.id) == "string" and book.id:match("^[%w%-]+$") and #book.id <= 64
        and type(book.filename) == "string" and book.filename:match("^[^/\\]+%.epub$")
        and not book.filename:find("%.%.") and #book.filename <= 130
        and type(book.sha256) == "string" and book.sha256:match("^%x+$") and #book.sha256 == 64
end

-- Returns { fetched, already, failed } counts. Raises only when Folio cannot be reached or
-- speaks another version; a failed book is skipped and fetched again next time.
function Link.fetch(cfg, d)
    assert(cfg.server and cfg.server ~= "", "Set up Progress sync with Folio Link first")
    assert(cfg.username and cfg.key and cfg.device, "Log in to Progress sync first")
    local headers = Link.headers(cfg)
    local status, text = d.request("GET", Link.url(cfg.server, "/folio/v1/hello"), headers)
    assert(status == 200, "Folio did not answer. Is Folio Link on and the phone nearby?")
    local hello = d.decode(text)
    assert(hello and hello.api == Link.API, "Update Folio or the Folio plugin so they match")

    status, text = d.request("GET", Link.url(cfg.server, "/folio/v1/queue"), headers)
    assert(status == 200, "Folio did not send the list of books")
    local queue = d.decode(text)
    local result = { fetched = 0, already = 0, failed = 0 }
    for _, book in ipairs(queue and queue.books or {}) do
        local ok = pcall(function()
            assert(valid(book), "Invalid book entry")
            local target = cfg.folder .. "/" .. book.filename
            local outcome = "fetched"
            if d.exists(target) and d.sha256(target) == book.sha256 then
                outcome = "already"
            else
                -- Never replace a file that is already there; save beside it instead.
                if d.exists(target) then target = Link.unique(cfg.folder, book.filename, d.exists) end
                local part = target .. ".part"
                local got = d.download(Link.url(cfg.server, "/folio/v1/books/" .. book.id .. ".epub"), headers, part)
                local hash = got == 200 and d.sha256(part)
                if got ~= 200 or (hash and hash ~= book.sha256) or (not hash and d.size(part) ~= book.size) then
                    d.remove(part)
                    error("Download did not match")
                end
                assert(d.rename(part, target), "Could not save the book")
            end
            local acked = d.request("POST", Link.url(cfg.server, "/folio/v1/ack"), headers,
                d.encode({ id = book.id, sha256 = book.sha256, path = target }))
            assert(acked == 200, "Folio did not confirm")
            result[outcome] = result[outcome] + 1
        end)
        if not ok then result.failed = result.failed + 1 end
    end
    return result
end

return Link
