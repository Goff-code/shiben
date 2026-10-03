#!/usr/bin/env python3
"""Host SQL contract checks; does not execute Android SQLiteOpenHelper methods."""
import json
import re
import sqlite3
import sys
from pathlib import Path

root = Path(sys.argv[1] if len(sys.argv) > 1 else '.')
source = (root / 'app/src/main/java/cn/focusledger/LedgerStore.java').read_text()
body = source[source.index('@Override public void onCreate'):source.index('@Override public void onUpgrade')]
db = sqlite3.connect(':memory:')
db.execute('PRAGMA foreign_keys=ON')
checks = 0

def check(ok, name):
    global checks
    if not ok:
        raise AssertionError(name)
    checks += 1

statements = re.findall(r'db\.execSQL\((.*?)\);', body, re.S)
for statement in statements:
    literals = re.findall(r'"((?:[^"\\]|\\.)*)"', statement)
    db.execute(''.join(json.loads('"' + literal + '"') for literal in literals))
check(len(statements) == 6, 'six extracted schema statements successfully create database')
db.execute('INSERT INTO projects VALUES (?,?,?,?,?)', ('p', '分类', 0, 0, 0))
db.execute('INSERT INTO tasks VALUES (?,?,?,?,?,?,?,?)', ('t', '任务', 'p', 25, 0, 127, None, 0))
db.execute('INSERT INTO task_checks VALUES (?,?,?,?,?)', ('t', '2026-10-03', 1, '旧任务', 'p'))
db.execute('UPDATE tasks SET title=?,archived=1 WHERE id=?', ('新任务', 't'))
check(db.execute('SELECT done,task_title FROM task_checks').fetchone() == (1, '旧任务'), 'completion snapshot retained after rename/archive')
db.execute('INSERT INTO sessions VALUES (?,?,?,?,?,?,?,?)', ('s', 1, 2, 'p', 't', '分类', b'first', 0))
db.execute('INSERT OR IGNORE INTO sessions VALUES (?,?,?,?,?,?,?,?)', ('s', 1, 2, 'p', 't', '分类', b'retry', 0))
check(db.execute('SELECT record FROM sessions').fetchone() == (b'first',), 'duplicate completion id is idempotent')
db.execute('UPDATE sessions SET deleted=1 WHERE id=?', ('s',))
db.execute('INSERT OR IGNORE INTO sessions VALUES (?,?,?,?,?,?,?,?)', ('s', 1, 2, 'p', 't', '分类', b'resurrect', 0))
check(db.execute('SELECT count(*) FROM sessions WHERE deleted=0').fetchone() == (0,), 'tombstone prevents deleted record resurrection')
db.commit()
try:
    with db:
        db.execute('INSERT INTO sessions VALUES (?,?,?,?,?,?,?,?)', ('atomic', 1, 2, 'p', 't', '分类', b'atomic', 0))
        db.execute('INSERT INTO timer_state VALUES (?,?)', (2, b'invalid singleton'))
except sqlite3.IntegrityError:
    pass
check(db.execute("SELECT count(*) FROM sessions WHERE id='atomic'").fetchone() == (0,), 'terminal insert rolls back with failed state save')
for plan in (15, 25, 10085, 2147483645):
    db.execute('INSERT INTO tasks VALUES (?,?,?,?,?,?,?,?)', (str(plan), 'Task', 'p', plan, 0, 127, None, 0))
    check(True, f'valid plan accepted: {plan}')
for plan in (1, 14, 16, 2147483647):
    rejected = False
    try:
        db.execute('INSERT INTO tasks VALUES (?,?,?,?,?,?,?,?)', (str(plan), 'Bad', 'p', plan, 0, 127, None, 0))
    except sqlite3.IntegrityError:
        rejected = True
    check(rejected, f'invalid plan rejected: {plan}')
print(f'PASS ({checks} checks): extracted SQLite schema, snapshots, idempotence, tombstones, atomic rollback, duration contract')
