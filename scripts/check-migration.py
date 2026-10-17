#!/usr/bin/env python3
"""Checks actual migration SQL against exported Room schemas using SQLite, without an Android runtime."""
import json,re,sqlite3
from pathlib import Path
root=Path(__file__).resolve().parents[1]
schemas=root/'app/schemas/dev.sadking.folio.data.LibraryDatabase'
v1=json.loads((schemas/'1.json').read_text())['database']
v2=json.loads((schemas/'2.json').read_text())['database']
def schema(connection,model):
    for entity in model['entities']:
        name=entity['tableName']
        connection.execute(entity['createSql'].replace('${TABLE_NAME}',name))
        for index in entity.get('indices',[]):connection.execute(index['createSql'].replace('${TABLE_NAME}',name))
old=sqlite3.connect(':memory:');old.execute('PRAGMA foreign_keys=ON');schema(old,v1)
columns=[f['columnName'] for f in v1['entities'][0]['fields']]
values=[1000 if c=='addedAt' else None if c=='coverPath' else 'old-book' if c=='id' else 'Preserved title' if c=='title' else '[]' if c in ('authors','tags','identifiers') else 'Unread' if c=='status' else 'unchanged' for c in columns]
old.execute(f'INSERT INTO books ({",".join(columns)}) VALUES ({",".join("?" for _ in columns)})',values)
old.execute("INSERT INTO collections VALUES ('shelf','Saved shelf')")
old.execute("INSERT INTO memberships VALUES ('old-book','shelf')")
old.execute("INSERT INTO transfers VALUES ('old-book','legacy-device','book.epub','hash',1000)")
source=(root/'app/src/main/java/dev/sadking/folio/data/Database.kt').read_text().split('val MIGRATION_1_2',1)[1].split('val MIGRATION_2_3',1)[0]
statements=re.findall(r'db\.execSQL\(\s*"([^"\n]+)"\s*\)',source)
assert len(statements)==7,statements
for sql in statements:old.execute(sql)
expected=sqlite3.connect(':memory:');schema(expected,v2)
for entity in v2['entities']:
    name=entity['tableName']
    assert old.execute(f'PRAGMA table_info({name})').fetchall()==expected.execute(f'PRAGMA table_info({name})').fetchall(),name
    old_indexes=sorted((r[1],r[2]) for r in old.execute(f'PRAGMA index_list({name})'))
    new_indexes=sorted((r[1],r[2]) for r in expected.execute(f'PRAGMA index_list({name})'))
    assert old_indexes==new_indexes,name
assert old.execute('SELECT title FROM books').fetchone()==('Preserved title',)
assert old.execute('SELECT ruleField,ruleValue,syncEnabled FROM collections').fetchone()==('Manual','',0)
assert old.execute('SELECT * FROM memberships').fetchone()==('old-book','shelf')
assert old.execute('SELECT deviceId FROM transfers').fetchone()==('legacy-device',)
assert not old.execute('PRAGMA foreign_key_check').fetchall()
print('Migration SQL matches Room v2 schema; v1 books, shelves, memberships and transfers preserved. Android Room validation still requires instrumentation.')

v3=json.loads((schemas/'3.json').read_text())['database']
source=(root/'app/src/main/java/dev/sadking/folio/data/Database.kt').read_text().split('val MIGRATION_2_3',1)[1].split('val MIGRATION_3_4',1)[0]
statements=re.findall(r'db\.execSQL\(\s*"([^"\n]+)"\s*\)',source)
assert len(statements)==2
for sql in statements:old.execute(sql)
expected3=sqlite3.connect(':memory:');schema(expected3,v3)
for entity in v3['entities']:
    name=entity['tableName']
    assert old.execute(f'PRAGMA table_info({name})').fetchall()==expected3.execute(f'PRAGMA table_info({name})').fetchall(),name
    assert sorted((r[1],r[2]) for r in old.execute(f'PRAGMA index_list({name})'))==sorted((r[1],r[2]) for r in expected3.execute(f'PRAGMA index_list({name})')),name
