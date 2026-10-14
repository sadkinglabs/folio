#!/usr/bin/env python3
"""Execute shipped permanent-removal Room DELETE SQL in SQLite and verify local-only ownership cleanup."""
from pathlib import Path
import json,re,sqlite3,tempfile
root=Path(__file__).resolve().parents[1]
schema=json.loads((root/'app/schemas/dev.sadking.folio.data.LibraryDatabase/6.json').read_text())['database']
source=(root/'app/src/main/java/dev/sadking/folio/data/Database.kt').read_text()
queries={name:sql for sql,name in re.findall(r'@Query\("([^"\n]+)"\)\s*suspend fun (deleteLocal\w+|deleteWish)\(',source)}
assert len(queries)==6,queries
connection=sqlite3.connect(':memory:');connection.execute('PRAGMA foreign_keys=ON')
for entity in schema['entities']:
 name=entity['tableName'];connection.execute(entity['createSql'].replace('${TABLE_NAME}',name))
 for index in entity.get('indices',[]):connection.execute(index['createSql'].replace('${TABLE_NAME}',name))
def insert(table,**values):
 entity=next(e for e in schema['entities'] if e['tableName']==table)
 fields=entity['fields'];columns=[f['columnName'] for f in fields]
 vals=[values.get(f['columnName'],1 if f['affinity']=='INTEGER' else 0.5 if f['affinity']=='REAL' else None if not f.get('notNull', False) else '[]' if f['columnName'] in ['authors','tags','identifiers'] else 'fixture') for f in fields]
 connection.execute(f'INSERT INTO {table} ({",".join(columns)}) VALUES ({",".join("?" for _ in columns)})',vals)
insert('books',id='removed',originalHash='a');insert('books',id='retained',originalHash='b')
insert('collections',id='shelf',name='Shelf')
for book in ['removed','retained']:
 insert('memberships',bookId=book,shelfId='shelf')
 for table in ['reading','annotations','transfers','sync_journal']:insert(table,id=book,bookId=book)
insert('wishlist',id='wish',catalogKey='hardcover:1',linkedBookId='removed');insert('wish_memberships',wishId='wish',shelfId='shelf')
for name in ['deleteLocalAnnotations','deleteLocalReading','deleteLocalTransfers','deleteLocalJournal','deleteLocalBook']:
 connection.execute(queries[name],{'id':'removed'})
connection.execute(queries['deleteWish'],{'id':'wish'})
assert connection.execute('SELECT id FROM books').fetchall()==[('retained',)]
for table in ['reading','annotations','transfers','sync_journal','memberships']:
 assert connection.execute(f'SELECT bookId FROM {table}').fetchall()==[('retained',)],table
assert not connection.execute('SELECT * FROM wish_memberships').fetchall()
assert not connection.execute('PRAGMA foreign_key_check').fetchall()
assert 'Sftp' not in (root/'app/src/main/java/dev/sadking/folio/data/LibraryRepository.kt').read_text()
print('Actual permanent-removal SQL cleans target rows and cascading lists, preserves unselected records and leaves no dangling foreign keys. Android filesystem/Room runtime execution still needs device tests.')
