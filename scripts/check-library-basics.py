#!/usr/bin/env python3
"""Check active-vs-removed queries and list deletion against exported Room 6 schema."""
from pathlib import Path
import json,re,sqlite3
root=Path(__file__).resolve().parents[1]
model=json.loads((root/'app/schemas/dev.sadking.folio.data.LibraryDatabase/6.json').read_text())['database']
source=(root/'app/src/main/java/dev/sadking/folio/data/Database.kt').read_text()
queries={name:sql for sql,name in re.findall(r'@Query\("([^"\n]+)"\)\s*(?:suspend )?fun (\w+)\(',source)}
c=sqlite3.connect(':memory:');c.execute('PRAGMA foreign_keys=ON')
for entity in model['entities']:
 name=entity['tableName'];c.execute(entity['createSql'].replace('${TABLE_NAME}',name))
 for index in entity.get('indices',[]):c.execute(index['createSql'].replace('${TABLE_NAME}',name))
def insert(table,**values):
 fields=next(e for e in model['entities'] if e['tableName']==table)['fields'];columns=[f['columnName'] for f in fields]
 vals=[values.get(f['columnName'],1 if f['affinity']=='INTEGER' else 0.5 if f['affinity']=='REAL' else None if not f.get('notNull',False) else '{}' if f['columnName']=='details' else '[]' if f['columnName'] in ['authors','tags','identifiers'] else 'fixture') for f in fields]
 c.execute(f'INSERT INTO {table} ({",".join(columns)}) VALUES ({",".join("?" for _ in columns)})',vals)
insert('books',id='book',originalHash='hash');insert('wishlist',id='wish',catalogKey='manual:key',linkedBookId='book')
insert('collections',id='shelf',name='List');insert('memberships',bookId='book',shelfId='shelf');insert('wish_memberships',wishId='wish',shelfId='shelf')
insert('annotations',id='note',bookId='book');insert('reading',bookId='book');insert('transfers',bookId='book')
insert('removed_entries',id='book',groupId='book',kind='EPUB');insert('removed_entries',id='wish',groupId='book',kind='Catalogue')
for name in ['allBooks','allWishes','allMemberships','allWishMemberships','allAnnotations','allReading','transferFlow']:
 assert not c.execute(queries[name]).fetchall(),name
assert c.execute('SELECT count(*) FROM annotations').fetchone()==(1,)
assert c.execute('SELECT count(*) FROM books').fetchone()==(1,)
c.execute(queries['restoreRemoved'],{'group':'book'})
for name in ['allBooks','allWishes','allMemberships','allWishMemberships','allAnnotations','allReading','transferFlow']:
 assert len(c.execute(queries[name]).fetchall())==1,name
# Delete a list without touching its books, reader data or stored file identity.
c.execute(queries['deleteShelf'],{'id':'shelf'})
assert not c.execute('SELECT * FROM memberships').fetchall();assert not c.execute('SELECT * FROM wish_memberships').fetchall()
assert c.execute('SELECT id,originalHash FROM books').fetchone()==('book','hash')
assert c.execute('SELECT count(*) FROM annotations').fetchone()==(1,)
assert not c.execute('PRAGMA foreign_key_check').fetchall()
print('Actual Room queries hide removed entries/activity/memberships, restore original records and notes, and delete lists without deleting books. Android filesystem/Room UI behavior still requires instrumentation.')
