"""Check that the old preview's synthetic private data survives a test install."""
import hashlib
import os
from pathlib import Path
import subprocess
import sys

# Exercise the one published rollback retained after historical release archival.
OLD_ID = "org.echoscript.app.preview06"
URL = "https://github.com/TwoWetKnuckles/echoscript/releases/download/android-preview-0.6.0-dev.20261113/EchoScript-offline-preview.apk"
SHA256 = "0efbfe18fffda6b0141cb13cdeb56e301ff12fa617539e47042b57a37fba50d3"
MARKER = "files/synthetic-preview-isolation.txt"
VALUE = b"SYNTHETIC_OLD_PREVIEW_DATA"

if len(sys.argv) != 3 or sys.argv[1] not in {"setup", "check"} or sys.argv[2] not in {"29", "36"}:
    raise SystemExit("Usage: qa-android-coexistence.py setup|check 29|36")
if sys.argv[2] == "29":
    print("Coexistence is exercised on the Android 16 emulator.")
    raise SystemExit(0)
qemu = subprocess.check_output(["adb", "shell", "getprop", "ro.kernel.qemu"], text=True, timeout=5).strip()
if qemu != "1":
    raise RuntimeError("Coexistence QA requires a disposable Android emulator")

if sys.argv[1] == "setup":
    apk = Path(os.environ["RUNNER_TEMP"]) / "echoscript-old-synthetic-qa.apk"
    try:
        subprocess.run(["curl", "--silent", "--show-error", "--fail", "--location", "--retry", "3", "--output", str(apk), URL], check=True, timeout=300)
        digest = hashlib.sha256()
        with apk.open("rb") as source:
            while block := source.read(1024 * 1024):
                digest.update(block)
        if digest.hexdigest() != SHA256:
            raise RuntimeError("Published synthetic-test preview APK did not match its pinned SHA-256")
        subprocess.run(["adb", "install", str(apk)], check=True, timeout=180)
        subprocess.run(["adb", "shell", "run-as", OLD_ID, "mkdir", "-p", "files"], check=True, timeout=10)
        subprocess.run(["adb", "shell", "run-as", OLD_ID, "tee", MARKER], input=VALUE + b"\n", stdout=subprocess.DEVNULL, check=True, timeout=10)
    finally:
        apk.unlink(missing_ok=True)
else:
    marker = subprocess.check_output(["adb", "shell", "run-as", OLD_ID, "cat", MARKER], timeout=10).strip()
    if marker != VALUE:
        raise RuntimeError("The earlier preview's synthetic private-data marker changed")
    print("PASS: earlier preview's synthetic data remains after candidate testing.")
