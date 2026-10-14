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
