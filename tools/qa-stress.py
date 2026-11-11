"""Synthetic desktop streaming stress QA. No real evidence; no cloud provider."""
import hashlib
import json
import math
import os
import pathlib
import struct
import subprocess
import sys
import time
import uuid
import zipfile

root = pathlib.Path(__file__).resolve().parents[1]
output = root / "qa-output/stress"
output.mkdir(parents=True, exist_ok=True)
seconds = int(os.environ.get("QA_STRESS_SECONDS", "3600"))
if not 60 <= seconds <= 14400:
    raise ValueError("QA duration must be between 60 seconds and four hours")


def sha_file(path):
    h = hashlib.sha256()
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(65536), b""):
            h.update(chunk)
    return h.hexdigest()


def canonical(value):
    # This vector uses integers/ASCII only; byte order matches the JCS verifier.
    return json.dumps(value, sort_keys=True, separators=(",", ":"), ensure_ascii=False).encode()


def measure(name, command, expected=0, rss_mib=64):
    metric = output / (name + ".rss-kib")
    start = time.monotonic()
    # A fresh helper isolates RUSAGE_CHILDREN from previous commands. Never infer
    # a per-command maximum from a cumulative parent-process high-water mark.
    helper = "import sys,subprocess,resource,pathlib;p=subprocess.run(sys.argv[2:],capture_output=True,timeout=150);rss=resource.getrusage(resource.RUSAGE_CHILDREN).ru_maxrss;pathlib.Path(sys.argv[1]).write_text(str(round(rss/1024 if sys.platform=='darwin' else rss)));sys.stdout.buffer.write(p.stdout);sys.stderr.buffer.write(p.stderr);sys.exit(p.returncode)"
    result = subprocess.run([sys.executable, "-c", helper, str(metric), *command], cwd=root,
                            capture_output=True, timeout=180)
    if (expected == 0 and result.returncode != 0) or (expected != 0 and result.returncode == 0):
        raise AssertionError(name + " unexpected exit: " + result.stderr.decode()[:500])
    rss_kib = int(metric.read_text().splitlines()[-1])
    if rss_kib > rss_mib * 1024:
        raise AssertionError(name + " exceeded measured peak RSS threshold")
    return {"check": name, "result": "PASS", "elapsedMs": round((time.monotonic() - start) * 1000),
            "peakRssKiB": rss_kib, "rssThresholdMiB": rss_mib}, result


source = output / "SYNTHETIC_TEST_NOT_EVIDENCE.wav"
derived = [output / "render-a.wav", output / "render-b.wav"]
package = output / "synthetic-hour.echocase"
results = []
report = {"scope": "DESKTOP_STREAMING_ONLY", "synthetic": True, "durationSeconds": seconds,
          "fullApplicationBetaGate": "NOT PASSED", "checks": results}
try:
    sample_rate = 16000
    length = seconds * sample_rate * 2
    header = struct.pack("<4sI4s4sIHHIIHH4sI", b"RIFF", length + 36, b"WAVE", b"fmt ", 16,
                         1, 1, sample_rate, sample_rate * 2, 2, 16, b"data", length)
    block = b"".join(struct.pack("<h", round(6000 * math.sin(2 * math.pi * 125 * i / sample_rate)))
                     for i in range(32768))
    with source.open("wb") as target:
        target.write(header)
        remaining = length
        while remaining:
            part = block[:min(len(block), remaining)]
            target.write(part)
            remaining -= len(part)
    source_hash = sha_file(source)
    binary = str(root / "target/release/echoscript-audio")
    for label, destination in zip(["stream-render-a", "stream-render-b"], derived):
        check, _ = measure(label, [binary, str(source), str(destination), "3", "highpass", "80", "0.707"])
        results.append(check)
    derived_hash = sha_file(derived[0])
    assert derived_hash == sha_file(derived[1]), "Streaming renders are not deterministic"
    check, _ = measure("existing-output-refused", [binary, str(source), str(derived[0]), "0"], expected=1)
    results.append(check)
    assert sha_file(derived[0]) == derived_hash, "Existing derivative was changed"
    truncated = output / "truncated.wav"
    truncated.write_bytes(header + b"short")
    invalid_output = output / "must-not-complete.wav"
    check, _ = measure("truncated-input-cleanup", [binary, str(truncated), str(invalid_output), "0"], expected=1)
    results.append(check)
    assert not invalid_output.exists(), "Failed render left a complete-looking derivative"
    assert sha_file(source) == source_hash, "Source changed during streaming QA"

    case_id, artifact_id = str(uuid.uuid4()), str(uuid.uuid4())
    audit = []

    def audit_event(action, details):
        entry = {"seq": len(audit) + 1, "timestampUtc": "2026-11-01T00:00:00.000Z",
                 "actor": "SYNTHETIC_QA", "deviceId": "DESKTOP_STRESS", "action": action,
                 "details": details, "prevHash": audit[-1]["entryHash"] if audit else "0" * 64}
        entry["entryHash"] = hashlib.sha256(canonical(entry)).hexdigest()
        audit.append(entry)

    audit_event("CASE_CREATE", {})
    artifact = {"id": artifact_id, "caseId": case_id, "type": "SOURCE", "parents": [],
                "name": "SYNTHETIC TEST DATA - NOT EVIDENCE", "path": "artifacts/" + artifact_id,
                "bytes": source.stat().st_size, "sha256": source_hash, "timestampUtc": "2026-11-01T00:00:00.000Z",
                "actor": "SYNTHETIC_QA", "revision": 1, "softwareVersion": "0.5.0-preview",
                "operation": {"algorithm": "synthetic-tone-vector", "version": "1",
                              "parameters": {"sampleRate": 16000, "seconds": seconds}}, "warnings": []}
    audit_event("ARTIFACT_COMMIT", {"artifactId": artifact_id, "sha256": source_hash, "type": "SOURCE", "parents": []})
    manifest = {"format": "org.echoscript.case/1", "schemaVersion": 1, "caseId": case_id,
                "title": "SYNTHETIC TEST DATA - NOT EVIDENCE", "createdAtUtc": "2026-11-01T00:00:00.000Z",
                "software": {"name": "EchoScript", "version": "0.5.0-preview"}, "artifacts": [artifact],
                "audit": audit, "activeArtifact": artifact_id}
    metadata = canonical(manifest)
    with zipfile.ZipFile(package, "w", compression=zipfile.ZIP_STORED, allowZip64=True) as archive:
        archive.write(source, artifact["path"])
        archive.writestr("manifest.json", metadata)
        archive.writestr("SHA256SUMS.txt", source_hash + "  " + artifact["path"] + "\n" + hashlib.sha256(metadata).hexdigest() + "  manifest.json\n")
        archive.writestr("README.txt", "SYNTHETIC TEST DATA - NOT EVIDENCE\n")
    check, process = measure("hour-package-independent-streaming-verifier",
                             ["node", "tools/case-verify/index.mjs", str(package), "--sha256", sha_file(package)], rss_mib=256)
    assert json.loads(process.stdout)["result"] == "PASS"
    results.append(check)
    report.update({"result": "PASS", "sourceBytes": source.stat().st_size, "sourceSha256": source_hash,
                   "derivativeSha256": derived_hash, "packageBytes": package.stat().st_size})
except Exception as failure:
    report.update({"result": "FAIL", "observed": str(failure)})
    raise
finally:
    (output / "results.json").write_text(json.dumps(report, indent=2) + "\n")
    for path in [source, *derived, package, output / "truncated.wav", output / "must-not-complete.wav"]:
        path.unlink(missing_ok=True)
    print(json.dumps(report))