old.execute("INSERT INTO wishlist VALUES ('wish','/works/OL1W','Title','[]',NULL,1,'Title',0,1000,NULL)")
assert old.execute('SELECT title FROM books').fetchone()==('Preserved title',)
assert old.execute('SELECT title FROM wishlist').fetchone()==('Title',)
assert not old.execute('PRAGMA foreign_key_check').fetchall()
print('Migration 2→3 matches Room v3 schema; existing library intact and wishlist table usable.')

v4=json.loads((schemas/'4.json').read_text())['database']
source=(root/'app/src/main/java/dev/sadking/folio/data/Database.kt').read_text().split('val MIGRATION_3_4',1)[1].split('val MIGRATION_4_5',1)[0]
statements=re.findall(r'db\.execSQL\(\s*"([^"\n]+)"\s*\)',source)
assert len(statements)==3,statements
for sql in statements:old.execute(sql)
expected4=sqlite3.connect(':memory:');schema(expected4,v4)
for entity in v4['entities']:
    name=entity['tableName']
    assert old.execute(f'PRAGMA table_info({name})').fetchall()==expected4.execute(f'PRAGMA table_info({name})').fetchall(),name
    assert old.execute(f'PRAGMA foreign_key_list({name})').fetchall()==expected4.execute(f'PRAGMA foreign_key_list({name})').fetchall(),name
    assert sorted((r[1],r[2]) for r in old.execute(f'PRAGMA index_list({name})'))==sorted((r[1],r[2]) for r in expected4.execute(f'PRAGMA index_list({name})')),name
assert old.execute('SELECT title FROM wishlist').fetchone()==('Title',)
assert old.execute('SELECT title FROM books').fetchone()==('Preserved title',)
assert not old.execute('PRAGMA foreign_key_check').fetchall()
print('Migration 3→4 matches Room v4 schema including foreign keys/indexes; books and wishlist preserved.')

v5=json.loads((schemas/'5.json').read_text())['database']
source=(root/'app/src/main/java/dev/sadking/folio/data/Database.kt').read_text().split('val MIGRATION_4_5',1)[1].split('val MIGRATION_5_6',1)[0]
statements=re.findall(r'db\.execSQL\(\s*"([^"\n]+)"\s*\)',source)
assert len(statements)==3,statements
for sql in statements:old.execute(sql)
expected5=sqlite3.connect(':memory:');schema(expected5,v5)
for entity in v5['entities']:
    name=entity['tableName']
    assert old.execute(f'PRAGMA table_info({name})').fetchall()==expected5.execute(f'PRAGMA table_info({name})').fetchall(),name
    assert old.execute(f'PRAGMA foreign_key_list({name})').fetchall()==expected5.execute(f'PRAGMA foreign_key_list({name})').fetchall(),name
    assert sorted((r[1],r[2]) for r in old.execute(f'PRAGMA index_list({name})'))==sorted((r[1],r[2]) for r in expected5.execute(f'PRAGMA index_list({name})')),name
old.execute("INSERT INTO wish_memberships VALUES ('wish','shelf')")
old.execute("INSERT INTO hardcover_observations VALUES (1,1,2,42,10,1000)")
old.execute("INSERT INTO hardcover_observations VALUES (2,1,3,100,10,1001)")
assert old.execute('SELECT title FROM books').fetchone()==('Preserved title',)
assert old.execute('SELECT count(*) FROM hardcover_observations').fetchone()==(2,)
try:
    old.execute("INSERT INTO wish_memberships VALUES ('missing','shelf')")
    raise AssertionError('Dangling membership accepted')
except sqlite3.IntegrityError:pass
old.execute("DELETE FROM wishlist WHERE id='wish'")
assert old.execute('SELECT count(*) FROM wish_memberships').fetchone()==(0,)
assert not old.execute('PRAGMA foreign_key_check').fetchall()
print('Migration 4→5 matches Room schema; catalogue list cascades and account-isolated observations verified in SQLite.')

