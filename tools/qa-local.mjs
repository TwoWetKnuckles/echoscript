import { Worker } from "node:worker_threads";
import { readFile, writeFile, mkdir, readdir } from "node:fs/promises";
import { randomBytes } from "node:crypto";
import { spawnSync } from "node:child_process";
import { resolve } from "node:path";
import assert from "node:assert/strict";
import { buildServer } from "../build/services/api/src/server.js";
import { digest } from "../packages/evidence/index.mjs";
const output = resolve("qa-output/local-provider");
await mkdir(output, { recursive: true });
const fixture = new Uint8Array(
  await readFile("specs/fixtures/synthetic-speech.wav"),
);
const provenance = JSON.parse(
  await readFile("specs/fixtures/synthetic-speech.provenance.json", "utf8"),
);
assert.equal(digest(fixture), provenance.sha256);
const fontBase64 = (
  await readFile("apps/web/assets/NotoSans-Regular.ttf")
).toString("base64");
const token = randomBytes(32).toString("hex"),
  headers = { authorization: `Bearer ${token}` };
const app = buildServer({
  token,
  python: process.env.VOSK_PYTHON || "python3",
  modelPath: resolve(
    process.env.VOSK_MODEL_PATH || "models/vosk-model-small-en-us-0.15",
  ),
});
const apiOrigin = await app.listen({ host: "127.0.0.1", port: 0 });
const results = [],
  renderedHashes = [];
let consecutive = 0;
const baselineFd =
  process.platform === "linux" ? (await readdir("/proc/self/fd")).length : null;
