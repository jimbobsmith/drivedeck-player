#!/usr/bin/env python3
"""Opt-in updater runtime harness. Fresh named DDPlayerUpdaterApi36 emulator only."""
import argparse
import json
from pathlib import Path
import re
import secrets
import subprocess

PACKAGE = "com.drivedeck.player.test"
RUNNER = PACKAGE + ".test/androidx.test.runner.AndroidJUnitRunner"
CLASS = "org.schabi.newpipe.updates.PlayerUpdaterRuntimeTest"
METHODS = {"verifyCandidatesAndDefaultOff", "cancelledWorkerCanRetry", "automaticJobsAndVerifiedBackgroundDownload", "stagePreparedAndLibrary", "settingsPreparedPortraitLandscapeAndInstallIntent", "assertUpgradeRetained"}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--adb", type=Path, required=True)
    parser.add_argument("--serial", required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--prepare", action="store_true")
    parser.add_argument("--bootstrap", type=Path)
    parser.add_argument("--test-apk", type=Path)
    parser.add_argument("--fixtures", type=Path)
    parser.add_argument("--method", choices=sorted(METHODS))
    args = parser.parse_args()
    if not re.fullmatch(r"emulator-\d+", args.serial):
        raise ValueError("Only a dedicated emulator serial is accepted")
    if not str(args.output.resolve()).lower().replace("\\", "/").startswith("d:/tmp/dd/"):
        raise ValueError("Disposable DriveDeck scratch output required")

    def adb(*words, timeout=120):
        result = subprocess.run([str(args.adb), "-s", args.serial, *map(str, words)], capture_output=True, timeout=timeout, check=False)
        if result.returncode:
            raise ValueError("Scoped ADB operation failed")
        return result.stdout

    name = adb("emu", "avd", "name").decode().replace("\r", "").splitlines()[0]
    if name != "DDPlayerUpdaterApi36":
        raise ValueError("Dedicated updater AVD required")
    if adb("shell", "getprop", "ro.hardware").decode().strip() not in ("ranchu", "goldfish"):
        raise ValueError("Physical devices are refused")
    if args.prepare:
        if not args.bootstrap or not args.test_apk or not args.fixtures:
            raise ValueError("Bootstrap, test APK and fixture directory required")
        if ("package:" + PACKAGE) in adb("shell", "pm", "list", "packages", PACKAGE).decode().splitlines():
            raise ValueError("Fresh emulator package required; no existing data will be cleared")
        args.output.mkdir(parents=True, exist_ok=False)
        permit = secrets.token_hex(32)
        (args.output / "permit-private.txt").write_text(permit, encoding="utf-8")
        for apk in (args.bootstrap, args.test_apk):
            result = adb("install", "-r", apk)
            if b"Success" not in result:
                raise ValueError("Fresh fixture APK installation failed")
        adb("shell", "run-as", PACKAGE, "mkdir", "-p", "files/updater-test-fixtures", "no_backup")
        sources = [(args.fixtures / name, "files/updater-test-fixtures/" + name) for name in ("valid.apk", "wrong-signer.apk", "other-package.apk", "current.apk")]
        sources.append((args.output / "permit-private.txt", "no_backup/updater-validation.permit"))
        for index, (source, destination) in enumerate(sources):
            remote = f"/data/local/tmp/dd-updater-{permit[:12]}-{index}"
            adb("push", source, remote)
            adb("shell", "run-as", PACKAGE, "cp", remote, destination)
            adb("shell", "rm", remote)
        (args.output / "owner.json").write_text(json.dumps({"avd": name, "package": PACKAGE, "freshInstall": True, "personalDevice": False}), encoding="utf-8")
        print(json.dumps({"result": "prepared", "avd": name, "personalDevicesTouched": False}))
    elif args.method:
        if not (args.output / "owner.json").is_file():
            raise ValueError("Prior fresh-install ownership record required")
        permit = (args.output / "permit-private.txt").read_text(encoding="utf-8")
        if not re.fullmatch(r"[a-f0-9]{64}", permit):
            raise ValueError("Fresh emulator permit invalid")
        result = adb("shell", "am", "instrument", "-w", "-r", "-e", "class", CLASS + "#" + args.method, "-e", "updaterPermit", permit, RUNNER, timeout=240)
        (args.output / (args.method + "-private.log")).write_bytes(result)
        text = result.decode("utf-8", errors="replace")
        if not re.search(r"OK \(1 test\)", text) or "FAILURES!!!" in text or "INSTRUMENTATION_FAILED" in text:
            raise ValueError("Runtime assertion failed; inspect private result")
        print(json.dumps({"result": "passed", "method": args.method, "avd": name}))
    else:
        raise ValueError("Choose --prepare or --method")


if __name__ == "__main__":
    try:
        main()
    except (ValueError, OSError, subprocess.TimeoutExpired) as error:
        raise SystemExit(str(error))
