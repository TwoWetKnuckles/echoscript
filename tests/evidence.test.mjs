import { test } from "node:test";
import assert from "node:assert/strict";
import {
  EvidenceCase,
  digest,
  canonical,
  verifyAudit,
  verifyManifest,
  inspectZip,
  encryptPackage,
  decryptPackage,
} from "../packages/evidence/index.mjs";
import { zipSync, unzipSync, strToU8 } from "fflate";
const source = new TextEncoder().encode("SYNTHETIC TEST DATA — NOT EVIDENCE");
const operation = {
  algorithm: "synthetic-fixture",
  version: "1",
  parameters: {},
};
function fixture() {
  const c = new EvidenceCase({
    title: "Synthetic test",
    actor: "TEST_EXAMINER",
  });
  const a = c.add(source, { type: "SOURCE", name: "synthetic.txt", operation });
  return { c, a };
}
test("SHA-256 and RFC8785 canonical ordering vectors", () => {
  assert.equal(
    digest(new TextEncoder().encode("abc")),
    "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
  );
  assert.equal(
    canonical({ z: 1, a: [1e30, 0.000001] }),
    '{"a":[1e+30,0.000001],"z":1}',
  );
  assert.throws(() => canonical({ a: NaN }));
  assert.throws(() => canonical("\ud800"));
});
test("source and returned byte buffers cannot mutate preserved original", () => {
  const { c, a } = fixture();
  const copy = c.bytes(a.id);
  copy[0] ^= 1;
  assert.equal(digest(c.bytes(a.id)), a.sha256);
  assert.throws(() => c.add(source, { type: "SOURCE", operation }));
  const m = c.manifest;
  m.artifacts[0].sha256 = "0".repeat(64);
  assert.equal(c.verify().valid, true);
});
test("undo, redo and historical branching preserve the full graph and audit", () => {
  const { c, a } = fixture();
  const d = c.add(source, { type: "ANALYSIS", parents: [a.id], operation });
  c.activate(a.id, "UNDO");
  c.activate(d.id, "REDO");
  c.activate(a.id, "UNDO");
  c.add(source, { type: "ANALYSIS", parents: [a.id], operation });
  assert.equal(c.manifest.artifacts.length, 3);
  assert.equal(c.verify().valid, true);
});
test("case round trip verifies source, parent linkage, hashes and chained audit", () => {
  const { c, a } = fixture();
  c.add(source, { type: "ANALYSIS", parents: [a.id], operation });
  const bytes = c.exportPackage();
  const restored = EvidenceCase.importPackage(bytes, {
    actor: "TEST_IMPORTER",
  });
  assert.equal(restored.verify().valid, true);
  assert.deepEqual(restored.bytes(a.id), source);
  assert.equal(restored.manifest.audit.at(-1).action, "IMPORT");
});
test("source tamper, audit tamper, missing parent and sidecar changes fail closed", () => {
  const { c } = fixture();
  const pkg = c.exportPackage();
  const files = unzipSync(pkg),
    m = c.manifest;
  files[m.artifacts[0].path][0] ^= 1;
  assert.throws(
    () => EvidenceCase.importPackage(zipSync(files, { level: 0 })),
    /modified/,
  );
  const audit = c.manifest.audit;
  audit[0].details = { forged: true };
  assert.throws(() => verifyAudit(audit));
  const changed = c.manifest;
  changed.artifacts[0].parents = ["missing"];
  assert.throws(() => verifyManifest(changed, new Map()));
});
test("unsafe paths, compression bomb and truncated ZIP fail before extraction", () => {
  assert.throws(
    () =>
      inspectZip(
        zipSync({
          "../x": source,
          "manifest.json": strToU8("{}"),
          "README.txt": source,
        }),
      ),
    /Unsafe/,
  );
  assert.throws(
    () =>
      inspectZip(
        zipSync({
          "artifacts/x": new Uint8Array(2 * 1024 * 1024),
          "manifest.json": strToU8("{}"),
          "README.txt": source,
        }),
      ),
    /Decompression/,
  );
  const { c } = fixture();
  assert.throws(() =>
    EvidenceCase.importPackage(c.exportPackage().slice(0, -1)),
  );
});
test("AES-GCM package authentication rejects tampering and wrong password", async () => {
  const { c } = fixture();
  const bytes = c.exportPackage();
  const sealed = await encryptPackage(bytes, "synthetic-test-passphrase");
  assert.deepEqual(
    await decryptPackage(sealed, "synthetic-test-passphrase"),
    bytes,
  );
  await assert.rejects(
    decryptPackage(sealed, "incorrect-passphrase"),
    /Authentication/,
  );
  sealed[sealed.length - 1] ^= 1;
  await assert.rejects(
    decryptPackage(sealed, "synthetic-test-passphrase"),
    /Authentication/,
  );
});
