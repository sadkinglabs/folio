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
local Sync
local Folio = WidgetContainer:extend { name = "folio", }
local function message(text) UIManager:show(InfoMessage:new { text = text }) end
local function bounded_read(path)
    local file = assert(io.open(path, "rb"))
    local bytes = file:read(4 * 1024 * 1024 + 1)
    file:close()
    assert(bytes and #bytes <= 4 * 1024 * 1024, "Librillo file too large")
    return bytes
end
function Folio:init()
    math.randomseed(os.time() + math.floor((os.clock() % 1) * 1e6))
    Bridge = dofile(self.path .. "/bridge.lua")
    Link = dofile(self.path .. "/link.lua")
    Sync = dofile(self.path .. "/sync.lua")
    self:applySetup()
    self.ui.menu:registerToMainMenu(self)
    Folio.startAutoSync()
end
-- Progress sync (kosync) keeps its settings in settings/kosync.lua (KOReader 2024 and later,
-- key "settings") and, in older versions, in the reader setting "kosync". Librillo writes both.
-- When Progress sync is already running, its settings are changed in place too, so it never
-- writes the old ones back.
local function live_kosync()
    local ok, PluginLoader = pcall(require, "pluginloader")
    if not ok or type(PluginLoader) ~= "table" or type(PluginLoader.enabled_plugins) ~= "table" then return nil end
    for _, plugin in ipairs(PluginLoader.enabled_plugins) do
        if type(plugin) == "table" and plugin.name == "kosync" and type(plugin.settings_obj) == "table"
            and type(plugin.settings_obj.data) == "table" then
            return plugin.settings_obj
        end
    end
end
local function kosync_file() return DataStorage:getSettingsDir() .. "/kosync.lua" end
local function kosync_store()
    local LuaSettings = require("luasettings")
    return {
        decode = function(text) return JSON.decode(text) end,
        read_kosync = function() return LuaSettings:open(kosync_file()):readSetting("settings") end,
        write_kosync = function(values)
            local live = live_kosync()
            if live and type(live.data.settings) == "table" then
                for key, value in pairs(values) do live.data.settings[key] = value end
                live:flush()
            else
                local file = LuaSettings:open(kosync_file())
                file:saveSetting("settings", values)
                file:flush()
            end
        end,
        read_legacy = function() return G_reader_settings:readSetting("kosync") end,
        write_legacy = function(values) G_reader_settings:saveSetting("kosync", values) end,
    }
end
-- The login both Progress sync and Librillo use: kosync.lua first, then the older setting.
local function kosync_login()
    local ok, store = pcall(kosync_store)
    local current = ok and select(2, pcall(store.read_kosync)) or nil
    if type(current) == "table" and current.userkey then return current end
    return G_reader_settings:readSetting("kosync") or {}
end
-- Librillo's own settings on this e-reader (library folder, automatic sync, questions, answers).
local function sync_settings()
    local values = G_reader_settings:readSetting("folio_sync")
    return type(values) == "table" and values or {}
end
local function save_sync_settings(values)
    G_reader_settings:saveSetting("folio_sync", values)
end
-- Librillo's installer leaves link-setup.json beside the plugin with the Link address, sign-in and
-- (version 2) the library folder. It is read once as JSON data, applied to Progress sync, and
-- removed so the key does not linger.
function Folio:applySetup()
    local path = self.path .. "/link-setup.json"
    if lfs.attributes(path, "mode") ~= "file" then return end
    local ok, text = pcall(bounded_read, path)
    os.remove(path)
    if not ok then return end
    local applied, options = pcall(Link.install, text, kosync_store())
    if not applied or not options then
        message("Librillo's setup file could not be read. Set up Progress sync by hand.")
        return
    end
    local settings = sync_settings()
    settings.root = options.root
    settings.auto_sync = options.auto_sync
    settings.device_name = options.device_name
    settings.set_up = options.version
    save_sync_settings(settings)
    G_reader_settings:saveSetting("folio_auto_fetch", options.auto_fetch)
    G_reader_settings:flush()
    UIManager:scheduleIn(1, function()
        message("Librillo is set up. Restart KOReader once so Progress sync uses it.")
    end)
end
-- The folder Folio's books go to: the one set here, else the Kindle default if it exists,
-- else "Librillo" in KOReader's home folder (any device).
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
    assert(root and lfs.attributes(root, "mode") == "directory", "Configure an existing Librillo book folder first")
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
        assert(latest, "No Librillo collection request found. Send one from the phone first.")
        return Bridge.validate(latest, root, ffiUtil.realpath, lfs.attributes)
    end)
    if not ok then message("Cannot load Librillo collection request. Check the configured folder and request format."); return end
    local names = {}
    for _, shelf in ipairs(plan) do names[#names + 1] = shelf.name end
    UIManager:show(ConfirmBox:new {
        text = "Add books to these KOReader collections?\n\n" .. table.concat(names, "\n") .. "\n\nExisting memberships and collections are retained. No books are removed.",
        ok_text = "Apply additions",
        ok_callback = function()
            local success = pcall(function() Bridge.apply(plan, ReadCollection) end)
            message(success and "Librillo collection additions applied." or "Collection update failed. Retry after checking files; additions are idempotent.")
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
    message(ok and "Statistics snapshot exported. Retrieve it from Librillo on your phone." or "Statistics export failed. Ensure the book is closed, statistics are enabled and the Librillo folder exists.")
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
        -- EPUBs in the folder and its subfolders (three levels), skipping KOReader's .sdr folders
        -- and hidden ones; at most 5000, so a huge folder cannot stall the fetch.
        list = function(folder)
            local found = {}
            local function walk(dir, depth)
                if depth > 3 or #found >= 5000 then return end
                local ok, iter, state = pcall(lfs.dir, dir)
                if not ok then return end
                for name in iter, state do
                    if name:sub(1, 1) ~= "." and not name:match("%.sdr$") then
                        local path = dir .. "/" .. name
                        local mode = lfs.attributes(path, "mode")
                        if mode == "directory" then walk(path, depth + 1)
                        elseif mode == "file" and name:lower():match("%.epub$") then found[#found + 1] = path end
                    end
                end
            end
            walk(folder, 0)
            return found
        end,
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
            pcall(function()
                local store = kosync_store()
                local current = store.read_kosync()
                if type(current) == "table" then
                    current.custom_server = url
                    store.write_kosync(current)
                end
            end)
        end,
    }
end
-- ---- Sync (protocol v2) ----

-- KOReader's home folder, which Librillo syncs. Everything else on the e-reader is left alone.
local function home_folder()
    local home = G_reader_settings:readSetting("home_dir")
    if not home then
        local ok, util = pcall(require, "apps/filemanager/filemanagerutil")
        home = ok and type(util.getDefaultDir) == "function" and util.getDefaultDir() or nil
    end
    home = home or require("device").home_dir
    home = home and ffiUtil.realpath(home)
    assert(home and lfs.attributes(home, "mode") == "directory" and home ~= "/",
        "Set a home folder in KOReader's file browser first.")
    return (home:gsub("/$", ""))
end
local function device_id()
    local id = G_reader_settings:readSetting("device_id") or G_reader_settings:readSetting("folio_device_id")
    if not id then
        local digits = {}
        for i = 1, 32 do digits[i] = string.format("%x", math.random(0, 15)) end
        id = table.concat(digits)
        G_reader_settings:saveSetting("folio_device_id", id)
    end
    return id
end
function Folio.syncConfig()
    local kosync = kosync_login()
    local settings = sync_settings()
    local data_dir = ffiUtil.realpath(DataStorage:getDataDir())
    local ok, Device = pcall(require, "device")
    return {
        server = kosync.custom_server, username = kosync.username, key = kosync.userkey,
        device = device_id(), name = settings.device_name or (ok and Device.model) or "KOReader",
        home = home_folder(), root = Sync.valid_root(settings.root) or Sync.ROOT,
        skip = { data_dir },
    }
end
-- The hash cache lives in its own file, so the reader settings stay small.
local function hash_cache()
    return require("luasettings"):open(DataStorage:getSettingsDir() .. "/folio-hashes.lua")
end
local function load_state()
    local settings = sync_settings()
    local ok, cache = pcall(hash_cache)
    return {
        hashes = ok and cache:readSetting("files") or {},
        answers = settings.answers or {}, questions = settings.questions or {},
        last_sync = settings.last_sync, collections_mtime = settings.collections_mtime,
    }
end
local function save_state(state)
    local settings = sync_settings()
    settings.answers = state.answers
    settings.questions = state.questions
    settings.last_sync = state.last_sync
    settings.collections_mtime = state.collections_mtime
    save_sync_settings(settings)
    G_reader_settings:flush()
    pcall(function()
        local cache = hash_cache()
        cache:saveSetting("files", state.hashes or {})
        cache:flush()
    end)
end
-- The book open in the reader right now, if any. Librillo never changes it while it is open.
local function open_reader()
    local ReaderUI = package.loaded["apps/reader/readerui"]
    local reader = type(ReaderUI) == "table" and ReaderUI.instance or nil
    if reader and reader.document and type(reader.document.file) == "string" then return reader end
end
local function open_path()
    local reader = open_reader()
    if not reader then return nil end
    return ffiUtil.realpath(reader.document.file) or reader.document.file
end
local function reset_book_info(path)
    local ok, BookList = pcall(require, "ui/widget/booklist")
    if ok and type(BookList) == "table" and BookList.resetBookInfoCache then BookList.resetBookInfoCache(path) end
end
local function sync_deps(progress)
    local base = link_deps()
    local http = require("socket.http")
    local ltn12 = require("ltn12")
    local socket = require("socket")
    local socketutil = require("socketutil")
    local DocSettings = require("docsettings")
    -- Local network only, so a generous wait per block and no limit on the whole transfer.
    local function call(method, url, headers, source, sink, extra)
        local all = {}
        for k, v in pairs(headers) do all[k] = v end
        for k, v in pairs(extra or {}) do all[k] = v end
        local chunks = {}
        -- Hello is quick, so a phone that isn't there never holds KOReader up for long.
        if url:find("/folio/v2/hello", 1, true) then socketutil:set_timeout(5, 10) else socketutil:set_timeout(60, -1) end
        local ok, code = pcall(function()
            return socket.skip(1, http.request { url = url, method = method, headers = all, source = source,
                sink = sink or ltn12.sink.table(chunks) })
        end)
        socketutil:reset_timeout()
        if not ok then return nil end
        return tonumber(code), table.concat(chunks)
    end
    local function collections_module()
        local ReadCollection = require("readcollection")
        if type(ReadCollection.coll) ~= "table" or type(ReadCollection.addRemoveItemMultiple) ~= "function" then
            error("This KOReader is too old for collections. Update KOReader.", 0)
        end
        if ReadCollection._read then pcall(ReadCollection._read, ReadCollection) end
        return ReadCollection
    end
    return {
        progress = progress,
        now = os.time,
        decode = base.decode,
        encode = base.encode,
        array = function(t) return JSON.array(t) end,
        request = function(method, url, headers, body)
            local source = body and ltn12.source.string(body) or nil
            local extra = body and { ["Content-Type"] = "application/json", ["Content-Length"] = tostring(#body) } or nil
            return call(method, url, headers, source, nil, extra)
        end,
        upload = function(url, headers, path, size)
            local file = io.open(path, "rb")
            if not file then return nil end
            local status, text = call("PUT", url, headers, ltn12.source.file(file), nil,
                { ["Content-Type"] = "application/octet-stream", ["Content-Length"] = tostring(size) })
            pcall(file.close, file)
            return status, text
        end,
        download = function(url, headers, path)
            local file = io.open(path, "wb")
            if not file then return nil end
            local status = call("GET", url, headers, nil, ltn12.sink.file(file))
            pcall(file.close, file)
            return status
        end,
        discover = function(cfg) return Link.discover(cfg, base) end,
        save_server = base.save_server,
        -- Files
        dir = function(path)
            local ok, iter, state = pcall(lfs.dir, path)
            if not ok then return nil end
            local names = {}
            for name in iter, state do
                if name ~= "." and name ~= ".." then names[#names + 1] = name end
            end
            return names
        end,
        lstat = function(path) return lfs.symlinkattributes(path) end,
        mkdir = function(path) return lfs.mkdir(path) end,
        rmdir = function(path) return lfs.rmdir(path) end,
        rename = function(from, to) return os.rename(from, to) == true end,
        remove = function(path) os.remove(path) end,
        sha256 = sha256_file,
        sha256_text = function(text) return require("ffi/sha2").sha256(text):lower() end,
        -- KOReader's notes for a book (its sidecar), or nil. For the open book, the live ones.
        sidecar = function(path)
            local reader = open_reader()
            if reader and open_path() == path then
                local data = {}
                for k, v in pairs(reader.doc_settings.data) do data[k] = v end
                if reader.annotation and type(reader.annotation.annotations) == "table" then
                    data.annotations = reader.annotation.annotations
                end
                return data
            end
            if not DocSettings:hasSidecarFile(path) then return nil end
            return DocSettings:open(path).data
        end,
        sidecar_mtime = function(path)
            local file = DocSettings:findSidecarFile(path)
            return file and lfs.attributes(file, "modification") or nil
        end,
        write_sidecar = function(path, change)
            local doc_settings = DocSettings:open(path)
            change(doc_settings.data)
            doc_settings:flush()
            reset_book_info(path)
        end,
        move_sidecar = function(from, to)
            if DocSettings.updateLocation then
                DocSettings.updateLocation(from, to)
            else
                -- Older KOReader: the notes folder sits beside the book, named after it.
                local old = from:match("(.*)%.") .. ".sdr"
                if lfs.attributes(old, "mode") == "directory" then os.rename(old, to:match("(.*)%.") .. ".sdr") end
            end
        end,
        relink = function(from, to)
            require("readhistory"):updateItem(from, to)
            local ReadCollection = require("readcollection")
            if ReadCollection.updateItem then ReadCollection:updateItem(from, to) end
            reset_book_info(from)
        end,
        forget = function(path)
            pcall(function() require("readhistory"):fileDeleted(path) end)
            pcall(function() require("readcollection"):removeItem(path) end)
            reset_book_info(path)
        end,
        purge = function(path)
            if DocSettings.updateLocation then pcall(DocSettings.updateLocation, path) end
            os.remove(path)
        end,
        reset = reset_book_info,
        collections = function()
            local ok, ReadCollection = pcall(collections_module)
            if not ok then return {} end
            local index = {}
            for name, coll in pairs(ReadCollection.coll) do
                for file in pairs(coll) do
                    index[file] = index[file] or {}
                    table.insert(index[file], name)
                end
            end
            return index
        end,
        set_collections = function(path, names)
            local ReadCollection = collections_module()
            local wanted = {}
            for _, name in ipairs(names) do
                wanted[name] = true
                if not ReadCollection.coll[name] then ReadCollection:addCollection(name) end
            end
            ReadCollection:addRemoveItemMultiple(ffiUtil.realpath(path) or path, wanted)
            ReadCollection:write()
        end,
        collections_mtime = function()
            return lfs.attributes(DataStorage:getSettingsDir() .. "/collection.lua", "modification")
        end,
        style = function()
            return { drawer = G_reader_settings:readSetting("highlight_drawing_style") or "lighten",
                color = G_reader_settings:readSetting("highlight_color") }
        end,
        open_path = open_path,
    }
end

-- One sync at a time, shared by every KOReader screen (the plugin is loaded once per screen).
local Runner = { busy = false, ticking = false, misses = 0 }
local function plain(err) return (tostring(err):gsub("^.-:%d+: ", "")) end

-- `look` allows searching the network for the phone when its address changed.
function Runner.run(automatic, look)
    if Runner.busy then
        if not automatic then message("Librillo is already syncing.") end
        return
    end
    Runner.busy = true
    local Trapper = require("ui/trapper")
    Trapper:wrap(function()
        local started, shown = os.time(), nil
        -- Progress shows at most once a second; an automatic sync only shows it after 3 seconds.
        local function progress(text)
            local now = os.time()
            if automatic and now - started < 3 then return true end
            if shown == now then return true end
            shown = now
            return Trapper:info("Librillo: " .. text .. "…")
        end
        local state, ran
        local ok, result = pcall(function()
            local cfg = Folio.syncConfig()
            cfg.discover = look ~= false
            state = load_state()
            local deps = sync_deps(progress)
            if automatic and not Sync.wants(cfg, deps, state) then return nil end
            ran = true
            return Sync.run(cfg, deps, state)
        end)
        -- Saved even when the sync failed: the hashes stay useful, and answers are only cleared
        -- once Librillo confirmed.
        if ran then pcall(save_state, state) end
        if shown then Trapper:clear() end
        Runner.busy = false
        if not ok then
            Runner.misses = Runner.misses + 1
            if not automatic then message(plain(result)) end
            return
        end
        Runner.misses = 0
        if result then
            local text = Sync.summary(result, automatic)
            if text ~= "" then message(text) end
        end
    end)
end
-- Sync now: the person asked, so KOReader may offer to turn Wi-Fi on.
function Runner.now()
    local NetworkMgr = require("ui/network/manager")
    NetworkMgr:runWhenConnected(function() Runner.run(false) end)
end
-- Automatic sync: only when set up, switched on and already connected. Never turns Wi-Fi on.
function Runner.auto(look)
    local settings = sync_settings()
    if not settings.auto_sync or not settings.set_up then return end
    local ok, connected = pcall(function() return require("ui/network/manager"):isConnected() end)
    if ok and connected then Runner.run(true, look) end
end
-- After Wi-Fi connects or a book closes: look for the phone if its address changed.
function Runner.soon()
    Runner.misses = 0
    UIManager:scheduleIn(3, function() Runner.auto(true) end)
end
function Runner.tick()
    Runner.ticking = false
    if not sync_settings().auto_sync then return end
    Runner.auto(false)
    Runner.schedule()
end
-- Every 5 minutes while automatic sync is on; each check is one small request to the phone.
-- While the phone can't be reached, the checks space out, up to every 80 minutes.
function Runner.schedule()
    if Runner.ticking then return end
    Runner.ticking = true
    UIManager:scheduleIn(300 * 2 ^ math.min(Runner.misses, 4), Runner.tick)
end
function Runner.stop()
    UIManager:unschedule(Runner.tick)
    Runner.ticking = false
end
function Folio.startAutoSync()
    local settings = sync_settings()
    if settings.auto_sync and settings.set_up then Runner.schedule() end
end

-- Questions from the last sync that have no answer yet.
local function open_questions()
    local settings = sync_settings()
    local answers = settings.answers or {}
    local list = {}
    for _, q in ipairs(settings.questions or {}) do
        if type(q) == "table" and not answers[q.id] then list[#list + 1] = q end
    end
    return list
end
-- One dialog per question, one button per option. Answers are kept and sent with the next sync,
-- which starts as soon as the last question is answered.
function Folio:questions()
    local list = open_questions()
    if #list == 0 then message("No questions right now."); return end
    local answered = false
    local i = 0
    local ask
    local function answer(q, option)
        local settings = sync_settings()
        settings.answers = settings.answers or {}
        settings.answers[q.id] = option
        save_sync_settings(settings)
        answered = true
        ask()
    end
    ask = function()
        i = i + 1
        local q = list[i]
        if not q then Runner.now(); return end
        local function later() if answered then Runner.now() end end
        if #q.options == 2 then
            UIManager:show(require("ui/widget/multiconfirmbox"):new {
                text = q.text,
                choice1_text = q.options[1], choice1_callback = function() answer(q, q.options[1]) end,
                choice2_text = q.options[2], choice2_callback = function() answer(q, q.options[2]) end,
                cancel_text = "Later", cancel_callback = later,
            })
        else
            local dialog
            local buttons = {}
            for _, option in ipairs(q.options) do
                buttons[#buttons + 1] = {{ text = option, callback = function()
                    UIManager:close(dialog); answer(q, option)
                end }}
            end
            buttons[#buttons + 1] = {{ text = "Later", callback = function() UIManager:close(dialog); later() end }}
            dialog = require("ui/widget/buttondialog"):new { title = q.text, buttons = buttons }
            UIManager:show(dialog)
        end
    end
    ask()
end
-- The library folder, inside KOReader's home folder. Books found elsewhere are moved into it.
function Folio:libraryFolder()
    local dialog
    dialog = InputDialog:new {
        title = "Library folder",
        description = "Folder inside KOReader's home folder where Librillo keeps your books.",
        input = Sync.valid_root(sync_settings().root) or Sync.ROOT,
        buttons = {{ { text = "Cancel", callback = function() UIManager:close(dialog) end },
            { text = "Save", callback = function()
                local root = Sync.valid_root(dialog:getInputText())
                if not root then message("Type a folder name, like Librillo."); return end
                local settings = sync_settings()
                settings.root = root
                save_sync_settings(settings)
                UIManager:close(dialog)
                message("Books will move to " .. root .. " at the next sync.")
            end } }},
    }
    UIManager:show(dialog)
    dialog:onShowKeyboard()
end
function Folio:findPhone()
    local NetworkMgr = require("ui/network/manager")
    NetworkMgr:runWhenOnline(function()
        local deps = link_deps()
        local ok, found = pcall(Link.discover, self:linkConfig(), deps)
        if ok and found then
            deps.save_server(found)
            message("Found Librillo at " .. found .. ". Progress sync now uses it.")
        else
            message("Librillo was not found. Turn on Librillo Link on the phone and join the same Wi-Fi or its hotspot.")
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
        local text = Link.summary(result, automatic)
        if text ~= "" then message(text) end
    end)
end
function Folio:onNetworkConnected()
    if G_reader_settings:readSetting("folio_auto_fetch", false) then
        UIManager:scheduleIn(2, function() self:fetchBooks(true) end)
    end
    Runner.soon()
end
-- A book was closed: its status and notes may have changed. Sync once the reader has closed.
function Folio:onCloseDocument()
    Runner.soon()
end
function Folio:onReaderReady()
    if G_reader_settings:readSetting("folio_auto_positions", false) then
        UIManager:nextTick(function() self:reading(true) end)
    end
end
function Folio:configure()
    local dialog
    dialog = InputDialog:new {
        title = "Librillo book folder", input = self:defaultFolder(),
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
    items.folio_bridge = { text = "Librillo", sub_item_table = {
        { text = "Sync now", callback = function() Runner.now() end },
        { text = "Sync automatically",
            checked_func = function() return sync_settings().auto_sync == true end,
            callback = function()
                local settings = sync_settings()
                settings.auto_sync = not settings.auto_sync
                settings.set_up = settings.set_up or true
                save_sync_settings(settings)
                if settings.auto_sync then Runner.schedule() else Runner.stop() end
            end },
        { text_func = function()
                return "Library folder: " .. (Sync.valid_root(sync_settings().root) or Sync.ROOT)
            end,
            keep_menu_open = true,
            callback = function() self:libraryFolder() end },
        { text_func = function() return "Questions (" .. #open_questions() .. ")" end,
            enabled_func = function() return #open_questions() > 0 end,
            callback = function() self:questions() end, separator = true },
        { text = "Get books from Librillo", callback = function() self:fetchBooks(false) end },
        { text = "Find Librillo on this network", callback = function() self:findPhone() end },
        { text = "Get books when Wi-Fi connects", checked_func = function() return G_reader_settings:readSetting("folio_auto_fetch", false) end,
            callback = function() G_reader_settings:saveSetting("folio_auto_fetch", not G_reader_settings:readSetting("folio_auto_fetch", false)) end },
        { text = "Librillo book folder", callback = function() self:configure() end },
        { text = "Continue from Librillo automatically", checked_func = function() return G_reader_settings:readSetting("folio_auto_positions", false) end,
            callback = function() G_reader_settings:saveSetting("folio_auto_positions", not G_reader_settings:readSetting("folio_auto_positions", false)) end },
        { text = "Review phone reading position", callback = function() self:reading() end },
        { text = "Review collection additions", callback = function() self:collections() end },
        { text = "Export reading statistics", callback = function() self:statistics() end },
    }}
end
return Folio
