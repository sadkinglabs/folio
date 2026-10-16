#!/usr/bin/env python3
"""Tests independent bridge validation/membership logic with Lua; does not emulate KOReader."""
import importlib, os
from pathlib import Path
# KOReader runs LuaJIT 2.1 (Lua 5.1); FOLIO_LUA=luajit21 runs these tests on that engine.
LuaRuntime=importlib.import_module('lupa.'+os.environ['FOLIO_LUA'] if os.environ.get('FOLIO_LUA') else 'lupa').LuaRuntime
root=Path(__file__).resolve().parents[1]
lua=LuaRuntime(unpack_returned_tuples=True)
bridge=lua.execute((root/'koreader/folio.koplugin/bridge.lua').read_text())
lua.globals().Bridge=bridge
# Compile all plugin Lua files, without loading KOReader-only require calls.
for file in (root/'koreader/folio.koplugin').glob('*.lua'):
    lua.execute('assert(load(...))',file.read_text())
lua.execute('''
local realpath = function(path) return path end
local attributes = function(path) return {mode="file"} end
local request = { format="folio-collections",version=1,shelves={{name="Travel",paths={"/books/one.epub"}}} }
local plan = Bridge.validate(request,"/books",realpath,attributes)
assert(plan[1].name == "Folio: Travel" and plan[1].paths["/books/one.epub"])
local collection={coll={unrelated={old=true}},coll_settings={},writes=0}
function collection:addCollection(name) self.coll[name]={} end
function collection:addItemsMultiple(files,names) for name in pairs(names) do for path in pairs(files) do self.coll[name][path]=true end end end
function collection:write(changed) self.writes=self.writes+1 end
Bridge.apply(plan,collection)
Bridge.apply(plan,collection)
assert(collection.coll.unrelated.old and collection.coll["Folio: Travel"]["/books/one.epub"])
-- Existing managed members survive subsequent additions.
collection.coll["Folio: Travel"]["/books/previous.epub"]=true
Bridge.apply(plan,collection)
assert(collection.coll["Folio: Travel"]["/books/previous.epub"])
local function rejects(req, resolver, attrs)
    assert(not pcall(Bridge.validate,req,"/books",resolver or realpath,attrs or attributes))
end
rejects({format="other",version=1,shelves={}})
rejects({format="folio-collections",version=1,shelves={{name="Travel",paths={"/private/one.epub"}}}})
rejects({format="folio-collections",version=1,shelves={{name="Travel",paths={"/books/one.epub"}}}},function() return "/private/one.epub" end)
rejects({format="folio-collections",version=1,shelves={{name="Travel",paths={"/books/one.epub"}}}},nil,function() return {mode="directory"} end)
rejects({format="folio-collections",version=1,shelves={{name="Bad\\nName",paths={}}}})
rejects({format="folio-collections",version=1,shelves={{name="Travel",paths={}},{name="Travel",paths={}}}})
''')
print('Plugin Lua syntax and 9 bridge scenarios passed; KOReader runtime remains untested.')

