import { test } from "node:test";
import assert from "node:assert/strict";
import { mkdtemp, writeFile, rm } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { EvidenceCase } from "../packages/evidence/index.mjs";
import { verifyZipFile } from "../tools/case-verify/streaming.mjs";
test("independent streaming ZIP verifier accepts a case and rejects archive CRC tamper", async () => {
  const dir = await mkdtemp(join(tmpdir(), "echoscript-synthetic-"));
  try {
    const c = new EvidenceCase({
      title: "SYNTHETIC TEST DATA — NOT EVIDENCE",
      actor: "TEST",
    });
    c.add(new TextEncoder().encode("synthetic"), {
      type: "SOURCE",
      operation: { algorithm: "fixture", version: "1", parameters: {} },
    });
    const bytes = c.exportPackage(),
      p = join(dir, "case.echocase");
    await writeFile(p, bytes);
    assert.equal((await verifyZipFile(p)).valid, true);
    const changed = bytes.slice(),
      v = new DataView(changed.buffer);
    for (let i = 0; i < changed.length - 46; i++)
      if (v.getUint32(i, true) === 0x02014b50) {
        changed[i + 16] ^= 1;
        break;
      }
    await writeFile(p, changed);
    await assert.rejects(verifyZipFile(p), /CRC/);
  } finally {
    await rm(dir, { recursive: true, force: true });
  }
});
