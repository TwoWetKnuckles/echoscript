import { test } from "node:test";
import assert from "node:assert/strict";
import { randomBytes } from "node:crypto";
import { buildServer } from "../build/services/api/src/server.js";
import { toneBytes } from "./support.mjs";
import { digest } from "../packages/evidence/index.mjs";
const token = randomBytes(32).toString("hex"),
  headers = { authorization: `Bearer ${token}` };
function server(extra = {}) {
  return buildServer({
    token,
    python: "python3",
    modelPath: "/missing-test-model",
    ...extra,
  });
}
test("provider authentication, consent/hash binding and replay policy fail closed", async () => {
  const app = server();
  try {
    const payload = toneBytes(),
      artifactHash = digest(payload);
    assert.equal(
      (await app.inject({ method: "POST", url: "/v1/consents", payload: {} }))
        .statusCode,
      401,
    );
    assert.equal(
      (
        await app.inject({
          method: "POST",
          url: "/v1/consents",
          headers,
          payload: {
            artifactHash,
            duration: 1,
            approved: false,
            provider: "vosk-local",
          },
        })
      ).statusCode,
      400,
    );
    const c = await app.inject({
      method: "POST",
      url: "/v1/consents",
      headers,
      payload: {
        artifactHash,
        duration: 1,
        approved: true,
        provider: "vosk-local",
      },
    });
    assert.equal(c.statusCode, 200);
    const id = c.json().consentId;
    const changed = payload.slice();
    changed[44] ^= 1;
    assert.equal(
      (
        await app.inject({
          method: "POST",
          url: "/v1/transcriptions",
          headers: {
            ...headers,
            "content-type": "audio/wav",
            "x-consent-id": id,
          },
          payload: Buffer.from(changed),
        })
      ).statusCode,
      400,
    );
    assert.equal(
      (
        await app.inject({
          method: "POST",
          url: "/v1/transcriptions",
          headers: { ...headers, "content-type": "audio/wav" },
          payload: Buffer.from(payload),
        })
      ).statusCode,
      403,
    );
  } finally {
    await app.close();
  }
});
test("air-gap policy denies submissions and origins are restricted", async () => {
  const app = server({
    providerEnabled: false,
    allowedOrigin: "http://localhost:4173",
  });
  try {
    assert.equal(
      (
        await app.inject({
          url: "/health",
          headers: { origin: "https://unapproved.invalid" },
        })
      ).statusCode,
      403,
    );
    assert.equal(
      (
        await app.inject({
          method: "POST",
          url: "/v1/consents",
          headers,
          payload: {
            artifactHash: "0".repeat(64),
            duration: 1,
            approved: true,
            provider: "vosk-local",
          },
        })
      ).statusCode,
      403,
    );
  } finally {
    await app.close();
  }
});
test("missing provider executable returns bounded failure without transcript leakage", async () => {
  const app = server({ python: "/does-not-exist" });
  try {
    const payload = toneBytes();
    const c = await app.inject({
      method: "POST",
      url: "/v1/consents",
      headers,
      payload: {
        artifactHash: digest(payload),
        duration: 1,
        approved: true,
        provider: "vosk-local",
      },
    });
    const h = {
      ...headers,
      "content-type": "audio/wav",
      "x-consent-id": c.json().consentId,
    };
    const r = await app.inject({
      method: "POST",
      url: "/v1/transcriptions",
      headers: h,
      payload: Buffer.from(payload),
    });
    assert.equal(r.statusCode, 502);
    assert.equal(r.json().error, "PROVIDER_UNAVAILABLE");
    assert.equal(
      (
        await app.inject({
          method: "POST",
          url: "/v1/transcriptions",
          headers: h,
          payload: Buffer.from(payload),
        })
      ).statusCode,
      403,
    );
  } finally {
    await app.close();
  }
});

test("global provider disable blocks Whisper even when a server key is configured", async () => {
  let requests = 0;
  const app = server({ providerEnabled: false, openAiEnabled: true, openAiApiKey: "synthetic",
    openAiFetch: async () => { requests++; throw Error("Must not be called"); } });
  try {
    const response = await app.inject({ method: "POST", url: "/v1/transcriptions/openai-whisper",
      headers: { ...headers, "content-type": "audio/wav" }, payload: Buffer.from(toneBytes()) });
    assert.equal(response.statusCode, 403); assert.equal(requests, 0);
  } finally { await app.close(); }
});

test("OpenAI transmission requires explicit model consent, exact hash, and one-use authorization", async () => {
  let requests = 0;
  const app = server({ openAiEnabled: true, openAiApiKey: "synthetic-secret", openAiFetch: async (url, options) => {
    requests++;
    assert.equal(url, "https://api.openai.com/v1/audio/transcriptions");
    assert.equal(options.redirect, "error");
    assert.equal(options.body.get("model"), "whisper-1");
    assert.deepEqual(options.body.getAll("timestamp_granularities[]"), ["word", "segment"]);
    return new Response(JSON.stringify({ text: "synthetic example", words: [] }), {status: 200});
  }});
  const payload=Buffer.from(toneBytes());
  const create = (extra={})=>app.inject({method:"POST",url:"/v1/consents",headers,payload:{artifactHash:digest(payload),duration:1,approved:true,provider:"openai-whisper",model:"whisper-1",acknowledgedExternal:true,...extra}});
  try {
    assert.equal((await create({acknowledgedExternal:false})).statusCode,403);
    assert.equal((await create({model:"other"})).statusCode,403);
    const consent=(await create()).json().consentId;
    const h={...headers,"content-type":"audio/wav","x-consent-id":consent};
    const altered=Buffer.from(payload);altered[44]^=1;
    assert.equal((await app.inject({method:"POST",url:"/v1/transcriptions/openai-whisper",headers:h,payload:altered})).statusCode,400);
    assert.equal(requests,0);
    assert.equal((await app.inject({method:"POST",url:"/v1/transcriptions",headers:h,payload})).statusCode,403);
    const response=await app.inject({method:"POST",url:"/v1/transcriptions/openai-whisper",headers:h,payload});
    assert.equal(response.statusCode,200);assert.equal(response.json().text,"synthetic example");
    assert.equal((await app.inject({method:"POST",url:"/v1/transcriptions/openai-whisper",headers:h,payload})).statusCode,403);
    assert.equal(requests,1);
  } finally { await app.close(); }
});
