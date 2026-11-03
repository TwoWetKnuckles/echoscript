import { test } from "node:test";
import assert from "node:assert/strict";
import { unzipSync } from "fflate";
import {
  exportTranscript,
  time,
  xml,
  createExportBundle,
} from "../packages/exports/index.mjs";
import { EvidenceCase, digest } from "../packages/evidence/index.mjs";
const t = {
  state: "AI_DRAFT",
  provider: "synthetic",
  model: "fixture",
  transmissionHash: "0".repeat(64),
  segments: [
    {
      id: "1",
      start: 1.234,
      end: 2.5,
      text: '<script>alert(1)</script> & "test"',
      speaker: null,
      confidence: null,
      alternatives: [],
      flags: [],
      verification: "UNVERIFIED",
      words: [],
    },
  ],
};
test("text formats escape hostile content and retain true uncertainty", () => {
  assert.equal(time(1.234, ","), "00:00:01,234");
  assert.equal(xml("<&"), "&lt;&amp;");
  const html = new TextDecoder().decode(exportTranscript(t, "html"));
  assert.ok(!html.includes("<script>"));
  assert.ok(html.includes("NOT AVAILABLE"));
  assert.ok(
    new TextDecoder().decode(exportTranscript(t, "vtt")).startsWith("WEBVTT"),
  );
  assert.ok(
    new TextDecoder()
      .decode(exportTranscript(t, "srt"))
      .includes("00:00:01,234 --> 00:00:02,500"),
  );
  assert.throws(() => exportTranscript(t, "ctm"), /word alignment/);
});
test("DOCX and XLSX are actual Office ZIP documents with safe inline strings", () => {
  const doc = unzipSync(exportTranscript(t, "docx"));
  assert.ok(doc["word/document.xml"]);
  const book = unzipSync(exportTranscript(t, "xlsx"));
  assert.ok(book["xl/workbook.xml"]);
  assert.ok(book["xl/worksheets/sheet1.xml"]);
  assert.ok(
    !new TextDecoder().decode(book["xl/worksheets/sheet1.xml"]).includes("<f>"),
  );
});
test("single-download bundle includes the exact derivative, complete history and independently hashed manifest", () => {
  const c = new EvidenceCase({
    title: "SYNTHETIC TEST DATA — NOT EVIDENCE",
    actor: "TEST",
  });
  const source = c.add(new Uint8Array([1, 2, 3]), {
    type: "SOURCE",
    operation: { algorithm: "synthetic", version: "1", parameters: {} },
  });
  const bytes = new Uint8Array([4, 5, 6]);
  const bundle = createExportBundle(c.manifest, {
    bytes,
    name: "enhanced.wav",
    mime: "audio/wav",
    format: "wav",
  });
  const files = unzipSync(bundle.bytes),
    keys = Object.keys(files);
  const audioPath = keys.find((k) => k.endsWith("/audio/enhanced.wav"));
  assert.deepEqual(files[audioPath], bytes);
  const manifest = JSON.parse(
    new TextDecoder().decode(
      files[keys.find((k) => k.endsWith("/manifest/export_manifest.json"))],
    ),
  );
  assert.equal(manifest.file.sha256, digest(bytes));
  assert.equal(manifest.sourceSha256, source.sha256);
  const sums = new TextDecoder().decode(
    files[keys.find((k) => k.endsWith("/hashes/SHA256SUMS.txt"))],
  );
  for (const line of sums.trim().split("\n")) {
    const [hash, path] = line.split("  ");
    assert.equal(digest(files[path]), hash);
  }
  assert.match(
    new TextDecoder().decode(files[manifest.processingHistory.path]),
    /ARTIFACT_COMMIT/,
  );
  assert.throws(
    () =>
      createExportBundle(c.manifest, {
        bytes,
        name: "../escape",
        mime: "audio/wav",
        format: "wav",
      }),
    /filename/,
  );
});
