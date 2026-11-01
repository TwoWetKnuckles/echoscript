import { test } from "node:test";
import assert from "node:assert/strict";
import {
  hashFile,
  makeManifest,
  checkManifest,
  MAX_BYTES,
} from "../dist/core.mjs";
test("SHA-256 agrees with the published abc test vector; source remains unchanged", async () => {
  const f = new File(["abc"], "test.wav");
  const hash = await hashFile(f);
  assert.equal(
    hash,
    "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
  );
  assert.equal(await f.text(), "abc");
});
test("manifest roundtrip, renamed file and changed bytes", async () => {
  const f = new File(["abc"], "test.wav");
  const hash = await hashFile(f);
  const record = JSON.parse(
    JSON.stringify(makeManifest(f, hash, new Date("2026-10-29T00:00:00Z"))),
  );
  assert.deepEqual(checkManifest(record, f, hash), {
    match: true,
    filenameMatches: true,
  });
  assert.deepEqual(
    checkManifest(record, new File(["abc"], "renamed.wav"), hash),
    { match: true, filenameMatches: false },
  );
  assert.equal(
    checkManifest(
      record,
      new File(["abd"], "test.wav"),
      await hashFile(new File(["abd"], "test.wav")),
    ).match,
    false,
  );
  assert.equal(
    checkManifest(record, new File(["abcd"], "test.wav"), hash).match,
    false,
  );
});
test("malformed manifests fail closed", () => {
  for (const m of [
    null,
    {},
    { schema: "other" },
    { schema: "echoscript.intake.v1", artifact: { bytes: 3, sha256: "fake" } },
  ])
    assert.throws(() => checkManifest(m, { size: 3, name: "test" }, "abc"));
});
test("empty and oversized inputs rejected before reading bytes", async () => {
  await assert.rejects(hashFile(new File([], "empty.wav")));
  await assert.rejects(
    hashFile({
      size: MAX_BYTES + 1,
      arrayBuffer() {
        throw Error("must not read");
      },
    }),
    /128 MiB/,
  );
});
