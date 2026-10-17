-- SPDX-License-Identifier: AGPL-3.0-or-later
-- Librillo sync (protocol v2) from the e-reader's side; docs/SYNC_PROTOCOL.md is the contract.
-- KOReader, the network and the file system are passed in as `d`, so the logic runs (and is
-- tested) outside KOReader. Independently written; no KOReader source copied.
--
-- One sync: say hello, send the manifest (every book file under KOReader's home folder with its
-- status, highlights and collections), run the actions Librillo sends back in order, then report
-- each result. Librillo changes nothing on the phone until that report arrives.
local Sync = {
    API = 2,
    ROOT = "Librillo",
    -- Hidden, so the manifest never lists it. Deleted books wait here until Librillo confirms.
    TRASH = ".librillo/trash",
    PARTS = ".librillo/parts",
    MAX_FILES = 50000,
    MAX_DEPTH = 12,
}

local FORMATS = { epub = true, pdf = true, cbz = true, cbr = true }
local TO_KOREADER = { reading = "reading", finished = "complete", abandoned = "abandoned" }
local FROM_KOREADER = {
    reading = "reading", complete = "finished", finished = "finished",
    abandoned = "abandoned", ["on hold"] = "abandoned",
}
local KINDS = { highlight = true, note = true, bookmark = true }
local DATETIME = "^%d%d%d%d%-%d%d%-%d%d %d%d:%d%d:%d%d$"

-- Errors meant for the e-reader's screen: plain words, no file position.
local function fail(text) error(text, 0) end

local function str(value) return type(value) == "string" and value or "" end

local function nonempty(value)
    if type(value) == "string" and value ~= "" then return value end
    return nil
end

local function strip(server) return (server or ""):gsub("/+$", "") end

-- A colour or drawing style as both sides keep it: lower case, short, else nil.
local function style_word(value)
    if type(value) ~= "string" or value == "" or #value > 32 or value:find("%c") then return nil end
    return value:lower()
end

-- When the note, colour or style last changed: KOReader's own time, if it has one.
local function updated_of(item)
    local value = item.datetime_updated
    if type(value) == "string" and value:match(DATETIME) then return value end
    return ""
end

local function sha256_hex(value)
    return type(value) == "string" and #value == 64 and value:match("^[0-9a-f]+$") ~= nil
end

function Sync.url(server, path) return strip(server) .. path end

function Sync.headers(cfg)
    return {
        ["Accept"] = "application/json",
        ["x-auth-user"] = cfg.username,
        ["x-auth-key"] = cfg.key,
        ["x-folio-device"] = cfg.device,
    }
end

-- The book format of a file name, or nil for anything Librillo does not sync.
function Sync.format(name)
    local ext = type(name) == "string" and name:match("%.([^./]+)$")
    ext = ext and ext:lower()
    return ext and FORMATS[ext] and ext or nil
end

-- The same check the phone makes: relative, "/" separators, no empty, "." or ".." parts, no
-- backslash or control characters, at most 1024 bytes. A file failing it is left out of the
-- manifest, since one bad path would make the phone refuse the whole list.
function Sync.relative_ok(path)
    if type(path) ~= "string" or path == "" or #path > 1024 then return false end
    if path:sub(1, 1) == "/" or path:find("\\", 1, true) or path:find("%c") then return false end
    for part in (path .. "/"):gmatch("([^/]*)/") do
        if part == "" or part == "." or part == ".." then return false end
    end
    return true
end

-- True when any part of the path starts with a dot: such places are never synced.
function Sync.hidden(path)
    return ("/" .. path):find("/%.") ~= nil
end

-- The library folder, as a plain relative path ("Librillo", "Books/Librillo"), or nil.
function Sync.valid_root(root)
    if type(root) ~= "string" then return nil end
    root = root:gsub("^%s+", ""):gsub("%s+$", ""):gsub("/+$", "")
    if #root > 200 or not Sync.relative_ok(root) or Sync.hidden(root) then return nil end
    return root
end

local function parent(path) return path:match("^(.*)/[^/]+$") or "" end

-- Creates the folders of a relative path under home, one level at a time.
local function make_dirs(home, rel_dir, d)
    if rel_dir == "" then return end
    local current = home
    for part in rel_dir:gmatch("[^/]+") do
        current = current .. "/" .. part
        local attr = d.lstat(current)
        if not attr then
            d.mkdir(current)
            attr = d.lstat(current)
        end
        if not attr or attr.mode ~= "directory" then fail("Couldn't create a folder for the book.") end
    end
end

