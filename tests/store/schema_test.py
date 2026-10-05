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

# Execute the actual V2 DDL and the SQL migration over the retained V1 fixture.
v2_body = source[source.index('private static void createV2Schema'):source.index('public synchronized List<Project>')]
for statement in re.findall(r'db\.execSQL\((.*?)\);', v2_body, re.S):
    literals = re.findall(r'"((?:[^"\\]|\\.)*)"', statement)
    db.execute(''.join(json.loads('"' + literal + '"') for literal in literals))
migration = source[source.index('@Override public void onUpgrade'):source.index('/** Additive upgrade:')]
for statement in re.findall(r'db\.execSQL\((.*?)\);', migration, re.S):
    literals = re.findall(r'"((?:[^"\\]|\\.)*)"', statement)
    db.execute(''.join(json.loads('"' + literal + '"') for literal in literals))
check(db.execute('SELECT complete,legacy FROM daily_snapshots').fetchone() == (0,1), 'migration marks historical inventory incomplete')
check(db.execute('SELECT title,rank,progress,weight,legacy_done FROM daily_task_snapshots').fetchone() == ('旧任务',None,None,None,1), 'migration keeps legacy check and unknown rank/progress/weight')
check(db.execute('SELECT record,deleted FROM sessions').fetchone() == (b'first',1), 'migration keeps tombstone and serialized record unchanged')
db.execute('INSERT INTO daily_snapshots(day,complete,legacy) VALUES (?,?,?)', ('2026-10-04',1,0))
db.execute('INSERT INTO daily_task_snapshots VALUES (?,?,?,?,?,?,?,?)', ('2026-10-04','t','今日任务',1,40,1.0,0,None))
for invalid in (-1,101):
    rejected=False
    try: db.execute('UPDATE daily_task_snapshots SET progress=? WHERE day=?', (invalid,'2026-10-04'))
    except sqlite3.IntegrityError: rejected=True
    check(rejected, f'progress {invalid} rejected by SQLite itself')
rejected=False
try: db.execute('INSERT INTO daily_task_snapshots VALUES (?,?,?,?,?,?,?,?)', ('2026-10-04','duplicate','重复优先级',1,0,.5,0,None))
except sqlite3.IntegrityError: rejected=True
check(rejected, 'duplicate active day rank rejected by unique index')
db.execute('UPDATE daily_task_snapshots SET archived=1 WHERE day=? AND task_id=?', ('2026-10-04','t'))
db.execute('INSERT INTO daily_task_snapshots VALUES (?,?,?,?,?,?,?,?)', ('2026-10-04','new','新任务',1,0,1.0,0,None))
check(db.execute('SELECT title,progress,weight FROM daily_task_snapshots WHERE day=? AND archived=1', ('2026-10-04',)).fetchone() == ('今日任务',40,1.0), 'archived-today evidence retained beside contiguous active rank')
check(db.execute('SELECT title,rank,progress,weight,legacy_done FROM daily_task_snapshots WHERE day=?', ('2026-10-03',)).fetchone() == ('旧任务',None,None,None,1), 'today mutations leave historical metadata unchanged')
print(f'PASS ({checks} checks including V2): nondestructive SQL migration, unknown legacy fields, progress constraints, unique ranks, archived retention, immutable history')

# History lists meaningful evidence, not an untouched empty daily header.
db.execute('INSERT INTO daily_snapshots(day,complete,legacy) VALUES (?,?,?)', ('2026-10-05',1,0))
db.execute('INSERT INTO daily_snapshots(day,complete,legacy) VALUES (?,?,?)', ('2026-10-06',1,0))
db.execute('INSERT INTO daily_task_snapshots VALUES (?,?,?,?,?,?,?,?)', ('2026-10-06','archived-only','已移除任务',1,0,1.0,1,None))
query = re.search(r'"(SELECT DISTINCT day FROM daily_task_snapshots UNION SELECT day FROM task_checks)"', source).group(1)
days = {row[0] for row in db.execute(query)}
check('2026-10-05' not in days, 'header-only empty day excluded from history')
check('2026-10-06' in days and '2026-10-03' in days, 'archived-only day and legacy checklist day retained in history')
print(f'PASS ({checks} checks including history visibility): empty headers hidden without deleting recorded evidence')

# Upgrade an already-populated V2 database using exactly the additive V3 DDL.
before_headers = db.execute('SELECT day,complete,legacy FROM daily_snapshots ORDER BY day').fetchall()
before_tasks = db.execute('SELECT * FROM daily_task_snapshots ORDER BY day,task_id').fetchall()
before_sessions = db.execute('SELECT * FROM sessions ORDER BY id').fetchall()
db.execute('INSERT INTO timer_state VALUES (1,?)', (b'active-timer-must-survive',))
v3_body = source[source.index('private static void createV3Schema'):source.index('private static void createV2Schema')]
for statement in re.findall(r'db\.execSQL\((.*?)\);', v3_body, re.S):
    literals = re.findall(r'"((?:[^"\\]|\\.)*)"', statement)
    db.execute(''.join(json.loads('"' + literal + '"') for literal in literals))
check(db.execute('SELECT day,complete,legacy FROM daily_snapshots ORDER BY day').fetchall() == before_headers, 'V2-to-V3 keeps every historical header unchanged')
check(db.execute('SELECT * FROM daily_task_snapshots ORDER BY day,task_id').fetchall() == before_tasks, 'V2-to-V3 keeps all frozen task names, priorities, weights, progress and archived evidence')
check(db.execute('SELECT * FROM sessions ORDER BY id').fetchall() == before_sessions, 'V2-to-V3 keeps raw session blobs and tombstones byte-for-byte')
check(db.execute('SELECT record FROM timer_state').fetchone() == (b'active-timer-must-survive',), 'V2-to-V3 keeps active timer blob byte-for-byte')
check(db.execute('SELECT count(*) FROM daily_snapshots WHERE settlement_grade IS NOT NULL').fetchone() == (0,), 'upgrade assigns no fabricated manual grades')
for grade in ('S', 'SS', 'SSS', 'SSS+'):
    db.execute('UPDATE daily_snapshots SET settlement_grade=? WHERE day=?', (grade,'2026-10-04'))
    check(db.execute('SELECT settlement_grade FROM daily_snapshots WHERE day=?', ('2026-10-04',)).fetchone() == (grade,), 'S-family override persists: ' + grade)
for grade in ('A', 'A+', 'SSSS', '', 'sss'):
    rejected = False
    try: db.execute('UPDATE daily_snapshots SET settlement_grade=? WHERE day=?', (grade,'2026-10-04'))
    except sqlite3.IntegrityError: rejected = True
    check(rejected, 'non-S-family override rejected by SQLite: ' + grade)
check('oldVersion < 2 && newVersion >= 2' in source and 'oldVersion < 3 && newVersion >= 3' in source, 'upgrade chain accepts both V1-to-V3 and V2-to-V3')
print(f'PASS ({checks} checks including V3): additive settlement column and historical/blob preservation')
