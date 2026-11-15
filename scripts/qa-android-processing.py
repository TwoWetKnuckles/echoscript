"""Actual process-kill recovery of a local render, on a disposable emulator only."""
import json
from pathlib import Path
import subprocess
import time

package = "org.echoscript.app.candidate06r2"
runner = package + ".test/androidx.test.runner.AndroidJUnitRunner"
output = Path("qa-output/android-processing")
output.mkdir(parents=True, exist_ok=True)
qemu = subprocess.check_output(["adb", "shell", "getprop", "ro.kernel.qemu"], text=True, timeout=5).strip()
if qemu != "1":
    raise RuntimeError("Processing recovery QA requires a disposable emulator")


def command(phase):
    return ["adb", "shell", "am", "instrument", "-w", "-r", "-e", "class",
            "org.echoscript.app.ProcessingRecoveryTest#actualProcessKillResumesTheSavedJob",
            "-e", "processingRecoveryPhase", phase, runner]


interrupted = subprocess.Popen(command("interrupt"), stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
captured = None
try:
    deadline = time.monotonic() + 110
    while True:
        probe = subprocess.run(["adb", "shell", "run-as", package, "cat", "files/synthetic-processing-ready.json"], capture_output=True, timeout=5)
        if probe.returncode == 0:
            marker = json.loads(probe.stdout)
            if not 1 <= marker["completedBeforeKill"] < 32:
                raise RuntimeError("No incomplete running render was observed")
            (output / "interruption-boundary.json").write_bytes(probe.stdout)
            break
        if interrupted.poll() is not None or time.monotonic() >= deadline:
            raise RuntimeError("The actual processing interruption boundary was not reached")
        time.sleep(0.1)
    subprocess.run(["adb", "shell", "am", "force-stop", package], check=True, timeout=10)
    captured = interrupted.communicate(timeout=15)
    if "OK (1 test)" in captured[0]:
        raise RuntimeError("Synthetic interruption phase completed instead of being killed")
finally:
    if interrupted.poll() is None:
        subprocess.run(["adb", "shell", "am", "force-stop", package], capture_output=True, timeout=10)
        interrupted.kill()
    if captured is None:
        captured = interrupted.communicate(timeout=5)
    (output / "synthetic-interrupted-worker.txt").write_text("".join(captured), encoding="utf-8")

verified = subprocess.run(command("verify"), capture_output=True, text=True, timeout=300)
(output / "synthetic-resumed-worker.txt").write_text(verified.stdout + verified.stderr, encoding="utf-8")
if verified.returncode or "OK (1 test)" not in verified.stdout:
    raise RuntimeError("Synthetic processing recovery failed; inspect its saved logs")
report = subprocess.check_output(["adb", "shell", "run-as", package, "cat", "files/synthetic-processing-result.json"], timeout=10)
result = json.loads(report)
if result.get("result") != "PASS" or result.get("completedPasses") != 32 or result.get("duplicatePasses") != 0 or not result.get("originalPreserved") or not result.get("completedPassReused"):
    raise RuntimeError("Saved processing recovery evidence does not substantiate its checks")
(output / "process-kill-render.json").write_bytes(report)
print("PASS: actual process-kill local-render recovery subset; full recovery gate remains outstanding.")
