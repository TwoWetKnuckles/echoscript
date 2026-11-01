import { test } from "node:test";
import assert from "node:assert/strict";
import { unzipSync } from "fflate";
import { exportTranscript, time, xml } from "../packages/exports/index.mjs";
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
