-- SPDX-License-Identifier: AGPL-3.0-or-later
-- Independently implemented protocol validation; no KOReader source copied.
local Bridge = {}
function Bridge.validate(request, root, realpath, attributes)
    assert(type(request) == "table" and request.format == "folio-collections" and request.version == 1, "Unsupported request")
    assert(type(request.shelves) == "table" and #request.shelves <= 1000, "Too many collections")
    local plan, names, count = {}, {}, 0
    for _, shelf in ipairs(request.shelves) do
        assert(type(shelf.name) == "string" and #shelf.name > 0 and #shelf.name <= 120 and not shelf.name:find("%c"), "Invalid collection name")
        local name = "Folio: " .. shelf.name
        assert(not names[name], "Duplicate collection")
        names[name] = true
        assert(type(shelf.paths) == "table", "Invalid membership")
        local paths = {}
        for _, path in ipairs(shelf.paths) do
            count = count + 1
            assert(count <= 10000 and type(path) == "string" and #path <= 4096, "Too many or invalid paths")
            local canonical = realpath(path)
            local attr = canonical and attributes(canonical)
            assert(canonical and canonical:sub(1, #root + 1) == root .. "/" and canonical:lower():sub(-5) == ".epub" and attr and attr.mode == "file", "Book outside configured folder or missing")
            paths[canonical] = true
        end
        plan[#plan + 1] = { name = name, paths = paths }
    end
    return plan
end
function Bridge.apply(plan, collections)
    local changed = {}
    for _, shelf in ipairs(plan) do
        if not collections.coll[shelf.name] then collections:addCollection(shelf.name) end
        collections:addItemsMultiple(shelf.paths, { [shelf.name] = true })
        changed[shelf.name] = true
    end
    collections:write(changed)
end
-- Reject unbounded, stale, escaping or mismatched handoffs. Partial digest supplements the
-- phone's full SHA-256 comparison; it is never used as Folio's book identity.
function Bridge.reading(request, root, current_path, digest, realpath, attributes, now)
    assert(type(request) == "table" and request.format == "folio-reading" and request.version == 1, "Unsupported reading request")
    assert(type(request.id) == "string" and request.id:match("^[0-9a-f]+%-[0-9a-f]+%-[0-9a-f]+%-[0-9a-f]+%-[0-9a-f]+$") and #request.id == 36, "Invalid request ID")
    assert(type(request.createdAt) == "number" and request.createdAt <= (now + 300) * 1000 and request.createdAt >= (now - 7 * 86400) * 1000, "Reading request expired")
    assert(type(request.path) == "string" and #request.path <= 4096, "Invalid path")
    local path = realpath(request.path)
    local attr = path and attributes(path)
    assert(path and path:sub(1, #root + 1) == root .. "/" and path == realpath(current_path) and attr and attr.mode == "file", "Open the requested EPUB inside the configured folder first")
    assert(type(request.digest) == "string" and #request.digest == 32 and request.digest:match("^[0-9a-f]+$") and request.digest == digest, "EPUB digest differs")
    assert(type(request.revision) == "string" and #request.revision == 64 and request.revision:match("^[0-9a-f]+$"), "Missing phone revision")
    assert(type(request.position) == "string" and #request.position > 0 and #request.position <= 4096 and not request.position:find("%c") and request.position:sub(1, 5) == "/body", "Invalid position")
    return request
end
return Bridge
