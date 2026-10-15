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

-- Finds Folio on the local network. The phone proves it knows the login key by answering
-- MD5(key .. nonce); answers without that proof are ignored, so the key is never sent to an
-- impostor. Returns "http://ip:port" or nil.
function Link.discover(cfg, d)
    assert(cfg.key, "Log in to Progress sync first")
    local nonce = d.nonce()
    local expected = d.md5(cfg.key .. nonce)
    for _, reply in ipairs(d.broadcast("FOLIO-DISCOVER " .. nonce) or {}) do
        local port, api, proof = (reply.text or ""):match("^FOLIO (%d+) (%d+) (%x+)$")
        if port and tonumber(api) == Link.API and expected and proof == expected
            and type(reply.ip) == "string" and reply.ip:match("^%d+%.%d+%.%d+%.%d+$") then
            return "http://" .. reply.ip .. ":" .. port
        end
    end
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
    assert(cfg.username and cfg.key and cfg.device, "Log in to Progress sync first")
    local headers = Link.headers(cfg)
    local function hello(server)
        if not server or server == "" then return nil end
        local ok, status, text = pcall(d.request, "GET", Link.url(server, "/folio/v1/hello"), headers)
        return ok and status == 200 and text or nil
    end
    local text = hello(cfg.server)
    if not text and d.broadcast then
        -- The phone's address may have changed: look for it, then remember where it is.
        local found = Link.discover(cfg, d)
        text = found and found ~= strip(cfg.server) and hello(found)
        if text then
            cfg.server = found
            if d.save_server then d.save_server(found) end
        end
    end
    assert(text, "Librillo did not answer. Is Librillo Link on and the phone on the same network?")
    local status
    local hello = d.decode(text)
    assert(hello and hello.api == Link.API, "Update Librillo or the Librillo plugin so they match")

    status, text = d.request("GET", Link.url(cfg.server, "/folio/v1/queue"), headers)
    assert(status == 200, "Librillo did not send the list of books")
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
            assert(acked == 200, "Librillo did not confirm")
            result[outcome] = result[outcome] + 1
        end)
        if not ok then result.failed = result.failed + 1 end
    end
    return result
end

return Link
