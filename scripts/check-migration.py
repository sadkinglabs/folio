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
source=(root/'app/src/main/java/dev/sadking/folio/data/Database.kt').read_text().split('val MIGRATION_6_7',1)[1]
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
