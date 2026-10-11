#!/usr/bin/env python3
import hashlib
import json
from pathlib import Path
import re
import sys
import xml.etree.ElementTree as ET
import zipfile

out, source = map(Path, sys.argv[1:])
certificates = []
apks = {}
for apk in (out / 'apks').glob('*.apk'):
    text = apk.with_suffix('.apk.cert.txt').read_text()
    certificate = re.findall(r'^Signer #1 certificate SHA-256 digest: (\w+)$', text, re.M)
    assert len(certificate) == 1
    assert certificate[0] != 'af1c11b14290abcd0aa95dfa3284ce931a822751f2780293f0aab9c90cce59d5', 'Production key must never enter CI'
    certificates.extend(certificate)
    with apk.open('rb') as f:
        sha = hashlib.file_digest(f, 'sha256').hexdigest()
    apks[apk.name] = {'bytes': apk.stat().st_size, 'sha256': sha, 'certificate': certificate[0]}
assert len(set(certificates)) == 1, 'All test-only APKs must use the same ephemeral default debug certificate'
for name, runner in [('current-tests.apk', 'LedgerInstrumentation'),
                     ('current-upgrade-tests.apk', 'ActionsUpgradeInstrumentation'),
                     ('old202-upgrade-tests.apk', 'ActionsUpgradeInstrumentation')]:
    manifest = (out / 'apks' / (name + '.manifest.txt')).read_text()
    assert 'cn.focusledger.' + runner in manifest and 'cn.goff.shiben' in manifest
    with zipfile.ZipFile(out / 'apks' / name) as z:
        assert any(runner.encode() in z.read(n) for n in z.namelist() if n.endswith('.dex'))
for name, code, version in [('current.apk', '7', '3.0'), ('old202-upgrade.apk', '5', '2.0.2')]:
    badging = (out / 'apks' / (name + '.badging.txt')).read_text()
    assert f"package: name='cn.goff.shiben' versionCode='{code}' versionName='{version}'" in badging
    assert "uses-permission: name='android.permission.INTERNET'" not in badging
with zipfile.ZipFile(out / 'apks/current.apk') as archive:
    originals = sorted((source / 'app/src/main/assets/export/grades').glob('*.png'))
    assert len(originals) == 13
    for png in originals:
        assert archive.read('assets/export/grades/' + png.name) == png.read_bytes()
tests = [ET.parse(p).getroot() for p in (source / 'app/build/test-results/testDebugUnitTest').glob('TEST-*.xml')]
count = sum(int(t.get('tests', 0)) for t in tests)
assert count == 177, ('Unexpected test count', count)
assert all(all(int(t.get(k, 0)) == 0 for k in ('failures', 'errors', 'skipped')) for t in tests)
lint = ET.parse(source / 'app/build/reports/lint-results-debug.xml').getroot()
assert not any(x.get('severity') in ('Error', 'Fatal') for x in lint.findall('issue'))
(out / 'build-verification.json').write_text(json.dumps({'source': 'b1838a8fb66b74b13c278991d097b9be48155688',
    'old_source': 'bdbfafa8c4ec4344ae171ba1761f6ffea7017555', 'unit_tests': count,
    'lint_errors': 0, 'grade_originals_exact': 13, 'signing_scope': 'ephemeral CI debug key; NOT the production key',
    'apks': apks}, indent=2) + '\n')