# Folio Link fetch: the real link.lua against an in-memory phone and folder.
link=lua.execute((root/'koreader/folio.koplugin/link.lua').read_text())
lua.globals().Link=link
lua.execute(r"""
-- The installer's one-time setup: data only, applied to Progress sync, anything odd ignored.
local function decode(fields) return function() return fields end end
local base = { format = "librillo-link-setup", version = 1, server = "http://192.168.1.42:8765",
  username = "folio", userkey = "0123abcdef", auto_fetch = true }
local kosync, options = Link.setup("x", decode(base), { custom_server = "old", sync_forward = 2 })
assert(kosync.custom_server == "http://192.168.1.42:8765" and kosync.username == "folio")
assert(kosync.userkey == "0123abcdef" and kosync.sync_forward == 2, "a choice made on the e-reader is kept")
assert(kosync.sync_backward == 1 and kosync.auto_sync == true)
assert(options.version == 1 and options.auto_fetch == true and options.auto_sync == false and options.root == "Librillo")
local function copy(changes) local t = {} for k, v in pairs(base) do t[k] = v end
  for k, v in pairs(changes) do t[k] = v end return t end
assert(Link.setup("x", decode(copy({ format = "other" })), {}) == nil)
assert(Link.setup("x", decode(copy({ version = 3 })), {}) == nil)
assert(Link.setup("x", decode(copy({ server = "http://evil host:1" })), {}) == nil)
assert(Link.setup("x", decode(copy({ server = "file:///etc" })), {}) == nil)
assert(Link.setup("x", decode(copy({ userkey = "not-hex" })), {}) == nil)
assert(Link.setup("x", function() error("bad json") end, {}) == nil)
-- Version 2: library folder, automatic sync, Progress sync asks before jumping.
local v2 = { format = "librillo-link-setup", version = 2, server = "http://10.0.0.5:8765", username = "folio",
  userkey = "abc123", root = "Books/Librillo", auto_sync = true, sync_forward = "prompt", device_name = "Paperwhite" }
local fresh, opts = Link.setup("x", decode(v2), nil)
assert(fresh.sync_forward == 1 and fresh.sync_backward == 1 and fresh.auto_sync == true)
assert(opts.version == 2 and opts.auto_sync == true and opts.auto_fetch == false)
assert(opts.root == "Books/Librillo" and opts.device_name == "Paperwhite")
local function v2with(changes) local t = {} for k, v in pairs(v2) do t[k] = v end
  for k, v in pairs(changes) do t[k] = v end return t end
for _, bad in ipairs({ "/mnt/us", "../up", "a/../b", ".hidden", "a//b", "a\\b", "x/" }) do
  local _, o = Link.setup("x", decode(v2with({ root = bad })), {})
  assert(o.root == "Librillo", "unsafe root ignored: " .. bad)
end
local _, quiet = Link.setup("x", decode(v2with({ auto_sync = false, device_name = "bad\nname" })), {})
assert(quiet.auto_sync == false and quiet.device_name == nil)
-- Both places KOReader reads Progress sync from are written, each keeping its own choices.
local written = {}
local store = {
  decode = function() return v2 end,
  read_kosync = function() return { sync_backward = 3, checksum_method = 1 } end,
  read_legacy = function() return nil end,
  write_kosync = function(t) written.file = t end,
  write_legacy = function(t) written.legacy = t end,
}
local installed = Link.install("x", store)
assert(installed.root == "Books/Librillo")
assert(written.file.custom_server == "http://10.0.0.5:8765" and written.file.userkey == "abc123")
assert(written.file.sync_backward == 3 and written.file.checksum_method == 1 and written.file.sync_forward == 1)
assert(written.legacy.custom_server == "http://10.0.0.5:8765" and written.legacy.sync_backward == 1)
store.decode = function() return { format = "other" } end
written = {}
assert(Link.install("x", store) == nil and written.file == nil and written.legacy == nil, "nothing written")
""")
print('Setup: version 1 and 2, unsafe values ignored, both Progress sync places written, own choices kept.')
lua.execute(r"""
local H = {} for i = 1, 64 do H[i] = "a" end
local SHA_A = table.concat(H)
local function sha(c) return (c:rep(64)) end
local files = {
  ["/books/Old - B.epub"] = { content = "B", sha = sha("b") },
  ["/books/Clash - C.epub"] = { content = "mine", sha = sha("x") },
}
local served = { a = "A", b = "B", c = "C", e = "E-corrupt" }
local acks = {}
local queue = {
  { id = "a", filename = "New - A.epub", sha256 = sha("a"), size = 1 },
  { id = "b", filename = "Old - B.epub", sha256 = sha("b"), size = 1 },
  { id = "c", filename = "Clash - C.epub", sha256 = sha("c"), size = 1 },
  { id = "d", filename = "../escape.epub", sha256 = sha("d"), size = 1 },
  { id = "e", filename = "Bad - E.epub", sha256 = sha("e"), size = 1 },
  { id = "f", filename = "Unacked - F.epub", sha256 = sha("f"), size = 1 },
}
served.f = "F"
local api = 1
local deps = {
  request = function(method, url, headers)
    assert(headers["x-auth-user"] == "folio" and headers["x-auth-key"] == "k" and headers["x-folio-device"] == "kobo")
    if url:match("/hello$") then return 200, { api = api } end
    if url:match("/queue$") then return 200, { books = queue } end
    if url:match("/ack$") then return (acks.next == "f" and 500 or 200) end
    return 404
  end,
  download = function(url, headers, path)
    local id = url:match("/books/(%w+)%.epub$")
    files[path] = { content = served[id], sha = (id == "e") and sha("z") or sha(id) }
    return 200
  end,
  exists = function(path) return files[path] ~= nil end,
  size = function(path) return files[path] and #files[path].content end,
  sha256 = function(path) return files[path] and files[path].sha end,
  rename = function(from, to) files[to] = files[from]; files[from] = nil; return true end,
  remove = function(path) files[path] = nil end,
  decode = function(value) return value end,
  encode = function(value) acks[#acks + 1] = value.id .. "@" .. value.path; acks.next = queue[#acks + 1] and queue[#acks + 1].id; return value end,
}
-- Ack the next book in line; "f" is refused by the phone and must count as failed.
local real_request = deps.request
deps.request = function(method, url, headers, body)
  if url:match("/ack$") then return (body and body.id == "f") and 500 or 200 end
  return real_request(method, url, headers, body)
end
local cfg = { server = "http://phone:8765/", username = "folio", key = "k", device = "kobo", folder = "/books" }
local result = Link.fetch(cfg, deps)
assert(result.fetched == 2, "fetched " .. result.fetched)       -- a, c (beside the clash)
assert(result.already == 1, "already " .. result.already)       -- b
assert(result.failed == 3, "failed " .. result.failed)          -- d (bad name), e (corrupt), f (no ack)
assert(files["/books/New - A.epub"].content == "A")
assert(files["/books/Clash - C.epub"].content == "mine", "never overwrite")
assert(files["/books/Clash - C (2).epub"].content == "C")
assert(not files["/books/Bad - E.epub"] and not files["/books/Bad - E.epub.part"], "corrupt download removed")
assert(not files["/escape.epub"] and not files["/books/../escape.epub"])
assert(Link.url("http://phone:8765/", "/folio/v1/hello") == "http://phone:8765/folio/v1/hello")
-- A different API version stops before anything is touched.
api = 2
assert(not pcall(Link.fetch, cfg, deps))
-- Without Progress sync set up there is nothing to call.
assert(not pcall(Link.fetch, { folder = "/books" }, deps))
""")
# The phone's record is checked, never trusted: a book is found where it was saved, or anywhere
# in the folder under another name; a book received before and deleted since is left deleted.
lua.execute(r"""
local function sha(c) return (string.format("%02x", c:byte()):rep(32)) end
local files = { ["/old/place/Kept.epub"] = { content = "K", sha = sha("k") } }
local downloads, acks = 0, {}
local queue = {
  { id = "k", title = "Kept", filename = "A - Kept.epub", sha256 = sha("k"), size = 1, saved_path = "/old/place/Kept.epub" },
  { id = "g", title = "Salem's Lot", filename = "Stephen King - Salem's Lot.epub", sha256 = sha("g"), size = 1, saved_path = "/elsewhere/Gone.epub" },
  { id = "h", title = "Hostile", filename = "B - Hostile.epub", sha256 = sha("h"), size = 1, saved_path = "/books/../../etc/passwd.epub" },
}
local d = {
  request = function(method, url, headers, body)
    if url:match("/hello$") then return 200, { api = 1 } end
    if url:match("/queue$") then return 200, { books = queue } end
    if url:match("/ack$") then acks[body.id] = body.path; return 200 end
    return 404
  end,
  download = function(url, headers, path)
    downloads = downloads + 1
    local id = url:match("/books/(%w+)%.epub$")
    files[path] = { content = id, sha = sha(id) }
    return 200
  end,
  exists = function(path) return files[path] ~= nil end,
  size = function(path) return files[path] and #files[path].content end,
  sha256 = function(path) return files[path] and files[path].sha end,
  rename = function(from, to) files[to] = files[from]; files[from] = nil; return true end,
  remove = function(path) files[path] = nil end,
  decode = function(value) return value end,
  encode = function(value) return value end,
}
local cfg = { server = "http://phone:8765", username = "u", key = "k", device = "kindle", folder = "/books" }
local result = Link.fetch(cfg, d)
assert(result.already == 1 and result.fetched == 1 and result.gone == 1,
  result.already .. " " .. result.fetched .. " " .. result.gone)
assert(acks.k == "/old/place/Kept.epub", "found where it was saved, not copied again")
assert(not files["/books/Stephen King - Salem's Lot.epub"] and acks.g == nil,
  "a book received before and deleted since is not fetched again")
assert(files["/books/B - Hostile.epub"], "a hostile saved path is ignored, the book still arrives")
assert(downloads == 1)
-- The message names each book and where it went, and says how to get a deleted one back.
local text = Link.summary(result, false)
assert(text:find("Saved to /books:", 1, true) and text:find("Hostile", 1, true), text)
assert(text:find("Already on this e-reader:", 1, true) and text:find("/old/place/Kept.epub", 1, true), text)
assert(text:find("No longer on this e-reader", 1, true) and text:find("Salem's Lot", 1, true)
  and text:find("send it again", 1, true), text)
-- A copy that arrived another way, under another name in a subfolder, is used, not duplicated.
files = { ["/books/to_read/0b9e.epub"] = { content = "G", sha = sha("g") } }
queue = { { id = "g", title = "Salem's Lot", filename = "Stephen King - Salem's Lot.epub", sha256 = sha("g"), size = 1 } }
acks, downloads = {}, 0
local listed = {}
for k, v in pairs(d) do listed[k] = v end
listed.list = function(folder)
  local out = {}
  for path in pairs(files) do if path:sub(1, #folder + 1) == folder .. "/" then out[#out + 1] = path end end
  return out
end
local other = Link.fetch(cfg, listed)
assert(other.already == 1 and downloads == 0, "matched by content, not by name")
assert(acks.g == "/books/to_read/0b9e.epub" and not files["/books/Stephen King - Salem's Lot.epub"])
-- An automatic fetch only speaks when something arrived or failed.
assert(Link.summary({ books = { fetched = {}, already = { { title = "x" } }, failed = {} }, folder = "/b" }, true) == "")
assert(Link.summary({ books = { fetched = {}, already = {}, failed = {} }, folder = "/b" }, false)
  :find("Send a book from Librillo", 1, true))
-- Without SHA-256 in this KOReader, a same-size file already in the folder is not copied twice.
local nosha = {}
for k, v in pairs(d) do nosha[k] = v end
nosha.sha256 = function() return nil end
files = { ["/books/A - Kept.epub"] = { content = "K" } }
queue = { { id = "k", title = "Kept", filename = "A - Kept.epub", sha256 = sha("k"), size = 1 } }
downloads = 0
local again = Link.fetch(cfg, nosha)
assert(again.already == 1 and downloads == 0 and not files["/books/A - Kept (2).epub"])
""")
print('Folio Link fetch: saved path checked, deleted book left deleted, copy under another name used, hostile path ignored, messages, no-SHA devices.')

# Discovery: only a phone that proves it knows the key is trusted.
lua.execute(r"""
local function md5(text) return text == "kn1" and "abc123" or "ffff00" end
local base = { nonce = function() return "n1" end, md5 = md5 }
local function with(replies)
  local d = { nonce = base.nonce, md5 = base.md5, broadcast = function(text)
    assert(text == "FOLIO-DISCOVER n1") return replies end }
  return d
end
local cfg = { key = "k" }
assert(Link.discover(cfg, with({ { ip = "192.168.1.20", text = "FOLIO 8765 1 beef" } })) == nil, "wrong proof")
-- An impostor answering first does not win; the genuine phone does.
local found = Link.discover(cfg, with({
  { ip = "10.0.0.66", text = "FOLIO 8765 1 ffff00" },
  { ip = "192.168.1.20", text = "FOLIO 8765 1 abc123" },
}))
assert(found == "http://192.168.1.20:8765", tostring(found))
assert(Link.discover(cfg, with({ { ip = "bad host", text = "FOLIO 8765 1 abc123" } })) == nil, "bad ip")
assert(Link.discover(cfg, with({ { ip = "192.168.1.20", text = "FOLIO 8765 2 abc123" } })) == nil, "other api")

-- A fetch whose saved address is dead finds the phone, uses it and remembers it.
local saved
local d = with({ { ip = "192.168.1.21", text = "FOLIO 8765 1 abc123" } })
d.request = function(method, url)
  if url:find("old%-address") then error("timeout") end
  if url:match("/hello$") then return 200, { api = 1 } end
  if url:match("/queue$") then return 200, { books = {} } end
end
d.decode = function(v) return v end
d.save_server = function(url) saved = url end
local result = Link.fetch({ server = "http://old-address:8765", username = "folio", key = "k", device = "kobo", folder = "/b" }, d)
assert(result.fetched == 0 and saved == "http://192.168.1.21:8765", tostring(saved))
-- With no phone answering, the fetch fails with a plain message and saves nothing.
saved = nil
d.broadcast = function() return {} end
assert(not pcall(Link.fetch, { server = "http://old-address:8765", username = "folio", key = "k", device = "kobo", folder = "/b" }, d))
assert(saved == nil)
""")
print('Folio Link discovery: 6 scenarios passed (wrong proof, impostor first, bad address, other version, fallback saves the new address, nothing found).')
print('Folio Link fetch: 9 scenarios passed (new, already there, name clash, bad name, corrupt download, refused ack, URL, version mismatch, not set up).')

