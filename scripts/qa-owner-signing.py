"""Exercises signing with a disposable synthetic key, never the owner's key."""
import hashlib
import importlib.util
import json
import os
import re
from pathlib import Path
import secrets
import subprocess
import tempfile

root = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location("owner_checks", root / "scripts/verify-owner-apk.py")
checks = importlib.util.module_from_spec(spec)
spec.loader.exec_module(checks)
tools = Path(os.environ["ANDROID_HOME"]) / "build-tools/36.0.0"
with tempfile.TemporaryDirectory(prefix="echoscript-SYNTHETIC-signer-") as directory:
    folder = Path(directory)
    password = secrets.token_urlsafe(32)
    env = dict(os.environ, ANDROID_SIGNING_STORE_FILE=str(folder / "synthetic.jks"), ANDROID_SIGNING_STORE_PASSWORD=password,
               ANDROID_SIGNING_KEY_ALIAS="SYNTHETIC_TEST_NOT_OWNER", ANDROID_SIGNING_KEY_PASSWORD=password)
    subprocess.run(["keytool", "-genkeypair", "-keystore", env["ANDROID_SIGNING_STORE_FILE"], "-alias", env["ANDROID_SIGNING_KEY_ALIAS"],
                    "-storepass:env", "ANDROID_SIGNING_STORE_PASSWORD", "-keypass:env", "ANDROID_SIGNING_KEY_PASSWORD", "-keyalg", "RSA",
                    "-keysize", "3072", "-validity", "2", "-dname", "CN=SYNTHETIC TEST NOT OWNER"], env=env, check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    certificate = folder / "certificate.der"
    subprocess.run(["keytool", "-exportcert", "-keystore", env["ANDROID_SIGNING_STORE_FILE"], "-alias", env["ANDROID_SIGNING_KEY_ALIAS"],
                    "-storepass:env", "ANDROID_SIGNING_STORE_PASSWORD", "-file", str(certificate)], env=env, check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    expected = hashlib.sha256(certificate.read_bytes()).hexdigest()
    subprocess.run([str(root / "apps/android/gradlew"), "-p", str(root / "apps/android"), ":app:assembleRelease", "--no-daemon", "--no-configuration-cache"], env=env, check=True)
    apk = root / "apps/android/app/build/outputs/apk/release/app-release.apk"
    result = checks.inspect(apk, tools, expected)
    debug = root / "apps/android/app/build/outputs/apk/debug/app-debug.apk"
    debug_cert = subprocess.check_output([str(tools / "apksigner"), "verify", "--print-certs", str(debug)], text=True)
    debug_pin = re.search(r"^Signer #1 certificate SHA-256 digest: ([a-fA-F0-9]{64})$", debug_cert, re.M)[1]
    for target, pin in [(apk, "0" * 64), (debug, debug_pin)]:
        try:
            checks.inspect(target, tools, pin)
        except ValueError:
            pass
        else:
            raise AssertionError("Wrong certificate/debug APK was accepted")
    output = root / "qa-output/signing"
    output.mkdir(parents=True, exist_ok=True)
    (output / "synthetic-signing.json").write_text(json.dumps({"check": "SYNTHETIC_SIGNING_INTEGRATION", "result": "PASS", "observed": result,
        "scope": "Disposable test key only; not owner provisioning, an upgrade test, MDM validation or release acceptance"}, indent=2) + "\n", encoding="utf-8")
    # The synthetic signed APK must not be distributed as an owner build.
    apk.unlink()
print("Synthetic signing integration and wrong-certificate/debug rejection passed; no owner key was used.")