old.execute("INSERT INTO wishlist VALUES ('wish6','hardcover:2','Retained catalogue','[]',NULL,2,'Retained catalogue',0,1000,NULL)")
v6=json.loads((schemas/'6.json').read_text())['database']
source=(root/'app/src/main/java/dev/sadking/folio/data/Database.kt').read_text().split('val MIGRATION_5_6',1)[1].split('val MIGRATION_6_7',1)[0]
statements=re.findall(r'db\.execSQL\(\s*"([^"\n]+)"\s*\)',source)
assert len(statements)==4,statements
for sql in statements:old.execute(sql)
expected6=sqlite3.connect(':memory:');schema(expected6,v6)
for entity in v6['entities']:
 name=entity['tableName']
 assert old.execute(f'PRAGMA table_info({name})').fetchall()==expected6.execute(f'PRAGMA table_info({name})').fetchall(),name
 assert old.execute(f'PRAGMA foreign_key_list({name})').fetchall()==expected6.execute(f'PRAGMA foreign_key_list({name})').fetchall(),name
 assert sorted((r[1],r[2]) for r in old.execute(f'PRAGMA index_list({name})'))==sorted((r[1],r[2]) for r in expected6.execute(f'PRAGMA index_list({name})')),name
assert old.execute('SELECT details,status FROM wishlist').fetchone()==('{}','')
assert old.execute('SELECT title FROM books').fetchone()==('Preserved title',)
old.execute("INSERT INTO removed_entries VALUES ('old-book','old-book','Preserved title','EPUB',1000)")
assert not old.execute('SELECT * FROM books WHERE id NOT IN (SELECT id FROM removed_entries)').fetchall()
assert old.execute('SELECT count(*) FROM books').fetchone()==(1,)
old.execute("DELETE FROM removed_entries WHERE groupId='old-book'")
assert old.execute('SELECT title FROM books WHERE id NOT IN (SELECT id FROM removed_entries)').fetchone()==('Preserved title',)
assert not old.execute('PRAGMA foreign_key_check').fetchall()
print('Migration 5→6 matches Room schema; legacy metadata defaults, soft removal/restore and original identities preserved.')

# New phone data uses revision-scoped anchors and cascades only on permanent book removal.
v7=json.loads((schemas/'7.json').read_text())['database']
source=(root/'app/src/main/java/dev/sadking/folio/data/Database.kt').read_text().split('val MIGRATION_6_7',1)[1].split('val MIGRATION_7_8',1)[0]
statements=re.findall(r'db\.execSQL\(\s*"([^"\n]+)"\s*\)',source)
assert len(statements)==3,statements
for sql in statements:old.execute(sql)
expected7=sqlite3.connect(':memory:');schema(expected7,v7)
for entity in v7['entities']:
 name=entity['tableName']
 assert old.execute(f'PRAGMA table_info({name})').fetchall()==expected7.execute(f'PRAGMA table_info({name})').fetchall(),name
 assert old.execute(f'PRAGMA foreign_key_list({name})').fetchall()==expected7.execute(f'PRAGMA foreign_key_list({name})').fetchall(),name
 assert sorted((r[1],r[2]) for r in old.execute(f'PRAGMA index_list({name})'))==sorted((r[1],r[2]) for r in expected7.execute(f'PRAGMA index_list({name})')),name
old.execute("INSERT INTO phone_reading VALUES ('old-book','revision-a','{}','/body/DocFragment/body/p',0.2,1000)")
old.execute("INSERT INTO phone_reading VALUES ('old-book','revision-b','{}','/body/DocFragment/body/p',0.3,2000)")
old.execute("INSERT INTO phone_bookmarks VALUES ('mark','old-book','revision-a','{}','Chapter',1000)")
assert old.execute('SELECT count(*) FROM phone_reading').fetchone()==(2,)
old.execute("DELETE FROM books WHERE id='old-book'")
assert old.execute('SELECT count(*) FROM phone_reading').fetchone()==(0,)
assert old.execute('SELECT count(*) FROM phone_bookmarks').fetchone()==(0,)
assert old.execute('SELECT title FROM wishlist').fetchone()==('Retained catalogue',)
assert not old.execute('PRAGMA foreign_key_check').fetchall()
print('Migration 6→7 matches Room schema; separate file-revision anchors, permanent-deletion cascades and catalogue data preservation verified.')

