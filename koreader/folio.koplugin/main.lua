-- SPDX-License-Identifier: AGPL-3.0-or-later
local WidgetContainer = require("ui/widget/container/widgetcontainer")
local UIManager = require("ui/uimanager")
local InfoMessage = require("ui/widget/infomessage")
local InputDialog = require("ui/widget/inputdialog")
local DataStorage = require("datastorage")
local JSON = require("rapidjson")
local lfs = require("libs/libkoreader-lfs")
local ffiUtil = require("ffi/util")
local Link
local Sync
local Stats
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
    Link = dofile(self.path .. "/link.lua")
    Sync = dofile(self.path .. "/sync.lua")
    Stats = dofile(self.path .. "/stats.lua")
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
-- Librillo's installer leaves link-setup.json beside the plugin with the phone's address, sign-in and
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
        message("Librillo's setup file couldn't be read. Set up the e-reader again from Librillo on your phone.")
        return
    end
    local settings = sync_settings()
    settings.root = options.root
    settings.auto_sync = options.auto_sync
    settings.device_name = options.device_name
    settings.set_up = options.version
    save_sync_settings(settings)
    G_reader_settings:flush()
    UIManager:scheduleIn(1, function()
        message("Librillo is set up. Restart KOReader once more, then tap Librillo > Sync now.")
    end)
end
local function sha256_file(path)
    local loaded, sha2 = pcall(require, "ffi/sha2")
    if not loaded or type(sha2.sha256) ~= "function" then return nil end
    local file = io.open(path, "rb")
    if not file then return nil end
    -- Streams the file. Returns nil when this KOReader can't hash files; sync then stops.
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
-- What finding Librillo on the network needs: a broadcast, MD5 for the phone's proof, a nonce,
-- and a place to remember the phone's new address.
local function discovery_deps()
    local socket = require("socket")
    return {
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
    local discovery = discovery_deps()
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
        decode = function(text) return JSON.decode(text) end,
        encode = function(value) return JSON.encode(value) end,
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
        discover = function(cfg) return Link.discover(cfg, discovery) end,
        save_server = discovery.save_server,
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
        -- KOReader's reading statistics, or nil when there are none. Only read (stats.lua).
        open_statistics = function()
            local path = DataStorage:getSettingsDir() .. "/statistics.sqlite3"
            if lfs.attributes(path, "mode") ~= "file" then return nil end
            return require("lua-ljsqlite3/init").open(path)
        end,
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
            local done = Sync.run(cfg, deps, state)
            -- Reading time follows a confirmed sync; it never fails it.
            Stats.send(cfg, deps, Sync.headers(cfg))
            return done
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
function Folio:libraryFolder(menu)
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
                if type(menu) == "table" and menu.updateItems then menu:updateItems() end
                message("Books will move to " .. root .. " at the next sync.")
            end } }},
    }
    UIManager:show(dialog)
    dialog:onShowKeyboard()
end
-- Wi-Fi connected: sync if something changed, looking for the phone if its address changed.
function Folio:onNetworkConnected()
    Runner.soon()
end
-- A book was closed: its status and notes may have changed. Sync once the reader has closed.
function Folio:onCloseDocument()
    Runner.soon()
end
-- What the plugin does, in one line.
local ABOUT = "Librillo keeps the books on this e-reader, with their reading status, highlights and "
    .. "collections, the same as Librillo on your phone."
-- The Librillo menu, built each time it opens, so Questions only shows when there are some.
function Folio:menuItems()
    local items = {
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
            callback = function(menu) self:libraryFolder(menu) end },
    }
    if #open_questions() > 0 then
        items[#items + 1] = { text_func = function() return "Questions (" .. #open_questions() .. ")" end,
            enabled_func = function() return #open_questions() > 0 end,
            callback = function() self:questions() end }
    end
    items[#items + 1] = { text = "About", keep_menu_open = true, callback = function() message(ABOUT) end }
    return items
end
function Folio:addToMainMenu(items)
    items.folio_bridge = { text = "Librillo", sub_item_table_func = function() return self:menuItems() end }
end
return Folio