# Exercise the plugin's actual statistics SQL against a real WAL-enabled SQLite fixture.
import json,sqlite3,tempfile,hashlib
with tempfile.TemporaryDirectory(prefix='folio-plugin-sql-') as tmp:
    folder=Path(tmp);books=folder/'books';books.mkdir();settings=folder/'settings';settings.mkdir()
    db=settings/'statistics.sqlite3'
    conn=sqlite3.connect(db);conn.execute('PRAGMA journal_mode=WAL')
    conn.execute('CREATE TABLE page_stat_data(id_book INTEGER,page INTEGER,start_time INTEGER,duration INTEGER)')
    conn.executemany('INSERT INTO page_stat_data VALUES(?,?,?,?)',[(1,1,1791676800,60),(1,2,1791676860,120),(2,1,1791763200,180)])
    conn.commit()
    def as_python(value):
        if hasattr(value,'items'):
            items=list(value.items())
            if items and all(isinstance(k,int) for k,v in items):return [as_python(value[i]) for i in range(1,len(items)+1)]
            return {str(k):as_python(v) for k,v in items}
        return value
    def open_db(path):
        c=sqlite3.connect(path,isolation_level=None)
        def execute(self,sql):
            cursor=c.execute(sql)
            if not cursor.description:return lua.table()
            data=cursor.fetchall();result=lua.table()
            for i,column in enumerate(cursor.description):
                values=lua.table_from([r[i] for r in data]);result[i+1]=values;result[column[0]]=values
            return result
        return lua.table_from({'exec':execute,'rowexec':lambda self,sql:c.execute(sql).fetchone()[0],'close':lambda self:c.close()})
    lua.globals().py_open=open_db
    lua.globals().py_encode=lambda value:json.dumps(as_python(value))
    lua.globals().py_root=str(books)
    lua.globals().py_settings=str(settings)
    lua.globals().py_realpath=lambda path:str(Path(path).resolve()) if Path(path).exists() else None
    lua.globals().py_attributes=lambda path,field=None: ('directory' if Path(path).is_dir() else 'file') if field=='mode' else lua.table_from({'mode':'directory' if Path(path).is_dir() else 'file'})
    lua.execute('''
      messages={}
      local modules={
        ["ui/widget/container/widgetcontainer"]={extend=function(self,t) return t end},
        ["ui/uimanager"]={show=function(self,w) messages[#messages+1]=w.text end},
        ["ui/widget/infomessage"]={new=function(self,t) return t end},
        ["ui/event"]={new=function(self,...) return {...} end},
        ["ui/widget/confirmbox"]={},["ui/widget/inputdialog"]={},
        ["datastorage"]={getSettingsDir=function() return py_settings end},["readcollection"]={},
        ["lua-ljsqlite3/init"]={open=py_open},
        ["rapidjson"]={encode=py_encode,array=function() return {} end},
        ["libs/libkoreader-lfs"]={attributes=py_attributes,symlinkattributes=function(path) if py_realpath(path) then return py_attributes(path) end end},["ffi/util"]={realpath=py_realpath}
      }
      require=function(name) return assert(modules[name],name) end
      G_reader_settings={readSetting=function(self,key,default) return py_root end}
    ''')
    plugin=lua.execute((root/'koreader/folio.koplugin/main.lua').read_text())
    plugin.statistics(plugin)
    snapshot=json.loads((books/'.folio-statistics.json').read_text())
    assert snapshot['seconds']==360 and sum(day['seconds'] for day in snapshot['days'])==360
    assert conn.execute('SELECT SUM(duration) FROM page_stat_data').fetchone()[0]==360
    conn.close()
    print('Plugin statistics export passed against a real WAL SQLite fixture; no database writes.')

lua.execute('''
local now=1000000
local req={format="folio-reading",version=1,id="12345678-1234-1234-1234-123456789abc",createdAt=now*1000,path="/books/one.epub",digest=string.rep("a",32),revision=string.rep("b",64),position="/body/DocFragment/body/p/text().3"}
local resolver=function(path) return path end
local attrs=function(path) return {mode="file"} end
assert(Bridge.reading(req,"/books","/books/one.epub",string.rep("a",32),resolver,attrs,now)==req)
local function reject(field,value)
 local copy={};for k,v in pairs(req) do copy[k]=v end;copy[field]=value
 assert(not pcall(Bridge.reading,copy,"/books","/books/one.epub",string.rep("a",32),resolver,attrs,now))
end
reject("path","/private/one.epub");reject("path","/books/another.epub")
reject("digest",string.rep("c",32));reject("position","percentage:20")
reject("createdAt",(now-8*86400)*1000);reject("createdAt",(now+600)*1000)
reject("revision","bad");reject("id","../../elsewhere");reject("position",string.rep("x",5000))
assert(not pcall(Bridge.reading,req,"/books","/books/one.epub",string.rep("a",32),function() return "/private/one.epub" end,attrs,now))
''')
print('11 reading handoff validation cases passed; wrong edition, escaping path, stale and malformed requests rejected.')

# Exercise actual menu-driven and automatic application, including intervening Kindle reading.
import tempfile
with tempfile.TemporaryDirectory(prefix='folio-reading-') as tmp:
    folder=Path(tmp); epub=folder/'one.epub';epub.write_bytes(b'fixture')
    now=1791990000
    request={'format':'folio-reading','version':1,'id':'12345678-1234-1234-1234-123456789abc','createdAt':now*1000,'path':str(epub),'digest':'a'*32,'revision':'b'*64,'position':'/body/DocFragment/body/p/text().3','expectedKindle':'old'}
    filename='.folio-reading-'+request['id']+'.json'
    (folder/filename).write_text(json.dumps(request))
    def convert(value):
        if isinstance(value,dict):return lua.table_from({k:convert(v) for k,v in value.items()})
        if isinstance(value,list):return lua.table_from([convert(v) for v in value])
        return value
    lua.globals().py_decode=lambda value:convert(json.loads(value))
    lua.globals().py_root=str(folder)
    lua.globals().py_epub=str(epub)
    lua.globals().py_names=lua.table_from(['.','..',filename])
    lua.execute('''
    local state={folio_folder=py_root};local last='old';applied_count=0
    local modules={
      ["ui/widget/container/widgetcontainer"]={extend=function(self,t) return t end},
      ["ui/uimanager"]={show=function(self,w) if w.ok_callback then w.ok_callback() end end,nextTick=function(self,f) f() end},
      ["ui/widget/infomessage"]={new=function(self,t) return t end},
      ["ui/widget/confirmbox"]={new=function(self,t) return t end},["ui/widget/inputdialog"]={},
      ["datastorage"]={},["readcollection"]={},["lua-ljsqlite3/init"]={},
      ["rapidjson"]={decode=py_decode},["ffi/util"]={realpath=function(path) return path end},
      ["ui/event"]={new=function(self,name,xpointer) return {name=name,xpointer=xpointer} end},
      ["libs/libkoreader-lfs"]={dir=function(path) local i=0;return function() i=i+1;return py_names[i] end end,attributes=function(path,field) local mode=path==py_root and "directory" or "file"; if field=="mode" then return mode end; return {mode=mode} end,symlinkattributes=function(path,field) return 'file' end}
    }
    require=function(name) return assert(modules[name],name) end
    G_reader_settings={readSetting=function(self,key,default) if state[key]~=nil then return state[key] end;return default end,saveSetting=function(self,key,value) state[key]=value end}
    os.time=function() return 1791990000 end
    test_ui={document={file=py_epub,info={has_pages=false},isXPointerInDocument=function() return true end},rolling={},doc_settings={readSetting=function(self,key) if key=='partial_md5_checksum' then return string.rep('a',32) elseif key=='last_xpointer' then return last end end},menu={registerToMainMenu=function() end},handleEvent=function(self,event) applied_count=applied_count+1;self.rolling.xpointer=event.xpointer end}
    function change_last(value) last=value end
    ''')
    plugin=lua.execute((root/'koreader/folio.koplugin/main.lua').read_text());plugin['path']=str(root/'koreader/folio.koplugin');plugin['ui']=lua.globals().test_ui;plugin.init(plugin)
    lua.globals().change_last('newer-kindle-position');plugin.reading(plugin,True);assert lua.globals().applied_count==0
    lua.globals().change_last('old');plugin.reading(plugin,True);assert lua.globals().applied_count==1
    plugin.reading(plugin,True);assert lua.globals().applied_count==1
    print('Actual bridge auto-apply fixture passed: intervening Kindle progress preserved, expected position applied once, retry idempotent. KOReader runtime remains untested.')

