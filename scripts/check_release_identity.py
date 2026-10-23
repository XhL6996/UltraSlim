#!/usr/bin/env python3
"""Check verified apksigner/aapt reports against the published upgrade identity."""
import argparse
import json
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent


def check_identity(certificate, badging, baseline, source):
    digests = re.findall(r"^Signer #\d+ certificate SHA-256 digest:\s*([0-9a-fA-F]+)\s*$", certificate, re.M)
    expected = baseline["certificateSha256"].lower()
    if not re.fullmatch(r"[0-9a-f]{64}", expected) or [x.lower() for x in digests] != [expected]:
        raise ValueError("APK signer does not match the published release certificate")
    package = re.search(r"^package: name='([^']+)' versionCode='(\d+)' versionName='([^']+)'", badging, re.M)
    if not package or package[1] != baseline["applicationId"]:
        raise ValueError("APK package does not match the published application ID")
    version_code = int(package[2])
    if version_code <= baseline["publishedVersionCode"]:
        raise ValueError("APK versionCode must exceed the last published versionCode")
    source_code = re.search(r"^\s*versionCode\s*=\s*(\d+)\s*$", source, re.M)
    source_name = re.search(r'^\s*versionName\s*=\s*"([^"\n]+)"\s*$', source, re.M)
    if not source_code or not source_name or version_code != int(source_code[1]) or package[3] != source_name[1]:
        raise ValueError("APK version does not match app/build.gradle.kts")
    return f"{package[1]} {package[3]} ({version_code})"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("certificate", type=Path)
    parser.add_argument("badging", type=Path)
    args = parser.parse_args()
    baseline = json.loads((ROOT / "scripts/release_identity.json").read_text(encoding="utf-8"))
    try:
        identity = check_identity(args.certificate.read_text(encoding="utf-8"),
                                  args.badging.read_text(encoding="utf-8"), baseline,
                                  (ROOT / "app/build.gradle.kts").read_text(encoding="utf-8"))
    except ValueError as error:
        raise SystemExit(f"Release identity check failed: {error}") from error
    print(f"PASS: published certificate, package and increasing version: {identity}")


if __name__ == "__main__":
    main()
