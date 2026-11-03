import { parentPort } from "node:worker_threads";
globalThis.self = { postMessage: (message) => parentPort.postMessage(message) };
await import("../apps/web/src/processing.worker.mjs");
parentPort.on("message", (data) => globalThis.self.onmessage({ data }));