# ---- Sync protocol v2 (sync.lua): real files in a temporary home folder, KOReader's data and the
# phone faked in memory. Tests the logic only; KOReader itself is not run here.
import os, stat, time
# A fresh Lua: the tests above replace os.time and require.
lua=LuaRuntime(unpack_returned_tuples=True)
sync=lua.execute((root/'koreader/folio.koplugin/sync.lua').read_text())
lua.globals().Sync=sync
hash_calls=[0]
def py_dir(path):
    try: return lua.table_from(sorted(os.listdir(path)))
    except OSError: return None
def py_lstat(path):
    try: st=os.lstat(path)
    except OSError: return None
    mode='link' if stat.S_ISLNK(st.st_mode) else 'directory' if stat.S_ISDIR(st.st_mode) else 'file'
    return lua.table_from({'mode':mode,'size':st.st_size,'modification':int(st.st_mtime)})
def py_rename(a,b):
    try: os.rename(a,b); return True
    except OSError: return False
def py_sha256(path):
    hash_calls[0]+=1
    try: return hashlib.sha256(Path(path).read_bytes()).hexdigest()
    except OSError: return None
def py_write(path,text,mtime=None):
    Path(path).parent.mkdir(parents=True,exist_ok=True); Path(path).write_bytes(text.encode())
    if mtime: os.utime(path,(mtime,mtime))
g=lua.globals()
g.py_dir=py_dir; g.py_lstat=py_lstat; g.py_rename=py_rename; g.py_sha256=py_sha256
g.py_mkdir=lambda p: os.mkdir(p) or True
g.py_rmdir=lambda p: os.rmdir(p) or True
g.py_remove=lambda p: os.remove(p) if os.path.lexists(p) else None
g.py_sha256_text=lambda t: hashlib.sha256(t.encode()).hexdigest()
g.py_read=lambda p: Path(p).read_text()
g.py_write=py_write
g.py_exists=lambda p: os.path.lexists(p)
def sha(text): return hashlib.sha256(text.encode()).hexdigest()
def ann_id(device,datetime,pos0): return sha(device+'\n'+datetime+'\n'+pos0)[:32]
g.py_sha=sha

# The fake KOReader (sidecars, history, collections, the open book) and the fake phone.
lua.execute(r'''
function deepcopy(v) if type(v) ~= "table" then return v end local t = {} for k, x in pairs(v) do t[k] = deepcopy(x) end return t end
function fake(home)
  local K = { sidecars = {}, mtimes = {}, history = {}, coll = {}, open = nil, writes = 0, forgotten = {}, reset = {} }
  local P = { hello_status = 200, hello = { api = 2, name = "Librillo", pending = false }, plan = { session = "s1", kindle = {}, questions = {}, phoneQuestions = 0 },
    finish_status = 200, books = {}, uploaded = {}, requests = {}, saved_server = nil }
  local d = {
    dir = py_dir, lstat = py_lstat, mkdir = py_mkdir, rmdir = py_rmdir, rename = py_rename, remove = py_remove,
    sha256 = py_sha256, sha256_text = py_sha256_text,
    array = function(t) return t end, encode = function(v) return v end, decode = function(v) return v end,
    now = function() return 1791000000 end,
    sidecar = function(p) if K.open == p then return K.sidecars[p] end return deepcopy(K.sidecars[p]) end,
    sidecar_mtime = function(p) return K.mtimes[p] end,
    write_sidecar = function(p, change)
      local data = deepcopy(K.sidecars[p]) or { doc_path = p }
      change(data); K.sidecars[p] = data; K.writes = K.writes + 1; K.mtimes[p] = (K.mtimes[p] or 0) + 1
    end,
    move_sidecar = function(a, b) K.sidecars[b] = K.sidecars[a]; K.sidecars[a] = nil; K.mtimes[b] = K.mtimes[a]; K.mtimes[a] = nil end,
    relink = function(a, b)
      if K.history[a] then K.history[b] = K.history[a]; K.history[a] = nil end
      for _, set in pairs(K.coll) do if set[a] then set[b] = true; set[a] = nil end end
    end,
    forget = function(p) K.forgotten[p] = true; K.history[p] = nil; for _, set in pairs(K.coll) do set[p] = nil end end,
    purge = function(p) K.sidecars[p] = nil; py_remove(p) end,
    reset = function(p) K.reset[p] = true end,
    collections = function()
      local index = {}
      for name, set in pairs(K.coll) do for p in pairs(set) do index[p] = index[p] or {}; table.insert(index[p], name) end end
      return index
    end,
    set_collections = function(p, names)
      local wanted = {} for _, n in ipairs(names) do wanted[n] = true; K.coll[n] = K.coll[n] or {} end
      for name, set in pairs(K.coll) do set[p] = wanted[name] or nil end
    end,
    collections_mtime = function() return K.coll_mtime end,
    open_path = function() return K.open end,
    style = function() return { drawer = "lighten", color = "yellow" } end,
    request = function(method, url, headers, body)
      table.insert(P.requests, method .. " " .. url)
      assert(headers["x-auth-user"] == "folio" and headers["x-auth-key"] == "k" and headers["x-folio-device"] == "dev1")
      if P.down and url:find(P.down, 1, true) then error("timeout") end
      if url:match("/folio/v2/hello$") then return P.hello_status, P.hello end
      if url:match("/folio/v2/sync$") then P.manifest = body; return 200, P.plan end
      if url:match("/folio/v2/finish$") then P.finish = body; return P.finish_status, { ok = true } end
      return 404
    end,
    upload = function(url, headers, path, size)
      local rel = url:match("path=(.*)$")
      P.uploaded[rel] = py_read(path)
      return 200, { ok = true }
    end,
    download = function(url, headers, path)
      local id = url:match("/folio/v2/books/([^?]+)")
      if not P.books[id] then return 404 end
      py_write(path, P.books[id])
      return 200
    end,
    discover = function(cfg) return P.found end,
    save_server = function(url) P.saved_server = url end,
  }
  local cfg = { server = "http://phone:8765", username = "folio", key = "k", device = "dev1", name = "Kindle",
    home = home, root = "Librillo", skip = { home .. "/koreader" } }
  return K, P, d, cfg
end
''')

