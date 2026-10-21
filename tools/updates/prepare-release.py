#!/usr/bin/env python3
"""Prepare DD-compatible release metadata from a verified universal APK. Never uploads/signs."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import zipfile

PACKAGES = {"com.drivedeck.player.test": "player-test-", "com.drivedeck.player": "player-release-"}
ABIS = {"arm64-v8a", "armeabi-v7a", "x86", "x86_64"}


def run(arguments):
    result = subprocess.run([str(x) for x in arguments], capture_output=True, text=True, encoding="utf-8", errors="replace", timeout=120, check=False)
    if result.returncode:
        raise ValueError("Required verification command failed")
    return result.stdout


def parse_badging(text):
    match = re.search(r"^package: name='([^']+)' versionCode='(\d+)' versionName='([^']+)'", text, re.M)
    if not match:
        raise ValueError("APK package/version metadata missing")
    minimum = re.search(r"^sdkVersion:'(\d+)'", text, re.M)
    if not minimum or int(minimum[1]) > 23:
        raise ValueError("Unexpected minimum Android SDK")
    return {"packageName": match[1], "versionCode": int(match[2]), "versionName": match[3]}


def verified_signer(text, expected):
    matches = re.findall(r"^Signer #\d+ certificate SHA-256 digest: ([a-f0-9]{64})$", text, re.M)
    if "Number of signers: 1" not in text or matches != [expected] or "Verified using v2 scheme (APK Signature Scheme v2): true" not in text:
        raise ValueError("APK signer or signature verification mismatch")
    return expected


def make_manifest(apk, metadata, tag, commit):
    if metadata["packageName"] not in PACKAGES or not tag.startswith(PACKAGES[metadata["packageName"]]):
        raise ValueError("Package and update channel differ")
    if not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9._-]{0,95}", tag) or not re.fullmatch(r"[a-f0-9]{40}", commit):
        raise ValueError("Invalid release tag/commit")
    if not re.fullmatch(r"[A-Za-z0-9._-]+-universal-(?:debug|release)\.apk", apk.name):
        raise ValueError("A universal APK is required for the one-version manifest")
    if not 1_000_000 <= apk.stat().st_size <= 800_000_000 or not 0 < metadata["versionCode"] <= 2_100_000_000:
        raise ValueError("APK size or version is outside the update contract")
    with zipfile.ZipFile(apk) as archive:
        observed = {name.split("/")[1] for name in archive.namelist() if name.startswith("lib/") and len(name.split("/")) >= 3}
        if observed != ABIS:
            raise ValueError("Universal APK native ABI set differs")
    with apk.open("rb") as source:
        digest = hashlib.file_digest(source, "sha256").hexdigest()
    return {"schemaVersion": 1, **metadata, "tag": tag, "commitSha": commit, "assets": [{"name": apk.name, "abi": "universal", "sha256": digest, "sizeBytes": apk.stat().st_size}]}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--apk", type=Path, required=True)
    parser.add_argument("--sdk-root", type=Path, required=True)
    parser.add_argument("--java", type=Path, required=True)
    parser.add_argument("--package", choices=PACKAGES, required=True)
    parser.add_argument("--signer-sha256", required=True)
    parser.add_argument("--tag", required=True)
    parser.add_argument("--commit", required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    if not re.fullmatch(r"[a-f0-9]{64}", args.signer_sha256):
        raise ValueError("Expected public signer SHA-256 required")
    root = Path(__file__).resolve().parents[2]
    if run(["git", "-C", root, "rev-parse", "HEAD"]).strip() != args.commit:
        raise ValueError("Release commit differs from checkout HEAD")
    if run(["git", "-C", root, "status", "--porcelain"]).strip():
        raise ValueError("Commit the reviewed source/evidence before preparing release metadata")
    tools = args.sdk_root / "build-tools" / "36.0.0"
    metadata = parse_badging(run([tools / ("aapt2.exe" if os.name == "nt" else "aapt2"), "dump", "badging", args.apk]))
    if metadata["packageName"] != args.package:
        raise ValueError("Unexpected APK package")
    signature = run([args.java, "-jar", tools / "lib" / "apksigner.jar", "verify", "--verbose", "--print-certs", args.apk])
    verified_signer(signature, args.signer_sha256)
    manifest = make_manifest(args.apk, metadata, args.tag, args.commit)
    args.output.mkdir(parents=True, exist_ok=True)
    target = args.output / "release-manifest.json"
    target.write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")
    lines = [f"{manifest['assets'][0]['sha256']}  {args.apk.name}", f"{hashlib.sha256(target.read_bytes()).hexdigest()}  release-manifest.json"]
    (args.output / "SHA256SUMS.txt").write_text("\n".join(lines) + "\n", encoding="utf-8")
    print(json.dumps({"result": "passed", "package": args.package, "versionCode": manifest["versionCode"], "apkSha256": manifest["assets"][0]["sha256"], "uploaded": False}))


if __name__ == "__main__":
    try:
        main()
    except (ValueError, OSError, zipfile.BadZipFile) as error:
        raise SystemExit(str(error))
