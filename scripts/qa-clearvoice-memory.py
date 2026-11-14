"""Compare the pinned graph's allocator policies using synthetic audio, in separate processes."""
import argparse
import ctypes
import ctypes.util
from datetime import datetime, timezone
import errno
import hashlib
import json
import os
from pathlib import Path
import resource
import socket
import subprocess
import sys
import time


def require_offline_process():
    """Fail closed before importing inference libraries; no telemetry destination is reachable."""
    path = ctypes.util.find_library("seccomp")
    if not path:
        raise RuntimeError("Linux libseccomp is required for this offline-only benchmark")
    lib = ctypes.CDLL(path, use_errno=True)
    lib.seccomp_init.argtypes = [ctypes.c_uint32]
    lib.seccomp_init.restype = ctypes.c_void_p
    lib.seccomp_syscall_resolve_name.argtypes = [ctypes.c_char_p]
    lib.seccomp_rule_add.argtypes = [ctypes.c_void_p, ctypes.c_uint32, ctypes.c_int, ctypes.c_uint]
    lib.seccomp_load.argtypes = [ctypes.c_void_p]
    lib.seccomp_release.argtypes = [ctypes.c_void_p]
    guard = lib.seccomp_init(0x7FFF0000)  # SCMP_ACT_ALLOW; deny all socket creation/transmission below.
    if not guard:
        raise RuntimeError("Could not create the offline execution guard")
    try:
        for name in [b"socket", b"socketpair", b"connect", b"sendto", b"sendmsg", b"sendmmsg"]:
            number = lib.seccomp_syscall_resolve_name(name)
            if number < 0 or lib.seccomp_rule_add(guard, 0x50000 | errno.EPERM, number, 0) != 0:
                raise RuntimeError("Could not install all offline execution rules")
        if lib.seccomp_load(guard) != 0:
            raise RuntimeError("Kernel refused the offline execution guard")
    finally:
        lib.seccomp_release(guard)
    try:
        socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    except PermissionError:
        pass
    else:
        raise RuntimeError("Kernel network guard did not deny socket creation")
    os.environ["ORT_DISABLE_TELEMETRY"] = "1"


require_offline_process()
MODEL_HASH = "5fedce707fd607dc5280e8c52182057b24fc5565a2e4215f3dccb6e77ab067c2"
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("model", type=Path)
parser.add_argument("output", type=Path)
parser.add_argument("--child", choices=["default", "bounded"])
args = parser.parse_args()
with args.model.open("rb") as stream:
    assert hashlib.file_digest(stream, "sha256").hexdigest() == MODEL_HASH

if args.child:
    import numpy as np
    import onnxruntime as ort
    ort.disable_telemetry_events()
    assert ort.__version__ == "1.32.0"
    options = ort.SessionOptions()
    options.intra_op_num_threads = 1
    options.inter_op_num_threads = 1
    options.execution_mode = ort.ExecutionMode.ORT_SEQUENTIAL
    if args.child == "bounded":
        options.enable_cpu_mem_arena = False
        options.enable_mem_pattern = False
    samples = np.random.default_rng(426).normal(0, .02, (1, 32000)).astype(np.float32)
    started = time.monotonic()
    session = ort.InferenceSession(str(args.model), options, providers=["CPUExecutionProvider"])
    first = session.run(None, {"audio": samples})[0]
    assert first.shape == (1, 32000) and np.isfinite(first).all()
    for _ in range(2):
        assert np.array_equal(first, session.run(None, {"audio": samples})[0])
    np.save(args.output, first, allow_pickle=False)
    measurement = {"policy": args.child, "runtime": ort.__version__, "numpy": np.__version__,
                   "maxRssKiB": resource.getrusage(resource.RUSAGE_SELF).ru_maxrss,
                   "seconds": time.monotonic() - started, "repeats": 3,
                   "outputSha256": hashlib.sha256(first.tobytes()).hexdigest()}
    args.output.with_suffix(".json").write_text(json.dumps(measurement, indent=2) + "\n")
    print(json.dumps(measurement))
else:
    import numpy as np
    args.output.mkdir(parents=True, exist_ok=True)
    measurements = []
    for policy in ["default", "bounded"]:
        output = args.output / (policy + ".npy")
        result = subprocess.run([sys.executable, __file__, str(args.model), str(output), "--child", policy],
                                capture_output=True, text=True, timeout=180)
        (args.output / (policy + "-runtime.txt")).write_text(result.stdout + result.stderr)
        result.check_returncode()
        measurements.append(json.loads(result.stdout))
    baseline = np.load(args.output / "default.npy", allow_pickle=False)
    bounded = np.load(args.output / "bounded.npy", allow_pickle=False)
    assert np.array_equal(baseline, bounded), "Allocator changes must preserve every synthetic output sample"
    assert measurements[1]["maxRssKiB"] < measurements[0]["maxRssKiB"], "No measured memory improvement"
    report = {"result": "PASS", "recordedAtUtc": datetime.now(timezone.utc).isoformat(), "scope": "Linux CPU synthetic allocator comparison, not Android resource acceptance",
              "network": "kernel-denied socket creation/connect/transmission before inference import",
              "modelSha256": MODEL_HASH, "inputShape": [1, 32000], "sampleRate": 16000,
              "exactOutputMatch": True, "measurements": measurements}
    (args.output / "memory-comparison.json").write_text(json.dumps(report, indent=2) + "\n")
    print(json.dumps(report))
