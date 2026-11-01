import { jsonBytes } from "./index.mjs";
const scoreSemantics = [
  "provider confidence",
  "decoder score",
  "model self-assessment — not calibrated",
];
function validateScore(score) {
  if (score === null) return;
  if (
    !score ||
    !Number.isFinite(score.value) ||
    !scoreSemantics.includes(score.semantics) ||
    (score.semantics === "provider confidence" &&
      (score.value < 0 || score.value > 1))
  )
    throw Error("Invalid confidence semantics");
}
const validText = (s, max) =>
  typeof s === "string" &&
  s.length <= max &&
  ![...s].some(
    (c) => c.codePointAt(0) < 32 && ![9, 10, 13].includes(c.codePointAt(0)),
  );
export function validateTranscript(t, duration) {
  if (
    t?.schema !== "org.echoscript.transcript/1" ||
    ![
      "AI_DRAFT",
      "UNDER_REVIEW",
      "HUMAN_EDITED",
      "HUMAN_VERIFIED",
      "SUPERSEDED",
    ].includes(t.state) ||
    !Array.isArray(t.segments) ||
    t.segments.length > 50000 ||
    !Number.isFinite(duration) ||
    duration <= 0 ||
    !validText(t.provider, 128) ||
    !validText(t.model, 256) ||
    !/^[a-f0-9]{64}$/.test(t.transmissionHash)
  )
    throw Error("Invalid transcript schema");
  const ids = new Set();
  let previousEnd = -Infinity;
  for (const s of t.segments) {
    if (
      !validText(s.id, 128) ||
      !s.id ||
      ids.has(s.id) ||
      !Number.isFinite(s.start) ||
      !Number.isFinite(s.end) ||
      !validText(s.text, 10000) ||
      (s.speaker !== null && !/^SPEAKER_[1-9][0-9]*$/.test(s.speaker)) ||
      !Array.isArray(s.alternatives) ||
      s.alternatives.length > 5 ||
      !Array.isArray(s.flags) ||
      s.flags.length > 64 ||
      s.flags.some((f) => !validText(f, 128)) ||
      !Array.isArray(s.words) ||
      s.words.length > 100000 ||
      !["UNVERIFIED", "HUMAN_EDITED", "HUMAN_VERIFIED"].includes(s.verification)
    )
      throw Error("Invalid transcript segment");
    ids.add(s.id);
    validateScore(s.confidence);
    for (const a of s.alternatives)
      if (
        !validText(a.text, 10000) ||
        (a.score !== null && !Number.isFinite(a.score)) ||
        a.source !== "provider-returned"
      )
        throw Error("Invalid alternative hypothesis");
    const flags = new Set(s.flags);
    if (s.start < 0 || s.end < 0 || s.end > duration || s.start > duration)
      flags.add("OUT_OF_RANGE");
    if (s.end <= s.start) flags.add("TIMESTAMP_ANOMALY");
    if (s.start < previousEnd) flags.add("OVERLAP");
    previousEnd = s.end;
    let wordEnd = -Infinity;
    for (const w of s.words) {
      if (
        !validText(w.text, 256) ||
        !w.text ||
        !Number.isFinite(w.start) ||
        !Number.isFinite(w.end)
      )
        throw Error("Invalid word alignment");
      validateScore(w.confidence);
      if (
        w.start < wordEnd ||
        w.end <= w.start ||
        w.start < s.start ||
        w.end > s.end
      )
        flags.add("TIMESTAMP_ANOMALY");
      if (w.start < 0 || w.end > duration) flags.add("OUT_OF_RANGE");
      wordEnd = w.end;
    }
    if (
      s.confidence?.semantics === "provider confidence" &&
      s.confidence.value < 0.5
    )
      flags.add("VERY_LOW_CONFIDENCE");
    s.flags = [...flags];
  }
  return t;
}
export function normalizeVosk(
  raw,
  {
    duration,
    events = [],
    transmissionHash,
    model = "vosk-model-small-en-us-0.15",
  },
) {
  if (!Array.isArray(raw?.results) || raw.results.length > 10000)
    throw Error("Malformed Vosk result");
  const segments = [];
  for (const result of raw.results) {
    if (
      !result ||
      (result.alternatives !== undefined &&
        (!Array.isArray(result.alternatives) || result.alternatives.length > 5))
    )
      throw Error("Malformed provider alternatives");
    const primary = result.alternatives?.[0] || result;
    const words = primary.result || [];
    if (!Array.isArray(words) || words.length > 100000)
      throw Error("Malformed provider word alignment");
    const text = primary.text;
    if (typeof text !== "string" || !text.trim()) continue;
    const start = words[0]?.start ?? 0,
      end = words.at(-1)?.end ?? duration;
    const flags = [];
    if (!events.some((e) => e.start < end && e.end > start))
      flags.push("LOW_ACOUSTIC_SUPPORT");
    if (!words.length) flags.push("TIMESTAMP_ANOMALY");
    if (start < 0 || end > duration) flags.push("OUT_OF_RANGE");
    const confidence = Number.isFinite(primary.confidence)
      ? { value: primary.confidence, semantics: "decoder score" }
      : null;
    segments.push({
      id: `segment-${segments.length + 1}`,
      start,
      end,
      text,
      speaker: null,
      confidence,
      alternatives: (result.alternatives || []).slice(1, 6).map((a) => ({
        text: a.text,
        score: Number.isFinite(a.confidence) ? a.confidence : null,
        source: "provider-returned",
      })),
      words: words.map((w) => ({
        text: w.word,
        start: w.start,
        end: w.end,
        confidence: Number.isFinite(w.conf)
          ? { value: w.conf, semantics: "provider confidence" }
          : null,
      })),
      flags,
      verification: "UNVERIFIED",
    });
  }
  const t = {
    schema: "org.echoscript.transcript/1",
    state: "AI_DRAFT",
    language: "en",
    provider: "vosk-local",
    model,
    transmissionHash,
    diarization: {
      supported: false,
      warning: "This Vosk adapter does not perform speaker diarization.",
    },
    segments,
    warnings: [
      "AI transcription requires independent human verification.",
      "Decoder scores are not calibrated probabilities.",
      "No provider-supported alternative hypothesis available when the alternatives list is empty.",
    ],
  };
  return validateTranscript(t, duration);
}
export function reviseTranscript(
  c,
  parentId,
  transcript,
  changes,
  { verified = false, duration },
) {
  if (
    !Array.isArray(changes) ||
    !changes.length ||
    typeof verified !== "boolean" ||
    new Set(changes.map((c) => c.id)).size !== changes.length
  )
    throw Error("Review requires unique segment changes");
  if (verified && changes.length !== transcript.segments.length)
    throw Error(
      "Full transcript verification requires explicit review of every segment",
    );
  const next = structuredClone(transcript);
  for (const change of changes) {
    const s = next.segments.find((x) => x.id === change.id);
    if (!s) throw Error("Unknown transcript segment");
    if (typeof change.text !== "string" || change.text.length > 10000)
      throw Error("Invalid correction");
    if (s.text !== change.text) {
      s.words = [];
      s.flags = [
        ...new Set([...s.flags, "WORD_ALIGNMENT_INVALIDATED_BY_HUMAN_EDIT"]),
      ];
    }
    s.text = change.text;
    s.verification = verified ? "HUMAN_VERIFIED" : "HUMAN_EDITED";
  }
  next.state = verified ? "HUMAN_VERIFIED" : "HUMAN_EDITED";
  validateTranscript(next, duration);
  const artifact = c.add(jsonBytes(next), {
    type: "HUMAN_TRANSCRIPT",
    parents: [parentId],
    name: verified ? "Human-verified transcript" : "Human-edited transcript",
    operation: {
      algorithm: "human-review",
      version: "1",
      parameters: { changes, explicitVerification: verified },
    },
  });
  c.log(verified ? "TRANSCRIPT_VERIFY" : "TRANSCRIPT_EDIT", {
    artifactId: artifact.id,
    parentId,
  });
  return { artifact, transcript: next };
}