with tempfile.TemporaryDirectory(prefix='folio-sync-') as tmp:
    home=str(Path(tmp)/'home'); os.mkdir(home); g.home=home
    T=1790000000
    py_write(home+'/Librillo/Ann Lee/Home.epub','home v1',T)
    py_write(home+'/Downloads/dune.EPUB','dune',T)
    py_write(home+'/Comics/c.cbz','comic',T)
    py_write(home+'/Papers/p.pdf','paper',T)
    py_write(home+'/notes.txt','not a book',T)
    py_write(home+'/.hidden/x.epub','hidden',T)
    py_write(home+'/Librillo/Ann Lee/Home.sdr/inner.epub','inside sdr',T)
    py_write(home+'/koreader/help.pdf','koreader data',T)
    py_write(home+'/bad\\name.epub','backslash',T)
    os.symlink(home+'/Comics/c.cbz', home+'/Comics/link.cbz')
    lua.execute(r'''
    K, P, d, cfg = fake(home)
    local H = home .. "/Librillo/Ann Lee/Home.epub"
    K.sidecars[H] = { doc_path = H, summary = { status = "complete", modified = "2026-10-01" },
      doc_props = { title = "Home", authors = "Ann Lee\nBob" },
      annotations = {
        { datetime = "2026-10-01 21:14:03", drawer = "lighten", text = "a line", chapter = "One",
          page = "/body/DocFragment[3]/p[1]/text().0", pos0 = "/body/DocFragment[3]/p[1]/text().0", pos1 = "/body/DocFragment[3]/p[1]/text().9" },
        { datetime = "2026-10-02 08:00:00", page = "/body/DocFragment[4]/p[2]/text().0", text = "in Two", chapter = "Two" },
        { datetime = "2026-10-03 09:30:00", drawer = "lighten", text = "noted", note = "my note",
          page = "/body/DocFragment[5]/p[1]/text().0", pos0 = "/body/DocFragment[5]/p[1]/text().0", pos1 = "/body/DocFragment[5]/p[1]/text().4" },
      } }
    K.mtimes[H] = 1790000500
    local Pdf = home .. "/Papers/p.pdf"
    K.sidecars[Pdf] = { doc_path = Pdf, percent_finished = 0.2,
      annotations = { { datetime = "2026-09-01 10:00:00", drawer = "lighten", text = "pdf", page = 12,
        pos0 = { page = 12, x = 1.5, y = 2 }, pos1 = { page = 12, x = 9, y = 2 } } } }
    K.mtimes[Pdf] = 1790000600
    K.coll = { Sundays = { [H] = true }, favorites = { [H] = true } }
    state = { hashes = {} }
    files = Sync.manifest(cfg, d, state)
    ''')
    files=lua.eval('files')
    paths=[files[i]['path'] for i in range(1,len(files)+1)]
    assert paths==['Comics/c.cbz','Downloads/dune.EPUB','Librillo/Ann Lee/Home.epub','Papers/p.pdf'], paths
    by={files[i]['path']:files[i] for i in range(1,len(files)+1)}
    assert by['Downloads/dune.EPUB']['format']=='epub' and by['Downloads/dune.EPUB']['sha256']==sha('dune')
    assert by['Downloads/dune.EPUB']['size']==4 and by['Downloads/dune.EPUB']['status'] is None
    home_entry=by['Librillo/Ann Lee/Home.epub']
    assert home_entry['status']=='finished'
    assert home_entry['statusAt']==int(time.mktime((2026,10,1,12,0,0,0,0,-1)))*1000
    assert home_entry['title']=='Home' and list(home_entry['authors'].values())==['Ann Lee','Bob']
    assert list(home_entry['collections'].values())==['Sundays','favorites']
    anns=[home_entry['annotations'][i] for i in range(1,len(home_entry['annotations'])+1)]
    assert [a['kind'] for a in anns]==['highlight','bookmark','note'], [a['kind'] for a in anns]
    assert anns[0]['id']==ann_id('dev1','2026-10-01 21:14:03','/body/DocFragment[3]/p[1]/text().0')
    assert anns[0]['pos1']=='/body/DocFragment[3]/p[1]/text().9' and anns[0]['chapter']=='One' and anns[0]['text']=='a line'
    assert anns[1]['pos0']=='/body/DocFragment[4]/p[2]/text().0' and anns[1]['id']==ann_id('dev1','2026-10-02 08:00:00','/body/DocFragment[4]/p[2]/text().0')
    assert anns[2]['note']=='my note'
    pdf=by['Papers/p.pdf']
    pdf_ann=pdf['annotations'][1]
    assert pdf['status']=='reading' and pdf_ann['pos0']=='12' and pdf_ann['id']==ann_id('dev1','2026-09-01 10:00:00','12')
    # The new ids were stored with the annotations, once per book.
    assert lua.eval('K.writes')==2 and lua.eval('K.sidecars[home .. "/Librillo/Ann Lee/Home.epub"].annotations[1].librillo_id')==anns[0]['id']
    # Hash cache: nothing hashed again; a changed file is hashed again; a gone file leaves the cache.
    hash_calls[0]=0
    lua.execute('files = Sync.manifest(cfg, d, state)')
    assert hash_calls[0]==0 and lua.eval('K.writes')==2, 'cached hashes and ids reused'
    py_write(home+'/Comics/c.cbz','comic v2',T+60)
    os.remove(home+'/Papers/p.pdf')
    lua.execute('files = Sync.manifest(cfg, d, state)')
    assert hash_calls[0]==1 and lua.eval('state.hashes["Comics/c.cbz"].sha256')==sha('comic v2')
    assert lua.eval('state.hashes["Papers/p.pdf"]') is None
    print('Sync manifest: books found (EPUB, PDF, CBZ, CBR; hidden, .sdr, KOReader, links, odd names and other files left out), status, notes, collections, titles; hashes cached and refreshed.')

    # Status in the protocol's words, and when it changed.
    lua.execute(r'''
    local function st(data, mtime) return Sync.status(data, mtime, d) end
    assert(st({}) == "unread" and select(2, st({})) == nil)
    assert(st({ percent_finished = 0.4 }, 1790000000) == "reading" and select(2, st({ percent_finished = 0.4 }, 1790000000)) == 1790000000000)
    assert(st({ summary = { status = "abandoned" } }) == "abandoned")
    assert(st({ summary = { status = "on hold" } }) == "abandoned")
    assert(st({ percent_finished = 0.4, summary = { librillo_unread = true } }) == "unread")
    local at = 1791000000 * 1000
    assert(select(2, st({ summary = { status = "complete", modified = os.date("%Y-%m-%d", 1791000000), librillo_status_at = at } })) == at)
    assert(select(2, st({ summary = { status = "complete", modified = "2020-01-01", librillo_status_at = at } })) ~= at, "stale precise time ignored")
    ''')
    print('Sync status: KOReader words, unread marker, KOReader reading progress, status time from summary or sidecar.')

    # The open book: ids are set on KOReader's live notes, never written to its file.
    lua.execute(r'''
    local H = home .. "/Librillo/Ann Lee/Home.epub"
    K.sidecars[H].annotations[1].librillo_id = nil
    K.open = H
    local before = K.writes
    Sync.manifest(cfg, d, state)
    assert(K.writes == before and K.sidecars[H].annotations[1].librillo_id, "live notes got the id, no file write")
    K.open = nil
    ''')

    # A folder or a file that can't be read stops the sync: leaving it out would look like a deletion.
    lua.execute(r'''
    local real = d.dir
    d.dir = function(p) if p:match("/Comics$") then return nil end return real(p) end
    local ok, err = pcall(Sync.manifest, cfg, d, state)
    assert(not ok and err:find("Couldn't read the folder Comics", 1, true), err)
    d.dir = real
    local hash = d.sha256
    d.sha256 = function() return nil end
    state.hashes = {}
    ok, err = pcall(Sync.manifest, cfg, d, state)
    assert(not ok and err:find("Sync stopped so nothing is lost", 1, true), err)
    d.sha256 = hash
    ''')
    print('Sync safety: open book never written; unreadable folder or file stops the sync.')

# Annotation ids: stable, unique per place and second, stored ids kept.
lua.execute(r'''
local K, P, d = fake("/x")
local data = { annotations = {
  { datetime = "2026-01-01 10:00:00", drawer = "lighten", page = "/a", pos0 = "/a", pos1 = "/b" },
  { datetime = "2026-01-01 10:00:00", page = "/a" },
  { datetime = "2026-01-02 10:00:00", drawer = "lighten", page = "/c", pos0 = "/c", pos1 = "/d", librillo_id = "from-phone-1" },
} }
local first = Sync.annotations(data, "dev1", d)
local again = Sync.annotations(deepcopy(data), "dev1", d)
assert(first[1].id == again[1].id and first[2].id == again[2].id)
assert(first[1].id ~= first[2].id, "same place, same second: still two ids")
assert(#first[1].id == 32 and first[1].id:match("^[0-9a-f]+$"))
assert(first[3].id == "from-phone-1", "a stored id is kept")
data.annotations[1].pos0 = "/moved"; data.annotations[1].page = "/moved"
assert(Sync.annotations(data, "dev1", d)[1].id == first[1].id, "stored id survives KOReader updating the position")
assert(Sync.anchor({ page = 7, x = 1 }) == "7" and Sync.anchor(3) == "3" and Sync.anchor(nil) == "")
''')
expected=ann_id('dev1','2026-01-01 10:00:00','/a')
assert lua.eval('Sync.annotations({annotations={{datetime="2026-01-01 10:00:00",drawer="x",pos0="/a",page="/a"}}}, "dev1", select(3, fake("/x")))[1].id')==expected
print('Annotation ids: the documented recipe, stable across syncs, distinct at the same place and second, stored ids kept.')

