#!/usr/bin/env python3
"""Static branding checks; these are not Android runtime or upgrade tests."""
from pathlib import Path
import re
import sys
import xml.etree.ElementTree as ET

root = Path(sys.argv[1] if len(sys.argv) > 1 else Path(__file__).resolve().parents[2])
checks = 0

def check(condition, message):
    global checks
    if not condition:
        raise AssertionError(message)
    checks += 1

def read(relative):
    return (root / relative).read_text(encoding="utf-8")

build = read("app/build.gradle")
manifest = ET.fromstring(read("app/src/main/AndroidManifest.xml"))
android = "{http://schemas.android.com/apk/res/android}"
app = manifest.find("application")
activity = read("app/src/main/java/cn/focusledger/MainActivity.java")
reports = read("app/src/main/java/cn/focusledger/Reports.java")
store = read("app/src/main/java/cn/focusledger/LedgerStore.java")
service = read("app/src/main/java/cn/focusledger/TimerService.java")
check(app.attrib[android + "label"] == "时本", "launcher/app label")
check("namespace 'cn.focusledger'" in build, "Java namespace preserved")
check("applicationId 'cn.focusledger'" in build, "application ID preserved")
check(re.search(r"versionCode\s+2\b", build), "versionCode incremented")
check("versionName '1.0.1'" in build, "versionName incremented")
check('label("时本", 16,' in activity, "in-app brand")
check('setContentDescription("时本标志")' in activity, "accessible brand label")
check('label("今天，你把注意力投向了哪里？",25,' in activity, "approved homepage headline")
check('label("记录每一份注意力，投资你想成为的人。",13,' in activity, "approved intro")
check('时本 · 本地原生版\\n记录每一份注意力，投资你想成为的人。' in activity, "settings footer")
check('ClipData.newPlainText("时本 "' in activity, "clipboard label")
check('Intent.EXTRA_TITLE,"时本-"' in activity, "CSV/JSON export filename")
check('out.append("# 时本 · ")' in reports, "Markdown report heading")
check('s==null?"时本":s.taskTitle' in service, "notification fallback title")
check('DATABASE_NAME = "focus-ledger.db"' in store, "database filename preserved")
check('root.put("format", "focus-ledger-backup")' in reports, "JSON backup format identifier preserved")
check(app.attrib[android + "allowBackup"] == "false", "cloud backup remains disabled")
check(not any(p.attrib.get(android + "name") == "android.permission.INTERNET" for p in manifest.findall("uses-permission")), "no network permission")
for path in sorted((root / "app/src").rglob("*")):
    if path.is_file() and path.suffix in (".java", ".xml"):
        check("专注账本" not in path.read_text(encoding="utf-8"), "no stale brand: " + str(path.relative_to(root)))
print(f"PASS ({checks} checks): brand/copy/export labels, release version, stable package/database/backup identity, privacy manifest")
