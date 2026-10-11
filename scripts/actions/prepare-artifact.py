#!/usr/bin/env python3
"""Bounded evidence only; exclude APKs, toolchains, caches and all key material."""
import hashlib
import json
import os
from pathlib import Path
import shutil
import sys

source, destination = map(Path, sys.argv[1:])
destination.mkdir(parents=True, exist_ok=False)
expected = ('graffiti', 'reminders', 'gallery', 'all', 'gallery_ui', 'gallery_view', 'seed', 'verify', 'routing')
results = {}
for suite in expected:
    p = source / 'runtime' / (suite + '.result.json')
    results[suite] = json.loads(p.read_text()) if p.is_file() else {'status': 'not_run_or_no_terminal_evidence'}
complete = all(r['status'] == 'passed' for r in results.values()) and (source / 'runtime/completed.txt').is_file()
summary = {'source': 'b1838a8fb66b74b13c278991d097b9be48155688', 'api': 35,
           'suites': results, 'all_requested_runtime_suites_passed': complete,
           'limits': ['test-certificate upgrade only; no production signing key',
                      'AOSP emulator does not prove physical sound/vibration or realme behavior',
                      '13 produced PNGs still require visual inspection after download',
                      'API29 compatibility not run by this workflow']}
(destination / 'summary.json').write_text(json.dumps(summary, indent=2) + '\n')
files, seen = [], {}
allowed_suffixes = {'.json', '.log', '.txt', '.png', '.xml', '.ini', '.md', '.properties'}
for p in sorted(source.rglob('*')):
    rel = p.relative_to(source)
    if not p.is_file() or p.is_symlink() or p.suffix not in allowed_suffixes:
        continue
    if rel.parts[0] == 'apks' and not p.name.endswith(('.apk.cert.txt', '.apk.badging.txt', '.apk.manifest.txt')):
        continue
    assert not any(t in p.name.lower() for t in ('.keystore', '.jks', 'private-key', 'credential'))
    with p.open('rb') as f:
        sha = hashlib.file_digest(f, 'sha256').hexdigest()
    record = {'path': str(rel), 'bytes': p.stat().st_size, 'sha256': sha}
    if sha in seen:
        record['identical_to'] = seen[sha]
    else:
        target = destination / rel
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(p, target)
        seen[sha] = str(rel)
    files.append(record)
(destination / 'files.json').write_text(json.dumps(files, indent=2) + '\n')
total = sum(p.stat().st_size for p in destination.rglob('*') if p.is_file())
assert total <= 96 * 1024 * 1024, ('Artifact exceeds pre-approved 96 MiB; refuse upload', total)
if os.environ.get('GITHUB_STEP_SUMMARY'):
    with open(os.environ['GITHUB_STEP_SUMMARY'], 'a') as f:
        f.write('Shiben 3.0 API35 KVM test evidence\n\n')
        for name, result in results.items():
            f.write('- ' + name + ': ' + result['status'] + '\n')
        f.write('\nDebug/test certificate only. No formal-release signing or realme validation.\n')
print(json.dumps({'artifact_bytes': total, 'all_runtime_suites_passed': complete}))
if os.environ.get('GITHUB_OUTPUT'):
    with open(os.environ['GITHUB_OUTPUT'], 'a') as f:
        f.write('ready=true\n')