const resourceSamples = [];
function errorRate(reference, hypothesis) {
  const a = reference.split(/\s+/),
    b = hypothesis.split(/\s+/),
    d = Array.from({ length: a.length + 1 }, (_, i) => [i]);
  for (let j = 0; j <= b.length; j++) d[0][j] = j;
  for (let i = 1; i <= a.length; i++)
    for (let j = 1; j <= b.length; j++)
      d[i][j] = Math.min(
        d[i - 1][j] + 1,
        d[i][j - 1] + 1,
        d[i - 1][j - 1] + (a[i - 1] === b[j - 1] ? 0 : 1),
      );
  return d[a.length][b.length] / a.length;
}
try {
  for (let run = 1; run <= 7; run++) {
    const started = Date.now(),
      worker = new Worker(new URL("./worker-node.mjs", import.meta.url));
    let n = 0;
    const jobs = new Map();
    worker.on("message", (m) => {
      const job = jobs.get(m.requestId);
      if (!job) return;
      jobs.delete(m.requestId);
      clearTimeout(job.timeout);
      m.error ? job.reject(Error(m.error)) : job.resolve(m.result);
    });
    const call = (action, data = {}) =>
      new Promise((resolve, reject) => {
        const requestId = ++n;
        const timeout = setTimeout(() => {
          jobs.delete(requestId);
          reject(Error("WORKER_TIMEOUT: " + action));
        }, 60000);
        jobs.set(requestId, { resolve, reject, timeout });
        worker.postMessage({ requestId, action, data });
      });
    try {
      let s = await call("import", {
        bytes: fixture,
        name: "SYNTHETIC_TEST_NOT_EVIDENCE.wav",
        title: "SYNTHETIC TEST DATA — NOT EVIDENCE",
        actor: "SYNTHETIC_QA_OPERATOR",
      });
      assert.ok(s.analysis.events.length, "Speech-event candidate stage");
      assert.ok(
        s.analysis.wave.length && s.analysis.spec.length,
        "Analysis data stage",
      );
      const event = s.analysis.events[0];
      const region = {
        start: event.start,
        end: Math.min(event.end, s.audio.duration),
      };
      s = await call("render", {
        graph: [
          { type: "highpass", frequency: 80, q: 0.707 },
          { type: "gain", gainDb: 3 },
          { type: "limiter", ceilingDb: -1 },
        ],
        selections: [region],
      });
      const enhanced = s.manifest.artifacts.find((a) => a.id === s.audioId);
      renderedHashes.push(enhanced.sha256);
      const preview = await call("audioBytes");
      assert.equal(digest(preview.bytes), enhanced.sha256);
      const transmission = await call("prepareTransmission");
      assert.equal(digest(transmission.bytes), transmission.artifact.sha256);
      await call("consent", {
        artifactId: transmission.artifact.id,
        sha256: transmission.artifact.sha256,
        duration: transmission.duration,
        approved: true,
      });
      const consent = await fetch(apiOrigin + "/v1/consents", {
        method: "POST",
        headers: { ...headers, "Content-Type": "application/json" },
        body: JSON.stringify({
          artifactHash: transmission.artifact.sha256,
          duration: transmission.duration,
          approved: true,
          provider: "vosk-local",
        }),
        signal: AbortSignal.timeout(90000),
      });
      assert.equal(consent.status, 200);
      const consentBody = await consent.json();
      const response = await fetch(apiOrigin + "/v1/transcriptions", {
        method: "POST",
        headers: {
          ...headers,
          "content-type": "audio/wav",
          "x-consent-id": consentBody.consentId,
        },
        body: transmission.bytes,
        signal: AbortSignal.timeout(90000),
      });
      assert.equal(response.status, 200);
      s = await call("providerResult", {
        bytes: new Uint8Array(await response.arrayBuffer()),
        transmissionId: transmission.artifact.id,
        transmissionHash: transmission.artifact.sha256,
      });
      assert.ok(
        s.transcript.segments.length,
        "Real local provider returned speech",
      );
      const text = s.transcript.segments.map((x) => x.text).join(" ");
      const wer = errorRate(provenance.referenceTranscript, text);
      assert.ok(wer <= 0.25, "Controlled fixture WER exceeded 25%");
      assert.ok(
        s.transcript.segments.every(
          (x) =>
            (x.start >= 0 &&
              x.end <= s.audio.duration &&
              x.confidence === null) ||
            x.confidence?.semantics === "decoder score",
        ),
      );
      assert.equal(
        s.transcript.diarization.supported,
        false,
        "Diarization must not be fabricated",
      );
      const ctm = await call("export", { format: "ctm" });
      await writeFile(`${output}/run-${run}.ctm`, ctm.bytes);
      const first = s.transcript.segments[0];
      s = await call("review", {
        changes: [
          { id: first.id, text: first.text + " [synthetic QA revision]" },
        ],
        verified: false,
      });
      assert.equal(s.transcript.state, "HUMAN_EDITED");
      for (const format of [
        "wav",
        "json",
        "txt",
        "html",
        "csv",
        "srt",
        "vtt",
        "docx",
        "xlsx",
        "pdf",
        "history",
      ]) {
        const file = await call("export", { format, fontBase64 });
        assert.equal(digest(file.bytes), file.sha256);
        await writeFile(`${output}/run-${run}.${format}`, file.bytes);
      }
      const exported = await call("export", {
        format: "case",
        passphrase: "synthetic-QA-package-password",
      });
      const packagePath = `${output}/run-${run}.echocase.enc`;
      await writeFile(packagePath, exported.bytes);
      const imported = await call("importPackage", {
        bytes: exported.bytes,
        passphrase: "synthetic-QA-package-password",
        actor: "SYNTHETIC_QA_IMPORTER",
      });
      assert.equal(imported.verification.valid, true);
      const verifier = spawnSync(
        process.execPath,
        [
          "tools/case-verify/index.mjs",
          packagePath,
          "--sha256",
          exported.sha256,
        ],
        {
          env: {
            ...process.env,
            ECHOSCRIPT_PACKAGE_PASSPHRASE: "synthetic-QA-package-password",
          },
          encoding: "utf8",
          timeout: 30000,
        },
      );
      assert.equal(verifier.status, 0, verifier.stderr);
      const verification = JSON.parse(verifier.stdout);
      assert.equal(verification.result, "PASS");
      for (const ext of ["pdf"]) {
        const check = spawnSync("pdfinfo", [`${output}/run-${run}.${ext}`], {
          encoding: "utf8",
          timeout: 10000,
        });
        if (check.error?.code !== "ENOENT")
          assert.equal(check.status, 0, check.stderr);
      }
      const native = spawnSync(
        process.env.DOC_PYTHON || "python3",
        [
          "-c",
          "import sys;from docx import Document;from openpyxl import load_workbook;from pypdf import PdfReader;assert len(Document(sys.argv[1]).paragraphs)>1;assert load_workbook(sys.argv[2]).active.max_row>1;assert len(PdfReader(sys.argv[3],strict=True).pages)>0",
          `${output}/run-${run}.docx`,
          `${output}/run-${run}.xlsx`,
          `${output}/run-${run}.pdf`,
        ],
        { encoding: "utf8", timeout: 10000 },
      );
      assert.equal(
        native.status,
        0,
        native.stderr || "Native document validation toolchain is required",
      );
      consecutive++;
      const result = {
        run,
        result: "PASS",
        consecutive,
        durationMs: Date.now() - started,
        wordErrorRate: wer,
        sourceHash: provenance.sha256,
        renderedHash: enhanced.sha256,
        packageHash: exported.sha256,
        scope: "LOCAL_WEB_ENGINE_WITH_REAL_VOSK_API",
        transport: "REAL_LOOPBACK_HTTP",
        diarization: "NOT_IMPLEMENTED",
        androidDeviceWorkflow: "NOT_EXECUTED",
        rssBytes: process.memoryUsage().rss,
      };
      results.push(result);
      process.stdout.write(JSON.stringify(result) + "\n");
    } catch (error) {
      consecutive = 0;
      results.push({ run, result: "FAIL", consecutive, error: error.message });
      throw error;
    } finally {
      for (const job of jobs.values()) clearTimeout(job.timeout);
      await worker.terminate();
      globalThis.gc?.();
      const memory = process.memoryUsage();
      const descriptors =
        baselineFd === null ? null : (await readdir("/proc/self/fd")).length;
      resourceSamples.push({
        run,
        heapUsed: memory.heapUsed,
        rss: memory.rss,
        descriptors,
      });
    }
  }
  assert.equal(
    new Set(renderedHashes).size,
    1,
    "Deterministic enhanced renders differ between runs",
  );
  assert.ok(
    resourceSamples.every((s) => s.heapUsed <= 64 * 1024 ** 2),
    "Post-worker heap exceeds the 64 MiB QA threshold",
  );
  assert.ok(
    resourceSamples.every((s) => s.rss <= 512 * 1024 ** 2),
    "Process RSS exceeds the 512 MiB QA threshold",
  );
  if (baselineFd !== null)
    assert.ok(
      resourceSamples.every((s) => s.descriptors <= baselineFd + 4),
      "File descriptor growth exceeds QA threshold",
    );
} catch (error) {
  process.stderr.write("Local workflow failed: " + error.message + "\n");
  process.exitCode = 1;
} finally {
  await app.close();
  await writeFile(
    `${output}/results.json`,
    JSON.stringify(
      {
        scope: "LOCAL_WEB_ENGINE_WITH_REAL_VOSK_API",
        consecutive,
        results,
        resourceSamples,
        resourceThresholds: {
          heapMiB: 64,
          rssMiB: 512,
          descriptorsAboveBaseline: 4,
        },
        fullApplicationBetaGate: "NOT PASSED",
        remainingMandatory: [
          "Android/device full workflow",
          "speaker diarization",
          "large cases and recovery",
          "qualified external provider path when configured",
          "complete authentication/sync and security release review",
        ],
      },
      null,
      2,
    ) + "\n",
  );
}
