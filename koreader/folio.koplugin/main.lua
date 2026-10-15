-- SPDX-License-Identifier: AGPL-3.0-or-later
local WidgetContainer = require("ui/widget/container/widgetcontainer")
local UIManager = require("ui/uimanager")
local InfoMessage = require("ui/widget/infomessage")
local ConfirmBox = require("ui/widget/confirmbox")
local InputDialog = require("ui/widget/inputdialog")
local DataStorage = require("datastorage")
local ReadCollection = require("readcollection")
local SQ3 = require("lua-ljsqlite3/init")
local JSON = require("rapidjson")
local lfs = require("libs/libkoreader-lfs")
local ffiUtil = require("ffi/util")
local Event = require("ui/event")
local Bridge
local Link
local Folio = WidgetContainer:extend { name = "folio", }
local function message(text) UIManager:show(InfoMessage:new { text = text }) end
local function bounded_read(path)
    local file = assert(io.open(path, "rb"))
    local bytes = file:read(4 * 1024 * 1024 + 1)
    file:close()
    assert(bytes and #bytes <= 4 * 1024 * 1024, "Folio file too large")
    return bytes
end
function Folio:init()
    math.randomseed(os.time() + math.floor((os.clock() % 1) * 1e6))
    Bridge = dofile(self.path .. "/bridge.lua")
    Link = dofile(self.path .. "/link.lua")
    self.ui.menu:registerToMainMenu(self)
end
-- The folder Folio's books go to: the one set here, else the Kindle default if it exists,
-- else "Folio" in KOReader's home folder (any device).
function Folio:defaultFolder()
    local saved = G_reader_settings:readSetting("folio_folder")
    if saved then return saved end
    if lfs.attributes("/mnt/us/Books/Folio", "mode") == "directory" then return "/mnt/us/Books/Folio" end
    local home = G_reader_settings:readSetting("home_dir") or DataStorage:getDataDir()
    return home:gsub("/$", "") .. "/Folio"
end
function Folio:root()
    local path = self:defaultFolder()
    local root = ffiUtil.realpath(path)
    assert(root and lfs.attributes(root, "mode") == "directory", "Configure an existing Folio book folder first")
    return root:gsub("/$", "")
end
function Folio:collections()
    local ok, plan = pcall(function()
        local root = self:root()
        local latest, timestamp, count = nil, -1, 0
        for name in lfs.dir(root) do
            if name:match("^%.folio%-collections%-%x[%x%-]*%.json$") then
                count = count + 1
                assert(count <= 100, "Too many collection requests; remove old request JSON files")
                local path = root .. "/" .. name
                local attr = lfs.symlinkattributes(path)
                if attr and attr.mode == "file" then
                    local request = assert(JSON.decode(bounded_read(path)))
                    local created = tonumber(request.createdAt) or 0
                    if created > timestamp then latest, timestamp = request, created end
                end
            end
        end
        assert(latest, "No Folio collection request found. Send one from the phone first.")
        return Bridge.validate(latest, root, ffiUtil.realpath, lfs.attributes)
    end)
    if not ok then message("Cannot load Folio collection request. Check the configured folder and request format."); return end
    local names = {}
    for _, shelf in ipairs(plan) do names[#names + 1] = shelf.name end
    UIManager:show(ConfirmBox:new {
        text = "Add books to these KOReader collections?\n\n" .. table.concat(names, "\n") .. "\n\nExisting memberships and collections are retained. No books are removed.",
        ok_text = "Apply additions",
        ok_callback = function()
            local success = pcall(function() Bridge.apply(plan, ReadCollection) end)
            message(success and "Folio collection additions applied." or "Collection update failed. Retry after checking files; additions are idempotent.")
        end,
    })
end
function Folio:statistics()
    local connection
    local ok = pcall(function()
        local root = self:root()
        local db = DataStorage:getSettingsDir() .. "/statistics.sqlite3"
        assert(lfs.attributes(db, "mode") == "file", "No statistics database")
        connection = SQ3.open(db)
        connection:exec("PRAGMA query_only=ON;")
        connection:exec("BEGIN;")
        local seconds = tonumber(connection:rowexec("SELECT COALESCE(SUM(duration),0) FROM page_stat_data WHERE duration>=0;")) or 0
        local rows = connection:exec("SELECT strftime('%Y-%m-%d',start_time,'unixepoch','localtime') AS day, SUM(duration) AS seconds FROM page_stat_data WHERE duration>=0 GROUP BY day ORDER BY day DESC LIMIT 366;")
        local days = JSON.array()
        if rows then for i, day in ipairs(rows[1]) do days[#days + 1] = { date = day, seconds = tonumber(rows[2][i]) or 0 } end end
        connection:exec("COMMIT;")
        connection:close(); connection = nil
        local output = assert(JSON.encode({ format = "folio-statistics", version = 1, exportedAt = os.time(), seconds = seconds, days = days }))
        local destination = root .. "/.folio-statistics.json"
        local temporary = root .. "/.folio-statistics-" .. tostring(os.time()) .. "-" .. tostring(math.random(1, 999999999)) .. ".part"
        assert(not lfs.symlinkattributes(temporary), "Staging destination exists")
        local file = assert(io.open(temporary, "wb"))
        assert(file:write(output)); assert(file:close())
        assert(os.rename(temporary, destination))
    end)
    if connection then pcall(function() connection:exec("ROLLBACK;"); connection:close() end) end
    message(ok and "Statistics snapshot exported. Retrieve it from Folio on your phone." or "Statistics export failed. Ensure the book is closed, statistics are enabled and the Folio folder exists.")
end
function Folio:reading(automatic)
    local document = self.ui.document
    if not document or document.info.has_pages then message("Open an EPUB first, then review its phone reading position."); return end
    local ok, request = pcall(function()
        local root = self:root()
        local candidates, count = {}, 0
        for name in lfs.dir(root) do
            if name:match("^%.folio%-reading%-[0-9a-f%-]+%.json$") then
                count = count + 1; assert(count <= 1000, "Too many reading requests")
                if lfs.symlinkattributes(root .. "/" .. name, "mode") == "file" then
                    local value = JSON.decode(bounded_read(root .. "/" .. name))
                    if value and value.path == document.file then candidates[#candidates + 1] = value end
                end
            end
        end
        table.sort(candidates, function(a, b) return tonumber(a.createdAt) > tonumber(b.createdAt) end)
        local latest = candidates[1]
        assert(latest, "No phone reading request for this EPUB")
        local applied = G_reader_settings:readSetting("folio_applied_positions", {})
        assert(not applied[latest.id], "This position request was already applied")
        local value = Bridge.reading(latest, root, document.file, self.ui.doc_settings:readSetting("partial_md5_checksum"), ffiUtil.realpath, lfs.attributes, os.time())
        assert(document:isXPointerInDocument(value.position), "Position is not supported in this EPUB")
        return value
    end)
    if not ok then if not automatic then message("Cannot apply phone position. Check the book, folder, EPUB revision, request age and whether it was already applied. Your place is unchanged.") end; return end
    local function apply()
        local success = pcall(function()
            assert(self.ui.document == document and document:isXPointerInDocument(request.position))
            Bridge.reading(request, self:root(), document.file, self.ui.doc_settings:readSetting("partial_md5_checksum"), ffiUtil.realpath, lfs.attributes, os.time())
            self.ui:handleEvent(Event:new("GotoXPointer", request.position))
            assert(self.ui.rolling and self.ui.rolling.xpointer == request.position, "Position not applied")
            local applied = G_reader_settings:readSetting("folio_applied_positions", {})
            applied[request.id] = os.time()
            for id, time in pairs(applied) do if time < os.time() - 8 * 86400 then applied[id] = nil end end
            G_reader_settings:saveSetting("folio_applied_positions", applied)
        end)
        if not automatic then message(success and "Continued from phone. Close the book to save the new Kindle position." or "Position handoff failed. Your bookmarks and highlights were retained.") end
    end
    if automatic then
        -- Auto mode was explicitly enabled once on this device. Never override intervening reading.
        local saved = self.ui.doc_settings:readSetting("last_xpointer")
        if request.expectedKindle ~= saved and not (request.expectedKindle == "" and not saved) then return end
        apply()
    else
        UIManager:show(ConfirmBox:new {
            text = "Continue from the phone's saved passage?\n\nYour current Kindle position will move. Existing bookmarks and highlights are retained.",
            ok_text = "Continue from phone", ok_callback = apply,
        })
    end
end
-- Folio Link: the server, username and key come from KOReader's Progress sync settings.
function Folio:linkConfig()
    local kosync = G_reader_settings:readSetting("kosync") or {}
    local folder = self:defaultFolder()
    if lfs.attributes(folder, "mode") ~= "directory" then lfs.mkdir(folder) end
    return {
        server = kosync.custom_server, username = kosync.username, key = kosync.userkey,
        device = G_reader_settings:readSetting("device_id") or "koreader", folder = self:root(),
    }
end
local function sha256_file(path)
    local loaded, sha2 = pcall(require, "ffi/sha2")
    if not loaded or type(sha2.sha256) ~= "function" then return nil end
    local file = io.open(path, "rb")
    if not file then return nil end
    -- Streams the file; if this KOReader cannot, the caller checks the size instead.
    local ok, digest = pcall(function()
        local append = sha2.sha256()
        while true do
            local chunk = file:read(65536)
            if not chunk then break end
            append(chunk)
        end
        return append()
    end)
    file:close()
    return ok and type(digest) == "string" and digest:lower() or nil
end
local function link_deps()
    local http = require("socket.http")
    local ltn12 = require("ltn12")
    local socket = require("socket")
    local socketutil = require("socketutil")
    local function call(method, url, headers, body, sink)
        local all, chunks, source = {}, {}, nil
        for k, v in pairs(headers) do all[k] = v end
        if body then
            all["Content-Type"] = "application/json"; all["Content-Length"] = tostring(#body)
            source = ltn12.source.string(body)
        end
        socketutil:set_timeout(socketutil.LARGE_BLOCK_TIMEOUT, socketutil.LARGE_TOTAL_TIMEOUT)
        local code = socket.skip(1, http.request { url = url, method = method, headers = all, source = source,
            sink = sink or ltn12.sink.table(chunks) })
        socketutil:reset_timeout()
        return tonumber(code), table.concat(chunks)
    end
    return {
        request = function(method, url, headers, body) return call(method, url, headers, body) end,
        download = function(url, headers, path)
            return (call("GET", url, headers, nil, ltn12.sink.file(assert(io.open(path, "wb")))))
        end,
        exists = function(path) return lfs.attributes(path, "mode") == "file" end,
        size = function(path) return lfs.attributes(path, "size") end,
        sha256 = sha256_file,
        rename = function(from, to) return os.rename(from, to) end,
        remove = function(path) os.remove(path) end,
        decode = function(text) return JSON.decode(text) end,
        encode = function(value) return JSON.encode(value) end,
        broadcast = function(text)
            local udp = socket.udp()
            udp:setoption("broadcast", true)
            udp:settimeout(0.5)
            udp:sendto(text, "255.255.255.255", 8766)
            local replies, deadline = {}, socket.gettime() + 2
            while socket.gettime() < deadline do
                local data, ip = udp:receivefrom()
                if data then replies[#replies + 1] = { ip = ip, text = data } end
            end
            udp:close()
            return replies
        end,
        md5 = function(text)
            local loaded, sha2 = pcall(require, "ffi/sha2")
            if loaded and type(sha2.md5) == "function" then return sha2.md5(text):lower() end
            local found, md5 = pcall(require, "ffi/MD5")
            return found and md5.sum(text):lower() or nil
        end,
        nonce = function()
            local digits = {}
            for i = 1, 32 do digits[i] = string.format("%x", math.random(0, 15)) end
            return table.concat(digits)
        end,
        -- Points KOReader's Progress sync at the phone's new address.
        save_server = function(url)
            local kosync = G_reader_settings:readSetting("kosync") or {}
            kosync.custom_server = url
            G_reader_settings:saveSetting("kosync", kosync)
        end,
    }
end
function Folio:findPhone()
    local NetworkMgr = require("ui/network/manager")
    NetworkMgr:runWhenOnline(function()
        local deps = link_deps()
        local ok, found = pcall(Link.discover, self:linkConfig(), deps)
        if ok and found then
            deps.save_server(found)
            message("Found Folio at " .. found .. ". Progress sync now uses it.")
        else
            message("Folio was not found. Turn on Folio Link on the phone and join the same Wi-Fi or its hotspot.")
        end
    end)
end
function Folio:fetchBooks(automatic)
    local NetworkMgr = require("ui/network/manager")
    NetworkMgr:runWhenOnline(function()
        local ok, result = pcall(function() return Link.fetch(self:linkConfig(), link_deps()) end)
        if not ok then
            if not automatic then message(tostring(result):gsub("^.-:%d+: ", "")) end
            return
        end
        if automatic and result.fetched == 0 and result.failed == 0 then return end
        local text = string.format("Folio: %d new %s.", result.fetched, result.fetched == 1 and "book" or "books")
        if result.already > 0 then text = text .. string.format(" %d already here.", result.already) end
        if result.failed > 0 then text = text .. string.format(" %d could not be fetched; they will be tried again.", result.failed) end
        message(text)
    end)
end
function Folio:onNetworkConnected()
    if G_reader_settings:readSetting("folio_auto_fetch", false) then
        UIManager:scheduleIn(2, function() self:fetchBooks(true) end)
    end
end
function Folio:onReaderReady()
    if G_reader_settings:readSetting("folio_auto_positions", false) then
        UIManager:nextTick(function() self:reading(true) end)
    end
end
function Folio:configure()
    local dialog
    dialog = InputDialog:new {
        title = "Folio book folder", input = self:defaultFolder(),
        buttons = {{ { text = "Cancel", callback = function() UIManager:close(dialog) end },
            { text = "Save", callback = function()
                local path = ffiUtil.realpath(dialog:getInputText())
                if path and lfs.attributes(path, "mode") == "directory" then
                    G_reader_settings:saveSetting("folio_folder", path); UIManager:close(dialog)
                else message("Choose an existing folder.") end
            end } }},
    }
    UIManager:show(dialog)
    dialog:onShowKeyboard()
end
function Folio:addToMainMenu(items)
    items.folio_bridge = { text = "Folio", sub_item_table = {
        { text = "Get books from Folio", callback = function() self:fetchBooks(false) end },
        { text = "Find Folio on this network", callback = function() self:findPhone() end },
        { text = "Get books when Wi-Fi connects", checked_func = function() return G_reader_settings:readSetting("folio_auto_fetch", false) end,
            callback = function() G_reader_settings:saveSetting("folio_auto_fetch", not G_reader_settings:readSetting("folio_auto_fetch", false)) end },
        { text = "Folio book folder", callback = function() self:configure() end },
        { text = "Continue from Folio automatically", checked_func = function() return G_reader_settings:readSetting("folio_auto_positions", false) end,
            callback = function() G_reader_settings:saveSetting("folio_auto_positions", not G_reader_settings:readSetting("folio_auto_positions", false)) end },
        { text = "Review phone reading position", callback = function() self:reading() end },
        { text = "Review collection additions", callback = function() self:collections() end },
        { text = "Export reading statistics", callback = function() self:statistics() end },
    }}
end
return Folio
