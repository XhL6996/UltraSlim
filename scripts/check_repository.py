#!/usr/bin/env python3
"""Check source layout, local doc links and pinned fixture/license bytes.

Uses Git's candidate file list when run in a checkout (including untracked source),
and walks a source archive otherwise. This is not a full secret/history scanner.
"""
from pathlib import Path
import hashlib
import json
import os
import re
import subprocess
import sys
from urllib.parse import unquote, urlsplit

ROOT = Path(__file__).resolve().parent.parent
GENERATED = {".git", ".gradle", ".kotlin", ".idea", ".codex", ".zcode", "__pycache__"}
GENERATED_PATHS = ("build/", "app/build/", "native/prefix/", "native/deps/build/",
                   "apple-live/.build/", "apple-live/dist/")


def generated(path):
    return (any(part in GENERATED for part in Path(path).parts)
            or path.startswith(GENERATED_PATHS) or path.startswith("build-"))


def candidates():
    result = subprocess.run(["git", "rev-parse", "--show-toplevel"], cwd=ROOT,
                            capture_output=True, text=True)
    if result.returncode == 0 and Path(result.stdout.strip()).resolve() == ROOT:
        raw = subprocess.check_output(
            ["git", "ls-files", "--cached", "--others", "--exclude-standard", "-z"], cwd=ROOT)
        return sorted({p for p in raw.decode().split("\0") if p and (ROOT / p).is_file()})
    paths = []
    for parent, directories, files in os.walk(ROOT, followlinks=False):
        directories[:] = [d for d in directories
                          if not generated(str((Path(parent) / d).relative_to(ROOT)) + "/")]
        paths.extend(str((Path(parent) / f).relative_to(ROOT)) for f in files)
    return sorted(paths)


def main():
    errors = []
    paths = candidates()
    required = ["LICENSE", "README.md", "CONTRIBUTING.md", "THIRD_PARTY_NOTICES.md",
                "docs/README.md", "docs/BUILDING.md", "docs/ARCHITECTURE.md",
                "docs/COMPATIBILITY.md", "docs/RELEASING.md", "tests/README.md",
                "gradle/wrapper/gradle-wrapper.jar", "third_party/licenses/manifest.json"]
    for path in required:
        if not (ROOT / path).is_file():
            errors.append(f"Missing required source file: {path}")
    for path in paths:
        p = ROOT / path
        if generated(path) or p.suffix.lower() in {".apk", ".aab", ".jks", ".keystore", ".p12", ".pfx"}:
            errors.append(f"Generated/signing file in source candidates: {path}")
        if p.name in {"local.properties", "key.properties", "keystore.properties", ".env"}:
            errors.append(f"Local configuration in source candidates: {path}")
        if path.startswith("third_party/") or p.suffix.lower() not in {".md", ".py", ".sh", ".kt", ".kts", ".yml", ".yaml", ".json", ".swift", ".cpp", ".h", ".properties"}:
            continue
        text = p.read_text(encoding="utf-8")
        if re.search(r"^-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----", text, re.M):
            errors.append(f"Private key marker: {path}")
        if re.search(r"(?:ghp_[A-Za-z0-9]{30,}|github_pat_[A-Za-z0-9_]{30,}|AKIA[A-Z0-9]{16})", text):
            errors.append(f"Credential-like token: {path}")
        if p.suffix == ".md":
            if re.search(r"/Users/[^/\s`]+/", text):
                errors.append(f"Personal absolute path: {path}")
            # This project's Markdown uses inline links. Ignore external URLs and anchors.
            for target in re.findall(r"\[[^\]]*\]\(([^\n]+?)\)", text):
                target = target.strip().strip("<>")
                if not target or target.startswith("#") or urlsplit(target).scheme:
                    continue
                local = unquote(target.split("#", 1)[0])
                if not (p.parent / local).exists():
                    errors.append(f"Broken local link in {path}: {target}")
    fixtures = ROOT / "tests/fixtures/libultrahdr"
    sums = fixtures / "SHA256SUMS"
    if not sums.is_file():
        errors.append("Missing fixture SHA256SUMS")
    else:
        for line in sums.read_text().splitlines():
            expected, name = line.split("  ", 1)
            p = fixtures / name
            if not p.is_file() or hashlib.sha256(p.read_bytes()).hexdigest() != expected:
                errors.append(f"Missing/changed pinned fixture: {name}")
    manifest = ROOT / "third_party/licenses/manifest.json"
    if manifest.is_file():
        for row in json.loads(manifest.read_text()):
            p = ROOT / row["path"]
            if not p.is_file() or hashlib.sha256(p.read_bytes()).hexdigest() != row["sha256"]:
                errors.append(f"Missing/changed license snapshot: {row['path']}")
    if errors:
        print("\n".join(f"ERROR: {e}" for e in errors), file=sys.stderr)
        return 1
    print(f"PASS: {len(paths)} source candidates; layout, local links, fixture and license hashes")
    return 0


if __name__ == "__main__":
    sys.exit(main())
