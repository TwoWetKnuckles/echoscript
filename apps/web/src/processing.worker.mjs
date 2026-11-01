import {
  EvidenceCase,
  digest,
  jsonBytes,
  safeJson,
  encryptPackage,
  decryptPackage,
} from "../../../packages/evidence/index.mjs";
import {
  decodeWav,
  encodeWav,
  analyze,
  render,
  meters,
  mixMono,
  noiseProfile,
} from "../../../packages/audio/index.mjs";
import {
  normalizeVosk,
  reviseTranscript,
} from "../../../packages/evidence/transcript.mjs";
import {
  exportTranscript,
  exportPdf,
  processingHistory,
} from "../../../packages/exports/index.mjs";
let c = null,
  audioId = null,
  transcriptId = null,
  transcript = null,
  events = [],
  profile = null,
  redo = [];
function audio() {
  if (!c || !audioId) throw Error("Import audio first");
  return decodeWav(c.bytes(audioId));
}
const op = (algorithm, parameters = {}) => ({
  algorithm,
  version: "1",
  parameters,
});
function snapshot() {
  const pcm = audio(),
    a = analyze(pcm),
    wave = [];
  events = a.events;
  const samples = pcm.channels[0],
    step = Math.max(1, Math.ceil(samples.length / 1024));
  for (let i = 0; i < samples.length; i += step) {
    let lo = 0,
      hi = 0;
    for (let k = i; k < Math.min(i + step, samples.length); k++) {
      lo = Math.min(lo, samples[k]);
      hi = Math.max(hi, samples[k]);
    }
    wave.push([lo, hi]);
  }
  const spec = [];
  for (
    let i = 0;
    i < a.frames.length;
    i += Math.max(1, Math.ceil(a.frames.length / 600))
  )
    spec.push(
      Array.from(a.frames[i].power, (p) =>
        Math.max(
          0,
          Math.min(255, (255 * (10 * Math.log10(p + 1e-12) + 100)) / 100),
        ),
      ),
    );
  return {
    manifest: c.manifest,
    audioId,
    transcriptId,
    transcript,
    analysis: { parameters: a.parameters, events: a.events, wave, spec },
    audio: {
      sampleRate: pcm.sampleRate,
      channels: pcm.channels.length,
      duration: pcm.duration,
      meters: meters(pcm),
    },
    verification: c.verify(),
  };
}
async function execute(action, data) {
  if (action === "import") {
    if (data.bytes.length > 16 * 1024 * 1024)
      throw Error(
        "This browser workbench limits source files to 16 MiB and each workspace to 64 MiB. Use Android/desktop for larger files.",
      );
    const next = new EvidenceCase({ title: data.title, actor: data.actor });
    const source = next.add(data.bytes, {
      type: "SOURCE",
      name: data.name,
      operation: op("hash-before-decode-import", { originalName: data.name }),
    });
    const pcm = decodeWav(next.bytes(source.id));
    const decoded = next.add(encodeWav(pcm), {
      type: "DECODE",
      parents: [source.id],
      name: "Canonical PCM16 decode",
      operation: op("wav-pcm16-decode", {
        sourceEncoding: pcm.sourceEncoding,
        sampleRate: pcm.sampleRate,
        channels: pcm.channels.length,
        bitDepth: 16,
      }),
    });
    next.log("DECODE", { artifactId: decoded.id, sourceId: source.id });
    c = next;
    audioId = decoded.id;
    transcriptId = null;
    transcript = null;
    profile = null;
    redo = [];
    const info = snapshot();
    c.log("ANALYSIS", {
      artifactId: audioId,
      parameters: info.analysis.parameters,
      eventCount: info.analysis.events.length,
    });
    return snapshot();
  }
  if (action === "importPackage") {
    let bytes = data.bytes;
    if (new TextDecoder().decode(bytes.subarray(0, 8)) === "ECHENC01")
      bytes = await decryptPackage(bytes, data.passphrase);
    const next = EvidenceCase.importPackage(bytes, { actor: data.actor });
    const m = next.manifest;
    const available = m.artifacts.filter((x) =>
      ["SOURCE", "DECODE", "ENHANCED_AUDIO"].includes(x.type),
    );
    if (!available.length) throw Error("Package lacks playable audio");
    const id = available.some((a) => a.id === m.activeArtifact)
      ? m.activeArtifact
      : available.at(-1).id;
    decodeWav(next.bytes(id));
    c = next;
    audioId = id;
    transcriptId =
      m.artifacts
        .filter((a) =>
          ["NORMALIZED_TRANSCRIPT", "HUMAN_TRANSCRIPT"].includes(a.type),
        )
        .at(-1)?.id || null;
    transcript = transcriptId ? safeJson(c.bytes(transcriptId)) : null;
    redo = [];
    return snapshot();
  }
  if (!c) throw Error("Import or open a case first");
  if (action === "auditSnapshot")
    return { manifest: c.manifest, verification: c.verify() };
  if (action === "render") {
    const pcm = audio(),
      result = render(pcm, data.graph, data.selections);
    const parent = audioId;
    const a = c.add(encodeWav(result.pcm), {
      type: "ENHANCED_AUDIO",
      parents: [parent],
      name: "Enhanced PCM16 WAV",
      operation: op(result.algorithm, {
        graph: result.graph,
        selections: result.selections,
        sampleRate: pcm.sampleRate,
        channels: pcm.channels.length,
        bitDepth: 16,
      }),
      warnings: result.warnings,
    });
    c.log("ENHANCEMENT_RENDER", { artifactId: a.id, parent });
    audioId = a.id;
    redo = [];
    return snapshot();
  }
  if (action === "selectRevision") {
    const a = c.manifest.artifacts.find(
      (a) =>
        a.id === data.id &&
        ["DECODE", "ENHANCED_AUDIO", "SOURCE"].includes(a.type),
    );
    if (!a) throw Error("Not an audio revision");
    decodeWav(c.bytes(a.id));
    audioId = a.id;
    c.activate(audioId, "REVISION_ACTIVATE");
    return snapshot();
  }
  if (action === "undo") {
    const a = c.manifest.artifacts.find((a) => a.id === audioId);
    if (a.type === "SOURCE" || !a.parents.length)
      throw Error("At the earliest audio revision");
    redo.push(audioId);
    audioId = a.parents[0];
    c.activate(audioId, "UNDO");
    return snapshot();
  }
  if (action === "redo") {
    if (!redo.length) throw Error("No undone revision");
    audioId = redo.pop();
    c.activate(audioId, "REDO");
    return snapshot();
  }
  if (action === "profile") {
    profile = noiseProfile(audio(), data.start, data.end);
    c.log("NOISE_PROFILE_SELECT", {
      artifactId: audioId,
      start: data.start,
      end: data.end,
      fftSize: 512,
      profile,
    });
    return { profile };
  }
  if (action === "prepareTransmission") {
    const input = audio(),
      pcm = mixMono(input, 16000),
      bytes = encodeWav(pcm);
    const a = c.add(bytes, {
      type: "TRANSCRIPTION_TRANSMISSION",
      parents: [audioId],
      name: "Local Vosk transmission WAV",
      operation: op("windowed-sinc-resample", {
        sampleRate: 16000,
        channels: 1,
        bitDepth: 16,
        kernelRadius: 32,
        cutoffRatio: 0.94,
        provider: "vosk-local",
      }),
    });
    return { artifact: a, duration: pcm.duration, bytes };
  }
  if (action === "consent") {
    c.log("TRANSCRIPT_SUBMISSION_APPROVAL", {
      artifactId: data.artifactId,
      provider: "vosk-local",
      model: "vosk-model-small-en-us-0.15",
      sha256: data.sha256,
      duration: data.duration,
      approved: data.approved,
      retention: "Local service; no evidence files written by this adapter",
      purpose: "Verbatim English transcript draft",
    });
    return { approved: data.approved };
  }
  if (action === "providerResult") {
    const parsed = safeJson(data.bytes);
    const raw = c.add(data.bytes, {
      type: "RAW_PROVIDER_RESULT",
      parents: [data.transmissionId],
      name: "Exact local Vosk result",
      operation: op("vosk-local", { model: "vosk-model-small-en-us-0.15" }),
    });
    transcript = normalizeVosk(parsed, {
      duration: audio().duration,
      events,
      transmissionHash: data.transmissionHash,
    });
    const normalized = c.add(jsonBytes(transcript), {
      type: "NORMALIZED_TRANSCRIPT",
      parents: [raw.id, data.transmissionId],
      name: "AI transcript draft",
      operation: op("vosk-normalization", {
        provider: "vosk-local",
        model: transcript.model,
      }),
    });
    transcriptId = normalized.id;
    c.log("TRANSCRIPTION_RESULT", {
      rawArtifactId: raw.id,
      normalizedArtifactId: transcriptId,
    });
    return snapshot();
  }
  if (action === "review") {
    if (!transcript) throw Error("No transcript to review");
    const next = reviseTranscript(c, transcriptId, transcript, data.changes, {
      verified: data.verified,
      duration: audio().duration,
    });
    transcript = next.transcript;
    transcriptId = next.artifact.id;
    return snapshot();
  }
  if (action === "speakerLabel") {
    if (!transcript) throw Error("No transcript");
    if (
      !/^SPEAKER_[1-9][0-9]*$/.test(data.speaker) ||
      typeof data.label !== "string" ||
      !data.label.trim() ||
      data.label.length > 128
    )
      throw Error("Invalid examiner-supplied speaker label");
    const next = structuredClone(transcript);
    next.speakerLabels = {
      ...next.speakerLabels,
      [data.speaker]: { label: data.label, source: "HUMAN_SUPPLIED" },
    };
    const a = c.add(jsonBytes(next), {
      type: "HUMAN_TRANSCRIPT",
      parents: [transcriptId],
      name: "Examiner speaker annotation",
      operation: op("human-speaker-label", {
        speaker: data.speaker,
        label: data.label,
        source: "HUMAN_SUPPLIED",
      }),
    });
    c.log("SPEAKER_RENAME", {
      speaker: data.speaker,
      label: data.label,
      source: "HUMAN_SUPPLIED",
    });
    transcript = next;
    transcriptId = a.id;
    return snapshot();
  }
  if (action === "annotate") {
    if (
      typeof data.text !== "string" ||
      !data.text.trim() ||
      data.text.length > 4096
    )
      throw Error("Invalid annotation");
    c.log("ANNOTATION", {
      artifactId: audioId,
      time: data.time,
      text: data.text,
    });
    return snapshot();
  }
  if (action === "log") {
    c.log(data.action, data.details || {});
    return { logged: true };
  }
  if (action === "audioBytes") return { bytes: c.bytes(audioId) };
  if (action === "export") {
    let bytes, name, mime;
    if (data.format === "wav") {
      bytes = encodeWav(audio());
      name = "enhanced.wav";
      mime = "audio/wav";
      c.add(bytes, {
        type: "AUDIO_EXPORT",
        parents: [audioId],
        name,
        operation: op("pcm16-wav-export", {
          format: "WAV",
          codec: "PCM",
          bitDepth: 16,
          sampleRate: audio().sampleRate,
          channels: audio().channels.length,
        }),
      });
    } else if (data.format === "history") {
      bytes = new TextEncoder().encode(processingHistory(c.manifest));
      name = `processing_history_${new Date().toISOString().replaceAll(":", "")}.txt`;
      mime = "text/plain";
    } else if (data.format === "pdf") {
      bytes = exportPdf(c.manifest, transcript, data.fontBase64);
      name = "processing_report.pdf";
      mime = "application/pdf";
      c.add(bytes, {
        type: "REPORT",
        parents: [audioId, ...(transcriptId ? [transcriptId] : [])],
        name,
        operation: op("pdf-report", { format: "PDF" }),
      });
    } else if (data.format === "case") {
      bytes = c.exportPackage();
      if (data.passphrase) bytes = await encryptPackage(bytes, data.passphrase);
      name = data.passphrase ? "case.echocase.enc" : "case.echocase";
      mime = "application/octet-stream";
      return { bytes, name, mime, sha256: digest(bytes) };
    } else {
      if (!transcript) throw Error("Transcribe or import a transcript first");
      bytes = exportTranscript(transcript, data.format);
      name = `transcript.${data.format}`;
      mime = "application/octet-stream";
      c.add(bytes, {
        type: "DOCUMENT_EXPORT",
        parents: [transcriptId],
        name,
        operation: op("transcript-export", { format: data.format }),
      });
    }
    c.log("EXPORT", { name, sha256: digest(bytes), format: data.format });
    return { bytes, name, mime, sha256: digest(bytes) };
  }
  if (action === "snapshot") return snapshot();
  throw Error("Unknown workspace operation");
}
let queue = Promise.resolve();
self.onmessage = (event) => {
  queue = queue.then(async () => {
    const { requestId, action, data } = event.data;
    try {
      const result = await execute(action, data || {});
      self.postMessage({ requestId, result });
    } catch (error) {
      self.postMessage({ requestId, error: error.message });
    }
  });
};