-- Removes the empty folders a file left behind, up to but not including `stop`.
local function prune(home, rel_dir, stop, d)
    while rel_dir ~= "" and rel_dir ~= stop and rel_dir:sub(1, #stop + 1) == stop .. "/" do
        local names = d.dir(home .. "/" .. rel_dir)
        if not names or #names > 0 then return end
        d.rmdir(home .. "/" .. rel_dir)
        rel_dir = parent(rel_dir)
    end
end

-- A name that is free: the one asked for, else "Name (2).epub", "Name (3).epub", ...
function Sync.free_name(home, rel, d)
    if not d.lstat(home .. "/" .. rel) then return rel end
    local dir, name = rel:match("^(.*/)([^/]+)$")
    dir, name = dir or "", name or rel
    local stem, ext = name:match("^(.+)(%.[^.]+)$")
    if not stem then stem, ext = name, "" end
    for n = 2, 999 do
        local candidate = dir .. stem .. " (" .. n .. ")" .. ext
        if not d.lstat(home .. "/" .. candidate) then return candidate end
    end
    fail("Too many books with this name.")
end

-- Every book file under home: EPUB, PDF, CBZ and CBR, skipping hidden folders, KOReader's .sdr
-- folders, links and the folders in `skip`. A folder that can't be read stops the sync, because
-- leaving its books out would look like they were deleted.
function Sync.walk(home, d, skip)
    local found = {}
    local skipped = {}
    for _, path in ipairs(skip or {}) do skipped[path] = true end
    local function visit(rel_dir, depth)
        local abs_dir = rel_dir == "" and home or home .. "/" .. rel_dir
        local names = d.dir(abs_dir)
        if not names then
            fail("Couldn't read the folder " .. (rel_dir == "" and home or rel_dir)
                .. ". Sync stopped so nothing is lost.")
        end
        table.sort(names)
        for _, name in ipairs(names) do
            if name:sub(1, 1) ~= "." then
                local rel = rel_dir == "" and name or rel_dir .. "/" .. name
                local abs = home .. "/" .. rel
                local attr = d.lstat(abs)
                if attr and attr.mode == "directory" then
                    if not name:lower():match("%.sdr$") and not skipped[abs] and depth < Sync.MAX_DEPTH then
                        visit(rel, depth + 1)
                    end
                elseif attr and attr.mode == "file" then
                    local format = Sync.format(name)
                    if format and Sync.relative_ok(rel) then
                        found[#found + 1] = { path = rel, abs = abs, size = attr.size,
                            mtime = attr.modification, format = format }
                        if #found > Sync.MAX_FILES then
                            fail("There are more than 50,000 books under KOReader's home folder. Sync stopped.")
                        end
                    end
                end
            end
        end
    end
    visit("", 0)
    return found
end

-- ---- KOReader's data, in the protocol's words ----

local function int_text(value)
    if value == math.floor(value) and value > -1e15 and value < 1e15 then
        return string.format("%d", value)
    end
    return tostring(value)
end

-- An annotation's place as text: an xpointer for EPUB, a page number for PDF and comics.
function Sync.anchor(value)
    if type(value) == "string" then return value end
    if type(value) == "number" then return int_text(value) end
    if type(value) == "table" and value.page ~= nil then return Sync.anchor(value.page) end
    return ""
end

function Sync.annotation_id(device, datetime, anchor, d)
    return d.sha256_text(device .. "\n" .. datetime .. "\n" .. anchor):sub(1, 32)
end

local function kind_of(item)
    if not item.drawer then return "bookmark" end
    return nonempty(item.note) and "note" or "highlight"
end

-- One annotation as the protocol sends it, every field text.
function Sync.wire(item)
    local kind = type(item.kind) == "string" and KINDS[item.kind] and item.kind or "highlight"
    return {
        id = item.id, text = str(item.text), note = str(item.note), chapter = str(item.chapter),
        datetime = str(item.datetime), pos0 = Sync.anchor(item.pos0), pos1 = Sync.anchor(item.pos1),
        kind = kind, color = style_word(item.color) or "", drawer = style_word(item.drawer) or "",
        updated = type(item.updated) == "string" and item.updated:match(DATETIME) and item.updated or "",
    }
end

-- The book's annotations in the protocol's form. Each gets a stable id: the one stored with it
-- (`librillo_id`), else first 32 hex of SHA-256 of device id, datetime and start, which is then
-- stored on the annotation. Returns the list, whether any id was new, and whether the list is
-- complete: KOReader's annotations list was there to read (a sidecar from KOReader 2024 or later).
-- Only a complete list lets Librillo take a missing annotation as deleted here. Annotations
-- Librillo sent that KOReader can't show yet (`librillo_pending`) are listed too, so they are kept.
function Sync.annotations(data, device, d)
    local out, seen, changed = {}, {}, false
    local complete = type(data.annotations) == "table"
    local list = complete and data.annotations or {}
    for _, item in ipairs(list) do
        if type(item) == "table" then
            local kind = kind_of(item)
            local start = item.pos0
            if start == nil then start = item.page end
            local anchor = Sync.anchor(start)
            local datetime = str(item.datetime)
            local id = nonempty(item.librillo_id)
            if not id or seen[id] then
                id = Sync.annotation_id(device, datetime, anchor, d)
                if seen[id] then
                    -- Two annotations made at the same place in the same second.
                    id = Sync.annotation_id(device, datetime,
                        anchor .. "\n" .. Sync.anchor(item.pos1) .. "\n" .. kind, d)
                end
                if not seen[id] then
                    item.librillo_id = id
                    changed = true
                end
            end
            if not seen[id] then
                seen[id] = true
                out[#out + 1] = {
                    id = id, text = str(item.text), note = str(item.note), chapter = str(item.chapter),
                    datetime = datetime, pos0 = anchor,
                    pos1 = item.pos1 ~= nil and Sync.anchor(item.pos1) or anchor, kind = kind,
                    color = style_word(item.color) or "", drawer = style_word(item.drawer) or "",
                    updated = updated_of(item),
                }
            end
        end
    end
    if type(data.librillo_pending) == "table" then
        for _, item in ipairs(data.librillo_pending) do
            if type(item) == "table" and nonempty(item.id) and not seen[item.id] then
                seen[item.id] = true
                out[#out + 1] = Sync.wire(item)
            end
        end
    end
    return out, changed, complete
end

-- KOReader's status in the protocol's words, and when it last changed (epoch milliseconds).
-- KOReader writes "reading" when a book is first opened; a sidecar without a status belongs to a
-- book KOReader never opened (or one Librillo marked unread).
function Sync.status(data, sidecar_mtime, d)
    local summary = type(data.summary) == "table" and data.summary or {}
    local status = FROM_KOREADER[summary.status]
    if not status then
        if summary.librillo_unread then
            status = "unread"
        elseif data.percent_finished or data.last_xpointer or data.last_page then
            status = "reading"
        else
            status = "unread"
        end
    end
    local at
    local y, m, day = str(summary.modified):match("^(%d%d%d%d)%-(%d%d)%-(%d%d)$")
    local precise = summary.librillo_status_at
    if type(precise) == "number" and y and os.date("%Y-%m-%d", math.floor(precise / 1000)) == summary.modified then
        at = precise
    elseif y then
        at = os.time({ year = tonumber(y), month = tonumber(m), day = tonumber(day), hour = 12 }) * 1000
    elseif type(sidecar_mtime) == "number" then
        at = math.floor(sidecar_mtime) * 1000
    end
    return status, at
end

-- Title and authors from KOReader's book information, when it has some.
function Sync.props(props)
    if type(props) ~= "table" then return nil, {} end
    local title = nonempty(props.title)
    local authors = {}
    if type(props.authors) == "string" then
        for line in props.authors:gmatch("[^\n]+") do
            local name = line:gsub("^%s+", ""):gsub("%s+$", "")
            if name ~= "" then authors[#authors + 1] = name end
        end
    end
    return title, authors
end

-- Writes a status Librillo sent into KOReader's summary, the way KOReader's own status buttons do.
function Sync.apply_status(data, status, now)
    data.summary = type(data.summary) == "table" and data.summary or {}
    local summary = data.summary
    if status == "unread" then
        summary.status = nil
        summary.librillo_unread = true
    else
        summary.status = TO_KOREADER[status]
        summary.librillo_unread = nil
    end
    summary.modified = os.date("%Y-%m-%d", now)
    summary.librillo_status_at = now * 1000
end

-- A KOReader annotation for one Librillo sent, or nil when KOReader couldn't show it (a PDF
-- highlight without coordinates, a missing position).
function Sync.to_koreader(item, paging, style, now)
    local datetime = str(item.datetime)
    if not datetime:match("^%d%d%d%d%-%d%d%-%d%d %d%d:%d%d:%d%d$") then
        datetime = os.date("%Y-%m-%d %H:%M:%S", now)
    end
    local base = { datetime = datetime, text = nonempty(item.text), note = nonempty(item.note),
        chapter = nonempty(item.chapter), librillo_id = item.id }
    if type(item.updated) == "string" and item.updated:match(DATETIME) then
        base.datetime_updated = item.updated
    end
    if paging then
        -- PDF and comics: a page bookmark on the right page, holding the text and the note.
        local page = tonumber(item.pos0)
        if not page or page < 1 or page ~= math.floor(page) then return nil end
        base.page = page
        return base
    end
    local pos0 = type(item.pos0) == "string" and item.pos0:sub(1, 1) == "/" and item.pos0 or nil
    if not pos0 then return nil end
    base.page = pos0
    if item.kind ~= "bookmark" then
        local pos1 = type(item.pos1) == "string" and item.pos1:sub(1, 1) == "/" and item.pos1 or pos0
        base.pos0, base.pos1 = pos0, pos1
        -- The colour and style picked on the phone, else KOReader's own default.
        base.drawer = style_word(item.drawer) or style and style.drawer or "lighten"
        base.color = style_word(item.color) or style and style.color or nil
        base.text = base.text or ""
    end
    return base
end

-- Adds the annotations whose id isn't there yet. Returns how many were new. KOReader is told the
-- list changed, so it sorts them and counts their pages the next time the book opens.
-- Librillo may hold an older copy of a note made here, imported over SSH before sync, under
-- another id. One with the same time and start as a note already here is that note: it is
-- skipped, and the note here keeps its own id.
function Sync.add_annotations(data, incoming, paging, device, d, style, now)
    local have, same = {}, {}
    for _, item in ipairs(Sync.annotations(data, device, d)) do
        have[item.id] = true
        if item.datetime ~= "" then same[item.datetime .. "\n" .. item.pos0] = true end
    end
    local list = data.annotations
    if type(list) ~= "table" then
        -- A sidecar from before KOReader 2024 (bookmarks and highlights): don't touch its format.
        if data.bookmarks == nil and data.highlight == nil then
            list = {}
            data.annotations = list
        else
            list = nil
        end
    end
    local added, shown = 0, 0
    for _, item in ipairs(incoming) do
        local key = type(item) == "table" and str(item.datetime) ~= ""
            and str(item.datetime) .. "\n" .. Sync.anchor(item.pos0) or nil
        if type(item) == "table" and type(item.id) == "string" and #item.id > 0 and #item.id <= 512
                and not have[item.id] and not (key and same[key]) then
            have[item.id] = true
            if key then same[key] = true end
            added = added + 1
            local annotation = list and Sync.to_koreader(item, paging, style, now)
            if annotation then
                list[#list + 1] = annotation
                shown = shown + 1
            else
                local wire = Sync.wire(item)
                wire.pos0, wire.pos1 = str(item.pos0), str(item.pos1)
                data.librillo_pending = type(data.librillo_pending) == "table" and data.librillo_pending or {}
                table.insert(data.librillo_pending, wire)
            end
        end
    end
    if shown > 0 then data.annotations_externally_modified = true end
    return added
end

-- Finds each annotation by its id, giving ids to the ones without one first. Returns a table from
-- id to { list = the table holding it, index = its place }.
local function by_id(data, device, d)
    Sync.annotations(data, device, d)
    local found = {}
    for _, name in ipairs({ "annotations", "librillo_pending" }) do
        local list = data[name]
        if type(list) == "table" then
            for i, item in ipairs(list) do
                local id = type(item) == "table" and (nonempty(item.librillo_id) or nonempty(item.id))
                if id and not found[id] then found[id] = { list = list, index = i, item = item } end
            end
        end
    end
    return found
end

-- Sets the note, colour, style and edit time Librillo sent, for the annotations here with those
-- ids. A bookmark never gets a drawing style (KOReader would take it for a highlight), and a blank
-- style leaves KOReader's alone. Returns how many changed.
function Sync.edit_annotations(data, incoming, device, d)
    local found = by_id(data, device, d)
    local edited = 0
    for _, wanted in ipairs(incoming) do
        local hit = type(wanted) == "table" and type(wanted.id) == "string" and found[wanted.id]
        if hit then
            local item = hit.item
            item.note = nonempty(wanted.note)
            if hit.list == data.librillo_pending then
                item.color = style_word(wanted.color) or ""
                item.drawer = style_word(wanted.drawer) or ""
                item.updated = type(wanted.updated) == "string" and wanted.updated or ""
                item.kind = item.note and "note" or (item.kind == "bookmark" and "bookmark" or "highlight")
            else
                if item.drawer then
                    item.color = style_word(wanted.color)
                    if style_word(wanted.drawer) then item.drawer = style_word(wanted.drawer) end
                end
                if type(wanted.updated) == "string" and wanted.updated:match(DATETIME) then
                    item.datetime_updated = wanted.updated
                end
            end
            edited = edited + 1
        end
    end
    if edited > 0 then data.annotations_externally_modified = true end
    return edited
end

-- Deletes the annotations with these ids: the owner deleted them on the phone, which keeps them
-- there for 30 days. Returns how many were deleted.
function Sync.delete_annotations(data, ids, device, d)
    local wanted = {}
    for _, id in ipairs(ids) do
        if type(id) == "string" and id ~= "" then wanted[id] = true end
    end
    by_id(data, device, d)
    local deleted = 0
    for _, name in ipairs({ "annotations", "librillo_pending" }) do
        local list = data[name]
        if type(list) == "table" then
            for i = #list, 1, -1 do
                local item = list[i]
                local id = type(item) == "table" and (nonempty(item.librillo_id) or nonempty(item.id))
                if id and wanted[id] then
                    table.remove(list, i)
                    deleted = deleted + 1
                end
            end
        end
    end
    if deleted > 0 then data.annotations_externally_modified = true end
    return deleted
end

-- ---- The manifest ----

-- Every book file with its hash, status, annotations and collections. Hashes are cached by path,
-- size and modification time in `state.hashes`, so files are hashed once.
function Sync.manifest(cfg, d, state, progress)
    local found = Sync.walk(cfg.home, d, cfg.skip)
    local collections = d.collections() or {}
    local open = d.open_path()
    local cache, fresh = state.hashes or {}, {}
    local files = {}
    local to_hash = 0
    for _, file in ipairs(found) do
        local hit = cache[file.path]
        if not (type(hit) == "table" and hit.size == file.size and hit.mtime == file.mtime
                and sha256_hex(hit.sha256)) then
            to_hash = to_hash + 1
        end
    end
    local hashed = 0
    for _, file in ipairs(found) do
        local hit = cache[file.path]
        local sha
        if type(hit) == "table" and hit.size == file.size and hit.mtime == file.mtime and sha256_hex(hit.sha256) then
            sha = hit.sha256
        else
            hashed = hashed + 1
            if progress and progress("Checking books: " .. hashed .. " of " .. to_hash) == false then
                fail("Sync stopped.")
            end
            sha = d.sha256(file.abs)
            if not sha256_hex(sha) then
                fail("Couldn't read " .. file.path .. ". Sync stopped so nothing is lost.")
            end
        end
        fresh[file.path] = { size = file.size, mtime = file.mtime, sha256 = sha }
        local names = {}
        for _, name in ipairs(collections[file.abs] or {}) do names[#names + 1] = name end
        table.sort(names)
        local entry = { path = file.path, size = file.size, sha256 = sha, format = file.format,
            collections = d.array(names) }
        local data = d.sidecar(file.abs)
        local annotations = {}
        if type(data) == "table" then
            entry.status, entry.statusAt = Sync.status(data, d.sidecar_mtime(file.abs), d)
            local changed, complete
            annotations, changed, complete = Sync.annotations(data, cfg.device, d)
            -- Only a list read in full can tell Librillo an annotation was deleted here; it also
            -- says this plugin takes edits and deletions. A book without notes says nothing.
            if complete then entry.annotationsComplete = true end
            if changed and file.abs ~= open then
                -- Keep the new ids with the annotations, so they never change.
                pcall(d.write_sidecar, file.abs, function(stored)
                    Sync.annotations(stored, cfg.device, d)
                end)
            end
            local title, authors = Sync.props(d.props and d.props(file.abs, data) or data.doc_props)
            entry.title = title
            entry.authors = d.array(authors)
        end
        entry.annotations = d.array(annotations)
        entry.authors = entry.authors or d.array({})
        files[#files + 1] = entry
    end
    state.hashes = fresh
    return files
end

-- Whether anything changed here since the last confirmed sync, without hashing: a book added,
-- removed or rewritten, KOReader's notes for a book (status, highlights) or its collections.
-- Each cache entry remembers how things were when Librillo last confirmed a sync. The open
-- book's notes don't count while it is open, since KOReader saves them as you read; closing it
-- counts.
function Sync.changed(cfg, d, state)
    local ok, found = pcall(Sync.walk, cfg.home, d, cfg.skip)
    if not ok then return true end
    local cache = state.hashes or {}
    local open = d.open_path()
    local seen = 0
    for _, file in ipairs(found) do
        local entry = cache[file.path]
        if type(entry) ~= "table" or not entry.synced or entry.size ~= file.size or entry.mtime ~= file.mtime then
            return true
        end
        if file.abs ~= open and (d.sidecar_mtime(file.abs) or false) ~= entry.notes then return true end
        seen = seen + 1
    end
    for _ in pairs(cache) do seen = seen - 1 end
    if seen ~= 0 then return true end
    return (d.collections_mtime and d.collections_mtime() or false) ~= state.collections_mtime
end

-- Remembers how every book looks now that Librillo confirmed the sync (see Sync.changed).
local function mark_synced(cfg, d, state)
    for path, entry in pairs(state.hashes) do
        if type(entry) == "table" then
            entry.notes = d.sidecar_mtime(cfg.home .. "/" .. path) or false
            entry.synced = true
        end
    end
    state.collections_mtime = d.collections_mtime and d.collections_mtime() or false
end

-- ---- Talking to Librillo ----

-- Says hello to Librillo, looking for it on the network when its address changed. Returns the
-- answer ({api, name, pending}). Raises a plain message when Librillo can't be used.
function Sync.connect(cfg, d)
    if not (cfg.username and cfg.key and cfg.device) then
        fail("Set up Librillo from your phone first.")
    end
    local headers = Sync.headers(cfg)
    local function hello(server)
        if type(server) ~= "string" or server == "" then return nil end
        local ok, status, text = pcall(d.request, "GET", Sync.url(server, "/folio/v2/hello"), headers)
        if not ok then return nil end
        return status, text
    end
    local status, text = hello(cfg.server)
    -- Looking for the phone takes a couple of seconds, so the regular automatic checks skip it.
    if status == nil and d.discover and cfg.discover ~= false then
        local ok, found = pcall(d.discover, cfg)
        if ok and type(found) == "string" and found ~= strip(cfg.server) then
            status, text = hello(found)
            if status ~= nil then
                cfg.server = found
                if d.save_server then d.save_server(found) end
            end
        end
    end
    if status == nil then
        fail("Librillo didn't answer. Open Librillo on your phone and join the same Wi-Fi or its hotspot.")
    elseif status == 401 then
        fail("Librillo didn't accept this e-reader's sign-in. Set it up again from your phone.")
    elseif status == 404 then
        fail("Update Librillo on your phone to sync.")
    elseif status ~= 200 then
        fail("Librillo couldn't sync right now (error " .. tostring(status) .. "). Try again.")
    end
    local ok, answer = pcall(d.decode, text)
    if not ok or type(answer) ~= "table" or answer.api ~= Sync.API then
        fail("Update Librillo or the Librillo plugin so they match.")
    end
    return answer
end

-- Whether an automatic sync should run now: never synced, the phone has changes for this
-- e-reader, or something changed here since the last sync. Raises when Librillo can't be reached.
function Sync.wants(cfg, d, state)
    local hello = Sync.connect(cfg, d)
    if not state.last_sync then return true end
    if hello.pending == true then return true end
    return Sync.changed(cfg, d, state)
end

-- ---- Running Librillo's actions ----

local function new_result()
    return { sent = 0, received = 0, updated = 0, removed = 0, failed = 0,
        failures = {}, questions = {}, new_questions = 0, phone_questions = 0 }
end

-- Moves a book file and KOReader's data for it: notes (.sdr), history and collections.
local function move_book(ctx, from_abs, to_abs, relink)
    if not ctx.d.rename(from_abs, to_abs) then fail("Couldn't move the book.") end
    pcall(ctx.d.move_sidecar, from_abs, to_abs)
    if relink then pcall(ctx.d.relink, from_abs, to_abs) end
end

local function check_closed(ctx, abs)
    local open = ctx.d.open_path()
    if open and (open == abs or open:lower() == abs:lower()) then
        fail("open on the e-reader")
    end
end

local function need_path(path)
    if not (Sync.relative_ok(path) and not Sync.hidden(path)) then fail("Invalid path.") end
    return path
end

local function need_url(url)
    if type(url) ~= "string" or url:sub(1, 10) ~= "/folio/v2/" then fail("Invalid address.") end
    return url
end

local function need_file(ctx, rel)
    local attr = ctx.d.lstat(ctx.home .. "/" .. rel)
    if not attr or attr.mode ~= "file" then fail("The book isn't there any more.") end
    return attr
end

local function cached_hash(ctx, rel)
    local attr = need_file(ctx, rel)
    local hit = ctx.state.hashes[rel]
    if type(hit) == "table" and hit.size == attr.size and hit.mtime == attr.modification then
        return hit.sha256
    end
    return ctx.d.sha256(ctx.home .. "/" .. rel)
end

local function remember_hash(ctx, rel, sha)
    local attr = ctx.d.lstat(ctx.home .. "/" .. rel)
    if attr then ctx.state.hashes[rel] = { size = attr.size, mtime = attr.modification, sha256 = sha } end
end

local ACTIONS = {}

function ACTIONS.upload(ctx, a)
    local rel = need_path(a.path)
    if not ctx.listed[rel] then fail("Not a listed book.") end
    local attr = need_file(ctx, rel)
    local status, text = ctx.d.upload(Sync.url(ctx.cfg.server, need_url(a.url)), ctx.headers,
        ctx.home .. "/" .. rel, attr.size)
    local ok, answer = pcall(ctx.d.decode, text or "")
    if status ~= 200 or not ok or type(answer) ~= "table" or answer.ok ~= true then
        fail("Couldn't send the book.")
    end
    ctx.result.sent = ctx.result.sent + 1
    ctx.uploaded[rel] = true
end

function ACTIONS.move(ctx, a)
    local from = need_path(ctx.actual(a.from))
    local to = need_path(a.to)
    if not Sync.format(to) or Sync.format(to) ~= Sync.format(from) then fail("Invalid path.") end
    need_file(ctx, from)
    local from_abs = ctx.home .. "/" .. from
    check_closed(ctx, from_abs)
    local target = to
    if to:lower() ~= from:lower() then target = Sync.free_name(ctx.home, to, ctx.d) end
    make_dirs(ctx.home, parent(target), ctx.d)
    move_book(ctx, from_abs, ctx.home .. "/" .. target, true)
    ctx.state.hashes[target] = ctx.state.hashes[from]
    ctx.state.hashes[from] = nil
    prune(ctx.home, parent(from), ctx.cfg.root, ctx.d)
    if target ~= to then ctx.renamed[to] = target end
    if not ctx.uploaded[a.from] then ctx.changed[target] = true end
    return target
end

function ACTIONS.download(ctx, a)
    local replaces = type(a.replaces) == "string" and a.replaces:lower() or nil
    local to = need_path(replaces and ctx.actual(a.to) or a.to)
    if not Sync.format(to) then fail("Invalid path.") end
    local sha = type(a.sha256) == "string" and a.sha256:lower() or nil
    if not sha256_hex(sha) or (replaces and not sha256_hex(replaces)) then fail("Invalid file details.") end
    local size = tonumber(a.size)
    local url = need_url(a.url)
    local to_abs = ctx.home .. "/" .. to
    if replaces then
        need_file(ctx, to)
        check_closed(ctx, to_abs)
        if cached_hash(ctx, to) ~= replaces then
            fail("The book changed on the e-reader, so it wasn't replaced.")
        end
    elseif ctx.d.lstat(to_abs) then
        fail("Another file is already there, so it wasn't replaced.")
    end
    make_dirs(ctx.home, Sync.PARTS, ctx.d)
    local part = ctx.home .. "/" .. Sync.PARTS .. "/" .. sha .. ".part"
    ctx.d.remove(part)
    local status = ctx.d.download(Sync.url(ctx.cfg.server, url), ctx.headers, part)
    local attr = ctx.d.lstat(part)
    if status ~= 200 or not attr or (size and attr.size ~= size) or ctx.d.sha256(part) ~= sha then
        ctx.d.remove(part)
        fail("The download didn't arrive whole.")
    end
    -- The download took a while: check again before touching anything.
    if replaces then
        check_closed(ctx, to_abs)
        if cached_hash(ctx, to) ~= replaces then
            ctx.d.remove(part)
            fail("The book changed on the e-reader, so it wasn't replaced.")
        end
    else
        if ctx.d.lstat(to_abs) then
            ctx.d.remove(part)
            fail("Another file is already there, so it wasn't replaced.")
        end
        make_dirs(ctx.home, parent(to), ctx.d)
    end
    if not ctx.d.rename(part, to_abs) then
        ctx.d.remove(part)
        fail("Couldn't save the book. Is the e-reader full?")
    end
    remember_hash(ctx, to, sha)
    if replaces then
        -- KOReader keeps the old file's partial MD5 for Progress sync; let it work out the new one.
        if ctx.d.sidecar(to_abs) then
            pcall(ctx.d.write_sidecar, to_abs, function(data) data.partial_md5_checksum = nil end)
        end
        if ctx.d.reset then pcall(ctx.d.reset, to_abs) end
        ctx.changed[to] = true
    else
        ctx.result.received = ctx.result.received + 1
    end
    return to
end

function ACTIONS.delete(ctx, a)
    local rel = need_path(ctx.actual(a.path))
    local abs = ctx.home .. "/" .. rel
    if not ctx.d.lstat(abs) then return end -- already gone
    need_file(ctx, rel)
    check_closed(ctx, abs)
    -- Into the hidden trash first; emptied only once Librillo confirms the sync.
    local trash = Sync.TRASH .. "/" .. rel
    local trash_abs = ctx.home .. "/" .. trash
    if ctx.d.lstat(trash_abs) then fail("Couldn't delete the book.") end
    make_dirs(ctx.home, parent(trash), ctx.d)
    move_book(ctx, abs, trash_abs, false)
    ctx.trashed[#ctx.trashed + 1] = { orig = abs, trash = trash_abs }
    ctx.state.hashes[rel] = nil
    prune(ctx.home, parent(rel), ctx.cfg.root, ctx.d)
    ctx.result.removed = ctx.result.removed + 1
end

function ACTIONS.status(ctx, a)
    local rel = need_path(ctx.actual(a.path))
    if not (a.status == "unread" or TO_KOREADER[a.status]) then fail("Invalid status.") end
    need_file(ctx, rel)
    local abs = ctx.home .. "/" .. rel
    check_closed(ctx, abs)
    ctx.d.write_sidecar(abs, function(data) Sync.apply_status(data, a.status, ctx.now) end)
    if ctx.d.reset then pcall(ctx.d.reset, abs) end
    ctx.changed[rel] = true
end

function ACTIONS.annotations(ctx, a)
    local rel = need_path(ctx.actual(a.path))
    if type(a.annotations) ~= "table" then fail("Invalid notes.") end
    need_file(ctx, rel)
    local abs = ctx.home .. "/" .. rel
    check_closed(ctx, abs)
    local paging = Sync.format(rel) ~= "epub"
    local style = ctx.d.style and ctx.d.style() or nil
    local added = 0
    ctx.d.write_sidecar(abs, function(data)
        added = Sync.add_annotations(data, a.annotations, paging, ctx.cfg.device, ctx.d, style, ctx.now)
    end)
    if added > 0 then
        if ctx.d.reset then pcall(ctx.d.reset, abs) end
        ctx.changed[rel] = true
    end
end

-- Edits and deletions Librillo sends to a plugin that reported a complete list (rule 7).
local function change_annotations(ctx, a, field, change)
    local rel = need_path(ctx.actual(a.path))
    if type(a[field]) ~= "table" then fail("Invalid notes.") end
    need_file(ctx, rel)
    local abs = ctx.home .. "/" .. rel
    check_closed(ctx, abs)
    local count = 0
    ctx.d.write_sidecar(abs, function(data) count = change(data, a[field]) end)
    if count > 0 then
        if ctx.d.reset then pcall(ctx.d.reset, abs) end
        ctx.changed[rel] = true
    end
end

ACTIONS["edit-annotations"] = function(ctx, a)
    change_annotations(ctx, a, "annotations", function(data, list)
        return Sync.edit_annotations(data, list, ctx.cfg.device, ctx.d)
    end)
end

ACTIONS["delete-annotations"] = function(ctx, a)
    change_annotations(ctx, a, "ids", function(data, ids)
        return Sync.delete_annotations(data, ids, ctx.cfg.device, ctx.d)
    end)
end

function ACTIONS.collections(ctx, a)
    local rel = need_path(ctx.actual(a.path))
    if type(a.collections) ~= "table" then fail("Invalid collections.") end
    local names = {}
    for _, name in ipairs(a.collections) do
        if type(name) ~= "string" or name == "" or #name > 200 or name:find("%c") then
            fail("Invalid collection name.")
        end
        names[#names + 1] = name
    end
    need_file(ctx, rel)
    local abs = ctx.home .. "/" .. rel
    check_closed(ctx, abs)
    ctx.d.set_collections(abs, names)
    ctx.changed[rel] = true
end

-- Puts back books deleted in a sync Librillo never confirmed, so nothing is lost.
function Sync.restore_trash(home, d)
    local root = home .. "/" .. Sync.TRASH
    if not d.lstat(root) then return 0 end
    local restored = 0
    local function visit(rel_dir)
        local names = d.dir(root .. (rel_dir == "" and "" or "/" .. rel_dir)) or {}
        for _, name in ipairs(names) do
            local rel = rel_dir == "" and name or rel_dir .. "/" .. name
            local attr = d.lstat(root .. "/" .. rel)
            if attr and attr.mode == "directory" and not name:lower():match("%.sdr$") then
                visit(rel)
            elseif attr and attr.mode == "file" and Sync.format(name) then
                local orig = home .. "/" .. rel
                if not d.lstat(orig) then
                    local ok = pcall(make_dirs, home, parent(rel), d)
                    if ok and d.rename(root .. "/" .. rel, orig) then
                        pcall(d.move_sidecar, root .. "/" .. rel, orig)
                        restored = restored + 1
                    end
                end
            end
        end
    end
    visit("")
    return restored
end

local function empty_trash(ctx)
    for _, item in ipairs(ctx.trashed) do
        pcall(ctx.d.forget, item.orig)
        pcall(ctx.d.purge, item.trash)
        local rel = item.trash:sub(#ctx.home + 2)
        pcall(prune, ctx.home, parent(rel), Sync.TRASH, ctx.d)
    end
end

local function put_back(ctx)
    for i = #ctx.trashed, 1, -1 do
        local item = ctx.trashed[i]
        if not ctx.d.lstat(item.orig) then
            local rel = item.orig:sub(#ctx.home + 2)
            if pcall(make_dirs, ctx.home, parent(rel), ctx.d) and ctx.d.rename(item.trash, item.orig) then
                pcall(ctx.d.move_sidecar, item.trash, item.orig)
            end
        end
    end
end

local function questions_of(list)
    local out = {}
    for _, q in ipairs(type(list) == "table" and list or {}) do
        if type(q) == "table" and type(q.id) == "string" and q.id:match("^q%-[0-9a-f]+$")
                and type(q.text) == "string" and type(q.options) == "table" then
            local options = {}
            for _, option in ipairs(q.options) do
                if type(option) == "string" and option ~= "" then options[#options + 1] = option end
            end
            if #options > 0 then
                out[#out + 1] = { id = q.id, text = q.text, options = options,
                    kind = str(q.kind), path = nonempty(q.path) }
            end
        end
    end
    return out
end

-- One whole sync. `state` holds what the plugin keeps between syncs (hashes, answers, questions,
-- last_sync, collections_mtime); it is updated in place, and the caller saves it. Returns what happened;
-- raises a plain message when the sync could not run.
function Sync.run(cfg, d, state)
    local progress = d.progress or function() return true end
    local result = new_result()
    state.hashes = state.hashes or {}
    Sync.restore_trash(cfg.home, d)
    local headers = Sync.headers(cfg)
    Sync.connect(cfg, d)

    local files = Sync.manifest(cfg, d, state, progress)
    local answers, sent_answers = {}, {}
    for id, option in pairs(state.answers or {}) do
        if type(id) == "string" and type(option) == "string" then
            answers[id] = option
            sent_answers[#sent_answers + 1] = id
        end
    end
    local body = d.encode({
        device = { id = cfg.device, name = cfg.name or "KOReader" },
        home = cfg.home, root = cfg.root, files = d.array(files), answers = answers,
    })
    progress("Asking Librillo what to do")
    local ok, status, text = pcall(d.request, "POST", Sync.url(cfg.server, "/folio/v2/sync"), headers, body)
    if not ok or status == nil then
        fail("Librillo stopped answering. Try again.")
    elseif status == 403 then
        fail("Librillo didn't accept this e-reader's list of books. Update Librillo and the plugin.")
    elseif status == 413 then
        fail("The list of books is too big for Librillo.")
    elseif status ~= 200 then
        fail("Librillo couldn't sync right now (error " .. tostring(status) .. "). Try again.")
    end
    local decoded, plan = pcall(d.decode, text)
    if not decoded or type(plan) ~= "table" or type(plan.session) ~= "string" or type(plan.kindle) ~= "table" then
        fail("Librillo sent something this plugin can't read. Update the plugin.")
    end

    local listed = {}
    for _, file in ipairs(files) do listed[file.path] = true end
    local ctx = { cfg = cfg, d = d, state = state, home = cfg.home, headers = headers, result = result,
        listed = listed, uploaded = {}, renamed = {}, changed = {}, trashed = {},
        now = d.now and d.now() or os.time() }
    function ctx.actual(path) return ctx.renamed[path] or path end

    local results = {}
    local count = #plan.kindle
    for i, action in ipairs(plan.kindle) do
        if progress("Syncing: " .. i .. " of " .. count) == false then break end
        local index = type(action) == "table" and tonumber(action.index) or (i - 1)
        local run = type(action) == "table" and ACTIONS[action.op]
        local done, path
        if run then
            done, path = pcall(run, ctx, action)
        else
            done, path = false, "Unknown action."
        end
        if done then
            results[#results + 1] = { index = index, ok = true, path = type(path) == "string" and path or nil }
        else
            result.failed = result.failed + 1
            local where = type(action) == "table" and (action.path or action.to or action.from) or nil
            result.failures[#result.failures + 1] = { path = type(where) == "string" and where or "?",
                error = tostring(path) }
            results[#results + 1] = { index = index, ok = false, error = tostring(path) }
        end
    end
    for _ in pairs(ctx.changed) do result.updated = result.updated + 1 end

    progress("Finishing")
    local finished, fin_status, fin_text = pcall(d.request, "POST", Sync.url(cfg.server, "/folio/v2/finish"),
        headers, d.encode({ session = plan.session, results = d.array(results) }))
    local confirmed = false
    if finished and fin_status == 200 then
        local read, answer = pcall(d.decode, fin_text)
        confirmed = read and type(answer) == "table" and answer.ok == true
    end
    if not confirmed then
        put_back(ctx)
        fail("Librillo didn't confirm the sync. Nothing was deleted; sync again.")
    end
    empty_trash(ctx)

    local before = {}
    for _, q in ipairs(state.questions or {}) do before[q.id] = true end
    result.questions = questions_of(plan.questions)
    for _, q in ipairs(result.questions) do
        if not before[q.id] then result.new_questions = result.new_questions + 1 end
    end
    result.phone_questions = tonumber(plan.phoneQuestions) or 0
    state.questions = result.questions
    for _, id in ipairs(sent_answers) do state.answers[id] = nil end
    state.last_sync = ctx.now * 1000
    mark_synced(cfg, d, state)
    return result
end

local function count(n, one, many) return n .. " " .. (n == 1 and one or many) end

-- What a sync did, in plain words for the e-reader's screen. Empty when an automatic sync has
-- nothing worth saying.
function Sync.summary(result, automatic)
    local parts = {}
    if result.sent > 0 then parts[#parts + 1] = count(result.sent, "book sent", "books sent") end
    if result.received > 0 then parts[#parts + 1] = result.received .. " received" end
    if result.updated > 0 then parts[#parts + 1] = result.updated .. " updated" end
    if result.removed > 0 then parts[#parts + 1] = result.removed .. " removed" end
    local lines = {}
    if #parts > 0 then
        local first = parts[1]
        if not first:find("book", 1, true) then
            -- "1 received" alone reads oddly: name the books once.
            local n, rest = first:match("^(%d+) (.*)$")
            parts[1] = count(tonumber(n), "book", "books") .. " " .. rest
        end
        lines[#lines + 1] = table.concat(parts, ", ") .. "."
    end
    local asking = #result.questions
    local say_questions = asking > 0 and (not automatic or result.new_questions > 0)
    if say_questions then
        lines[#lines + 1] = (asking == 1 and "1 question needs" or asking .. " questions need")
            .. " your answer: Librillo > Questions."
    end
    if automatic and #parts == 0 and not say_questions then return "" end
    if result.failed > 0 and not automatic then
        lines[#lines + 1] = (result.failed == 1 and "1 change couldn't be made" or result.failed .. " changes couldn't be made")
            .. ". It will be tried again next time."
        for _, failure in ipairs(result.failures) do
            if failure.error == "open on the e-reader" then
                lines[#lines + 1] = "Close the book you're reading, then sync again."
                break
            end
        end
    end
    if result.phone_questions > 0 and not automatic then
        lines[#lines + 1] = (result.phone_questions == 1 and "1 question is" or result.phone_questions .. " questions are")
            .. " waiting on your phone."
    end
    if #lines == 0 then return "Everything is up to date." end
    return table.concat(lines, "\n\n")
end

return Sync
