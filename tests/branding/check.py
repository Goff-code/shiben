#!/usr/bin/env python3
"""V2 source identity/privacy/UI contracts. Runtime checks are separate."""
from pathlib import Path
import re,sys,xml.etree.ElementTree as ET
root=Path(sys.argv[1] if len(sys.argv)>1 else '.').resolve()
def read(p): return (root/p).read_text()
checks=0
def check(value,label):
 global checks
 assert value,label
 checks+=1
build=read('app/build.gradle'); activity=read('app/src/main/java/cn/focusledger/MainActivity.java')
store=read('app/src/main/java/cn/focusledger/LedgerStore.java')
manifest=ET.fromstring(read('app/src/main/AndroidManifest.xml')); a='{http://schemas.android.com/apk/res/android}'
app=manifest.find('application')
check(app.attrib[a+'label']=='时本','brand')
check("applicationId 'cn.goff.shiben'" in build,'stable package')
check("namespace 'cn.focusledger'" in build,'retained Java namespace')
check(re.search(r'versionCode\s+3\b',build),'version code3')
check("versionName '2.0.0'" in build,'version2.0.0')
check('minSdk 26' in build and 'targetSdk 35' in build,'SDK limits')
check(app.attrib[a+'allowBackup']=='false','cloud backup off')
check(app.attrib[a+'fullBackupContent']=='false','legacy backup off')
check(not any(x.attrib[a+'name']=='android.permission.INTERNET' for x in manifest.findall('uses-permission')),'no network')
check('focus-ledger.db' in store,'database stable')
check('DROP TABLE' not in store,'no destructive migration')
check('DATABASE_VERSION = 2' in store,'database v2')
for text in ['专注','任务','今日','开始专注','调整任务进度','保存进度','更多','历史记录','导出记录']:
 check(text in activity,'UI '+text)
check('if(tab==0&&page.isEmpty()){scroll=null' in activity,'focus not wrapped in ScrollView')
check('current>=0&&current<100&&seek.getProgress()==100' in activity,'only explicit progress transition celebrates')
check('2000' in read('app/src/main/java/cn/focusledger/UiKit.java'),'2 second animation')
check('AtomicFile' in read('app/src/main/java/cn/focusledger/DailyArchive.java'),'atomic daily persistence')
check('getRecordedDays' in read('app/src/main/java/cn/focusledger/ExportWorker.java'),'complete date export source')
for p in (root/'app/src/main').rglob('*'):
 if p.is_file() and p.suffix in ('.xml','.java'):
  check('专注账本' not in p.read_text(),'no stale brand '+p.name)
print(f'PASS ({checks} checks): V2 identity, privacy, migration and native interface contracts')
