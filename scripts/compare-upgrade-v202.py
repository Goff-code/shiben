#!/usr/bin/env python3
"""Strict additive v2→v3 and semantic daily-report retention on disposable QA data."""
import hashlib,json,re,sqlite3,sys,xml.etree.ElementTree as ET
from pathlib import Path
before,after=map(Path,sys.argv[1:3])
checks=0
def check(ok,label):
 global checks
 assert ok,label
 checks+=1; print('PASS '+label)
def open_db(root):
 path=root/'databases/focus-ledger.db';check(path.is_file(),'captured SQLite exists '+root.name)
 return sqlite3.connect(path)
a,b=open_db(before),open_db(after)
check(a.execute('PRAGMA user_version').fetchone()[0]==2,'baseline schema2')
check(b.execute('PRAGMA user_version').fetchone()[0]==3,'upgraded additive schema3')
def tables(db):return {r[0] for r in db.execute("SELECT name FROM sqlite_master WHERE type='table'")}
check(tables(a)==tables(b),'table inventory unchanged')
for table in sorted(tables(a)):
 old=[r[1] for r in a.execute('PRAGMA table_info("'+table+'")')]
 new=[r[1] for r in b.execute('PRAGMA table_info("'+table+'")')]
 check(new==old+(['settlement_grade'] if table=='daily_snapshots' else []),'only approved column addition: '+table)
 if table=='timer_state':continue
 columns=','.join('"'+x+'"' for x in old)
 left=a.execute('SELECT '+columns+' FROM "'+table+'" ORDER BY rowid').fetchall()
 right=b.execute('SELECT '+columns+' FROM "'+table+'" ORDER BY rowid').fetchall()
 check(left==right,'all existing values/blobs preserved: '+table+' ('+str(len(left))+')')
check(b.execute('SELECT count(*) FROM daily_snapshots WHERE settlement_grade IS NOT NULL').fetchone()[0]==0,'migration fabricates no manual overrides')
def prefs(root):
 return {p.name:{c.attrib['name']:(c.tag,c.attrib.get('value'),c.text,sorted(x.text for x in c)) for c in ET.parse(p).getroot()} for p in (root/'shared_prefs').glob('*.xml')}
p,q=prefs(before),prefs(after);check(bool(p) and p==q,'all existing preferences preserved semantically')
def files(root):return {p.name:p.read_text() for p in (root/'files/daily-markdown-v2').glob('*.md')}
left,right=files(before),files(after)
check(bool(left) and left.keys()==right.keys(),'all durable daily report dates retained')
def duration(ms):
 sec=ms//1000;h=sec//3600;m=sec//60%60;s=sec%60
 return (str(h)+'小时'+str(m)+'分钟' if h else str(m)+'分钟' if m else '')+(str(s)+'秒' if s or not(h or m) else '')
for name,old in left.items():
 new=right[name]
 day=re.search(r'^# 时本 · (\d{4}-\d{2}-\d{2})$',old,re.M).group(1)
 check(new.startswith('# '+day+'｜时本\n'),'new approved day/brand title '+day)
 sections=re.findall(r'^## (.+)$',new,re.M)
 check(sections==['今日投资注意力时长总计','总完成度','当日任务与完成度','当日结算等级'],'only four approved sections '+day)
 milliseconds=int(re.search(r'（(\d+) 毫秒）',old).group(1))
 check(duration(milliseconds) in new,'all original attention duration retained '+day)
 progress=re.search(r'总进度原值（百分数）：([^\n]+)',old).group(1)
 fresh=re.search(r'## 总完成度\s+([^\n]+)',new).group(1).strip('* ')
 if progress=='未知':check(fresh=='未知','unknown historical completion stays unknown '+day)
 else:
  raw=float(progress); shown=fresh.replace('%','').replace('<','')
  check(abs(float(shown)-raw)<=.051,'weighted completion retains original value within displayed precision '+day)
 for line in old.splitlines():
  if not line.startswith('| ') or line.startswith('| 优先级') or line.startswith('| ---'):continue
  # Original names can contain escaped pipes, so split only unescaped separators.
  cells=re.split(r'(?<!\\)\|',line)[1:-1]
  if len(cells)<4:continue
  title=cells[1].strip(); value=cells[3].strip()
  check(title in new and value in new,'historical task name/progress retained '+day+': '+title)
 check(not any(x in new for x in ['原始毫秒','实际毫秒','口径与保存','权重原值','SQLite','总进度原值']), 'technical fields absent from requested MD '+day)
print(f'PASS ({checks} checks): additive upgrade and semantic record retention; paused timer compared separately')
