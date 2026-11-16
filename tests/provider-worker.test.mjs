import { test } from "node:test";
import assert from "node:assert/strict";
import { Worker } from "node:worker_threads";
import { readFile } from "node:fs/promises";
import { digest, jsonBytes } from "../packages/evidence/index.mjs";

test(
  "worker keeps Vosk default and binds Whisper consent/results to the selected audio",
  { timeout: 30000 },
  async () => {
    const worker = new Worker(
      new URL("../tools/worker-node.mjs", import.meta.url),
    );
    let sequence = 0;
    const pending = new Map();
    worker.on("message", (message) => {
      const request = pending.get(message.requestId);
      if (!request) return;
      pending.delete(message.requestId);
      clearTimeout(request.timeout);
      message.error
        ? request.reject(Error(message.error))
        : request.resolve(message.result);
    });
    worker.on("error", (error) => {
      for (const request of pending.values()) {
        clearTimeout(request.timeout);
        request.reject(error);
      }
      pending.clear();
    });
    const call = (action, data = {}) =>
      new Promise((resolve, reject) => {
        const requestId = ++sequence;
        const timeout = setTimeout(() => {
          pending.delete(requestId);
          reject(Error(`Worker timeout: ${action}`));
        }, 5000);
        pending.set(requestId, { resolve, reject, timeout });
        worker.postMessage({ requestId, action, data });
      });
    try {
      const bytes = new Uint8Array(
        await readFile("specs/fixtures/synthetic-speech.wav"),
      );
      let state = await call("import", {
        bytes,
        name: "SYNTHETIC.wav",
        title: "Synthetic route contract",
        actor: "SYNTHETIC_QA",
      });
      const local = await call("prepareTransmission");
      assert.equal(local.artifact.operation.parameters.provider, "vosk-local");
      await call("consent", {
        artifactId: local.artifact.id,
        sha256: local.artifact.sha256,
        duration: local.duration,
        approved: true,
      });
      state = await call("providerResult", {
        transmissionId: local.artifact.id,
        transmissionHash: local.artifact.sha256,
        bytes: jsonBytes({
          results: [
            {
              text: "synthetic",
              result: [{ word: "synthetic", start: 0.2, end: 0.6, conf: 0.8 }],
            },
          ],
        }),
      });
      assert.equal(state.transcript.provider, "vosk-local");
      const external = await call("prepareTransmission", {
        provider: "openai-whisper",
      });
      const approval = {
        artifactId: external.artifact.id,
        sha256: external.artifact.sha256,
        duration: external.duration,
        approved: true,
      };
      await assert.rejects(
        call("consent", { ...approval, provider: "vosk-local" }),
        /Transmission changed/,
      );
      await call("consent", { ...approval, provider: "openai-whisper" });
      const result = {
        transmissionId: external.artifact.id,
        transmissionHash: external.artifact.sha256,
        bytes: jsonBytes({
          text: "synthetic",
          language: "en",
          segments: [{ text: "synthetic", start: 0.2, end: 0.6 }],
          words: [{ word: "synthetic", start: 0.2, end: 0.6 }],
        }),
      };
      await assert.rejects(
        call("providerResult", { ...result, provider: "vosk-local" }),
        /binding mismatch/,
      );
      state = await call("providerResult", {
        ...result,
        provider: "openai-whisper",
      });
      assert.equal(state.transcript.provider, "openai-whisper");
      assert.equal(state.transcript.segments[0].confidence, null);
      assert.equal(
        state.manifest.artifacts.find((a) => a.type === "SOURCE").sha256,
        digest(bytes),
      );
      await call("render", {
        graph: [{ type: "gain", gainDb: 1 }],
        selections: [],
      });
      await assert.rejects(
        call("consent", { ...approval, provider: "openai-whisper" }),
        /Transmission changed/,
      );
      await assert.rejects(
        call("providerResult", { ...result, provider: "openai-whisper" }),
        /binding mismatch/,
      );
      await assert.rejects(
        call("prepareTransmission", { provider: "unsupported" }),
        /supported transcription provider/,
      );
    } finally {
      await worker.terminate();
      for (const request of pending.values()) clearTimeout(request.timeout);
    }
  },
);