# Folio Link adds one table for every device's latest kosync report; nothing existing changes.
v8=json.loads((schemas/'8.json').read_text())['database']
source=(root/'app/src/main/java/dev/sadking/folio/data/Database.kt').read_text().split('val MIGRATION_7_8',1)[1].split('val MIGRATION_8_9',1)[0]
statements=re.findall(r'db\.execSQL\(\s*"([^"\n]+)"\s*\)',source)
assert len(statements)==1,statements
old.execute("INSERT INTO reading VALUES ('kept','kindle',0.4,'xp','reading',0,1,'a.sdr','h','d')")
for sql in statements:old.execute(sql)
expected8=sqlite3.connect(':memory:');schema(expected8,v8)
for entity in v8['entities']:
 name=entity['tableName']
 assert old.execute(f'PRAGMA table_info({name})').fetchall()==expected8.execute(f'PRAGMA table_info({name})').fetchall(),name
 assert old.execute(f'PRAGMA foreign_key_list({name})').fetchall()==expected8.execute(f'PRAGMA foreign_key_list({name})').fetchall(),name
 assert sorted((r[1],r[2]) for r in old.execute(f'PRAGMA index_list({name})'))==sorted((r[1],r[2]) for r in expected8.execute(f'PRAGMA index_list({name})')),name
assert old.execute("SELECT percent FROM reading WHERE bookId='kept'").fetchone()==(0.4,)
old.execute("INSERT INTO kosync_progress VALUES ('doc','kobo-1','Kobo','/body/p',0.5,10,10000)")
assert not old.execute('PRAGMA foreign_key_check').fetchall()
print('Migration 7→8 matches Room schema; KOReader reading data kept and Folio Link reports stored.')

# Schema 9: books gain a format (every existing book is an EPUB) and sync gets a baseline table.
old.execute("INSERT INTO books VALUES ('v8-book','Kept','[]','','','','[]','','','[]','A/Kept.epub','A/Kept.epub',NULL,'h8','h8',1000,'Reading')")
v9=json.loads((schemas/'9.json').read_text())['database']
assert v9['version']==9
source=(root/'app/src/main/java/dev/sadking/folio/data/Database.kt').read_text().split('val MIGRATION_8_9',1)[1].split('val MIGRATION_9_10',1)[0]
statements=re.findall(r'db\.execSQL\(\s*"([^"\n]+)"\s*\)',source)
assert len(statements)==2,statements
for sql in statements:old.execute(sql)
expected9=sqlite3.connect(':memory:');schema(expected9,v9)
for entity in v9['entities']:
 name=entity['tableName']
 assert old.execute(f'PRAGMA table_info({name})').fetchall()==expected9.execute(f'PRAGMA table_info({name})').fetchall(),name
 assert old.execute(f'PRAGMA foreign_key_list({name})').fetchall()==expected9.execute(f'PRAGMA foreign_key_list({name})').fetchall(),name
 assert sorted((r[1],r[2]) for r in old.execute(f'PRAGMA index_list({name})'))==sorted((r[1],r[2]) for r in expected9.execute(f'PRAGMA index_list({name})')),name
assert old.execute("SELECT format,title,status FROM books WHERE id='v8-book'").fetchone()==('epub','Kept','Reading')
assert old.execute("SELECT percent FROM reading WHERE bookId='kept'").fetchone()==(0.4,)
old.execute("INSERT INTO sync_baseline VALUES ('v8-book','kindle-1','A/Kept.epub','h8','Reading',1000,'[]','[]',2000)")
try:
    old.execute("INSERT INTO sync_baseline VALUES ('v8-book','kindle-1','A/Kept.epub','h8','Finished',1001,'[]','[]',2001)")
    raise AssertionError('Two baselines for one book on one e-reader')
except sqlite3.IntegrityError:pass
# Purging a book keeps what the e-reader last had, so the next sync can act on it.
old.execute("DELETE FROM books WHERE id='v8-book'")
assert old.execute('SELECT count(*) FROM sync_baseline').fetchone()==(1,)
assert not old.execute('PRAGMA foreign_key_check').fetchall()
print('Migration 8→9 matches Room schema; existing books become EPUB and keep their data, baselines are one per book per e-reader and outlive a purge.')