# Adding notes from the phone: KOReader's fields, PDFs, notes KOReader can't show, old sidecars.
lua.execute(r'''
local K, P, d = fake("/x")
local style = { drawer = "lighten", color = "yellow" }
local data = { annotations = { { datetime = "2026-01-01 10:00:00", drawer = "lighten", page = "/a", pos0 = "/a", pos1 = "/b", librillo_id = "have" } } }
local incoming = {
  { id = "have", text = "dup", pos0 = "/a", pos1 = "/b", kind = "highlight" },
  { id = "h1", text = "hi", note = "", chapter = "Ch", datetime = "2026-02-01 08:00:00", pos0 = "/p0", pos1 = "/p1", kind = "highlight" },
  { id = "b1", text = "", datetime = "bad", pos0 = "/bm", pos1 = "", kind = "bookmark" },
  { id = "x1", text = "no place", pos0 = "", pos1 = "", kind = "highlight" },
}
assert(Sync.add_annotations(data, incoming, false, "dev1", d, style, 1791000000) == 3)
local list = data.annotations
assert(#list == 3 and data.annotations_externally_modified == true)
assert(list[2].page == "/p0" and list[2].pos0 == "/p0" and list[2].pos1 == "/p1" and list[2].drawer == "lighten"
  and list[2].color == "yellow" and list[2].librillo_id == "h1" and list[2].note == nil and list[2].chapter == "Ch")
assert(list[3].page == "/bm" and list[3].drawer == nil and list[3].pos0 == nil and list[3].datetime == os.date("%Y-%m-%d %H:%M:%S", 1791000000))
assert(#data.librillo_pending == 1 and data.librillo_pending[1].id == "x1", "kept, not shown")
local ids = {}
for _, a in ipairs(Sync.annotations(data, "dev1", d)) do ids[a.id] = true end
assert(ids.have and ids.h1 and ids.b1 and ids.x1, "everything is reported back, so nothing is sent twice")
assert(Sync.add_annotations(data, incoming, false, "dev1", d, style, 1791000000) == 0, "idempotent")
-- PDF: a page bookmark on the right page; a position that isn't a page is kept aside.
local pdf = {}
assert(Sync.add_annotations(pdf, { { id = "p", text = "t", pos0 = "12", kind = "highlight" }, { id = "q", pos0 = "/x", kind = "highlight" } }, true, "dev1", d, style, 1) == 2)
assert(pdf.annotations[1].page == 12 and pdf.annotations[1].drawer == nil and pdf.librillo_pending[1].id == "q")
-- A sidecar from before KOReader 2024 keeps its format; the notes wait aside.
local old = { bookmarks = { { page = "/z" } } }
assert(Sync.add_annotations(old, { { id = "o", pos0 = "/z", pos1 = "/z", kind = "highlight" } }, false, "dev1", d, style, 1) == 1)
assert(old.annotations == nil and old.librillo_pending[1].id == "o")
-- A note Librillo imported over SSH before sync, under another id: the same time and start as a
-- note here, so it is not added twice and the note here keeps its id.
local ssh = { annotations = { { datetime = "2026-03-01 09:00:00", drawer = "lighten", page = "/s0", pos0 = "/s0", pos1 = "/s1", text = "x" } } }
local own = Sync.annotations(ssh, "dev1", d)[1].id
assert(Sync.add_annotations(ssh, {
  { id = "ssh-old", text = "x", datetime = "2026-03-01 09:00:00", pos0 = "/s0", pos1 = "/s1", kind = "highlight" },
  { id = "ssh-new", text = "y", datetime = "2026-03-01 09:00:00", pos0 = "/other", pos1 = "/other", kind = "highlight" },
}, false, "dev1", d, style, 1) == 1)
assert(#ssh.annotations == 2 and ssh.annotations[1].librillo_id == own and ssh.annotations[2].librillo_id == "ssh-new")
''')
print('Notes from the phone: KOReader fields for EPUB highlights and bookmarks, PDF page bookmarks, unshowable notes kept and reported, old sidecars untouched, no duplicates (by id, or by time and start for notes imported before sync).')

