#!/usr/bin/env python3
"""Fail before downloads/builds if the reviewed one-off checkout contract differs."""
import json
import os
from pathlib import Path
import subprocess

ROOT = Path(__file__).resolve().parents[2]
BASE = 'b1838a8fb66b74b13c278991d097b9be48155688'
BRANCH = 'refs/heads/qa/shiben-3.0-kvm-20261010-8fc0e7'
MESSAGE = 'qa: validate Shiben 3.0 once [20261010-8fc0e7]'


def git(*args):
    return subprocess.check_output(['git', '-C', str(ROOT), *args], text=True).strip()


assert os.environ.get('GITHUB_ACTIONS') == 'true'
assert os.environ['GITHUB_REPOSITORY'] == 'Goff-code/shiben'
assert os.environ['GITHUB_EVENT_NAME'] == 'push'
assert os.environ['GITHUB_REF'] == BRANCH
assert os.environ['GITHUB_RUN_ATTEMPT'] == '1'
assert git('rev-parse', 'HEAD') == os.environ['GITHUB_SHA']
assert git('rev-parse', 'HEAD^') == BASE
assert git('log', '-1', '--format=%B') == MESSAGE
allowed = {'.github/workflows/shiben-3.0-kvm-oneoff.yml', 'docs/QA_ACTIONS_3.0.md', 'docs/QA_ACTIONS_UPGRADE.md'}
prefixes = ('scripts/actions/', 'tests/actions/', 'tests/actions-upgrade/')
for name in git('diff', '--name-only', BASE, 'HEAD').splitlines():
    assert name in allowed or name.startswith(prefixes), ('Out-of-scope change', name)
assert not git('status', '--porcelain', '--untracked-files=no')
assert git('rev-parse', BASE + '^{tree}') == 'a98e365ba03ed9622e17a43d9398853df2efa014'
print(json.dumps({'reviewed_checkout': git('rev-parse', 'HEAD'), 'fixed_product': BASE,
                  'parent_and_scope_checked': True, 'run_attempt': 1}))
