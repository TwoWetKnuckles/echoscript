import { test } from "node:test";
import assert from "node:assert/strict";
import {
  EvidenceCase,
  jsonBytes,
  digest,
} from "../packages/evidence/index.mjs";
import {
  normalizeVosk,
  reviseTranscript,
  validateTranscript,
} from "../packages/evidence/transcript.mjs";
import { exportTranscript } from "../packages/exports/index.mjs";

// Provider-contract vector, deliberately not a real recognizer accuracy result.
const raw = {
  results: [
    {
      alternatives: [
        {
          text: "synthetic test",
          confidence: 417.2,
          result: [
            { word: "synthetic", start: 0.1, end: 0.6 },
            { word: "test", start: 0.7, end: 1.1 },
          ],
        },
        { text: "synthetic text", confidence: 410.1 },
      ],
    },
  ],
};
const options = {
  duration: 3,
  events: [{ start: 0, end: 2 }],
  transmissionHash: "0".repeat(64),
};
test("normalization retains genuine provider scores and alternatives without inventing confidence or speakers", () => {
  const t = normalizeVosk(raw, options),
    s = t.segments[0];
  assert.equal(s.confidence.value, 417.2);
  assert.equal(s.confidence.semantics, "decoder score");
  assert.equal(s.words[0].confidence, null);
  assert.equal(s.speaker, null);
  assert.equal(s.alternatives[0].text, "synthetic text");
  assert.equal(t.diarization.supported, false);
  const noAlts = normalizeVosk(
    {
      results: [
        {
          text: "synthetic",
          result: [{ word: "synthetic", start: 0.1, end: 1, conf: 0.3 }],
        },
      ],
    },
    options,
  );
  assert.equal(noAlts.segments[0].confidence, null);
  assert.deepEqual(noAlts.segments[0].alternatives, []);
  const invalid = structuredClone(t);
  invalid.segments[0].words[0].confidence = {
    value: 8,
    semantics: "provider confidence",
  };
  assert.throws(() => validateTranscript(invalid, 3), /confidence/);
});
test("timestamp anomalies and overlap remain review flags; subtitle and CTM exports fail instead of clamping", () => {
  const changed = structuredClone(raw);
  changed.results[0].alternatives[0].result[0].start = -0.1;
  const t = normalizeVosk(changed, options);
  assert.equal(t.segments[0].start, -0.1);
  assert.ok(t.segments[0].flags.includes("OUT_OF_RANGE"));
  assert.throws(() => exportTranscript(t, "srt"), /timestamps/);
  assert.throws(() => exportTranscript(t, "ctm"), /anomalies/);
  assert.match(new TextDecoder().decode(exportTranscript(t, "json")), /-0.1/);
  const overlap = structuredClone(t.segments[0]);
  overlap.id = "overlap";
  t.segments.push(overlap);
  validateTranscript(t, 3);
  assert.ok(t.segments[1].flags.includes("OVERLAP"));
});
test("human verification requires all segments and preserves parent bytes and word-alignment uncertainty", () => {
  const c = new EvidenceCase({
    title: "SYNTHETIC TEST DATA — NOT EVIDENCE",
    actor: "TEST",
  });
  const source = c.add(new Uint8Array([1]), {
    type: "SOURCE",
    operation: { algorithm: "synthetic", version: "1", parameters: {} },
  });
  const t = normalizeVosk(raw, options),
    second = structuredClone(t.segments[0]);
  second.id = "segment-2";
  second.start = 1.5;
  second.end = 2.5;
  second.words = [];
  t.segments.push(second);
  const parent = c.add(jsonBytes(t), {
    type: "NORMALIZED_TRANSCRIPT",
    parents: [source.id],
    operation: { algorithm: "fixture", version: "1", parameters: {} },
  });
  const before = digest(c.bytes(parent.id));
  assert.throws(
    () =>
      reviseTranscript(c, parent.id, t, [{ id: "segment-1", text: "review" }], {
        verified: true,
        duration: 3,
      }),
    /every segment/,
  );
  assert.throws(
    () =>
      reviseTranscript(
        c,
        parent.id,
        t,
        [
          { id: "segment-1", text: "review" },
          { id: "segment-1", text: "again" },
        ],
        { verified: false, duration: 3 },
      ),
    /unique/,
  );
  const next = reviseTranscript(
    c,
    parent.id,
    t,
    t.segments.map((s) => ({
      id: s.id,
      text: s.text + " [synthetic correction]",
    })),
    { verified: true, duration: 3 },
  );
  assert.equal(next.transcript.state, "HUMAN_VERIFIED");
  assert.equal(digest(c.bytes(parent.id)), before);
  assert.equal(next.transcript.segments[0].words.length, 0);
  assert.ok(
    next.transcript.segments[0].flags.includes(
      "WORD_ALIGNMENT_INVALIDATED_BY_HUMAN_EDIT",
    ),
  );
  assert.equal(c.verify().valid, true);
  assert.throws(
    () => exportTranscript(next.transcript, "ctm"),
    /word alignment/,
  );
});