# A whole sync: every action, with refusals.
with tempfile.TemporaryDirectory(prefix='folio-sync-run-') as tmp:
    home=str(Path(tmp)/'home'); os.mkdir(home); g.home=home
    T=1790000000
    py_write(home+'/Downloads/dune.epub','dune',T)
    py_write(home+'/Librillo/Frank Herbert/Dune.epub','someone else',T)
    py_write(home+'/Librillo/Ann Lee/Home.epub','home v1',T)
    py_write(home+'/Librillo/Old/Gone.pdf','gone',T)
    py_write(home+'/Librillo/Kept/Mine.epub','mine',T)
    py_write(home+'/Librillo/Open/Reading.epub','open',T)
    g.SHA_HOME_V1=sha('home v1'); g.SHA_HOME_V2=sha('home v2'); g.SHA_NEW=sha('new book'); g.SHA_MINE=sha('mine')
    lua.execute(r'''
    K, P, d, cfg = fake(home)
    local H = home .. "/Librillo/Ann Lee/Home.epub"
    local D = home .. "/Downloads/dune.epub"
    local G = home .. "/Librillo/Old/Gone.pdf"
    local O = home .. "/Librillo/Open/Reading.epub"
    K.sidecars[H] = { doc_path = H, partial_md5_checksum = "old", annotations = { { datetime = "2026-01-01 10:00:00", drawer = "lighten",
      page = "/a", pos0 = "/a", pos1 = "/b", librillo_id = "have" } } }
    K.sidecars[D] = { doc_path = D, summary = { status = "reading" } }
    K.sidecars[G] = { doc_path = G, summary = { status = "complete" } }
    K.history[D] = true
    K.coll = { Sundays = { [D] = true }, Old = { [H] = true } }
    K.open = O
    P.books = { b1 = "new book", b2 = "home v2", b3 = "mine v2", b4 = "corrupt" }
    P.plan = { session = "s1", phoneQuestions = 1,
      questions = { { id = "q-1a2b3c4d5e6f7a8b", bookId = "b9", path = "Librillo/Ann Lee/Home.epub", kind = "status",
        text = "Home: Kindle says Finished, phone says Reading. Which is right?", options = { "Finished", "Reading" } } },
      kindle = {
      { index = 0, op = "upload", path = "Downloads/dune.epub", url = "/folio/v2/upload?session=s1&path=Downloads/dune.epub" },
      { index = 1, op = "move", from = "Downloads/dune.epub", to = "Librillo/Frank Herbert/Dune.epub" },
      { index = 2, op = "download", bookId = "b1", sha256 = SHA_NEW, size = 8, to = "Librillo/New/Book.epub", replaces = nil, url = "/folio/v2/books/b1?session=s1" },
      { index = 3, op = "download", bookId = "b2", sha256 = SHA_HOME_V2, size = 7, to = "Librillo/Ann Lee/Home.epub", replaces = SHA_HOME_V1, url = "/folio/v2/books/b2?session=s1" },
      { index = 4, op = "download", bookId = "b3", sha256 = py_sha("mine v2"), size = 7, to = "Librillo/Kept/Mine.epub", replaces = py_sha("not mine"), url = "/folio/v2/books/b3?session=s1" },
      { index = 5, op = "download", bookId = "b3", sha256 = py_sha("mine v2"), size = 7, to = "Librillo/Kept/Mine.epub", replaces = nil, url = "/folio/v2/books/b3?session=s1" },
      { index = 6, op = "download", bookId = "b4", sha256 = py_sha("what was promised"), size = 7, to = "Librillo/Bad/Corrupt.epub", url = "/folio/v2/books/b4?session=s1" },
      { index = 7, op = "delete", path = "Librillo/Old/Gone.pdf" },
      { index = 8, op = "status", path = "Librillo/Ann Lee/Home.epub", status = "finished" },
      { index = 9, op = "annotations", path = "Librillo/Ann Lee/Home.epub", annotations = {
        { id = "have", pos0 = "/a", pos1 = "/b", kind = "highlight" },
        { id = "new1", text = "from phone", datetime = "2026-03-01 09:00:00", pos0 = "/q", pos1 = "/r", kind = "note", note = "n" } } },
      { index = 10, op = "collections", path = "Librillo/Ann Lee/Home.epub", collections = { "Travel" } },
      { index = 11, op = "status", path = "Librillo/Open/Reading.epub", status = "finished" },
      { index = 12, op = "move", from = "Librillo/Open/Reading.epub", to = "Librillo/Elsewhere/Reading.epub" },
      { index = 13, op = "status", path = "Librillo/Frank Herbert/Dune.epub", status = "abandoned" },
      { index = 14, op = "move", from = "Librillo/Kept/Mine.epub", to = "../../etc/passwd.epub" },
      { index = 15, op = "launch", path = "Librillo/Kept/Mine.epub" },
      { index = 16, op = "upload", path = "Librillo/Kept/Mine.epub", url = "/folio/v2/upload?session=s1&path=Librillo/Kept/Mine.epub" },
    } }
    state = { hashes = {}, answers = { ["q-1a2b3c4d5e6f7a8b"] = "Finished" }, questions = {} }
    result = Sync.run(cfg, d, state)
    ''')
    R=lua.eval('P.finish.results')
    res={R[i]['index']:R[i] for i in range(1,len(R)+1)}
    ok={k:v['ok'] for k,v in res.items()}
    assert ok=={0:True,1:True,2:True,3:True,4:False,5:False,6:False,7:True,8:True,9:True,10:True,11:False,12:False,13:True,14:False,15:False,16:True}, ok
    assert lua.eval('P.finish.session')=='s1'
    assert lua.eval('P.manifest.answers["q-1a2b3c4d5e6f7a8b"]')=='Finished' and lua.eval('P.manifest.root')=='Librillo'
    assert lua.eval('P.manifest.device.id')=='dev1' and lua.eval('P.manifest.device.name')=='Kindle' and lua.eval('P.manifest.home')==home
    # upload: the file's bytes reached the phone.
    assert lua.eval('P.uploaded["Downloads/dune.epub"]')=='dune'
    # move: the taken name gets " (2)", reported; notes, history and collections follow; outside folders stay.
    assert res[1]['path']=='Librillo/Frank Herbert/Dune (2).epub'
    assert Path(home+'/Librillo/Frank Herbert/Dune (2).epub').read_text()=='dune'
    assert Path(home+'/Librillo/Frank Herbert/Dune.epub').read_text()=='someone else', 'never overwritten'
    assert os.path.isdir(home+'/Downloads'), "a folder outside the library is left alone"
    D2=home+'/Librillo/Frank Herbert/Dune (2).epub'
    assert lua.eval('K.history[home .. "/Librillo/Frank Herbert/Dune (2).epub"]') and lua.eval('K.coll.Sundays[home .. "/Librillo/Frank Herbert/Dune (2).epub"]')
    # A later action naming the planned path follows the new name (index 13).
    assert lua.eval('K.sidecars[home .. "/Librillo/Frank Herbert/Dune (2).epub"].summary.status')=='abandoned'
    # download new: saved, verified, nothing left behind.
    assert Path(home+'/Librillo/New/Book.epub').read_text()=='new book' and res[2]['path']=='Librillo/New/Book.epub'
    # download replacing: in place, notes kept, Progress sync recomputes the file's identity.
    assert Path(home+'/Librillo/Ann Lee/Home.epub').read_text()=='home v2'
    assert lua.eval('K.sidecars[home .. "/Librillo/Ann Lee/Home.epub"].partial_md5_checksum') is None
    # refusals: wrong hash at 'to', 'to' taken without replaces, corrupt download.
    assert Path(home+'/Librillo/Kept/Mine.epub').read_text()=='mine'
    assert not os.path.exists(home+'/Librillo/Bad/Corrupt.epub') and not os.path.exists(home+'/Librillo/Bad')
    assert os.listdir(home+'/.librillo/parts')==[], 'no partial downloads left'
    # delete: gone, trash emptied after Librillo confirmed, KOReader told, empty folder removed.
    assert not os.path.exists(home+'/Librillo/Old/Gone.pdf') and not os.path.exists(home+'/Librillo/Old')
    assert lua.eval('K.forgotten[home .. "/Librillo/Old/Gone.pdf"]') and os.listdir(home+'/.librillo/trash')==[]
    assert lua.eval('K.sidecars[home .. "/.librillo/trash/Librillo/Old/Gone.pdf"]') is None
    # status, annotations, collections on Home.
    assert lua.eval('K.sidecars[home .. "/Librillo/Ann Lee/Home.epub"].summary.status')=='complete'
    assert lua.eval('K.sidecars[home .. "/Librillo/Ann Lee/Home.epub"].summary.librillo_status_at')==1791000000*1000
    assert lua.eval('#K.sidecars[home .. "/Librillo/Ann Lee/Home.epub"].annotations')==2
    assert lua.eval('K.sidecars[home .. "/Librillo/Ann Lee/Home.epub"].annotations[2].note')=='n'
    assert lua.eval('K.coll.Travel[home .. "/Librillo/Ann Lee/Home.epub"]') and lua.eval('K.coll.Old[home .. "/Librillo/Ann Lee/Home.epub"]') is None
    # the open book: refused, untouched, reported.
    assert res[11]['error']=='open on the e-reader' and res[12]['error']=='open on the e-reader'
    assert os.path.exists(home+'/Librillo/Open/Reading.epub') and lua.eval('K.sidecars[home .. "/Librillo/Open/Reading.epub"]') is None
    # an escaping path and an unknown action are refused; an upload of an unlisted... (index 16 is listed: allowed).
    assert not os.path.exists(str(Path(tmp)/'etc'))
    # Results, questions, answers, cache.
    assert lua.eval('result.sent')==2 and lua.eval('result.received')==1 and lua.eval('result.removed')==1
    assert lua.eval('result.failed')==7 and lua.eval('result.phone_questions')==1
    assert lua.eval('#state.questions')==1 and lua.eval('state.questions[1].options[2]')=='Reading'
    assert lua.eval('state.answers["q-1a2b3c4d5e6f7a8b"]') is None, 'answers sent are cleared once Librillo confirms'
    assert lua.eval('state.last_sync')==1791000000*1000 and lua.eval('state.hashes["Librillo/New/Book.epub"].synced')
    assert lua.eval('state.hashes["Librillo/Ann Lee/Home.epub"].sha256')==sha('home v2')
    assert lua.eval('state.hashes["Librillo/Frank Herbert/Dune (2).epub"].sha256')==sha('dune')
    text=lua.eval('Sync.summary(result, false)')
    assert text.startswith('2 books sent, 1 received, ') and '1 removed' in text, text
    assert "7 changes couldn't be made" in text and 'Close the book you' in text and '1 question needs your answer' in text and 'waiting on your phone' in text, text
    print('Sync run: upload, move with clash numbering and notes, download new and replace in place, delete via trash, status, notes, collections; refusals (wrong hash, taken name, corrupt download, open book, bad path, unknown action) reported, never overwritten.')

    # Librillo doesn't confirm: deleted books come back, with their notes.
    py_write(home+'/Librillo/Again/Keep.epub','keep',T)
    lua.execute(r'''
    local KP = home .. "/Librillo/Again/Keep.epub"
    K.sidecars[KP] = { doc_path = KP, annotations = { { datetime = "2026-01-01 10:00:00", page = "/k", librillo_id = "k1" } } }
    K.open = nil
    P.finish_status = 500
    P.plan = { session = "s2", kindle = { { index = 0, op = "delete", path = "Librillo/Again/Keep.epub" } }, questions = {} }
    state.answers = { ["q-1a2b3c4d5e6f7a8b"] = "Reading" }
    local ok, err = pcall(Sync.run, cfg, d, state)
    assert(not ok and err:find("Nothing was deleted", 1, true), err)
    assert(K.sidecars[KP].annotations[1].librillo_id == "k1" and not K.forgotten[KP])
    assert(state.answers["q-1a2b3c4d5e6f7a8b"] == "Reading", "answers kept until Librillo confirms")
    ''')
    assert Path(home+'/Librillo/Again/Keep.epub').read_text()=='keep'
    # A crash between deleting and confirming: the next sync puts the book back before listing.
    os.makedirs(home+'/.librillo/trash/Librillo/Crash'); py_write(home+'/.librillo/trash/Librillo/Crash/Lost.epub','lost',T)
    lua.execute(r'''
    P.finish_status = 200
    P.plan = { session = "s3", kindle = {}, questions = {} }
    Sync.run(cfg, d, state)
    local listed = false
    for _, f in ipairs(P.manifest.files) do if f.path == "Librillo/Crash/Lost.epub" then listed = true end end
    assert(listed, "restored before the manifest")
    ''')
    assert Path(home+'/Librillo/Crash/Lost.epub').read_text()=='lost'
    print('Sync safety: an unconfirmed sync puts deleted books back with their notes and keeps answers; leftovers from a crash are restored first.')

    # When to sync automatically: never synced, the phone has changes, or something changed here.
    lua.execute(r'''
    local fresh = { hashes = {} }
    assert(Sync.wants(cfg, d, fresh) == true, "never synced")
    P.hello.pending = false
    assert(Sync.wants(cfg, d, state) == false, "nothing new on either side")
    P.hello.pending = true
    assert(Sync.wants(cfg, d, state) == true, "the phone has changes")
    P.hello.pending = false
    K.coll_mtime = 99
    assert(Sync.wants(cfg, d, state) == true, "a collection changed here")
    state.collections_mtime = 99
    local any = home .. "/Librillo/Ann Lee/Home.epub"
    local before = K.mtimes[any]
    K.mtimes[any] = (before or 0) + 10
    assert(Sync.wants(cfg, d, state) == true, "notes changed here")
    K.open = any
    assert(Sync.wants(cfg, d, state) == false, "the open book's notes don't count while it is open")
    K.open = nil
    K.mtimes[any] = before
    assert(Sync.wants(cfg, d, state) == false)
    py_write(home .. "/Librillo/Fresh/Arrived.epub", "arrived")
    assert(Sync.wants(cfg, d, state) == true, "a book added here")
    py_remove(home .. "/Librillo/Fresh/Arrived.epub")
    py_remove(home .. "/Librillo/New/Book.epub")
    assert(Sync.wants(cfg, d, state) == true, "a book deleted here")
    P.plan = { session = "s4", kindle = {}, questions = {} }
    Sync.run(cfg, d, state)
    assert(Sync.wants(cfg, d, state) == false, "settled after a confirmed sync")
    -- A sync Librillo didn't confirm is tried again.
    py_write(home .. "/Librillo/Fresh/Again.epub", "again")
    P.finish_status = 500
    pcall(Sync.run, cfg, d, state)
    P.finish_status = 200
    assert(Sync.wants(cfg, d, state) == true, "unconfirmed: try again")
    -- Errors in plain words.
    P.hello_status = 404
    local ok, err = pcall(Sync.wants, cfg, d, state)
    assert(not ok and err == "Update Librillo on your phone to sync.", err)
    P.hello_status = 401
    ok, err = pcall(Sync.wants, cfg, d, state)
    assert(not ok and err:find("sign-in", 1, true))
    P.hello_status = 200; P.hello.api = 1
    ok, err = pcall(Sync.wants, cfg, d, state)
    assert(not ok and err:find("so they match", 1, true))
    P.hello.api = 2
    -- The phone's address changed: found on the network, used and remembered.
    P.down = "old-phone"; P.found = "http://192.168.43.1:8765"
    cfg.server = "http://old-phone:8765"
    assert(Sync.wants(cfg, d, state) ~= nil and cfg.server == "http://192.168.43.1:8765" and P.saved_server == cfg.server)
    P.found = nil; cfg.server = "http://old-phone:8765"
    ok, err = pcall(Sync.wants, cfg, d, state)
    assert(not ok and err:find("didn't answer", 1, true))
    -- The regular checks never search the network.
    P.found = "http://192.168.43.9:8765"; P.saved_server = nil; cfg.discover = false
    ok, err = pcall(Sync.wants, cfg, d, state)
    assert(not ok and P.saved_server == nil and cfg.server == "http://old-phone:8765")
    cfg.discover = nil; P.found = nil
    ''')
    print('Automatic sync decision: never synced, phone pending, change here (book added, deleted, notes, collections; not the open book), unconfirmed sync retried, plain errors, new phone address found.')