# Schema 10: books remember when their status last changed. Existing books get 0, "unknown".
old.execute("INSERT INTO books VALUES ('v9-book','Kept','[]','','','','[]','','','[]','A/Kept.pdf','A/Kept.pdf',NULL,'h9','h9',1000,'Finished','pdf')")
v10=json.loads((schemas/'10.json').read_text())['database']
assert v10['version']==10
source=(root/'app/src/main/java/dev/sadking/folio/data/Database.kt').read_text().split('val MIGRATION_9_10',1)[1].split('val MIGRATION_10_11',1)[0]
statements=re.findall(r'db\.execSQL\(\s*"([^"\n]+)"\s*\)',source)
assert len(statements)==1,statements
for sql in statements:old.execute(sql)
expected10=sqlite3.connect(':memory:');schema(expected10,v10)
for entity in v10['entities']:
 name=entity['tableName']
 assert old.execute(f'PRAGMA table_info({name})').fetchall()==expected10.execute(f'PRAGMA table_info({name})').fetchall(),name
 assert old.execute(f'PRAGMA foreign_key_list({name})').fetchall()==expected10.execute(f'PRAGMA foreign_key_list({name})').fetchall(),name
 assert sorted((r[1],r[2]) for r in old.execute(f'PRAGMA index_list({name})'))==sorted((r[1],r[2]) for r in expected10.execute(f'PRAGMA index_list({name})')),name
assert old.execute("SELECT status,statusAt,format FROM books WHERE id='v9-book'").fetchone()==('Finished',0,'pdf')
assert old.execute('SELECT count(*) FROM sync_baseline').fetchone()==(1,)
assert not old.execute('PRAGMA foreign_key_check').fetchall()
print('Migration 9→10 matches Room schema; books keep their status, with an unknown (0) time of change, and baselines are kept.')

# Schema 11 (M10): Hardcover sync gets baselines, match records and questions; catalogue entries
# remember when their status last changed. Existing entries get 0, "unknown".
old.execute("INSERT INTO wishlist (id,catalogKey,title,authors,coverPath,hardcoverId,hardcoverTitle,hardcoverSynced,addedAt,linkedBookId,details,status) VALUES ('wish10','hardcover:10','Kept entry','[]',NULL,10,'Kept entry',1,1000,NULL,'{}','Want to Read')")
v11=json.loads((schemas/'11.json').read_text())['database']
assert v11['version']==11
source=(root/'app/src/main/java/dev/sadking/folio/data/Database.kt').read_text().split('val MIGRATION_10_11',1)[1].split('val MIGRATION_11_12',1)[0]
statements=re.findall(r'db\.execSQL\(\s*"([^"\n]+)"\s*\)',source)
assert len(statements)==4,statements
for sql in statements:old.execute(sql)
expected11=sqlite3.connect(':memory:');schema(expected11,v11)
for entity in v11['entities']:
 name=entity['tableName']
 assert old.execute(f'PRAGMA table_info({name})').fetchall()==expected11.execute(f'PRAGMA table_info({name})').fetchall(),name
 assert old.execute(f'PRAGMA foreign_key_list({name})').fetchall()==expected11.execute(f'PRAGMA foreign_key_list({name})').fetchall(),name
 assert sorted((r[1],r[2]) for r in old.execute(f'PRAGMA index_list({name})'))==sorted((r[1],r[2]) for r in expected11.execute(f'PRAGMA index_list({name})')),name
assert old.execute("SELECT title,status,statusAt,hardcoverId FROM wishlist WHERE id='wish10'").fetchone()==('Kept entry','Want to Read',0,10)
assert old.execute("SELECT status,statusAt FROM books WHERE id='v9-book'").fetchone()==('Finished',0)
assert old.execute('SELECT count(*) FROM sync_baseline').fetchone()==(1,)
assert old.execute('SELECT count(*) FROM hardcover_observations').fetchone()==(2,)
old.execute("INSERT INTO hardcover_baseline VALUES (1,10,1,0,2000)")
old.execute("INSERT INTO hardcover_baseline VALUES (2,10,3,5,2000)")
old.execute("INSERT INTO hardcover_matches VALUES ('v9-book','UNSURE','[]','key',2000)")
old.execute("INSERT INTO hardcover_questions VALUES (1,10,'wish10','Kept entry',3,'Reading',2000)")
for sql in ["INSERT INTO hardcover_baseline VALUES (1,10,2,0,2001)","INSERT INTO hardcover_matches VALUES ('v9-book','NOT_FOUND','[]','key',2001)","INSERT INTO hardcover_questions VALUES (1,10,'wish10','Kept entry',2,'Paused',2001)"]:
    try:
        old.execute(sql)
        raise AssertionError('Duplicate key accepted: '+sql)
    except sqlite3.IntegrityError:pass
