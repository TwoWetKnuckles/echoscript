"""Real process interruption in a disposable emulator with synthetic input only."""
import json
from pathlib import Path
import subprocess
import time

root = Path(__file__).resolve().parents[1]
package = "org.echoscript.app.preview"
runner = package + ".test/androidx.test.runner.AndroidJUnitRunner"
test_class = "org.echoscript.app.CrashRecoveryTest#checkpointAndInterrupt"
device_output = f"/sdcard/Android/data/{package}/files/synthetic-crash-qa"
output = Path("qa-output/android-crash")
output.mkdir(parents=True, exist_ok=True)

# AGP's unified test platform removes its instrumentation installation after
# connectedDebugAndroidTest. Reinstall the exact built pair for separate phases.
# Refuse a physical/user device: this harness is disposable-emulator-only.
qemu = subprocess.check_output(["adb", "shell", "getprop", "ro.kernel.qemu"], text=True, timeout=5).strip()
if qemu != "1":
    raise RuntimeError("Synthetic crash harness requires a disposable Android emulator")
for relative in ["app/build/outputs/apk/debug/app-debug.apk", "app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk"]:
    apk = root / "apps/android" / relative
    if not apk.is_file():
        raise RuntimeError("Build both application and instrumentation APKs before the crash harness")
    subprocess.run(["adb", "install", "-r", "-t", str(apk)], check=True, timeout=60, stdout=subprocess.DEVNULL)
instrumentations = subprocess.check_output(["adb", "shell", "pm", "list", "instrumentation"], text=True, timeout=5)
if runner not in instrumentations:
    raise RuntimeError("The exact synthetic instrumentation runner was not installed")


def command(phase):
    return ["adb", "shell", "am", "instrument", "-w", "-r", "-e", "class", test_class,
            "-e", "recoveryPhase", phase, "-e", "additionalTestOutputDir", device_output, runner]


def execute(phase):
    result = subprocess.run(command(phase), capture_output=True, text=True, timeout=60)
    (output / f"synthetic-{phase}.txt").write_text(result.stdout + result.stderr, encoding="utf-8")
    if result.returncode or "OK (1 test)" not in result.stdout:
        raise RuntimeError(f"Synthetic recovery {phase} did not complete successfully")


execute("prepare")
interrupted = subprocess.Popen(command("interrupt"), stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
try:
    deadline = time.monotonic() + 30
    while True:
        ready = subprocess.run(["adb", "shell", "run-as", package, "test", "-f", "files/synthetic-crash-ready"], timeout=5, capture_output=True).returncode == 0
        if ready:
            break
        if interrupted.poll() is not None or time.monotonic() >= deadline:
            raise RuntimeError("The actual encrypted-acquisition interruption boundary was not reached")
        time.sleep(0.2)
    subprocess.run(["adb", "shell", "am", "force-stop", package], check=True, timeout=10)
    stdout, stderr = interrupted.communicate(timeout=15)
    (output / "synthetic-interrupt.txt").write_text(stdout + stderr, encoding="utf-8")
    if "OK (1 test)" in stdout:
        raise RuntimeError("Interrupt phase completed instead of being killed")
finally:
    if interrupted.poll() is None:
        interrupted.kill()
        interrupted.communicate(timeout=5)
execute("verify")
subprocess.run(["adb", "pull", device_output + "/process-kill-import.json", str(output / "process-kill-import.json")], check=True, timeout=15, stdout=subprocess.DEVNULL)
result = json.loads((output / "process-kill-import.json").read_text())
if result.get("result") != "PASS" or not result.get("originalPreserved") or result.get("falseCompleteCase") is not False:
    raise RuntimeError("Synthetic crash evidence did not substantiate the subset checks")
print("PASS: actual Android process-kill acquisition subset; full recovery gate remains outstanding.")
