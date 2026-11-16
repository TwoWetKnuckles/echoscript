"""Synthetic CPU throughput/parity comparison. It does not predict phone thermals or cloud queues."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import resource
import statistics
import subprocess
import sys
import time

os.environ["ORT_DISABLE_TELEMETRY"] = "1"
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("model", type=Path)
parser.add_argument("output", type=Path)
parser.add_argument("--threads", type=int, choices=[1, 2, 4])
parser.add_argument("--arena", action="store_true")
args = parser.parse_args()
expected = "5fedce707fd607dc5280e8c52182057b24fc5565a2e4215f3dccb6e77ab067c2"
with args.model.open("rb") as stream:
    assert hashlib.file_digest(stream, "sha256").hexdigest() == expected

if args.threads:
    import numpy as np
    import onnxruntime as ort
    ort.disable_telemetry_events()
    assert ort.__version__ == "1.32.0" and np.__version__ == "1.26.4"
    options = ort.SessionOptions()
    options.intra_op_num_threads = args.threads
    options.inter_op_num_threads = 1
    options.execution_mode = ort.ExecutionMode.ORT_SEQUENTIAL
    options.enable_cpu_mem_arena = args.arena
    options.enable_mem_pattern = args.arena
    start = time.perf_counter()
    session = ort.InferenceSession(str(args.model), options, providers=["CPUExecutionProvider"])
    init = time.perf_counter() - start
    # Identical inputs across processes; mixtures are synthetic, not a speech-quality corpus.
    rng = np.random.default_rng(20261116)
    samples = [rng.normal(0, .02, (1, 32000)).astype(np.float32) for _ in range(2)]
    session.run(None, {"audio": samples[0]})
    latencies = []
    outputs = []
    for sample in samples:
        start = time.perf_counter()
        actual = session.run(None, {"audio": sample})[0]
        latencies.append(time.perf_counter() - start)
        assert actual.shape == (1, 32000) and np.isfinite(actual).all()
        outputs.append(actual)
    repeated = session.run(None, {"audio": samples[-1]})[0]
    assert np.array_equal(repeated, outputs[-1])
    np.save(args.output.with_suffix(".npy"), np.stack(outputs), allow_pickle=False)
    value = {"threads": args.threads, "arena": args.arena, "initSeconds": init,
        "secondsPerOneSecondHop": statistics.median(latencies), "windowLatencies": latencies,
        "maxRssKiB": resource.getrusage(resource.RUSAGE_SELF).ru_maxrss,
        "repeatableWithinProfile": True}
    args.output.write_text(json.dumps(value, indent=2) + "\n")
else:
    import numpy as np
    args.output.mkdir(parents=True, exist_ok=True)
    records = []
    baseline = None
    for arena in [False, True]:
        for threads in [1, 2, 4]:
            path = args.output / f"threads-{threads}-arena-{int(arena)}.json"
            command = [sys.executable, __file__, str(args.model), str(path), "--threads", str(threads)]
            if arena:
                command.append("--arena")
            print(f"Benchmarking threads={threads}, arena={arena}", flush=True)
            subprocess.run(command, check=True, timeout=600)
            data = json.loads(path.read_text())
            actual = np.load(path.with_suffix(".npy"), allow_pickle=False)
            if baseline is None:
                baseline = actual
            data["maxAbsoluteDifferenceFromBaseline"] = float(np.max(np.abs(actual - baseline)))
            # Compare the PCM16 result too; tiny float differences may quantize differently.
            quantize = lambda x: np.clip(np.rint(x * 32768), -32768, 32767).astype(np.int16)
            data["changedPcmSamples"] = int(np.count_nonzero(quantize(actual) != quantize(baseline)))
            records.append(data)
            print(json.dumps(data), flush=True)
    report = {"scope": "Linux synthetic pinned-model CPU profiles; not phone or speech-quality acceptance",
        "modelSha256": expected, "runtime": "onnxruntime/1.32.0", "numpy": "1.26.4",
        "logicalCpus": os.cpu_count(), "profiles": records}
    (args.output / "throughput.json").write_text(json.dumps(report, indent=2) + "\n")