assert not old.execute('PRAGMA foreign_key_check').fetchall()
print('Migration 10→11 matches Room schema; catalogue entries and books keep their status (entries with an unknown (0) time of change), e-reader baselines and observations are kept, and the Hardcover tables enforce one row per key.')

# Schema 12: device profiles (names, merges and forgets for the devices that report positions).
# A new table only: every report, snapshot and position stays as it was.
old.execute("INSERT INTO kosync_progress VALUES ('doc11','kindle-11','KindlePaperWhite5SE','/body/p',0.5,20,20000)")
v12=json.loads((schemas/'12.json').read_text())['database']
assert v12['version']==12
source=(root/'app/src/main/java/dev/sadking/folio/data/Database.kt').read_text().split('val MIGRATION_11_12',1)[1].split('val MIGRATION_12_13',1)[0]
statements=re.findall(r'db\.execSQL\(\s*"([^"\n]+)"\s*\)',source)
assert len(statements)==1,statements
for sql in statements:old.execute(sql)
expected12=sqlite3.connect(':memory:');schema(expected12,v12)
for entity in v12['entities']:
 name=entity['tableName']
 assert old.execute(f'PRAGMA table_info({name})').fetchall()==expected12.execute(f'PRAGMA table_info({name})').fetchall(),name
 assert old.execute(f'PRAGMA foreign_key_list({name})').fetchall()==expected12.execute(f'PRAGMA foreign_key_list({name})').fetchall(),name
 assert sorted((r[1],r[2]) for r in old.execute(f'PRAGMA index_list({name})'))==sorted((r[1],r[2]) for r in expected12.execute(f'PRAGMA index_list({name})')),name
assert old.execute("SELECT percent FROM reading WHERE bookId='kept'").fetchone()==(0.4,)
assert old.execute("SELECT count(*) FROM kosync_progress").fetchone()==(2,)
assert old.execute("SELECT title,status FROM wishlist WHERE id='wish10'").fetchone()==('Kept entry','Want to Read')
old.execute("INSERT INTO device_profiles (deviceKey,updatedAt) VALUES ('ssh:old',3000)")
assert old.execute("SELECT displayName,mergedInto,hiddenAt FROM device_profiles").fetchone()==('',None,0)
old.execute("INSERT INTO device_profiles VALUES ('ereader:kindle-11','Paperwhite',NULL,0,3001)")
try:
    old.execute("INSERT INTO device_profiles VALUES ('ssh:old','Again',NULL,0,3002)")
    raise AssertionError('Two profiles for one device')
except sqlite3.IntegrityError:pass
assert not old.execute('PRAGMA foreign_key_check').fetchall()
print('Migration 11→12 matches Room schema; device profiles default to shown and unnamed, one per device, and reports, old positions and the library are kept.')

# Schema 13 (M11): highlights get a colour, a drawing style, an edit time and a soft delete; sync
# baselines can remember the annotation states both sides agreed on. Every existing row keeps its
# look, is unedited and live; every baseline's states are unknown (NULL).
old.execute("INSERT INTO annotations VALUES ('n12','v9-book','link:kindle-11','A line','My note','One','2026-10-01 10:00:00','/body/p[1]','/body/p[2]','note')")
v13=json.loads((schemas/'13.json').read_text())['database']
assert v13['version']==13
source=(root/'app/src/main/java/dev/sadking/folio/data/Database.kt').read_text().split('val MIGRATION_12_13',1)[1].split('val MIGRATION_13_14',1)[0]
statements=re.findall(r'db\.execSQL\(\s*"([^"\n]+)"\s*\)',source)
assert len(statements)==5,statements
assert all(sql.startswith('ALTER TABLE') and 'ADD COLUMN' in sql for sql in statements),'additive only'
for sql in statements:old.execute(sql)
expected13=sqlite3.connect(':memory:');schema(expected13,v13)
for entity in v13['entities']:
 name=entity['tableName']
 assert old.execute(f'PRAGMA table_info({name})').fetchall()==expected13.execute(f'PRAGMA table_info({name})').fetchall(),name
 assert old.execute(f'PRAGMA foreign_key_list({name})').fetchall()==expected13.execute(f'PRAGMA foreign_key_list({name})').fetchall(),name
 assert sorted((r[1],r[2]) for r in old.execute(f'PRAGMA index_list({name})'))==sorted((r[1],r[2]) for r in expected13.execute(f'PRAGMA index_list({name})')),name