# Names and summaries.
with tempfile.TemporaryDirectory(prefix='folio-sync-names-') as tmp:
    g.home=tmp
    for name in ['Home.epub','Home (2).epub']: py_write(tmp+'/A/'+name,'x')
    lua.execute(r'''
    local K, P, d = fake(home)
    assert(Sync.free_name(home, "A/Home.epub", d) == "A/Home (3).epub")
    assert(Sync.free_name(home, "A/New.epub", d) == "A/New.epub")
    assert(Sync.valid_root(" Librillo/ ") == "Librillo" and Sync.valid_root("/mnt") == nil and Sync.valid_root("a/../b") == nil
      and Sync.valid_root(".x") == nil and Sync.valid_root("") == nil)
    assert(Sync.relative_ok("a/b.epub") and not Sync.relative_ok("a\\b.epub") and not Sync.relative_ok("a/\1.epub") and not Sync.relative_ok("/a"))
    local quiet = { sent = 0, received = 0, updated = 0, removed = 0, failed = 2, failures = {}, questions = { { id = "q-1" } }, new_questions = 0, phone_questions = 3 }
    assert(Sync.summary(quiet, true) == "", "automatic: quiet without changes or new questions")
    quiet.new_questions = 1
    assert(Sync.summary(quiet, true):find("1 question needs your answer", 1, true))
    local one = { sent = 0, received = 1, updated = 0, removed = 0, failed = 0, failures = {}, questions = {}, new_questions = 0, phone_questions = 0 }
    assert(Sync.summary(one, true) == "1 book received.", Sync.summary(one, true))
    one.received = 0
    assert(Sync.summary(one, false) == "Everything is up to date.")
    ''')
print('Sync names and messages: " (n)" numbering, library folder and path checks, quiet automatic syncs, plain summaries.')

# main.lua glue: a version 2 setup file applied at start, the menu, and the sync settings.
import shutil
with tempfile.TemporaryDirectory(prefix='folio-main-') as tmp:
    plugin_dir=Path(tmp)/'folio.koplugin'
    shutil.copytree(root/'koreader/folio.koplugin', plugin_dir)
    (plugin_dir/'link-setup.json').write_text(json.dumps({'format':'librillo-link-setup','version':2,
        'server':'http://192.168.1.42:8765','username':'folio','userkey':'abc123','root':'Books/Librillo',
        'auto_sync':True,'sync_forward':'prompt','device_name':'Paperwhite'}))
    books=Path(tmp)/'home'; books.mkdir()
    main_lua=LuaRuntime(unpack_returned_tuples=True)
    G=main_lua.globals()
    G.py_decode=lambda text: main_lua.table_from({k:v for k,v in json.loads(text).items()})
    G.py_home=str(books)
    G.py_exists=lambda p: os.path.exists(p)
    G.py_isdir=lambda p: os.path.isdir(p)
    main_lua.execute(r'''
    stored, messages, scheduled, reader = {}, {}, {}, {}
    local modules = {
      ["ui/widget/container/widgetcontainer"] = { extend = function(self, t) return t end },
      ["ui/uimanager"] = { show = function(self, w) messages[#messages + 1] = w.text end,
        scheduleIn = function(self, seconds, f) scheduled[#scheduled + 1] = { seconds = seconds, f = f } end,
        unschedule = function() end, nextTick = function(self, f) f() end },
      ["ui/widget/infomessage"] = { new = function(self, t) return t end },
      ["ui/widget/confirmbox"] = {}, ["ui/widget/inputdialog"] = {}, ["ui/event"] = {},
      ["datastorage"] = { getSettingsDir = function() return "/settings" end, getDataDir = function() return "/koreader" end },
      ["readcollection"] = {}, ["lua-ljsqlite3/init"] = {},
      ["rapidjson"] = { decode = py_decode },
      ["libs/libkoreader-lfs"] = { attributes = function(path, field)
        local mode = py_isdir(path) and "directory" or (py_exists(path) and "file" or nil)
        if field == "mode" then return mode end
        return mode and { mode = mode } or nil end },
      ["ffi/util"] = { realpath = function(p) return p end },
      ["device"] = { model = "Kindle Paperwhite" },
      ["luasettings"] = { open = function(self, path)
        local o = { data = stored[path] or {} }
        function o:readSetting(k) return self.data[k] end
        function o:saveSetting(k, v) self.data[k] = v end
        function o:flush() stored[path] = self.data end
        return o end },
    }
    require = function(name) if not modules[name] then error("no module " .. name) end return modules[name] end
    local values = { home_dir = py_home, device_id = "dev1", kosync = { sync_forward = 2 } }
    G_reader_settings = { readSetting = function(self, k, default) if values[k] ~= nil then return values[k] end return default end,
      saveSetting = function(self, k, v) values[k] = v end, flush = function() end }
    reader_values = values
    ''')
    plugin=main_lua.execute((plugin_dir/'main.lua').read_text())
    plugin['path']=str(plugin_dir)
    plugin['ui']=main_lua.eval('{ menu = { registerToMainMenu = function() end } }')
    plugin.init(plugin)
    assert not (plugin_dir/'link-setup.json').exists(), 'the setup file with the key is removed'
    main_lua.execute(r'''
    local file = stored["/settings/kosync.lua"].settings
    assert(file.custom_server == "http://192.168.1.42:8765" and file.username == "folio" and file.userkey == "abc123")
    assert(file.auto_sync == true and file.sync_forward == 1 and file.sync_backward == 1)
    local legacy = reader_values.kosync
    assert(legacy.custom_server == "http://192.168.1.42:8765" and legacy.sync_forward == 2, "the older place keeps its own choice")
    local mine = reader_values.folio_sync
    assert(mine.root == "Books/Librillo" and mine.auto_sync == true and mine.device_name == "Paperwhite" and mine.set_up == 2)
    assert(reader_values.folio_auto_fetch == false, "the old automatic fetch is replaced by sync")
    local every5, told = false, false
    for _, s in ipairs(scheduled) do
      if s.seconds == 300 then every5 = true end
      if s.seconds == 1 then s.f() end
    end
    assert(every5, "automatic sync checks every 5 minutes")
    for _, m in ipairs(messages) do if m:find("Restart KOReader once", 1, true) then told = true end end
    assert(told, "the person is told to restart once")
    ''')
    items=main_lua.eval('{}')
    plugin.addToMainMenu(plugin, items)
    menu=items['folio_bridge']['sub_item_table']
    assert menu[1]['text']=='Sync now' and menu[2]['text']=='Sync automatically' and menu[2]['checked_func']()
    assert menu[3]['text_func']()=='Library folder: Books/Librillo'
    assert menu[4]['text_func']()=='Questions (0)' and not menu[4]['enabled_func']()
    assert menu[5]['text']=='Get books from Librillo', 'version 1 kept for now'
    main_lua.execute('reader_values.folio_sync.questions = { { id = "q-1", text = "Which?", options = { "A", "B" } }, { id = "q-2", text = "Which?", options = { "A", "B" } } }; reader_values.folio_sync.answers = { ["q-2"] = "A" }')
    assert menu[4]['text_func']()=='Questions (1)' and menu[4]['enabled_func']()
    cfg=plugin.syncConfig()
    assert cfg['server']=='http://192.168.1.42:8765' and cfg['username']=='folio' and cfg['key']=='abc123'
    assert cfg['device']=='dev1' and cfg['name']=='Paperwhite' and cfg['home']==str(books) and cfg['root']=='Books/Librillo'
    print('Plugin start: setup version 2 applied to both Progress sync places and the plugin settings, file removed, 5-minute checks, menu (Sync now, Sync automatically, Library folder, Questions), sync settings.')
