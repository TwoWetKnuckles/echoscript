import { jsonBytes } from "./index.mjs";
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
    t.segments.length > 50000
  )
    throw Error("Invalid transcript schema");
  for (const s of t.segments) {
    if (
      typeof s.id !== "string" ||
      !Number.isFinite(s.start) ||
      !Number.isFinite(s.end) ||
      s.start < 0 ||
      s.end < s.start ||
      typeof s.text !== "string" ||
      s.text.length > 10000 ||
      (s.speaker !== null && !/^SPEAKER_[1-9][0-9]*$/.test(s.speaker)) ||
      !Array.isArray(s.alternatives) ||
      !Array.isArray(s.flags)
    )
      throw Error("Invalid transcript segment");
    if (
      s.confidence !== null &&
      (!Number.isFinite(s.confidence.value) ||
        ![
          "provider confidence",
          "decoder score",
          "model self-assessment — not calibrated",
        ].includes(s.confidence.semantics))
    )
      throw Error("Invalid confidence semantics");
    for (const a of s.alternatives)
      if (
        typeof a.text !== "string" ||
        (a.score !== null && !Number.isFinite(a.score)) ||
        a.source !== "provider-returned"
      )
        throw Error("Invalid alternative hypothesis");
    if (s.end > duration && !s.flags.includes("OUT_OF_RANGE"))
      s.flags.push("OUT_OF_RANGE");
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
    const primary = result.alternatives?.[0] || result;
    const words = primary.result || [];
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
      alternatives: (result.alternatives || [])
        .slice(1, 6)
        .map((a) => ({
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
  const next = structuredClone(transcript);
  for (const change of changes) {
    const s = next.segments.find((x) => x.id === change.id);
    if (!s) throw Error("Unknown transcript segment");
    if (typeof change.text !== "string" || change.text.length > 10000)
      throw Error("Invalid correction");
    if (s.text !== change.text) {
      s.words = [];
      s.flags.push("WORD_ALIGNMENT_INVALIDATED_BY_HUMAN_EDIT");
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
