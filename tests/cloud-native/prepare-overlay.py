#!/usr/bin/env python3
"""Stage pinned androidTest Java plus the reviewed QA replacements; never edit a checkout."""
import argparse
import hashlib
import json
from pathlib import Path
import subprocess

PIN = "b1838a8fb66b74b13c278991d097b9be48155688"
PREFIX = "app/src/androidTest/java/"
REPLACEMENTS = (
    "cn/focusledger/GraffitiInstrumentationChecks.java",
    "cn/focusledger/LedgerInstrumentation.java",
    "cn/focusledger/ReminderInstrumentationChecks.java",
)


def git(repo, *arguments):
    return subprocess.check_output(["git", "-C", str(repo), *arguments])


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("worktree", type=Path, help="clean current production worktree pinned at b1838a8")
    parser.add_argument("output", type=Path, help="new staging directory outside that worktree")
    args = parser.parse_args()
    repo = args.worktree.resolve()
    output = args.output.resolve()
    proposal = Path(__file__).resolve().parent
    if git(repo, "rev-parse", "HEAD").decode().strip() != PIN:
        raise SystemExit("Refusing overlay: worktree HEAD is not the pinned production commit")
    if git(repo, "diff", "--name-only", "HEAD").strip():
        raise SystemExit("Refusing overlay: tracked worktree content differs from the pin")
    if output == repo or repo in output.parents:
        raise SystemExit("Output must be outside the pinned production worktree")
    if output.exists():
        raise SystemExit("Output already exists; use a new staging directory")
    names = git(repo, "ls-tree", "-r", "--name-only", "-z", PIN, "--", PREFIX).decode().split("\0")
    sources = {name[len(PREFIX):]: git(repo, "show", f"{PIN}:{name}")
               for name in names if name.endswith(".java")}
    if not all(name in sources for name in REPLACEMENTS):
        raise SystemExit("The pinned tree does not contain the expected test sources")
    original_hashes = {name: hashlib.sha256(data).hexdigest() for name, data in sources.items()}
    for name in REPLACEMENTS:
        sources[name] = (proposal / "java" / name).read_bytes()
    output.mkdir(parents=True)
    for name, data in sources.items():
        target = output / "java" / name
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes(data)
    manifest = {
        "production_commit": PIN,
        "replacements": list(REPLACEMENTS),
        "pinned_java_sha256": original_hashes,
        "staged_java_sha256": {name: hashlib.sha256(data).hexdigest() for name, data in sources.items()},
    }
    (output / "overlay.json").write_text(json.dumps(manifest, indent=2) + "\n")
    print(f"Staged {len(sources)} pinned test Java sources, with {len(REPLACEMENTS)} reviewed replacements: {output}")


if __name__ == "__main__":
    main()
