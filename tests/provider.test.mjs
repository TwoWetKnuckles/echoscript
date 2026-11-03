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
