"""Owner-signing distribution checks; not a complete release acceptance gate."""
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys


def inspect(apk, tools, expected):
    if not re.fullmatch(r"[a-fA-F0-9]{64}", expected):
        raise ValueError("A pinned public signing-certificate SHA-256 is required")
    certificates = subprocess.check_output([str(tools / "apksigner"), "verify", "--verbose", "--print-certs", str(apk)], text=True)
    fingerprints = re.findall(r"^Signer #\d+ certificate SHA-256 digest: ([a-fA-F0-9]{64})$", certificates, re.M)
    if len(fingerprints) != 1 or fingerprints[0].lower() != expected.lower():
        raise ValueError("APK certificate differs from the independently pinned owner certificate")
    subprocess.run([str(tools / "zipalign"), "-c", "-P", "16", "4", str(apk)], check=True, stdout=subprocess.DEVNULL)
    metadata = subprocess.check_output([str(tools / "aapt"), "dump", "badging", str(apk)], text=True)
    if "application-debuggable" in metadata:
        raise ValueError("A debuggable APK cannot be an owner distribution candidate")
    package = re.search(r"^package: name='([^']+)' versionCode='(\d+)' versionName='([^']+)'", metadata, re.M)
    minimum = re.search(r"^sdkVersion:'(\d+)'", metadata, re.M)
    if not package or package[1] != "org.echoscript.app" or not minimum or int(minimum[1]) != 29:
        raise ValueError("Unexpected package identity or Android compatibility boundary")
    permissions = subprocess.check_output([str(tools / "aapt"), "dump", "permissions", str(apk)], text=True)
    if re.search(r"android\.permission\.(INTERNET|ACCESS_NETWORK_STATE)\b", permissions):
        raise ValueError("The offline APK unexpectedly requests network access")
    return {"applicationId": package[1], "versionCode": int(package[2]), "versionName": package[3], "minSdk": 29, "debuggable": False, "signerCertificateSha256": fingerprints[0].lower()}


def main():
    apk = Path(sys.argv[1]).resolve()
    output = Path(sys.argv[2]).resolve()
    tools = Path(os.environ["ANDROID_HOME"]) / "build-tools" / "36.0.0"
    result = inspect(apk, tools, os.environ.get("EXPECTED_CERT_SHA256", ""))
    source = os.environ.get("GITHUB_SHA", "")
    if not re.fullmatch(r"[a-f0-9]{40}", source):
        raise ValueError("An exact source commit is required")
    output.mkdir(parents=True, exist_ok=False)
    target = output / "EchoScript-owner-evaluation.apk"
    shutil.copyfile(apk, target)
    with target.open("rb") as stream:
        apk_hash = hashlib.file_digest(stream, "sha256").hexdigest()
    result.update({"sourceCommit": source, "apkSha256": apk_hash, "scope": "Owner-signed evaluation candidate; full product release acceptance is separate", "betaGate": "NOT PASSED"})
    (output / "BUILD.json").write_text(json.dumps(result, indent=2) + "\n", encoding="utf-8")
    (output / "SHA256SUMS.txt").write_text(f"{result['apkSha256']}  {target.name}\n", encoding="ascii")
    (output / "INSTALL.txt").write_text("EchoScript owner-signed evaluation candidate — BETA GATE: NOT PASSED\n\nAndroid 10+; install only on an approved synthetic-test device. Verify SHA256SUMS and the independently trusted owner certificate before installation. This package uses org.echoscript.app and does not replace org.echoscript.app.preview or automatically migrate its vault. Preserve and independently verify a protected export before any reset/uninstall. Test clean-device import and same-signer upgrades before relying on a deployment. Follow the release candidate matrix and operator/security instructions in the exact source revision. This signing job does not publish a release or establish legal admissibility/compliance.\n", encoding="utf-8")
    for name in ("LICENSE", "NOTICE", "DISCLAIMER.md"):
        shutil.copyfile(Path(__file__).resolve().parents[1] / name, output / name)
    print("Owner certificate, non-debug package identity, alignment and offline permission checks passed. Full release gate remains NOT PASSED.")


if __name__ == "__main__":
    main()
