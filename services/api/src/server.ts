import Fastify from "fastify";
import { randomUUID, timingSafeEqual, createHash } from "node:crypto";
import { spawn } from "node:child_process";
import { fileURLToPath } from "node:url";
type Consent = {
  artifactHash: string;
  duration: number;
  expires: number;
  provider: "vosk-local" | "openai-whisper";
};
export type ServerOptions = {
  token: string;
  python: string;
  modelPath: string;
  allowedOrigin?: string;
  providerEnabled?: boolean;
  timeoutMs?: number;
  workerPath?: string;
  // External processing is opt-in. This key remains on the server only.
  openAiApiKey?: string;
  openAiEnabled?: boolean;
  openAiFetch?: typeof fetch;
};
export function buildServer(options: ServerOptions) {
  if (options.token.length < 32)
    throw Error("API_TOKEN must be at least 32 characters");
  const app = Fastify({
    logger: false,
    bodyLimit: 24 * 1024 * 1024,
    requestTimeout: 90000,
    connectionTimeout: 10000,
  });
  const consents = new Map<string, Consent>();
  let running = false;
  app.addContentTypeParser(
    "audio/wav",
    { parseAs: "buffer" },
    (_req, body, done) => done(null, body),
  );
  app.addHook("onRequest", async (req, reply) => {
    reply
      .header("Cache-Control", "no-store")
      .header("X-Content-Type-Options", "nosniff");
    const origin = req.headers.origin;
    if (origin) {
      if (origin !== options.allowedOrigin)
        return reply.code(403).send({ error: "ORIGIN_DENIED" });
      reply
        .header("Access-Control-Allow-Origin", origin)
        .header("Vary", "Origin");
    }
    if (req.method === "OPTIONS") {
      reply
        .header(
          "Access-Control-Allow-Headers",
          "Authorization, Content-Type, X-Consent-ID",
        )
        .header("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
      return reply.code(204).send();
    }
    if (req.url === "/health") return;
    const supplied = req.headers.authorization?.startsWith("Bearer ")
      ? req.headers.authorization.slice(7)
      : "";
    const a = Buffer.from(supplied),
      b = Buffer.from(options.token);
    if (a.length !== b.length || !timingSafeEqual(a, b))
      return reply.code(401).send({ error: "UNAUTHORIZED" });
  });
  app.get("/health", async () => ({
    status: "ok",
    provider: "vosk-local",
    externalProvidersEnabled: options.openAiEnabled === true && !!options.openAiApiKey,
    retention: "No evidence files written; bounded in-memory local processing",
    diarization: false,
    wordTimestamps: true,
    confidence: "provider-returned scores; not calibrated probabilities",
    nBest: true,
    providerEnabled: options.providerEnabled !== false,
  }));
  app.post<{
    Body: {
      artifactHash: string;
      duration: number;
      approved: boolean;
      provider: string;
      model?: string;
      acknowledgedExternal?: boolean;
    };
  }>(
    "/v1/consents",
    {
      schema: {
        body: {
          type: "object",
          additionalProperties: false,
          required: ["artifactHash", "duration", "approved", "provider"],
          properties: {
            artifactHash: { type: "string", pattern: "^[a-f0-9]{64}$" },
            duration: { type: "number", exclusiveMinimum: 0, maximum: 750 },
            approved: { const: true },
            provider: { enum: ["vosk-local", "openai-whisper"] },
            model: { type: "string", maxLength: 64 },
            acknowledgedExternal: { type: "boolean" },
          },
        },
      },
    },
    async (req, reply) => {
      if (options.providerEnabled === false)
        return reply.code(403).send({ error: "PROVIDER_DISABLED" });
      if (req.body.provider === "openai-whisper" &&
          (!options.openAiEnabled || !options.openAiApiKey || req.body.model !== "whisper-1" || req.body.acknowledgedExternal !== true))
        return reply.code(403).send({ error: "EXTERNAL_PROVIDER_DISABLED_OR_NOT_ACKNOWLEDGED" });
      for (const [key, c] of consents)
        if (c.expires < Date.now()) consents.delete(key);
      if (consents.size >= 1000)
        return reply.code(429).send({ error: "CONSENT_LIMIT" });
      const consentId = randomUUID();
      consents.set(consentId, {
        artifactHash: req.body.artifactHash,
        duration: req.body.duration,
        provider: req.body.provider as Consent["provider"],
        expires: Date.now() + 5 * 60 * 1000,
      });
      return { consentId, expiresInSeconds: 300 };
    },
  );
  app.post<{ Body: Buffer }>("/v1/transcriptions", async (req, reply) => {
    if (options.providerEnabled === false)
      return reply.code(403).send({ error: "PROVIDER_DISABLED" });
    const consentId = req.headers["x-consent-id"];
    const c =
      typeof consentId === "string" ? consents.get(consentId) : undefined;
    if (!c || c.provider !== "vosk-local" || c.expires < Date.now())
      return reply.code(403).send({ error: "CONSENT_REQUIRED_OR_EXPIRED" });
    if (!Buffer.isBuffer(req.body) || req.body.length < 44)
      return reply.code(400).send({ error: "INVALID_AUDIO" });
    if (createHash("sha256").update(req.body).digest("hex") !== c.artifactHash)
      return reply.code(400).send({ error: "TRANSMISSION_HASH_MISMATCH" });
    const b = req.body;
    if (
      b.toString("ascii", 0, 4) !== "RIFF" ||
      b.toString("ascii", 8, 12) !== "WAVE" ||
      b.toString("ascii", 36, 40) !== "data" ||
      b.readUInt16LE(20) !== 1 ||
      b.readUInt16LE(22) !== 1 ||
      b.readUInt32LE(24) !== 16000 ||
      b.readUInt16LE(34) !== 16 ||
      b.readUInt32LE(40) !== b.length - 44 ||
      Math.abs((b.length - 44) / 32000 - c.duration) > 0.001
    )
      return reply.code(400).send({ error: "CANONICAL_MONO_PCM16_REQUIRED" });
    if (running)
      return reply
        .code(429)
        .header("Retry-After", "2")
        .send({ error: "LOCAL_PROVIDER_BUSY" });
    consents.delete(consentId as string);
    running = true;
    try {
      const raw = await new Promise<Buffer>((resolve, reject) => {
        const child = spawn(
          options.python,
          [
            options.workerPath ||
              fileURLToPath(
                new URL(
                  "../../../../services/api/vosk_worker.py",
                  import.meta.url,
                ),
              ),
          ],
          {
            env: { PATH: process.env.PATH, VOSK_MODEL_PATH: options.modelPath },
            stdio: ["pipe", "pipe", "pipe"],
            shell: false,
          },
        );
        const chunks: Buffer[] = [];
        let length = 0,
          done = false;
        const finish = (error?: Error) => {
          if (done) return;
          done = true;
          clearTimeout(timer);
          if (error) {
            child.kill("SIGKILL");
            reject(error);
          } else resolve(Buffer.concat(chunks));
        };
        const timer = setTimeout(
          () => finish(Error("PROVIDER_TIMEOUT")),
          options.timeoutMs ?? 60000,
        );
        req.raw.once("aborted", () => finish(Error("REQUEST_CANCELLED")));
        child.stdout.on("data", (chunk: Buffer) => {
          length += chunk.length;
          if (length > 8 * 1024 * 1024)
            finish(Error("PROVIDER_RESPONSE_TOO_LARGE"));
          else chunks.push(chunk);
        });
        child.stderr.resume();
        child.stdin.on("error", () => finish(Error("PROVIDER_INPUT_FAILED")));
        child.on("error", () => finish(Error("PROVIDER_UNAVAILABLE")));
        child.on("close", (code) =>
          finish(code === 0 ? undefined : Error("PROVIDER_FAILED")),
        );
        child.stdin.end(req.body);
      });
      try {
        const parsed = JSON.parse(raw.toString("utf8"));
        if (!Array.isArray(parsed.results)) throw Error();
      } catch {
        return reply.code(502).send({ error: "PROVIDER_SCHEMA_MISMATCH" });
      }
      return reply.type("application/json").send(raw);
    } catch (error) {
      const code = error instanceof Error ? error.message : "PROVIDER_FAILED";
      return reply
        .code(code === "PROVIDER_TIMEOUT" ? 504 : 502)
        .send({ error: code });
    } finally {
      running = false;
    }
  });
  // External endpoint accepts only a separately approved canonical transmission.
  app.post<{ Body: Buffer }>("/v1/transcriptions/openai-whisper", async (req, reply) => {
    if (options.providerEnabled === false || !options.openAiEnabled || !options.openAiApiKey) return reply.code(403).send({ error: "EXTERNAL_PROVIDER_DISABLED" });
    const consentId = req.headers["x-consent-id"];
    const c = typeof consentId === "string" ? consents.get(consentId) : undefined;
    if (!c || c.provider !== "openai-whisper" || c.expires < Date.now()) return reply.code(403).send({ error: "CONSENT_REQUIRED_OR_EXPIRED" });
    const b = req.body;
    if (!Buffer.isBuffer(b) || b.length < 44 || b.length > 24 * 1024 * 1024) return reply.code(400).send({ error: "INVALID_AUDIO" });
    if (createHash("sha256").update(b).digest("hex") !== c.artifactHash) return reply.code(400).send({ error: "TRANSMISSION_HASH_MISMATCH" });
    if (b.toString("ascii",0,4)!=="RIFF" || b.toString("ascii",8,12)!=="WAVE" || b.toString("ascii",36,40)!=="data" || b.readUInt16LE(20)!==1 || b.readUInt16LE(22)!==1 || b.readUInt32LE(24)!==16000 || b.readUInt16LE(34)!==16 || b.readUInt32LE(40)!==b.length-44 || Math.abs((b.length-44)/32000-c.duration)>.001)
      return reply.code(400).send({ error: "CANONICAL_MONO_PCM16_REQUIRED" });
    if (running) return reply.code(429).send({ error: "PROVIDER_BUSY" });
    consents.delete(consentId as string); running = true;
    const controller = new AbortController();
    const timer = setTimeout(() => controller.abort(), options.timeoutMs ?? 60000);
    req.raw.once("aborted", () => controller.abort());
    reply.raw.once("close", () => { if (!reply.raw.writableEnded) controller.abort(); });
    try {
      const form = new FormData();
      form.append("file", new Blob([new Uint8Array(b).buffer], { type: "audio/wav" }), "transmission.wav");
      form.append("model", "whisper-1"); form.append("response_format", "verbose_json");
      form.append("timestamp_granularities[]", "word");
      form.append("timestamp_granularities[]", "segment");
      const response = await (options.openAiFetch ?? fetch)("https://api.openai.com/v1/audio/transcriptions", {
        method: "POST", redirect: "error", signal: controller.signal,
        headers: { Authorization: `Bearer ${options.openAiApiKey}` }, body: form,
      });
      if (!response.ok || !response.body) return reply.code(502).send({ error: "OPENAI_REQUEST_FAILED", providerStatus: response.status });
      const reader = response.body.getReader(); const chunks: Uint8Array[] = []; let length = 0;
      while (true) {
        const { done, value } = await reader.read(); if (done) break;
        length += value.byteLength;
        if (length > 8 * 1024 * 1024) { await reader.cancel(); return reply.code(502).send({ error: "PROVIDER_RESPONSE_TOO_LARGE" }); }
        chunks.push(value);
      }
      const raw = Buffer.concat(chunks); let parsed: unknown;
      try { parsed = JSON.parse(raw.toString("utf8")); } catch { return reply.code(502).send({ error: "PROVIDER_SCHEMA_MISMATCH" }); }
      if (!parsed || typeof parsed !== "object" || !("text" in parsed) || typeof parsed.text !== "string") return reply.code(502).send({ error: "PROVIDER_SCHEMA_MISMATCH" });
      return reply.type("application/json").send(raw);
    } catch {
      return reply.code(controller.signal.aborted ? 504 : 502).send({ error: controller.signal.aborted ? "PROVIDER_TIMEOUT_OR_CANCELLED" : "PROVIDER_UNAVAILABLE" });
    } finally { clearTimeout(timer); running = false; }
  });
  return app;
}
