#!/usr/bin/env python3
"""Tests the KOReader plugin's logic with Lua (lupa): setup, finding the phone, sync and the menu.

KOReader itself is not run; its pieces, the phone and the file system are faked or temporary."""
import importlib, json, os, hashlib, shutil, stat, tempfile, time
from pathlib import Path
# KOReader runs LuaJIT 2.1 (Lua 5.1); FOLIO_LUA=luajit21 runs these tests on that engine.
LuaRuntime=importlib.import_module('lupa.'+os.environ['FOLIO_LUA'] if os.environ.get('FOLIO_LUA') else 'lupa').LuaRuntime
root=Path(__file__).resolve().parents[1]
plugin_files=sorted(p.name for p in (root/'koreader/folio.koplugin').glob('*.lua'))
# The old book fetch, collection requests, reading handoff and statistics file export are gone;
# reading time now goes to the phone after a sync (stats.lua).
assert plugin_files==['_meta.lua','link.lua','main.lua','stats.lua','sync.lua'], plugin_files
lua=LuaRuntime(unpack_returned_tuples=True)
# Compile all plugin Lua files, without loading KOReader-only require calls.
for file in plugin_files:
    lua.execute('assert(load(...))',(root/'koreader/folio.koplugin'/file).read_text())
print('Plugin Lua compiles: '+', '.join(plugin_files)+'.')

# Setup and finding the phone: the real link.lua.
link=lua.execute((root/'koreader/folio.koplugin/link.lua').read_text())
lua.globals().Link=link
for gone in ['fetch','summary','unique','url','headers']:
    assert lua.eval('Link.'+gone) is None, 'removed: Link.'+gone