assert old.execute("SELECT text,note,kind,color,drawer,editedAt,deletedAt FROM annotations WHERE id='n12'").fetchone()==('A line','My note','note','','','',0)
assert old.execute('SELECT count(*) FROM sync_baseline').fetchone()==(1,)
assert old.execute('SELECT annotationStates FROM sync_baseline').fetchone()==(None,)
assert old.execute("SELECT count(*) FROM device_profiles").fetchone()==(2,)
assert old.execute("SELECT status,statusAt FROM books WHERE id='v9-book'").fetchone()==('Finished',0)
assert not old.execute('PRAGMA foreign_key_check').fetchall()
print('Migration 12→13 matches Room schema; existing highlights keep their text, note and look, unedited and live; baselines are kept with unknown annotation states.')

# Schema 14 (M12): reading statistics get five new tables (sessions, KOReader digests, word counts,
# Hardcover read dates, and Kindle book names from A1). Nothing existing changes.
v14=json.loads((schemas/'14.json').read_text())['database']
assert v14['version']==14
source=(root/'app/src/main/java/dev/sadking/folio/data/Database.kt').read_text().split('val MIGRATION_13_14',1)[1].split('val ALL_MIGRATIONS',1)[0]
statements=re.findall(r'db\.execSQL\(\s*"([^"\n]+)"\s*\)',source)
assert len(statements)==11,statements
assert all(sql.startswith(('CREATE TABLE IF NOT EXISTS','CREATE INDEX IF NOT EXISTS')) for sql in statements),'additive only'
for sql in statements:old.execute(sql)
expected14=sqlite3.connect(':memory:');schema(expected14,v14)
for entity in v14['entities']:
 name=entity['tableName']
 assert old.execute(f'PRAGMA table_info({name})').fetchall()==expected14.execute(f'PRAGMA table_info({name})').fetchall(),name
 assert old.execute(f'PRAGMA foreign_key_list({name})').fetchall()==expected14.execute(f'PRAGMA foreign_key_list({name})').fetchall(),name
 assert sorted((r[1],r[2]) for r in old.execute(f'PRAGMA index_list({name})'))==sorted((r[1],r[2]) for r in expected14.execute(f'PRAGMA index_list({name})')),name
digest='0123456789abcdef0123456789abcdef'
old.execute(f"INSERT INTO reading_sessions VALUES ('k1','KINDLE','ereader:kindle-1',NULL,'{digest}',1000,60,0.1,0.2,0.05,3,2000)")
old.execute(f"INSERT INTO koreader_digests VALUES ('{digest}','book','h',2000)")
old.execute("INSERT INTO book_words VALUES ('book','h',90000,2000)")
old.execute("INSERT INTO hardcover_reads VALUES (1,10,42,'2026-01-01','2026-02-01',2000)")
old.execute(f"INSERT INTO stats_book_names VALUES ('{digest}','Kindle title','Author',2000)")
for sql in ["INSERT INTO reading_sessions VALUES ('k1','PHONE','phone:p',NULL,NULL,1000,60,NULL,NULL,NULL,NULL,2001)","INSERT INTO hardcover_reads VALUES (1,10,42,NULL,NULL,2001)"]:
    try:
        old.execute(sql)
        raise AssertionError('Duplicate key accepted: '+sql)
    except sqlite3.IntegrityError:pass
assert old.execute("SELECT title,status FROM books WHERE id='v9-book'").fetchone()==('Kept','Finished')
assert old.execute("SELECT text,note FROM annotations WHERE id='n12'").fetchone()==('A line','My note')
assert old.execute("SELECT title FROM wishlist WHERE id='wish10'").fetchone()==('Kept entry',)
assert old.execute('SELECT count(*) FROM sync_baseline').fetchone()==(1,)
assert not old.execute('PRAGMA foreign_key_check').fetchall()
print('Migration 13→14 matches Room schema; the five statistics tables take rows and keep one row per key, and existing books, highlights, baselines and catalogue data are kept.')
