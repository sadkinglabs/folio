#!/usr/bin/env python3
"""Optional online conformance check; requires graphql-core==3.2.7. Never needs an account/token."""
from pathlib import Path
import hashlib,json,re,urllib.request
from graphql import build_schema,parse,validate
project=Path(__file__).resolve().parents[1]
url='https://raw.githubusercontent.com/hardcoverapp/hardcover-docs/main/schema.graphql'
with urllib.request.urlopen(url,timeout=30) as response:
    raw=response.read(2*1024*1024+1)
assert len(raw)<=2*1024*1024,'Published schema exceeds safety limit'
schema=build_schema(raw.decode('utf-8'))
source=(project/'app/src/main/java/dev/sadking/folio/data/HardcoverClient.kt').read_text()
operations=[]
for token in re.findall(r'"(?:[^"\\]|\\.)*"',source):
    value=json.loads(token.replace('\\$','$'))
    if value.startswith(('query(','mutation(','{ me ')):
        errors=validate(schema,parse(value))
        assert not errors,errors
        operations.append(value)
assert len(set(operations))==14,f'Expected 14 distinct client operations, found {len(set(operations))}'
print(f'{len(set(operations))} distinct client operations ({len(operations)} occurrences) match official Hardcover schema SHA256 {hashlib.sha256(raw).hexdigest()}; no authenticated calls were made.')
