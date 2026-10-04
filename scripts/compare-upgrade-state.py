#!/usr/bin/env python3
"""Compare disposable emulator snapshots before/after an authorized in-place QA update."""
import hashlib
import json
from pathlib import Path
import sqlite3
import sys
before, after = map(Path, sys.argv[1:3])

def tables(root):
    db = sqlite3.connect(root / 'databases/focus-ledger.db')
    result = {}
    for (name,) in db.execute("SELECT name FROM sqlite_master WHERE type='table' ORDER BY name"):
        if name == 'timer_state':
            continue  # Native instrumentation compares logical timer state; checkpoints may change.
        rows = []
        for row in db.execute('SELECT * FROM "' + name.replace('"', '""') + '"'):
            rows.append([{'blob_hex': x.hex()} if isinstance(x, bytes) else x for x in row])
        result[name] = sorted(rows, key=lambda r: json.dumps(r, sort_keys=True, ensure_ascii=False))
    db.close()
    return result
left, right = tables(before), tables(after)
assert left.keys() == right.keys(), 'database table set changed'
for name in left:
    assert left[name] == right[name], 'row content changed across package update: ' + name
    print(f'PASS unchanged table {name}: {len(left[name])} rows')

def archive(root):
    base = root / 'files/daily-markdown-v2'
    return {str(p.relative_to(base)): hashlib.sha256(p.read_bytes()).hexdigest()
            for p in sorted(base.glob('*.md'))}
a, b = archive(before), archive(after)
assert a and a == b, 'durable daily Markdown changed or disappeared across update'
print(f'PASS {len(a)} durable Markdown files retain exact SHA256')
print('PASS same-signature package update retained all captured logical database data and daily files')
