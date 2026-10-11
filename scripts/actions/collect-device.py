#!/usr/bin/env python3
"""Collect only known synthetic QA output directories, never private signing data."""
from pathlib import Path, PurePosixPath
import os
import re
import subprocess
import sys
import tarfile

destination = Path(sys.argv[1])
destination.mkdir(parents=True, exist_ok=False)
serial = os.environ['ANDROID_SERIAL']
assert serial == 'emulator-5554' and os.environ.get('GITHUB_ACTIONS') == 'true'
base = ['adb', '-s', serial]
result = subprocess.run(base + ['shell', 'run-as', 'cn.goff.shiben', 'find', 'files', '-maxdepth', '1', '-type', 'd'],
                        capture_output=True, text=True, timeout=20, check=True)
names = [n.strip() for n in result.stdout.splitlines() if re.fullmatch(r'files/qa-[a-z0-9-]+', n.strip())]
requested = sys.argv[2:]
optional = '--optional' in requested
requested = [n for n in requested if n != '--optional']
assert requested and all(re.fullmatch(r'qa-[a-z0-9-]+', n) for n in requested)
names = [n for n in names if n.removeprefix('files/') in requested]
if not names and optional:
    raise SystemExit(0)
assert names, ('No requested QA output found', requested)
if names:
    archive = destination / 'synthetic-qa.tar'
    with archive.open('xb') as output:
        subprocess.run(base + ['exec-out', 'run-as', 'cn.goff.shiben', 'tar', 'cf', '-', *names], stdout=output, timeout=45, check=True)
    assert archive.stat().st_size <= 96 * 1024 * 1024, 'QA collection exceeds explicit bound'
    with tarfile.open(archive) as tar:
        for item in tar:
            p = PurePosixPath(item.name)
            assert not p.is_absolute() and '..' not in p.parts and (item.isdir() or item.isfile())
            assert len(p.parts) >= 2 and 'files/' + p.parts[1] in names
        tar.extractall(destination, filter='data')
    archive.unlink()
