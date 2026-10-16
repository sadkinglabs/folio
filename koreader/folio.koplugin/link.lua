-- SPDX-License-Identifier: AGPL-3.0-or-later
-- Finding Librillo on the network and applying the one-time setup Librillo's installer leaves.
-- Sync itself is in sync.lua. Network and file access are passed in, so the logic runs (and is
-- tested) outside KOReader.
-- API is the version the phone states when it answers the network search.
local Link = { API = 1 }

-- Finds Librillo on the local network. The phone proves it knows the sign-in key by answering
-- MD5(key .. nonce); answers without that proof are ignored, so the key is never sent to an
-- impostor. Returns "http://ip:port" or nil.
function Link.discover(cfg, d)
    assert(cfg.key, "Set up Librillo from your phone first.")
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

-- The one-time setup Librillo's installer leaves beside the plugin: the phone's address and the
-- sign-in for Progress sync, and (version 2) the library folder and sync settings. Data only:
-- anything unexpected is ignored. Returns the updated Progress sync settings and the plugin's
-- options ({ version, auto_sync, root, device_name }), or nil when the setup is not usable.
-- Progress sync is set to sync automatically and to ask before jumping to a newer or older place
-- (1 = prompt), unless those were already chosen on this e-reader.
local STRATEGIES = { prompt = 1, silent = 2, disable = 3 }
function Link.setup(text, decode, kosync)
    local ok, setup = pcall(decode, text)
    if not ok or type(setup) ~= "table" or setup.format ~= "librillo-link-setup"
        or (setup.version ~= 1 and setup.version ~= 2) then
        return nil
    end
    local function clean(value, pattern)
        if type(value) == "string" and #value <= 200 and value:match(pattern) then return value end
        return nil
    end
    local server = clean(setup.server, "^https?://[%w%.%-]+:%d+$")
    local username = clean(setup.username, "^[%w%._%-]+$")
    local userkey = clean(setup.userkey, "^%x+$")
    if not (server and username and userkey) then return nil end
    local updated = {}
    for key, value in pairs(type(kosync) == "table" and kosync or {}) do updated[key] = value end
    updated.custom_server = server
    updated.username = username
    updated.userkey = userkey
    updated.auto_sync = true
    if updated.sync_forward == nil then updated.sync_forward = STRATEGIES[setup.sync_forward] or 1 end
    if updated.sync_backward == nil then updated.sync_backward = 1 end
    -- The library folder: a plain relative path under KOReader's home folder.
    local root = clean(setup.root, "^[^/\\%c][^\\%c]*$")
    if root then
        for part in (root .. "/"):gmatch("([^/]*)/") do
            if part == "" or part:sub(1, 1) == "." then root = nil; break end
        end
    end
    local name = type(setup.device_name) == "string" and #setup.device_name <= 64
        and not setup.device_name:find("%c") and setup.device_name:match("%S") and setup.device_name or nil
    local options = { version = setup.version, root = root or "Librillo", device_name = name }
    -- Version 1 came from a Librillo without two-way sync: Progress sync only, no automatic sync.
    options.auto_sync = setup.version == 2 and setup.auto_sync ~= false
    return updated, options
end

-- Applies the setup to both places KOReader keeps Progress sync's settings: settings/kosync.lua
-- (KOReader 2024 and later) and the older "kosync" reader setting. Each keeps its own choices.
-- `store` reads and writes them. Returns the plugin's options, or nil when the setup is not usable.
function Link.install(text, store)
    local current = store.read_kosync()
    local updated, options = Link.setup(text, store.decode, current)
    if not updated then return nil end
    local legacy = Link.setup(text, store.decode, store.read_legacy())
    store.write_kosync(updated)
    store.write_legacy(legacy)
    return options
end

return Link
