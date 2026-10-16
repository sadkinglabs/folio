#!/usr/bin/env python3
"""Tests independent bridge validation/membership logic with Lua; does not emulate KOReader."""
from pathlib import Path
from lupa import LuaRuntime
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
local kosync, auto = Link.setup("x", decode(base), { custom_server = "old", sync_forward = 2 })
assert(kosync.custom_server == "http://192.168.1.42:8765" and kosync.username == "folio")
assert(kosync.userkey == "0123abcdef" and kosync.sync_forward == 2 and auto == true)
local function copy(changes) local t = {} for k, v in pairs(base) do t[k] = v end
  for k, v in pairs(changes) do t[k] = v end return t end
assert(Link.setup("x", decode(copy({ format = "other" })), {}) == nil)
assert(Link.setup("x", decode(copy({ version = 2 })), {}) == nil)
assert(Link.setup("x", decode(copy({ server = "http://evil host:1" })), {}) == nil)
assert(Link.setup("x", decode(copy({ server = "file:///etc" })), {}) == nil)
assert(Link.setup("x", decode(copy({ userkey = "not-hex" })), {}) == nil)
assert(Link.setup("x", function() error("bad json") end, {}) == nil)
""")
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
# The phone's record is checked, never trusted: a book it thinks was delivered is fetched again
# when its file is gone, and found where it was saved when it is still there.
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
assert(result.already == 1 and result.fetched == 2, result.already .. " " .. result.fetched)
assert(acks.k == "/old/place/Kept.epub", "found where it was saved, not copied again")
assert(files["/books/Stephen King - Salem's Lot.epub"], "a missing book is fetched again")
assert(acks.g == "/books/Stephen King - Salem's Lot.epub", "the phone learns the new place")
assert(files["/books/B - Hostile.epub"], "a hostile saved path is ignored, the book still arrives")
assert(downloads == 2)
-- The message names each book and where it went.
local text = Link.summary(result, false)
assert(text:find("Saved to /books:", 1, true) and text:find("Salem's Lot", 1, true), text)
assert(text:find("Already on this e-reader:", 1, true) and text:find("/old/place/Kept.epub", 1, true), text)
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
print('Folio Link fetch: saved path checked, missing book fetched again, hostile path ignored, messages, no-SHA devices.')

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
