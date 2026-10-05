#!/usr/bin/env python3
"""Summarize only final runtime logs; never promote partial assertions to a pass."""
import json
from pathlib import Path
import re
import sys
root = Path(sys.argv[1] if len(sys.argv) > 1 else 'artifacts/v2.0.1/runtime')
results = []
for directory in ('api29-final', 'api29-static-final', 'api35-final'):
    folder = root / directory
    if not folder.exists():
        continue
    logs = sorted(folder.glob('*.log'))
    for path in logs:
        if not (path.name.startswith(('layout-', 'dialogs-', 'empty-history', 'all.', 'saf.'))):
            continue
        content = path.read_text(errors='replace')
        terminal = re.findall(r'^OK \((\d+) checks\)', content, re.M)
        failures = re.findall(r'^(?:FAIL .*|INSTRUMENTATION_RESULT: shortMsg=.*)', content, re.M)
        results.append({'log': str(path.relative_to(root)),
                        'status': 'failed' if failures else 'passed' if terminal else 'running_or_incomplete',
                        'checks': int(terminal[-1]) if terminal and not failures else None,
                        'failure': failures[-1] if failures else None})
print(json.dumps({'root': str(root), 'final_runtime_logs': results,
                  'passed_checks': sum(x['checks'] or 0 for x in results),
                  'all_listed_suites_passed': bool(results) and all(x['status'] == 'passed' for x in results),
                  'scope_note': 'Only logs actually present are listed. Missing systems, suites and official signed-package upgrade checks are not implied to have run.'},
                 ensure_ascii=False, indent=2))
