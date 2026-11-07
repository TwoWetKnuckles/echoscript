import { test } from "node:test";
import assert from "node:assert/strict";
import { generateKeyPairSync, sign } from "node:crypto";
import { strToU8, unzipSync, zipSync } from "fflate";
import {
  EvidenceCase,
  digest,
  canonical,
} from "../packages/evidence/index.mjs";
import { verifyZipBuffer } from "../tools/case-verify/streaming.mjs";
import { CHECKPOINT_ALGORITHM } from "../tools/case-verify/checkpoints.mjs";

function fixture(curve = "prime256v1") {
  const c = new EvidenceCase({
    title: "SYNTHETIC TEST DATA — NOT EVIDENCE",
    actor: "TEST",
  });
  const source = c.add(strToU8("SYNTHETIC source, not evidence"), {
    type: "SOURCE",
    operation: { algorithm: "synthetic", version: "1", parameters: {} },
  });
  const { privateKey, publicKey } = generateKeyPairSync("ec", {
    namedCurve: curve,
  });
  const spki = publicKey.export({ format: "der", type: "spki" });
  const fingerprint = digest(spki),
    snapshot = c.manifest;
  const payload = {
    algorithm: "ECDSA_P256_SHA256",
    actor: "SYNTHETIC_EXAMINER",
    createdAtUtc: new Date().toISOString(),
    caseId: snapshot.caseId,
    keySha256: fingerprint,
    snapshotSha256: digest(strToU8(canonical(snapshot))),
    snapshot,
  };
  const envelope = {
    schema: "org.echoscript.checkpoint/1",
    payload,
    publicKeySpki: spki.toString("base64"),
    signature: sign(
      "sha256",
      Buffer.from(canonical(payload)),
      privateKey,
    ).toString("base64"),
  };
  const checkpoint = c.add(strToU8(canonical(envelope)), {
    type: "REPORT",
    parents: [source.id],
    name: "Synthetic signed checkpoint.json",
    operation: {
      algorithm: CHECKPOINT_ALGORITHM,
      version: "1",
      parameters: { keySha256: fingerprint },
    },
  });
  return { c, source, checkpoint, fingerprint };
}
function rewrite(bytes, change) {
  const files = unzipSync(bytes),
    manifest = JSON.parse(new TextDecoder().decode(files["manifest.json"]));
  change(files, manifest);
  // Model an adversary who also rewrites the unsigned audit tail and sidecar.
  // The signed historical prefix must still make this forgery fail.
  for (const entry of manifest.audit) {
    if (entry.action === "ARTIFACT_COMMIT")
      entry.details.sha256 = manifest.artifacts.find(
        (a) => a.id === entry.details.artifactId,
      ).sha256;
  }
  let previous = "0".repeat(64);
  for (const entry of manifest.audit) {
    entry.prevHash = previous;
    const { entryHash, ...body } = entry;
    void entryHash;
    entry.entryHash = digest(strToU8(canonical(body)));
    previous = entry.entryHash;
  }
  files["manifest.json"] = strToU8(canonical(manifest));
  files["SHA256SUMS.txt"] = strToU8(
    [
      ...manifest.artifacts.map((a) => `${a.sha256}  ${a.path}`),
      `${digest(files["manifest.json"])}  manifest.json`,
    ].join("\n") + "\n",
  );
  return zipSync(files, { level: 0 });
}
test("device checkpoint verifies independently; embedded keys do not establish trust", async () => {
  const { c, fingerprint } = fixture(),
    bytes = c.exportPackage();
  const unpinned = await verifyZipBuffer(bytes);
  assert.equal(unpinned.signatures, "VERIFIED_CHECKPOINT_PREFIXES");
  assert.equal(unpinned.trustedCheckpointCoversAllEvidence, false);
  assert.equal(unpinned.checkpoints[0].trustedExternalKey, false);
  assert.equal(unpinned.checkpoints[0].coversAllCurrentEvidence, true);
  assert.ok(unpinned.checkpoints[0].auditEntriesOutsideSignedSnapshot > 0);
  const pinned = await verifyZipBuffer(bytes, {
    trustedKeys: new Set([fingerprint]),
  });
  assert.equal(pinned.trustedCheckpointCoversAllEvidence, true);
  await assert.rejects(
    verifyZipBuffer(bytes, { trustedKeys: new Set(["0".repeat(64)]) }),
    /trusted checkpoint/,
  );
});
test("a valid checkpoint does not authenticate later evidence revisions", async () => {
  const { c, source, fingerprint } = fixture();
  c.add(strToU8("SYNTHETIC later revision"), {
    type: "ANALYSIS",
    parents: [source.id],
    operation: { algorithm: "synthetic", version: "1", parameters: {} },
  });
  const bytes = c.exportPackage(),
    result = await verifyZipBuffer(bytes);
  assert.equal(result.checkpoints[0].result, "VALID_SIGNATURE");
  assert.equal(result.checkpoints[0].coversAllCurrentEvidence, false);
  await assert.rejects(
    verifyZipBuffer(bytes, { trustedKeys: new Set([fingerprint]) }),
    /covers all current/,
  );
});
test("rewriting a signed case header plus checksum sidecar fails", async () => {
  const { c } = fixture();
  const changed = rewrite(c.exportPackage(), (_, m) => {
    m.title = "Forged signed case title";
  });
  await assert.rejects(verifyZipBuffer(changed), /Signed case header/);
});
test("changing checkpoint bytes and recomputing artifact hashes cannot forge a signature", async () => {
  const { c, checkpoint } = fixture();
  const changed = rewrite(c.exportPackage(), (files, m) => {
    const envelope = JSON.parse(
      new TextDecoder().decode(files[checkpoint.path]),
    );
    envelope.payload.actor = "Forged examiner";
    files[checkpoint.path] = strToU8(canonical(envelope));
    const a = m.artifacts.find((a) => a.id === checkpoint.id);
    a.sha256 = digest(files[a.path]);
    a.bytes = files[a.path].length;
  });
  await assert.rejects(verifyZipBuffer(changed), /signature/);
});
test("checkpoint curve, schema and canonical encoding are enforced", async () => {
  const { c } = fixture("secp384r1");
  await assert.rejects(verifyZipBuffer(c.exportPackage()), /P-256|schema/);
  const normal = fixture();
  const changed = rewrite(normal.c.exportPackage(), (files, m) => {
    files[normal.checkpoint.path] = strToU8(
      " " + new TextDecoder().decode(files[normal.checkpoint.path]),
    );
    const a = m.artifacts.find((a) => a.id === normal.checkpoint.id);
    a.sha256 = digest(files[a.path]);
    a.bytes = files[a.path].length;
  });
  await assert.rejects(verifyZipBuffer(changed), /canonical/);
});
