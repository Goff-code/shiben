#!/usr/bin/env python3
"""Validate unsigned release packaging without claiming signature/installability."""
import hashlib
import os
from pathlib import Path
import re
import subprocess
import sys
import zipfile

root = Path(__file__).resolve().parents[1]
apk = Path(sys.argv[1] if len(sys.argv) > 1 else root / 'app/build/outputs/apk/release/app-release-unsigned.apk').resolve()
sdk = Path(os.environ.get('ANDROID_HOME', root / 'tools/android-sdk'))
tools = sdk / 'build-tools/35.0.0'
count = 0

def command(*args):
    return subprocess.check_output([str(x) for x in args], text=True, stderr=subprocess.STDOUT)

def check(condition, label):
    global count
    if not condition:
        raise AssertionError(label)
    count += 1
    print('PASS ' + label)

badging = command(tools / 'aapt', 'dump', 'badging', apk)
manifest = command(tools / 'aapt', 'dump', 'xmltree', apk, 'AndroidManifest.xml')
command(tools / 'zipalign', '-c', '-p', '4', apk)
check("package: name='cn.goff.shiben' versionCode='8' versionName='3.1'" in badging, 'stable identity and 3.1 version')
check("application-label:'时本'" in badging, 'installed app label')
check("launchable-activity: name='cn.focusledger.MainActivity'" in badging, 'launcher activity resolves')
check("sdkVersion:'26'" in badging and "targetSdkVersion:'35'" in badging, 'Android API26 minimum / API35 target')
check('application-debuggable' not in badging and 'android:debuggable' not in manifest, 'release is not debuggable')
check('android.permission.INTERNET' in manifest, 'Internet permission for opt-in AI sync')
check(re.search(r'android:allowBackup[^\n]*=\(type 0x12\)0x0\b', manifest), 'system cloud backup disabled')
check(re.search(r'android:fullBackupContent[^\n]*=\(type 0x12\)0x0\b', manifest), 'legacy cloud backup disabled')
with zipfile.ZipFile(apk) as archive:
    names = archive.namelist()
    check(not any(n.lower().endswith(('.jks', '.keystore', '.p12', '.pem')) for n in names), 'no private keys or certificate files bundled')
    check(not any(n.startswith('lib/') for n in names), 'no native libraries or ABI restrictions')
    dex = b''.join(archive.read(n) for n in names if re.fullmatch(r'classes\d*\.dex', n))
    check(b'LedgerInstrumentation' not in dex and b'StoreV2Instrumentation' not in dex and b'ExportInstrumentationChecks' not in dex and b'AttentionV202Instrumentation' not in dex and b'GalleryInstrumentationChecks' not in dex and b'RangeSummaryInstrumentationChecks' not in dex, 'test runners absent')
    check('专注账本'.encode() not in dex, 'legacy visible brand absent')
    check('导出分页测试'.encode() not in dex and not any(n.lower().endswith(('.db', '.sqlite', '.sqlite3')) for n in names), 'no packaged test fixtures or prefilled database')
print(f'PASS ({count} checks): unsigned release packaging only, no signature or installability claim')
print(next(line for line in badging.splitlines() if line.startswith('package:')))
print('APK SHA256: ' + hashlib.sha256(apk.read_bytes()).hexdigest())