lua.execute(r"""
-- The installer's one-time setup: data only, applied to Progress sync, anything odd ignored.
local function decode(fields) return function() return fields end end
local base = { format = "librillo-link-setup", version = 1, server = "http://192.168.1.42:8765",
  username = "folio", userkey = "0123abcdef", auto_fetch = true }
local kosync, options = Link.setup("x", decode(base), { custom_server = "old", sync_forward = 2 })
assert(kosync.custom_server == "http://192.168.1.42:8765" and kosync.username == "folio")
assert(kosync.userkey == "0123abcdef" and kosync.sync_forward == 2, "a choice made on the e-reader is kept")
assert(kosync.sync_backward == 1 and kosync.auto_sync == true)
assert(options.version == 1 and options.auto_sync == false and options.root == "Librillo")
assert(options.auto_fetch == nil, "the old automatic fetch is gone")
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
assert(opts.version == 2 and opts.auto_sync == true and opts.auto_fetch == nil)
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

# Finding the phone: only a phone that proves it knows the key is trusted.
lua.execute(r"""
local function md5(text) return text == "kn1" and "abc123" or "ffff00" end
local function with(replies)
  return { nonce = function() return "n1" end, md5 = md5, broadcast = function(text)
    assert(text == "FOLIO-DISCOVER n1") return replies end }
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
assert(Link.discover(cfg, with({ { ip = "192.168.1.20", text = "FOLIO 8765 2 abc123" } })) == nil, "other version")
assert(Link.discover(cfg, with({})) == nil and Link.discover(cfg, with(nil)) == nil, "nobody answered")
local ok, err = pcall(Link.discover, {}, with({}))
assert(not ok and tostring(err):find("Set up Librillo from your phone first.", 1, true), tostring(err))
""")
print('Finding the phone: 6 scenarios passed (wrong proof, impostor first, bad address, other version, nobody answered, not set up).')

# ---- Sync protocol v2 (sync.lua): real files in a temporary home folder, KOReader's data and the
# phone faked in memory. Tests the logic only; KOReader itself is not run here.
# A fresh Lua for the sync tests.
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

# M11: colours, notes and deletions both ways. The manifest says when its list is complete, and the
# plugin applies the phone's edits and deletions to KOReader's notes.
lua.execute(r'''
local K, P, d = fake("/x")
local data = { annotations = {
  { datetime = "2026-01-01 10:00:00", drawer = "lighten", color = "Yellow", page = "/a", pos0 = "/a", pos1 = "/b", librillo_id = "h1" },
  { datetime = "2026-01-02 10:00:00", drawer = "underscore", color = "green", note = "n", datetime_updated = "2026-01-05 08:00:00",
    page = "/c", pos0 = "/c", pos1 = "/d", librillo_id = "h2" },
  { datetime = "2026-01-03 10:00:00", page = "/e", librillo_id = "bm" },
  { datetime = "2026-01-04 10:00:00", drawer = "lighten", page = "/f", pos0 = "/f", pos1 = "/g" },
}, librillo_pending = { { id = "pend", text = "t", pos0 = "", pos1 = "", kind = "highlight" } } }
local list, _, complete = Sync.annotations(data, "dev1", d)
assert(complete == true, "a KOReader annotations list is complete")
assert(list[1].color == "yellow" and list[1].drawer == "lighten" and list[1].updated == "")
assert(list[2].color == "green" and list[2].drawer == "underscore" and list[2].updated == "2026-01-05 08:00:00")
assert(list[3].kind == "bookmark" and list[3].drawer == "" and list[3].color == "")
local _, _, old_complete = Sync.annotations({ bookmarks = { { page = "/z" } } }, "dev1", d)
assert(old_complete == false, "a sidecar from before KOReader 2024 is never complete")
-- Edits: note, colour, style and time; a bookmark keeps no style; unknown ids are ignored.
local fourth = list[4].id
local n = Sync.edit_annotations(data, {
  { id = "h1", note = "new note", color = "purple", drawer = "underscore", updated = "2026-02-01 09:00:00" },
  { id = "h2", note = "", color = "blue", drawer = "", updated = "bad time" },
  { id = "bm", note = "for the mark", color = "green", drawer = "lighten" },
  { id = fourth, note = "fourth", color = "", drawer = "lighten" },
  { id = "pend", note = "waits", color = "green", drawer = "lighten", updated = "2026-02-02 09:00:00" },
  { id = "nobody", note = "x" },
}, "dev1", d)
assert(n == 5, n)
local a = data.annotations
assert(a[1].note == "new note" and a[1].color == "purple" and a[1].drawer == "underscore" and a[1].datetime_updated == "2026-02-01 09:00:00")
assert(a[2].note == nil and a[2].color == "blue" and a[2].drawer == "underscore", "a blank style keeps KOReader's")
assert(a[2].datetime_updated == "2026-01-05 08:00:00", "an odd time is not written")
assert(a[3].drawer == nil and a[3].color == nil and a[3].note == "for the mark", "a bookmark stays a bookmark")
assert(a[4].librillo_id == fourth and a[4].note == "fourth" and a[4].color == nil)
assert(data.librillo_pending[1].note == "waits" and data.librillo_pending[1].color == "green")
assert(data.annotations_externally_modified == true)
local again = Sync.annotations(data, "dev1", d)
assert(again[1].kind == "note" and again[1].color == "purple" and again[1].updated == "2026-02-01 09:00:00")
assert(again[3].kind == "bookmark", "still a bookmark")
local pending_out = again[5]
assert(pending_out.id == "pend" and pending_out.note == "waits" and pending_out.kind == "note" and pending_out.updated == "2026-02-02 09:00:00")
-- Deletions: by stored id, by recipe id and from the notes waiting aside; the rest stay.
data.annotations_externally_modified = nil
assert(Sync.delete_annotations(data, { "h2", fourth, "pend", "nobody" }, "dev1", d) == 3)
local left = {}
for _, x in ipairs(Sync.annotations(data, "dev1", d)) do left[#left + 1] = x.id end
assert(#left == 2 and left[1] == "h1" and left[2] == "bm", table.concat(left, ","))
assert(#data.librillo_pending == 0 and data.annotations_externally_modified == true)
assert(Sync.delete_annotations(data, { "nobody" }, "dev1", d) == 0, "nothing else is touched")
-- A note from the phone keeps the colour, style and edit time picked there.
local fresh = { annotations = {} }
Sync.add_annotations(fresh, { { id = "c1", text = "t", datetime = "2026-03-01 08:00:00", pos0 = "/p", pos1 = "/q",
  kind = "highlight", color = "green", drawer = "underscore", updated = "2026-03-02 08:00:00" } }, false, "dev1", d,
  { drawer = "lighten", color = "yellow" }, 1)
assert(fresh.annotations[1].color == "green" and fresh.annotations[1].drawer == "underscore" and fresh.annotations[1].datetime_updated == "2026-03-02 08:00:00")
''')
with tempfile.TemporaryDirectory(prefix='folio-sync-edits-') as tmp:
    home=str(Path(tmp)/'home'); os.mkdir(home); g.home=home
    py_write(home+'/Librillo/A/Notes.epub','notes',1790000000)
    py_write(home+'/Librillo/A/Bare.epub','bare',1790000000)
    py_write(home+'/Librillo/A/Old.epub','old',1790000000)
    py_write(home+'/Librillo/A/Open.epub','open',1790000000)
    lua.execute(r'''
    K, P, d, cfg = fake(home)
    local N = home .. "/Librillo/A/Notes.epub"
    local O = home .. "/Librillo/A/Open.epub"
    K.sidecars[N] = { doc_path = N, annotations = {
      { datetime = "2026-01-01 10:00:00", drawer = "lighten", page = "/a", pos0 = "/a", pos1 = "/b", librillo_id = "keep" },
      { datetime = "2026-01-02 10:00:00", drawer = "lighten", page = "/c", pos0 = "/c", pos1 = "/d", librillo_id = "gone" } } }
    K.sidecars[home .. "/Librillo/A/Old.epub"] = { doc_path = "old", bookmarks = { { page = "/z" } } }
    K.sidecars[O] = { doc_path = O, annotations = { { datetime = "2026-01-01 10:00:00", drawer = "lighten", page = "/a", pos0 = "/a", pos1 = "/b", librillo_id = "o1" } } }
    K.open = O
    P.plan = { session = "e1", questions = {}, kindle = {
      { index = 0, op = "edit-annotations", path = "Librillo/A/Notes.epub", annotations = { { id = "keep", note = "edited", color = "blue", drawer = "lighten", updated = "2026-02-01 10:00:00" } } },
      { index = 1, op = "delete-annotations", path = "Librillo/A/Notes.epub", ids = { "gone" } },
      { index = 2, op = "delete-annotations", path = "Librillo/A/Open.epub", ids = { "o1" } },
      { index = 3, op = "edit-annotations", path = "Librillo/A/Notes.epub", annotations = "bad" },
    } }
    state = { hashes = {}, answers = {}, questions = {} }
    result = Sync.run(cfg, d, state)
    ''')
    mf=lua.eval('P.manifest.files')
    by={mf[i]['path']:mf[i] for i in range(1,len(mf)+1)}
    assert by['Librillo/A/Notes.epub']['annotationsComplete'] is True
    assert by['Librillo/A/Bare.epub']['annotationsComplete'] is None, 'no notes file: nothing can look deleted'
    assert by['Librillo/A/Old.epub']['annotationsComplete'] is None, 'an old notes file is never complete'
    assert by['Librillo/A/Open.epub']['annotationsComplete'] is True, "the open book's live notes are whole"
    R=lua.eval('P.finish.results'); ok={R[i]['index']:R[i]['ok'] for i in range(1,len(R)+1)}
    assert ok=={0:True,1:True,2:False,3:False}, ok
    assert lua.eval('P.finish.results[3].error')=='open on the e-reader'
    notes=lua.eval('K.sidecars[home .. "/Librillo/A/Notes.epub"].annotations')
    assert len(notes)==1 and notes[1]['librillo_id']=='keep' and notes[1]['note']=='edited' and notes[1]['color']=='blue'
    assert notes[1]['datetime_updated']=='2026-02-01 10:00:00'
    assert len(lua.eval('K.sidecars[home .. "/Librillo/A/Open.epub"].annotations'))==1, 'the open book is never written'
    assert lua.eval('K.reset[home .. "/Librillo/A/Notes.epub"]'), 'KOReader reloads the notes'
    print('Colours, notes and deletions (M11): a complete list is flagged (never for a book without a notes file or an old one), colour, style, note and edit time read and written both ways, bookmarks stay bookmarks, deletions by id only, the open book untouched.')

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

# Reading time (stats.lua): KOReader's statistics database, read only, sent after a sync. A real
# SQLite file stands in for KOReader's; the connection mimics lua-ljsqlite3 (exec returns columns).
import sqlite3
stats=lua.execute((root/'koreader/folio.koplugin/stats.lua').read_text())
g.Stats=stats
class StatsDb:
    def __init__(self,path): self.c=sqlite3.connect(path,isolation_level=None); self.sql=[]; self.closed=False
    def exec(self,sql):
        self.sql.append(sql); rows=self.c.execute(sql).fetchall()
        if not rows: return None
        return lua.table_from([lua.table_from(list(column)) for column in zip(*rows)])
    def rowexec(self,sql):
        self.sql.append(sql); return self.c.execute(sql).fetchone()[0]
    def close(self): self.c.close(); self.closed=True
lua.execute(r'''
function stats_db(py) return {
  exec = function(self, sql) return py.exec(sql) end,
  rowexec = function(self, sql) return py.rowexec(sql) end,
  close = function(self) py.close() end } end
''')
def day_of(ts): return time.strftime('%Y-%m-%d',time.localtime(ts))
with tempfile.TemporaryDirectory(prefix='folio-stats-') as tmp:
    path=tmp+'/statistics.sqlite3'
    c=sqlite3.connect(path)
    c.execute('CREATE TABLE page_stat_data (id_book INTEGER, page INTEGER NOT NULL DEFAULT 0, start_time INTEGER NOT NULL DEFAULT 0, duration INTEGER NOT NULL DEFAULT 0, total_pages INTEGER NOT NULL DEFAULT 0, UNIQUE (id_book, page, start_time))')
    T=1791000000
    rows=[(1,1,T,600),(1,2,T+700,900),(2,5,T-86400,1200),(2,6,T-86400+60,-5)]
    # 400 more days of reading: only the newest 366 days are sent.
    rows+=[(3,i,T-86400*(i+2),60) for i in range(400)]
    c.executemany('INSERT INTO page_stat_data (id_book,page,start_time,duration,total_pages) VALUES (?,?,?,?,100)',[r for r in rows])
    c.commit(); c.close()
    before=Path(path).read_bytes()
    g.py_old_phone=StatsDb(path)
    db=StatsDb(path); g.py_db=db
    lua.execute(r'''
    local K, P, d, cfg = fake(home)
    d.open_statistics = function() return stats_db(py_old_phone) end
    assert(Stats.send(cfg, d, Sync.headers(cfg)) == false, "a phone without the route: nothing kept, no error")
    stats_request = nil
    d.open_statistics = function() return stats_db(py_db) end
    d.request = function(method, url, headers, body)
      stats_request = stats_request or { method = method, url = url, headers = headers, body = body }
      return 200, { ok = true }
    end
    stats_sent = Stats.send(cfg, d, Sync.headers(cfg))
    ''')
    assert db.closed, 'the database is closed after reading'
    assert Path(path).read_bytes()==before, 'the statistics database is never written'
    statements=[s.split(' ')[0] for s in db.sql]
    assert statements[:2]==['PRAGMA','BEGIN;'] and statements[-1]=='COMMIT;' and set(statements[2:-1])=={'SELECT'}, db.sql
    assert 'query_only=ON' in db.sql[0]
    request=lua.eval('stats_request')
    assert lua.eval('stats_sent')==True
    assert request['method']=='POST' and request['url']=='http://phone:8765/folio/v2/statistics', request['url']
    headers=request['headers']
    assert headers['x-auth-user']=='folio' and headers['x-auth-key']=='k' and headers['x-folio-device']=='dev1'
    body=request['body']
    assert body['format']=='folio-statistics' and body['version']==1 and body['exportedAt']==1791000000
    assert body['device']['id']=='dev1' and body['device']['name']=='Kindle'
    assert body['seconds']==600+900+1200+400*60, body['seconds']
    days=[(body['days'][i]['date'],body['days'][i]['seconds']) for i in range(1,len(body['days'])+1)]
    expected={}
    for _,_,start,duration in rows:
        if duration>=0: expected[day_of(start)]=expected.get(day_of(start),0)+duration
    assert days==sorted(expected.items(),reverse=True)[:366], days[:3]
    assert len(days)==366 and (day_of(T-86400),1200) in days, 'the newest 366 days; a negative time is left out of its day'
    assert sum(s for _,s in days)<=body['seconds']
    # Only totals leave the e-reader: no titles, no book ids, no pages.
    for i in range(1,len(body['days'])+1):
        assert sorted(body['days'][i].keys())==['date','seconds']
    assert sorted(k for k in body.keys())==['days','device','exportedAt','format','seconds','version']

    # Nothing to send, and the sync is never failed: no database, a broken one, a phone that errs.
    Path(tmp+'/broken.sqlite3').write_bytes(b'not a database at all')
    broken=StatsDb(tmp+'/broken.sqlite3'); g.py_broken=broken
    empty=sqlite3.connect(tmp+'/empty.sqlite3'); empty.close()
    g.py_empty=StatsDb(tmp+'/empty.sqlite3')
    lua.execute(r'''
    local K, P, d, cfg = fake(home)
    local asked = 0
    d.request = function() asked = asked + 1; return 200 end
    d.open_statistics = function() return nil end
    assert(Stats.send(cfg, d, Sync.headers(cfg)) == false and asked == 0, "no database: nothing sent")
    d.open_statistics = function() error("cannot open") end
    assert(Stats.send(cfg, d, Sync.headers(cfg)) == false and asked == 0, "an unopenable database: nothing sent")
    d.open_statistics = function() return stats_db(py_broken) end
    assert(Stats.send(cfg, d, Sync.headers(cfg)) == false and asked == 0, "a damaged database: nothing sent")
    d.open_statistics = function() return stats_db(py_empty) end
    assert(Stats.send(cfg, d, Sync.headers(cfg)) == false and asked == 0, "statistics never switched on: nothing sent")
    d.open_statistics = nil
    assert(Stats.send(cfg, d, Sync.headers(cfg)) == false and asked == 0)
    ''')
    assert broken.closed and g.py_empty.closed, 'a database that could not be read is still closed'
    g.py_db_again=StatsDb(path)
    lua.execute(r'''
    local K, P, d, cfg = fake(home)
    d.open_statistics = function() return stats_db(py_db_again) end
    d.request = function() error("timeout") end
    assert(Stats.send(cfg, d, Sync.headers(cfg)) == false, "a phone that stops answering: no error")
    d.request = function() return 403 end
    assert(Stats.send(cfg, d, Sync.headers(cfg)) == false, "refused: no error")
    -- Odd rows are left out rather than sent: no date, a day longer than Librillo takes.
    local odd = { exec = function(self, sql)
        if sql:find("GROUP BY", 1, true) then
          return { { "2026-10-14", false, "bad", "2026-10-12", "2026-10-11" }, { 600, 5, 7, 20000000, 30.5 } }
        end
      end, rowexec = function() return 605 end, close = function() end }
    local value = Stats.read(odd)
    assert(value.seconds == 605 and #value.days == 2, #value.days)
    assert(value.days[1].date == "2026-10-14" and value.days[1].seconds == 600)
    assert(value.days[2].date == "2026-10-11" and value.days[2].seconds == 30)
    ''')
print('Reading time: read only from a real statistics database (366 newest days, negative times left out, totals only), sent after a sync with the sign-in; no database, a damaged one, statistics off, or a phone that errs sends nothing and raises nothing.')


# Reading sessions (stats.lua, version 2): the Lua port of KoreaderSessions.derive gives the same
# sessions as the Kotlin reference for every shared case, and the database path (windows, the cut,
# chunks of 500, the names of books the phone doesn't know) is checked on real SQLite files.
import random
shared=json.loads((root/'core/src/test/resources/stats/koreader-sessions.json').read_text())
def lua_rows(events):
    return lua.table_from([lua.table_from({'md5':e[0],'page':e[1],'start':e[2],'duration':e[3],'total':e[4]}) for e in events])
def as_list(table): return [table[i] for i in range(1,len(table)+1)]
def close_or_none(a,b): return (a is None and b is None) or (a is not None and b is not None and abs(a-b)<=1e-9)
def assert_same_sessions(got,want,label):
    assert len(got)==len(want), (label,len(got),len(want),got,want)
    for g_,w in zip(got,want):
        assert g_['book']==w['book'] and g_['start']==w['start'] and g_['seconds']==w['seconds'] and g_['pages']==w['pages'], (label,g_['book'],g_['start'],g_['seconds'],g_['pages'],w)
        for key in ('from','to','read'):
            assert close_or_none(g_[key],w[key]), (label,key,g_[key],w[key])
for case in shared['cases']:
    got=as_list(stats.derive(lua_rows(case['events']),lua_rows(case['before'])))
    assert_same_sessions(got,case['sessions'],case['name'])
# Rounding is floor(x * 1e6 + 0.5) / 1e6 after clamping, as in Kotlin (2/3 rounds up, 1/3 down).
rounded=as_list(stats.derive(lua_rows([['0123456789abcdef0123456789abcdef',2,1700000000,60,3],['0123456789abcdef0123456789abcdef',3,1700000060,60,3]]),lua_rows([])))
assert len(rounded)==1 and rounded[0]['from']==0.333333 and rounded[0]['to']==1.0 and rounded[0]['read']==0.666667, dict(rounded[0].items())
assert stats.round6(2/3)==0.666667 and stats.round6(-1)==0 and stats.round6(7)==1 and stats.round6(0.0000005)==0.000001
# The same rows fed one at a time to a Deriver, as the database reader does, give the same result.
lua.execute(r'''
function derive_streamed(events)
  local deriver = Stats.Deriver.new({})
  for _, row in ipairs(events) do deriver:add(row) end
  return deriver:finish()
end''')
streamed=[{'md5':'0123456789abcdef0123456789abcdef','page':i+1,'start':1700000000+60*i,'duration':60,'total':100} for i in range(3)]
assert_same_sessions(as_list(lua.eval('derive_streamed')(lua_rows([[r['md5'],r['page'],r['start'],r['duration'],r['total']] for r in streamed]))),
    [{'book':'0123456789abcdef0123456789abcdef','start':1700000000,'seconds':180,'pages':3,'from':0.0,'to':0.03,'read':0.03}],'deriver')
# Numbers from the database can be int64 or text; they go through tonumber.
lua.execute(r'''
do
  local calls = 0
  local db = {
    exec = function(self, sql)
      if sql:find("ORDER BY", 1, true) then
        calls = calls + 1
        if calls == 1 then return { { "0123456789ABCDEF0123456789ABCDEF" }, { "1" }, { "1700000000" }, { "60" }, { "100" } } end
      end
    end,
    rowexec = function() return "1700000000" end,
  }
  local sessions = Stats.readSessions(db, 0, 1791000000)
  assert(#sessions == 1 and sessions[1].book == "0123456789abcdef0123456789abcdef" and sessions[1].seconds == 60, #sessions)
  assert(sessions[1].from == 0 and sessions[1].to == 0.01 and sessions[1].start == 1700000000)
  assert(calls >= 2, "windows of 30 days up to now")
end''')

# A statistics file like KOReader's, with titles and authors for the names.
def build_stats_db(path,books,rows):
    c=sqlite3.connect(path)
    c.execute('CREATE TABLE book (id INTEGER PRIMARY KEY, md5 TEXT, pages INTEGER, title TEXT, authors TEXT)')
    c.execute('CREATE TABLE page_stat_data (id_book INTEGER, page INTEGER NOT NULL DEFAULT 0, start_time INTEGER NOT NULL DEFAULT 0, duration INTEGER NOT NULL DEFAULT 0, total_pages INTEGER NOT NULL DEFAULT 0, UNIQUE (id_book, page, start_time))')
    c.executemany('INSERT INTO book VALUES (?,?,?,?,?)',books)
    c.executemany('INSERT INTO page_stat_data (id_book,page,start_time,duration,total_pages) VALUES (?,?,?,?,?)',rows)
    c.commit(); c.close()
def events_of(books,rows):
    by_id={b[0]:b for b in books}
    out=[]
    for book_id,page,start,duration,total in rows:
        if duration>0 and by_id[book_id][1] is not None:
            out.append([by_id[book_id][1],page,start,duration,total if total>0 else (by_id[book_id][2] or 0)])
    return out
lua.execute(r'''
-- One send of sessions to a fake phone. opts: watermark, known, get_status, no_until, fail_at, get_error.
function run_sessions(py, opts)
  local K, P, d, cfg = fake(home)
  local log = { gets = 0, posts = {}, order = {} }
  d.open_statistics = function() return stats_db(py) end
  d.request = function(method, url, headers, body)
    assert(url == "http://phone:8765/folio/v2/statistics", url)
    assert(headers["x-auth-user"] == "folio" and headers["x-auth-key"] == "k" and headers["x-folio-device"] == "dev1")
    log.order[#log.order + 1] = method
    if method == "GET" then
      log.gets = log.gets + 1
      assert(body == nil)
      if opts.get_error then error("timeout") end
      if opts.get_status ~= 200 then return opts.get_status end
      local answer = { format = "folio-statistics", versions = { 1, 2 }, known = opts.known }
      if not opts.no_until then answer["until"] = opts.watermark end
      return 200, answer
    end
    log.posts[#log.posts + 1] = body
    if opts.fail_at == #log.posts then return 403 end
    return 200, { ok = true }
  end
  log.result = Stats.sendSessions(cfg, d, Sync.headers(cfg))
  return log
end''')
DEFAULT_WINDOW=lua.eval('Stats.WINDOW')
assert DEFAULT_WINDOW==30*86400 and lua.eval('Stats.PER_REQUEST')==500 and lua.eval('Stats.MAX_REQUESTS')==40
assert lua.eval('Stats.MAX_PAGE_SECONDS')==120 and lua.eval('Stats.GAP_SECONDS')==300 and lua.eval('Stats.LOOKBACK')==86400
def run_sessions(path,watermark=0,window=None,**opts):
    py=StatsDb(path); g.py_session_db=py
    given={'watermark':watermark,'get_status':opts.pop('get_status',200)}
    if 'known' in opts: given['known']=lua.table_from(opts.pop('known'))
    given.update(opts)
    lua.execute('Stats.WINDOW=%d'%(window or DEFAULT_WINDOW))
    try: log=lua.eval('run_sessions')(py,lua.table_from(given))
    finally: lua.execute('Stats.WINDOW=%d'%DEFAULT_WINDOW)
    return py,log
def read_only(py):
    first_words=[q.split(' ')[0] for q in py.sql]
    assert 'query_only=ON' in py.sql[0] and first_words[0]=='PRAGMA' and first_words[1]=='BEGIN;' and first_words[-1]=='COMMIT;' and set(first_words[2:-1])=={'SELECT'}, py.sql
    assert py.closed, 'closed after reading'
def posts_of(log): return [log['posts'][i] for i in range(1,len(log['posts'])+1)]
def sent_sessions(log):
    out=[]
    for post in posts_of(log): out+=[{k:v for k,v in s.items()} for s in as_list(post['sessions'])]
    return out
def as_wire(session): return {k:session.get(k) for k in ('book','start','seconds','pages','from','to','read')}

with tempfile.TemporaryDirectory(prefix='folio-sessions-') as tmp:
    NOW=1791000000
    BASE=NOW-75*86400
    MD5=['aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa','bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb','cccccccccccccccccccccccccccccccc','dddddddddddddddddddddddddddddddd']
    books=[(1,MD5[0],0,'Dune','Frank Herbert'),(2,MD5[1],200,'Emma','Jane Austen'),(3,MD5[2],0,'Ulysses','James Joyce'),(4,MD5[3].upper(),0,'Kim','Rudyard Kipling')]
    rnd=random.Random(7)
    rows=[(1,1,BASE,60,100)]
    # 70 days of reading: pauses of 0 to 400 seconds between pages, some long pages, some overlaps.
    for day in range(70):
        book_id=day%4+1
        at=BASE+day*86400+3600*(8+day%5)
        for page in range(1+day,1+day+rnd.randint(6,14)):
            duration=rnd.choice([20,45,60,90,130,200,600])
            rows.append((book_id,page,at,duration,0 if book_id==2 else 100+book_id))
            at+=min(duration,120)+rnd.choice([0,5,30,120,299,300,301,400])-rnd.choice([0,0,0,15])
    # A session across the first 30-day window's edge, and across a 1-day edge.
    for i in range(6): rows.append((1,500+i,BASE+30*86400-200+i*100,100,100))
    for i in range(6): rows.append((3,700+i,BASE+10*86400-250+i*100,100,300))
    rows+=[(2,9,BASE+5*86400,0,100),(2,10,BASE+5*86400+10,-4,100),(9,3,BASE+5*86400,60,100)]  # unusable: no time, negative, no such book
    rows=[r for r in rows if r[0]!=9]
    path=tmp+'/statistics.sqlite3'
    build_stats_db(path,books,rows)
    original=Path(path).read_bytes()
    events=events_of(books,rows)
    full=[as_wire(dict(s.items())) for s in as_list(stats.derive(lua_rows(events),lua_rows([])))]
    assert len(full)>100 and any(s['seconds']>=600 and s['pages']>=5 for s in full)
    assert all(close_or_none(s['read'],s['read']) for s in full) and any(s['read'] is not None for s in full)

    # The first sync (until 0) sends the whole history; the window size makes no difference.
    py,default_run=run_sessions(path)
    read_only(py)
    sent_default=[as_wire(s) for s in sent_sessions(default_run)]
    assert default_run['result']==True and default_run['gets']==1
    assert_same_sessions(sent_default,full,'default windows')
    py,small_run=run_sessions(path,window=86400)
    read_only(py)
    assert_same_sessions([as_wire(s) for s in sent_sessions(small_run)],sent_default,'windows of a day')
    assert_same_sessions([as_wire(s) for s in sent_sessions(small_run)],full,'windows of a day against one pass')
    assert [p['version'] for p in posts_of(default_run)]==[2] and posts_of(default_run)[0]['format']=='folio-statistics'
    post=posts_of(default_run)[0]
    assert post['exportedAt']==NOW and post['device']['id']=='dev1' and post['device']['name']=='Kindle'
    assert [s['start'] for s in full]==sorted(s['start'] for s in full), 'sorted by start'

    # With a watermark, only sessions from a day before it; a session across the cut is not sent.
    crossing=next(s for s in full if s['seconds']>=500 and s['pages']>=5 and s['start']>BASE+3*86400)
    cut=crossing['start']+300
    rows_before_cut=[e for e in events if e[2]<cut]
    assert any(e[0]==crossing['book'] and crossing['start']<=e[2]<cut for e in events) and any(e[0]==crossing['book'] and e[2]>=cut and e[2]<=crossing['start']+crossing['seconds']+300 for e in events)
    for window in (None,86400):
        py,cut_run=run_sessions(path,watermark=cut+86400,window=window)
        read_only(py)
        wanted=[s for s in full if s['start']>=cut]
        assert_same_sessions([as_wire(s) for s in sent_sessions(cut_run)],wanted,'from the cut, window %s'%window)
        assert crossing['start'] not in [s['start'] for s in sent_sessions(cut_run)] and all(s['start']>=cut for s in sent_sessions(cut_run))
        # The part of the crossing session after the cut is not sent cut short.
        assert not [s for s in sent_sessions(cut_run) if s['book']==crossing['book'] and cut<=s['start']<=crossing['start']+crossing['seconds']+300]
    # A watermark small enough that the cut is 0 sends everything.
    py,low=run_sessions(path,watermark=3600)
    assert_same_sessions([as_wire(s) for s in sent_sessions(low)],full,'a watermark under a day')
    assert Path(path).read_bytes()==original, 'the statistics database is never written'

    # Nothing is sent when the phone has nothing to ask: no watermark, an error, or an old phone.
    for label,opts in (('old phone',{'get_status':404}),('no until',{'no_until':True}),('error',{'get_error':True}),('refused',{'get_status':403})):
        py,none=run_sessions(path,**opts)
        assert none['result']==False and none['gets']==1 and len(posts_of(none))==0 and not py.sql, (label,py.sql)
    # No sessions at all (never read): nothing is posted, and that counts as sent.
    quiet=tmp+'/quiet.sqlite3'
    build_stats_db(quiet,books,[])
    py,nothing=run_sessions(quiet)
    read_only(py)
    assert nothing['result']==True and len(posts_of(nothing))==0
    # An unreadable database: rolled back, closed, nothing sent.
    Path(tmp+'/broken.sqlite3').write_bytes(b'not a database at all')
    py,unreadable=run_sessions(tmp+'/broken.sqlite3')
    assert unreadable['result']==False and len(posts_of(unreadable))==0 and py.closed

    # 1,200 sessions go as 3 requests of 500, 500 and 200; the first refusal stops the sending.
    many=tmp+'/many.sqlite3'
    many_books=[(1,MD5[0],100,'Dune','Frank Herbert')]
    many_rows=[(1,1+i%50,NOW-1200*1000+i*1000,60,0) for i in range(1200)]
    build_stats_db(many,many_books,many_rows)
    py,run=run_sessions(many)
    read_only(py)
    assert run['result']==True and [len(as_list(p['sessions'])) for p in posts_of(run)]==[500,500,200], [len(as_list(p['sessions'])) for p in posts_of(run)]
    assert [s['start'] for s in sent_sessions(run)]==[NOW-1200*1000+i*1000 for i in range(1200)]
    py,stopped=run_sessions(many,fail_at=2)
    assert stopped['result']==False and len(posts_of(stopped))==2, 'a refusal on the second request stops the sending'
    py,thrown=run_sessions(many,fail_at=1)
    assert thrown['result']==False and len(posts_of(thrown))==1
    # At most 40 requests a sync; the rest go next time.
    huge=tmp+'/huge.sqlite3'
    build_stats_db(huge,many_books,[(1,1+i%50,NOW-20100*1000+i*1000,60,0) for i in range(20100)])
    py,capped=run_sessions(huge)
    assert capped['result']==False and len(posts_of(capped))==40 and sum(len(as_list(p['sessions'])) for p in posts_of(capped))==20000
    # ... and with the watermark that follows, the next sync sends the rest.
    last=sent_sessions(capped)[-1]
    py,rest=run_sessions(huge,watermark=last['start']+last['seconds'])
    cut_rest=last['start']+last['seconds']-86400
    assert [s['start'] for s in sent_sessions(rest)]==[t for t in (NOW-20100*1000+i*1000 for i in range(20100)) if t>=cut_rest] and rest['result']==True
    assert sent_sessions(rest)[-1]['start']==NOW-1000 and len(sent_sessions(rest))<500, 'the one-day look-back resends the last day, then the rest'

    # Names go only for books the phone doesn't know, and nothing else about a book is sent.
    bad_bytes=b'Bad \xff\xfe bytes \xc3 and \xed\xa0\x80 ok'
    named=tmp+'/named.sqlite3'
    letters='abcdef012'
    n_md5=[ch*32 for ch in letters]
    n_books=[
        (1,n_md5[0],100,'Known Book','Known Author'),
        (2,n_md5[1],100,'Unknown Book','Ann Lee\nBob Ray\r\nCy Fox'),
        (3,n_md5[2],100,'T\x01i\x07tle\x7f with\ttab\u0085 end','A\x00u\x1fthor'),
        (4,n_md5[3],100,'é'*600,'é'*600),
        (5,n_md5[4],100,'\U0001F4D6'*300,''),
        (6,n_md5[5],100,bad_bytes,bad_bytes),
        (7,n_md5[6],100,'',''),
        (8,n_md5[7],100,None,None),
        (9,n_md5[8],100,' \t\n \x01 ','Someone'),
    ]
    build_stats_db(named,n_books,[(i,1,NOW-100000+i*1000,60,0) for i in range(1,10)])
    py,with_known=run_sessions(named,known=[n_md5[0].upper(),'0'*32])
    read_only(py)
    assert with_known['result']==True and len(posts_of(with_known))==1
    sent_books={k:{kk:vv for kk,vv in v.items()} for k,v in posts_of(with_known)[0]['books'].items()}
    assert sorted(sent_books)==sorted([n_md5[1],n_md5[2],n_md5[3],n_md5[4],n_md5[5]]), sorted(sent_books)
    assert all(sorted(v)==['authors','title'] for v in sent_books.values())
    assert sent_books[n_md5[1]]=={'title':'Unknown Book','authors':'Ann Lee, Bob Ray, Cy Fox'}
    assert sent_books[n_md5[2]]=={'title':'Title withtab end','authors':'Author'}, sent_books[n_md5[2]]
    assert sent_books[n_md5[3]]=={'title':'é'*512,'authors':'é'*512}
    assert sent_books[n_md5[4]]=={'title':'\U0001F4D6'*256,'authors':''}, 'a character outside the BMP counts as two'
    assert sent_books[n_md5[5]]['title']=='Bad  bytes  and  ok' and sent_books[n_md5[5]]['authors']=='Bad  bytes  and  ok', sent_books[n_md5[5]]
    for entry in sent_books.values():
        assert len(entry['title'].encode('utf-16-le'))//2<=512 and len(entry['authors'].encode('utf-16-le'))//2<=512
        assert not any(ord(ch)<32 or 127<=ord(ch)<160 for ch in entry['title']+entry['authors'])
    # A book whose title is empty, only spaces or only control characters gets no entry (the phone would refuse the request), but its sessions are sent.
    for empty in (n_md5[6],n_md5[7],n_md5[8]):
        assert empty not in sent_books and empty in [s['book'] for s in sent_sessions(with_known)], (empty,sorted(sent_books),[s['book'] for s in sent_sessions(with_known)])
    assert len(sent_sessions(with_known))==9
    body=posts_of(with_known)[0]
    assert sorted(k for k in body.keys())==['books','device','exportedAt','format','sessions','version'], sorted(body.keys())
    assert sorted(body['device'].keys())==['id','name']
    for s in sent_sessions(with_known): assert set(s)<={'book','start','seconds','pages','from','to','read'}, s
    # A phone that doesn't answer `known` (or answers it with something else) gets a name for every book it has one for.
    py,without_known=run_sessions(named)
    assert sorted(posts_of(without_known)[0]['books'].keys())==sorted(n_md5[:6])
    py,odd_known=run_sessions(named,known=[1,True])
    assert sorted(posts_of(odd_known)[0]['books'].keys())==sorted(n_md5[:6])
    # A request holds names only for its own sessions; a book with no unknown digest has no `books`.
    py,all_known=run_sessions(named,known=n_md5)
    assert posts_of(all_known)[0]['books'] is None, 'no names to send, no key'
    # A database without titles still gives its sessions.
    plain=tmp+'/plain.sqlite3'
    c=sqlite3.connect(plain)
    c.execute('CREATE TABLE book (id INTEGER, md5 TEXT, pages INTEGER)')
    c.execute('CREATE TABLE page_stat_data (id_book INTEGER, page INTEGER NOT NULL DEFAULT 0, start_time INTEGER NOT NULL DEFAULT 0, duration INTEGER NOT NULL DEFAULT 0, total_pages INTEGER NOT NULL DEFAULT 0, UNIQUE (id_book, page, start_time))')
    c.execute("INSERT INTO book VALUES (1,'%s',100)"%MD5[0]); c.execute('INSERT INTO page_stat_data VALUES (1,1,%d,60,100)'%(NOW-5000)); c.commit(); c.close()
    py,plain_run=run_sessions(plain)
    assert plain_run['result']==True and len(sent_sessions(plain_run))==1 and posts_of(plain_run)[0]['books'] is None

    # Through Stats.send: the totals first, then the watermark, then the sessions. An old phone gets the totals only.
    g.py_make_db=lambda: StatsDb(path)
    lua.execute(r'''
    local K, P, d, cfg = fake(home)
    local order = {}
    d.open_statistics = function() return stats_db(py_make_db()) end
    d.request = function(method, url, headers, body)
      order[#order + 1] = method .. " " .. url:match("/folio/v2/.*$") .. (body and (" v" .. tostring(body.version)) or "")
      if method == "GET" then return 200, { ["until"] = 0, known = {} } end
      return 200, { ok = true }
    end
    assert(Stats.send(cfg, d, Sync.headers(cfg)) == true)
    assert(order[1] == "POST /folio/v2/statistics v1" and order[2] == "GET /folio/v2/statistics", order[1] .. "|" .. order[2])
    assert(order[3] == "POST /folio/v2/statistics v2" and #order == 3, table.concat(order, ","))
    -- A phone without version 2: the totals are sent, the GET answers 404, no session is posted.
    local old = {}
    d.open_statistics = function() return stats_db(py_make_db()) end
    d.request = function(method, url, headers, body)
      old[#old + 1] = method .. (body and (" v" .. tostring(body.version)) or "")
      if method == "GET" then return 404 end
      return 200, { ok = true }
    end
    assert(Stats.send(cfg, d, Sync.headers(cfg)) == true, "the totals still count")
    assert(table.concat(old, ",") == "POST v1,GET", table.concat(old, ","))
    -- Sessions that fail never fail the totals.
    d.request = function(method) if method == "GET" then error("timeout") end return 200, { ok = true } end
    assert(Stats.send(cfg, d, Sync.headers(cfg)) == true)
    ''')
    assert Path(path).read_bytes()==original
print('Reading sessions: the Lua matches the Kotlin reference on every shared case; read only from real files in windows (same result as one pass), from a day before the phone\'s watermark without a cut-short session, 500 a request, 40 requests at most, stopping at the first refusal; titles and authors only for books the phone does not know; an old phone gets the totals only.')


# main.lua glue: a version 2 setup file applied at start, the menu, and the sync settings.
# KOReader modules the plugin no longer needs (confirm box, collections, SQLite, events) are not
# provided: loading them would fail here.
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
    stored, messages, shown, scheduled, unscheduled, sync_requests = {}, {}, {}, {}, 0, 0
    typed = "Books/Librillo"
    local modules = {
      ["ui/widget/container/widgetcontainer"] = { extend = function(self, t) return t end },
      ["ui/uimanager"] = { show = function(self, w) shown[#shown + 1] = w; if w.text then messages[#messages + 1] = w.text end end,
        close = function() end,
        scheduleIn = function(self, seconds, f) scheduled[#scheduled + 1] = { seconds = seconds, f = f } end,
        unschedule = function() unscheduled = unscheduled + 1 end, nextTick = function(self, f) f() end },
      ["ui/widget/infomessage"] = { new = function(self, t) return t end },
      ["ui/widget/inputdialog"] = { new = function(self, t)
        t.getInputText = function() return typed end
        t.onShowKeyboard = function() end
        return t end },
      ["ui/widget/multiconfirmbox"] = { new = function(self, t) return t end },
      ["ui/network/manager"] = { runWhenConnected = function(self, f) sync_requests = sync_requests + 1 end },
      ["datastorage"] = { getSettingsDir = function() return "/settings" end, getDataDir = function() return "/koreader" end },
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
    G.plugin=plugin
    assert not (plugin_dir/'link-setup.json').exists(), 'the setup file with the key is removed'
    main_lua.execute(r'''
    local file = stored["/settings/kosync.lua"].settings
    assert(file.custom_server == "http://192.168.1.42:8765" and file.username == "folio" and file.userkey == "abc123")
    assert(file.auto_sync == true and file.sync_forward == 1 and file.sync_backward == 1)
    local legacy = reader_values.kosync
    assert(legacy.custom_server == "http://192.168.1.42:8765" and legacy.sync_forward == 2, "the older place keeps its own choice")
    local mine = reader_values.folio_sync
    assert(mine.root == "Books/Librillo" and mine.auto_sync == true and mine.device_name == "Paperwhite" and mine.set_up == 2)
    assert(reader_values.folio_auto_fetch == nil, "nothing written for the old book fetch")
    local every5, told = false, false
    for _, s in ipairs(scheduled) do
      if s.seconds == 300 then every5 = true end
      if s.seconds == 1 then s.f() end
    end
    assert(every5, "automatic sync checks every 5 minutes")
    for _, m in ipairs(messages) do
      if m:find("Restart KOReader once", 1, true) and m:find("Librillo > Sync now", 1, true) then told = true end
    end
    assert(told, "the person is told to restart, then sync")
    -- The old flows are gone.
    for _, name in ipairs({ "fetchBooks", "findPhone", "reading", "collections", "statistics", "configure",
        "linkConfig", "defaultFolder", "root", "onReaderReady" }) do
      assert(plugin[name] == nil, "removed: " .. name)
    end
    ''')
    print('Plugin start: setup version 2 applied to both Progress sync places and the plugin settings, file removed, nothing for the old fetch, 5-minute checks, restart then Sync now; old flows gone.')

    main_lua.execute(r'''
    function menu_texts()
      local items = {}
      plugin:addToMainMenu(items)
      local entry = items.folio_bridge
      assert(entry.text == "Librillo" and entry.sub_item_table == nil and entry.sub_item_table_func)
      local menu = entry.sub_item_table_func()
      local texts = {}
      for i, item in ipairs(menu) do texts[i] = item.text or item.text_func() end
      return menu, texts
    end
    local menu, texts = menu_texts()
    assert(#texts == 4 and texts[1] == "Sync now" and texts[2] == "Sync automatically"
      and texts[3] == "Library folder: Books/Librillo" and texts[4] == "About", table.concat(texts, " | "))
    assert(menu[2].checked_func() == true)
    -- About: one plain line, and the menu stays open.
    local before = #messages
    assert(menu[4].keep_menu_open)
    menu[4].callback()
    local about = messages[#messages]
    assert(#messages == before + 1 and not about:find("\n") and about:find("Librillo on your phone", 1, true), about)
    -- No words the owner found meaningless, and no jargon.
    for _, text in ipairs(texts) do about = about .. " " .. text end
    for _, word in ipairs({ "Get books", "Find Librillo", "Continue from", "Review", "statistics", "Link",
        "kosync", "manifest", "baseline", "Folio" }) do
      assert(not about:find(word, 1, true), "menu says " .. word)
    end
    -- Sync automatically: off stops the checks, on starts them again.
    local stops, checks = unscheduled, #scheduled
    menu[2].callback()
    assert(reader_values.folio_sync.auto_sync == false and unscheduled == stops + 1 and menu[2].checked_func() == false)
    menu[2].callback()
    assert(reader_values.folio_sync.auto_sync == true and #scheduled == checks + 1 and scheduled[#scheduled].seconds == 300)
    ''')
    print('Menu: Sync now, Sync automatically, Library folder, About (one plain line); no old items or jargon; the switch stops and starts the 5-minute checks.')

    main_lua.execute(r'''
    -- Library folder: a plain name is saved and the open menu shows it; a path outside is refused.
    local menu = menu_texts()
    local refreshed = 0
    local touchmenu = { updateItems = function() refreshed = refreshed + 1 end }
    assert(menu[3].keep_menu_open)
    typed = "Kindle Books"
    menu[3].callback(touchmenu)
    local dialog = shown[#shown]
    assert(dialog.title == "Library folder" and dialog.input == "Books/Librillo")
    dialog.buttons[1][2].callback()
    assert(reader_values.folio_sync.root == "Kindle Books" and refreshed == 1)
    assert(messages[#messages] == "Books will move to Kindle Books at the next sync.", messages[#messages])
    local _, texts = menu_texts()
    assert(texts[3] == "Library folder: Kindle Books")
    typed = "/mnt/us"
    menu[3].callback(touchmenu)
    shown[#shown].buttons[1][2].callback()
    assert(reader_values.folio_sync.root == "Kindle Books" and refreshed == 1)
    assert(messages[#messages] == "Type a folder name, like Librillo.")
    ''')
    print('Library folder: a plain name saved and shown at once; a path outside the home folder refused.')

    main_lua.execute(r'''
    -- Questions: only listed when one is open; answering keeps the answer and asks for a sync.
    reader_values.folio_sync.questions = { { id = "q-1", text = "Which?", options = { "A", "B" } },
      { id = "q-2", text = "Which?", options = { "A", "B" } } }
    reader_values.folio_sync.answers = { ["q-2"] = "A" }
    local menu, texts = menu_texts()
    assert(#texts == 5 and texts[4] == "Questions (1)" and texts[5] == "About", table.concat(texts, " | "))
    assert(menu[4].enabled_func())
    menu[4].callback()
    local ask = shown[#shown]
    assert(ask.text == "Which?" and ask.choice1_text == "A" and ask.choice2_text == "B" and ask.cancel_text == "Later")
    ask.choice1_callback()
    assert(reader_values.folio_sync.answers["q-1"] == "A" and reader_values.folio_sync.answers["q-2"] == "A")
    assert(sync_requests == 1, "a sync starts once the last question is answered")
    local _, after = menu_texts()
    assert(#after == 4 and after[4] == "About", "no Questions item without questions")
    ''')
    print('Questions: shown only while one is open; an answer is kept and a sync starts.')

    main_lua.execute(r'''
    -- Wi-Fi connects: only the sync check runs, even where the old automatic fetch was switched on.
    reader_values.folio_auto_fetch = true
    reader_values.folio_auto_positions = true
    local count = #scheduled
    plugin:onNetworkConnected()
    assert(#scheduled == count + 1 and scheduled[#scheduled].seconds == 3, "one check, 3 seconds later")
    plugin:onCloseDocument()
    assert(#scheduled == count + 2 and scheduled[#scheduled].seconds == 3)
    ''')
    cfg=plugin.syncConfig()
    assert cfg['server']=='http://192.168.1.42:8765' and cfg['username']=='folio' and cfg['key']=='abc123'
    assert cfg['device']=='dev1' and cfg['name']=='Paperwhite' and cfg['home']==str(books) and cfg['root']=='Kindle Books'
    print('Plugin events and settings: Wi-Fi connect and closing a book schedule one sync check (old fetch settings ignored); sync settings read back.')
