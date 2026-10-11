#!/usr/bin/env python3
"""adb exit zero alone never proves an Android Instrumentation suite passed."""
import json
from pathlib import Path
import re
import sys


def inspect(text, exit_code):
    terminal = re.findall(r'^OK \(([1-9][0-9]*) checks\)\s*$', text, re.M)
    codes = re.findall(r'^INSTRUMENTATION_CODE:\s*(-?[0-9]+)\s*$', text, re.M)
    failure = re.search(r'(^FAIL\b|INSTRUMENTATION_(FAILED|ABORTED)|shortMsg=|Process crashed|FATAL EXCEPTION)', text, re.M)
    passed = exit_code == 0 and len(terminal) == 1 and codes == ['-1'] and failure is None
    return {'status': 'passed' if passed else 'failed_or_incomplete', 'exit_code': exit_code,
            'checks': int(terminal[0]) if passed else None, 'terminal_codes': codes,
            'failure_marker': failure.group(0) if failure else None}


if __name__ == '__main__':
    path, code = Path(sys.argv[1]), int(sys.argv[2])
    result = inspect(path.read_text(errors='replace').replace('\r', ''), code)
    path.with_suffix('.result.json').write_text(json.dumps(result, indent=2) + '\n')
    print(json.dumps(result))
    raise SystemExit(0 if result['status'] == 'passed' else 1)
