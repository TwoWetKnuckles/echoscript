import { processingHistory } from "../../../packages/exports/index.mjs";
const $ = (id) => document.getElementById(id),
  worker = new Worker(new URL("./processing.worker.mjs", import.meta.url), {
    type: "module",
  });
const pending = new Map();
let counter = 0,
  busy = 0,
  state = null,
  regions = [],
  profile = null,
  objectUrl = null,
  requestAbort = null,
  pendingTransmission = null,
  lastExport = null,
  exportObjectUrl = null;
function message(text, error = false) {
  $("global-status").textContent = text;
  $("global-status").className = error ? "error" : "";
}
function disable() {
  for (const b of document.querySelectorAll("button"))
    if (b.id !== "cancel-api" && b.id !== "deny-consent") b.disabled = busy > 0;
}
function call(action, data = {}) {
  busy++;
  disable();
  const requestId = ++counter;
  return new Promise((resolve, reject) => {
    pending.set(requestId, { resolve, reject });
    worker.postMessage({ requestId, action, data });
  }).finally(() => {
    busy--;
    disable();
  });
}
worker.onmessage = ({ data }) => {
  const p = pending.get(data.requestId);
  if (!p) return;
  pending.delete(data.requestId);
  data.error ? p.reject(Error(data.error)) : p.resolve(data.result);
};
worker.onerror = () => {
  for (const p of pending.values())
    p.reject(
      Error(
        "Audio worker stopped unexpectedly. Preserve your original file and reload the workbench.",
      ),
    );
  pending.clear();
};
function action(id, handler) {
  $(id).onclick = async (e) => {
    e.preventDefault();
    try {
      await handler(e);
    } catch (error) {
      message(error.message, true);
    }
  };
}
const number = (id) => Number($(id).value);
function route() {
  const name = location.hash.slice(1) || "cases";
  const page =
    document.querySelector(`.page[id="${CSS.escape(name)}"]`) || $("cases");
  for (const p of document.querySelectorAll(".page")) p.hidden = p !== page;
  for (const a of document.querySelectorAll("nav a"))
    a.setAttribute("aria-current", a.hash === "#" + page.id ? "page" : "false");
  $("breadcrumb").textContent =
    "Workspace / " + page.id[0].toUpperCase() + page.id.slice(1);
  document.title = "EchoScript · " + page.id;
  if (page.id === "audit" && state) {
    const caseId = state.manifest.caseId;
    call("auditSnapshot")
      .then((next) => {
        if (state?.manifest.caseId !== caseId) return;
        state = {
          ...state,
          manifest: next.manifest,
          verification: next.verification,
        };
        $("audit-log").textContent = processingHistory(next.manifest);
        $("verification").textContent =
          `Hash/provenance/audit consistency: PASS · ${next.verification.artifacts} artifacts · ${next.verification.auditEntries} audit entries · signatures not checked in this view`;
      })
      .catch((error) => message(error.message, true));
  }
}
document.querySelector(".skip").onclick = (e) => {
  e.preventDefault();
  $("main").focus();
};
addEventListener("hashchange", route);
route();
function save(bytes, name, mime = "application/octet-stream") {
  const url = URL.createObjectURL(new Blob([bytes], { type: mime }));
  const a = document.createElement("a");
  a.href = url;
  a.download = name;
  a.click();
  setTimeout(() => URL.revokeObjectURL(url), 30000);
}
async function refresh(next, replaceAudio = true) {
  if (state?.manifest.caseId !== next.manifest.caseId) {
    regions = [];
    profile = null;
    pendingTransmission = null;
    lastExport = null;
    if (exportObjectUrl) URL.revokeObjectURL(exportObjectUrl);
    exportObjectUrl = null;
    $("save-export").hidden = true;
    $("save-export").removeAttribute("href");
    requestAbort?.abort();
    $("regions").textContent =
      "No regions selected: processing applies to the whole recording.";
    $("selection-start").value = 0;
    $("selection-end").value = Math.min(1, next.audio.duration);
    $("view-start").value = 0;
    $("view-duration").value = Math.min(10, next.audio.duration);
    $("human-verified").checked = false;
    $("transcript-search").value = "";
    $("transcript-filter").value = "all";
    $("annotation").value = "";
  }
  state = next;
  const m = state.manifest;
  $("case-summary").textContent =
    `${m.title} · ${m.caseId} · ${m.artifacts.length} artifacts · ${m.audit.length} audit entries`;
  const source = m.artifacts.find((a) => a.type === "SOURCE");
  $("audio-metrics").textContent =
    `${state.audio.duration.toFixed(3)} s · ${state.audio.sampleRate} Hz · ${state.audio.channels} channels · peak ${state.audio.meters.peak.toFixed(4)} · RMS ${state.audio.meters.rms.toFixed(4)} · clipped ${state.audio.meters.clippedSamples} samples (${state.audio.meters.clippedPercent.toFixed(3)}%)`;
  $("analysis-parameters").textContent = JSON.stringify(
    state.analysis.parameters,
  );
  $("selection-end").max = state.audio.duration;
  $("view-start").max = state.audio.duration;
  $("high-hz").value = Math.min(number("high-hz"), state.audio.sampleRate / 2);
  $("selection-end").value = Math.min(
    number("selection-end"),
    state.audio.duration,
  );
  if (number("view-duration") > state.audio.duration)
    $("view-duration").value = state.audio.duration.toFixed(3);
  const options = m.artifacts
    .filter((a) => ["SOURCE", "DECODE", "ENHANCED_AUDIO"].includes(a.type))
    .map((a) => {
      const o = document.createElement("option");
      o.value = a.id;
      o.textContent = `${a.revision} · ${a.type} · ${a.sha256.slice(0, 12)}`;
      return o;
    });
  $("revision").replaceChildren(...options);
  $("revision").value = state.audioId;
  $("events").replaceChildren(
    ...state.analysis.events.map((e) => {
      const b = document.createElement("button");
      b.textContent = `Candidate ${e.start.toFixed(3)}–${e.end.toFixed(3)} s · uncalibrated`;
      b.onclick = () => {
        $("selection-start").value = e.start.toFixed(3);
        $("selection-end").value = e.end.toFixed(3);
        call("log", {
          action: "SPEECH_EVENT_SELECT",
          details: { eventId: e.eventId, start: e.start, end: e.end },
        }).catch((x) => message(x.message, true));
        draw();
      };
      return b;
    }),
  );
  if (!state.analysis.events.length)
    $("events").textContent =
      "No speech-event candidates detected. This does not establish absence of speech.";
  $("audit-log").textContent = processingHistory(m);
  $("verification").textContent =
    `Hash/provenance/audit consistency: PASS · ${state.verification.artifacts} artifacts · ${state.verification.auditEntries} audit entries · signatures not checked in this view`;
  renderTranscript();
  draw();
  if (replaceAudio) {
    const { bytes } = await call("audioBytes");
    const p = $("player");
    p.pause();
    p.removeAttribute("src");
    p.load();
    if (objectUrl) URL.revokeObjectURL(objectUrl);
    objectUrl = URL.createObjectURL(new Blob([bytes], { type: "audio/wav" }));
    p.src = objectUrl;
  }
  message(`Case verified. Original SHA-256: ${source.sha256}`);
}
async function ingest(bytes, name, title, actor) {
  message(
    "Hashing original bytes, decoding and analyzing in the audio worker…",
  );
  await refresh(await call("import", { bytes, name, title, actor }));
  location.hash = "analyze";
}
$("import-form").onsubmit = async (e) => {
  e.preventDefault();
  try {
    const f = $("source").files[0];
    if (!f) throw Error("Select a WAV file");
    if (f.size > 16 * 1024 * 1024)
      throw Error("Browser source limit is 16 MiB.");
    await ingest(
      new Uint8Array(await f.arrayBuffer()),
      f.name,
      $("case-title").value,
      $("actor").value,
    );
  } catch (error) {
    message(error.message, true);
  }
};
action("sample", async () => {
  const response = await fetch(
    new URL("./fixtures/synthetic-speech.wav", import.meta.url),
  );
  if (!response.ok) throw Error("Synthetic sample is unavailable");
  await ingest(
    new Uint8Array(await response.arrayBuffer()),
    "SYNTHETIC_TEST_NOT_EVIDENCE.wav",
    "SYNTHETIC TEST DATA — NOT EVIDENCE",
    $("actor").value,
  );
});
action("open-case", async () => {
  const f = $("case-file").files[0];
  if (!f) throw Error("Select a case package");
  if (f.size > 69 * 1024 * 1024) throw Error("Package exceeds browser policy");
  message("Verifying the package before import…");
  await refresh(
    await call("importPackage", {
      bytes: new Uint8Array(await f.arrayBuffer()),
      passphrase: $("import-password").value,
      actor: $("actor").value,
    }),
  );
  $("import-password").value = "";
  location.hash = "analyze";
});
function selection() {
  if (!state) throw Error("Import audio first");
  const start = number("selection-start"),
    end = number("selection-end");
  if (
    !Number.isFinite(start) ||
    !Number.isFinite(end) ||
    start < 0 ||
    end <= start ||
    end > state.audio.duration
  )
    throw Error("Selection must be within the recording");
  return { start, end };
}
function graph() {
  const q = number("filter-q"),
    g = [];
  if (number("delay")) g.push({ type: "delay", seconds: number("delay") });
  if (number("highpass"))
    g.push({ type: "highpass", frequency: number("highpass"), q });
  if (number("hum"))
    g.push({
      type: "hum",
      frequency: number("hum"),
      harmonics: number("harmonics"),
      q: number("notch-q"),
    });
  if (number("notch"))
    g.push({ type: "notch", frequency: number("notch"), q: number("notch-q") });
  if (number("noise"))
    g.push({
      type: "noise",
      strength: number("noise-strength"),
      reductionDb: number("noise"),
      ...(profile ? { profile } : {}),
    });
  if (number("echo"))
    g.push({
      type: "echoReduction",
      delayMs: number("echo-delay"),
      strength: number("echo"),
    });
  if (number("dereverb"))
    g.push({
      type: "dereverb",
      delayMs: number("echo-delay"),
      strength: number("dereverb"),
    });
  if ($("declipping").checked) g.push({ type: "declipping", threshold: 0.98 });
  for (const [field, f] of [
    ["eq-low", 120],
    ["eq-mid", 1000],
    ["eq-high", Math.min(5000, state.audio.sampleRate * 0.4)],
  ])
    if (number(field))
      g.push({ type: "peak", frequency: f, q, gainDb: number(field) });
  if (number("bandpass"))
    g.push({ type: "bandpass", frequency: number("bandpass"), q });
  if (number("lowpass"))
    g.push({ type: "lowpass", frequency: number("lowpass"), q });
  g.push({ type: "gain", gainDb: number("gain") });
  if ($("isolate").checked)
    g.push({
      type: "isolate",
      lowHz: number("low-hz"),
      highHz: number("high-hz"),
      gainDb: 0,
      surroundDb: -24,
    });
  if ($("limiter").checked) g.push({ type: "limiter", ceilingDb: -1 });
  return g;
}
action("render", async () => {
  message("Rendering a separate PCM16 derivative…");
  await refresh(await call("render", { graph: graph(), selections: regions }));
});
action("undo", async () => refresh(await call("undo")));
action("redo", async () => refresh(await call("redo")));
$("revision").onchange = async () => {
  try {
    await refresh(await call("selectRevision", { id: $("revision").value }));
  } catch (e) {
    message(e.message, true);
  }
};
action("compare", async () => {
  if (!state) throw Error("Import audio");
  const revisions = state.manifest.artifacts.filter((a) =>
    ["SOURCE", "DECODE", "ENHANCED_AUDIO"].includes(a.type),
  );
  const target =
    state.audioId === revisions[0].id ? revisions.at(-1) : revisions[0];
  await refresh(await call("selectRevision", { id: target.id }));
});
action("add-region", async () => {
  regions.push(selection());
  $("regions").textContent =
    JSON.stringify(regions) +
    " · all regions share the current frequency-isolation settings";
  await call("log", {
    action: "REGION_SELECT",
    details: { regions, lowHz: number("low-hz"), highHz: number("high-hz") },
  });
  draw();
});
action("clear-regions", async () => {
  regions = [];
  $("regions").textContent =
    "No regions selected: processing applies to the whole recording.";
  await call("log", { action: "REGION_CLEAR" });
  draw();
});
action("noise-profile", async () => {
  const result = await call("profile", selection());
  profile = result.profile;
  message("Noise profile captured from the examiner-selected region.");
});
action("seek-selection", async () => {
  const r = selection();
  $("player").currentTime = r.start;
  await $("player").play();
});
action("save-note", async () => {
  await refresh(
    await call("annotate", {
      text: $("annotation").value,
      time: $("player").currentTime,
    }),
    false,
  );
  $("annotation").value = "";
});
function draw() {
  if (!state) return;
  const start = Math.max(0, number("view-start")),
    duration = Math.max(0.1, number("view-duration")),
    total = state.audio.duration;
  for (const name of ["waveform", "spectrogram"]) {
    const canvas = $(name),
      ctx = canvas.getContext("2d"),
      w = canvas.width,
      h = canvas.height;
    ctx.fillStyle = "#102631";
    ctx.fillRect(0, 0, w, h);
    if (name === "waveform") {
      ctx.strokeStyle = "#88c3bd";
      ctx.beginPath();
      for (let x = 0; x < w; x++) {
        const at = (start + (x / w) * duration) / total;
        const pair =
          state.analysis.wave[Math.floor(at * state.analysis.wave.length)];
        if (pair) {
          ctx.moveTo(x, h / 2 - pair[0] * h * 0.46);
          ctx.lineTo(x, h / 2 - pair[1] * h * 0.46);
        }
      }
      ctx.stroke();
    } else {
      const spectra = state.analysis.spec;
      for (let x = 0; x < w; x += 2) {
        const f =
          spectra[
            Math.floor(((start + (x / w) * duration) / total) * spectra.length)
          ];
        if (!f) continue;
        for (let k = 0; k < f.length; k += 2) {
          const a = f[k] / 255;
          ctx.fillStyle = `rgb(${Math.round(230 * a)},${Math.round(50 + 180 * a)},${Math.round(65 + 140 * a)})`;
          ctx.fillRect(
            x,
            h - (k / f.length) * h,
            2,
            Math.ceil((h * 2) / f.length),
          );
        }
      }
    }
    ctx.fillStyle = "#e9a66c44";
    for (const r of regions)
      ctx.fillRect(
        ((r.start - start) / duration) * w,
        0,
        ((r.end - r.start) / duration) * w,
        h,
      );
    ctx.strokeStyle = "#ffc080";
    ctx.beginPath();
    const px = (($("player").currentTime - start) / duration) * w;
    ctx.moveTo(px, 0);
    ctx.lineTo(px, h);
    ctx.stroke();
    ctx.fillStyle = "#d8ece9";
    ctx.font = "12px sans-serif";
    ctx.fillText(
      `${start.toFixed(3)} s → ${(start + duration).toFixed(3)} s${name === "spectrogram" ? " · " + state.audio.sampleRate / 2 + " Hz at top, 0 Hz at bottom" : ""}`,
      8,
      16,
    );
  }
}
for (const id of [
  "view-start",
  "view-duration",
  "selection-start",
  "selection-end",
  "low-hz",
  "high-hz",
])
  $(id).oninput = draw;
for (const name of ["waveform", "spectrogram"]) {
  let anchor = null;
  const canvas = $(name);
  canvas.onpointerdown = (e) => {
    if (!state) return;
    const r = canvas.getBoundingClientRect();
    anchor = {
      x: (e.clientX - r.left) / r.width,
      y: (e.clientY - r.top) / r.height,
    };
    canvas.setPointerCapture(e.pointerId);
  };
  canvas.onpointerup = (e) => {
    if (!anchor || !state) return;
    const r = canvas.getBoundingClientRect(),
      x = Math.max(0, Math.min(1, (e.clientX - r.left) / r.width)),
      y = Math.max(0, Math.min(1, (e.clientY - r.top) / r.height)),
      start = number("view-start"),
      span = number("view-duration");
    $("selection-start").value = Math.max(
      0,
      Math.min(state.audio.duration, start + Math.min(anchor.x, x) * span),
    ).toFixed(3);
    $("selection-end").value = Math.max(
      0,
      Math.min(state.audio.duration, start + Math.max(anchor.x, x) * span),
    ).toFixed(3);
    if (name === "spectrogram") {
      $("low-hz").value = Math.round(
        ((1 - Math.max(anchor.y, y)) * state.audio.sampleRate) / 2,
      );
      $("high-hz").value = Math.round(
        ((1 - Math.min(anchor.y, y)) * state.audio.sampleRate) / 2,
      );
    }
    anchor = null;
    draw();
  };
}
$("player").ontimeupdate = () => {
  if ($("loop").checked && $("player").currentTime >= number("selection-end"))
    $("player").currentTime = number("selection-start");
  draw();
  for (const el of document.querySelectorAll(".segment"))
    el.classList.toggle(
      "active",
      $("player").currentTime >= Number(el.dataset.start) &&
        $("player").currentTime < Number(el.dataset.end),
    );
};
$("player").onplay = () => {
  if (state)
    call("log", {
      action: "PLAYBACK",
      details: { artifactId: state.audioId, time: $("player").currentTime },
    }).catch((e) => message(e.message, true));
};
function renderTranscript() {
  const t = state?.transcript,
    container = $("transcript-segments");
  container.replaceChildren();
  if (!t) {
    $("transcript-provenance").textContent = "No transcript.";
    return;
  }
  $("transcript-provenance").textContent =
    `${t.state} · ${t.provider}/${t.model} · transmission ${t.transmissionHash} · speaker diarization unavailable in this adapter`;
  const search = $("transcript-search").value.toLowerCase(),
    filter = $("transcript-filter").value;
  for (const s of t.segments) {
    if (
      (search && !s.text.toLowerCase().includes(search)) ||
      (filter === "flags" && !s.flags.length) ||
      (filter === "low" &&
        !(
          s.confidence?.semantics === "provider confidence" &&
          s.confidence.value < 0.7
        ))
    )
      continue;
    const card = document.createElement("article");
    card.className = "segment";
    card.dataset.start = s.start;
    card.dataset.end = s.end;
    const seek = document.createElement("button");
    seek.textContent = `${s.start.toFixed(3)}–${s.end.toFixed(3)} · ${s.speaker || "Speaker unassigned"}`;
    seek.onclick = () => {
      $("player").currentTime = s.start;
      $("player")
        .play()
        .catch((e) => message(e.message, true));
    };
    const input = document.createElement("textarea");
    input.value = s.text;
    input.dataset.segment = s.id;
    input.setAttribute("aria-label", "Transcript " + s.id);
    const score = document.createElement("p");
    score.className = "metric";
    score.textContent = s.confidence
      ? `${s.confidence.semantics}: ${s.confidence.value} · not a calibrated probability`
      : "No provider-supported segment confidence available.";
    const alternatives = document.createElement("p");
    alternatives.textContent = s.alternatives.length
      ? "Returned alternatives: " +
        s.alternatives
          .map((a) => `${a.text} [decoder score ${a.score ?? "unavailable"}]`)
          .join(" | ")
      : "No provider-supported alternative hypothesis available.";
    const flags = document.createElement("p");
    flags.className = "warning";
    flags.textContent = s.flags.join(" · ");
    card.append(seek, input, score, alternatives, flags);
    container.append(card);
  }
}
$("transcript-search").oninput = renderTranscript;
$("transcript-filter").onchange = renderTranscript;
action("save-review", async () => {
  if (!state?.transcript) throw Error("No transcript");
  const changes = [...document.querySelectorAll("[data-segment]")].map(
    (el) => ({ id: el.dataset.segment, text: el.value }),
  );
  if (
    $("human-verified").checked &&
    changes.length !== state.transcript.segments.length
  )
    throw Error(
      "Clear filters and independently review all segments before verifying the full transcript",
    );
  await refresh(
    await call("review", { changes, verified: $("human-verified").checked }),
    false,
  );
  $("human-verified").checked = false;
});
function localOrigin() {
  const url = new URL($("api-url").value);
  if (
    !["http:", "https:"].includes(url.protocol) ||
    !["localhost", "127.0.0.1", "[::1]"].includes(url.hostname) ||
    url.username ||
    url.password ||
    url.pathname !== "/" ||
    url.search ||
    url.hash
  )
    throw Error("This build permits loopback local provider origins only");
  if ($("api-token").value.length < 32)
    throw Error("Enter the local service API token in Settings");
  return url.origin;
}
action("prepare-transcription", async () => {
  if ($("airgap").checked)
    throw Error(
      "Air-gapped mode — no external transcription performed. Disable the provider prohibition explicitly for local API testing.",
    );
  localOrigin();
  pendingTransmission = await call("prepareTransmission");
  if (pendingTransmission.duration > 750)
    throw Error(
      "Transmission exceeds the current local adapter request limit. Chunking remains outstanding.",
    );
  const rows = [
    ["Provider", "Local Vosk API"],
    ["Model", "vosk-model-small-en-us-0.15"],
    ["Duration", pendingTransmission.duration.toFixed(3) + " seconds"],
    ["Artifact SHA-256", pendingTransmission.artifact.sha256],
    ["What leaves the browser", "PCM16 mono WAV at 16 kHz"],
    ["Purpose", "Verbatim English transcript draft"],
    [
      "Retention",
      "Configured local adapter writes no evidence files; no cloud provider is used",
    ],
  ];
  $("consent-details").replaceChildren(
    ...rows.flatMap(([k, v]) => {
      const dt = document.createElement("dt"),
        dd = document.createElement("dd");
      dt.textContent = k;
      dd.textContent = v;
      return [dt, dd];
    }),
  );
  $("consent").showModal();
});
action("deny-consent", async () => {
  if (pendingTransmission)
    await call("consent", {
      artifactId: pendingTransmission.artifact.id,
      sha256: pendingTransmission.artifact.sha256,
      duration: pendingTransmission.duration,
      approved: false,
    });
  pendingTransmission = null;
  $("consent").close();
  message("Submission cancelled.");
});
action("approve-consent", async () => {
  if (!pendingTransmission) throw Error("Prepare a transmission first");
  const transmission = pendingTransmission,
    caseId = state.manifest.caseId;
  pendingTransmission = null;
  $("consent").close();
  const origin = localOrigin();
  await call("consent", {
    artifactId: transmission.artifact.id,
    sha256: transmission.artifact.sha256,
    duration: transmission.duration,
    approved: true,
  });
  requestAbort = new AbortController();
  const timer = setTimeout(() => requestAbort?.abort(), 70000);
  const headers = { Authorization: "Bearer " + $("api-token").value };
  message("Local Vosk transcription in progress…");
  try {
    const c = await fetch(origin + "/v1/consents", {
      method: "POST",
      headers: { ...headers, "Content-Type": "application/json" },
      body: JSON.stringify({
        artifactHash: transmission.artifact.sha256,
        duration: transmission.duration,
        approved: true,
        provider: "vosk-local",
      }),
      signal: requestAbort.signal,
    });
    if (!c.ok) throw Error("Consent rejected: HTTP " + c.status);
    const { consentId } = await c.json();
    const response = await fetch(origin + "/v1/transcriptions", {
      method: "POST",
      headers: {
        ...headers,
        "Content-Type": "audio/wav",
        "X-Consent-ID": consentId,
      },
      body: transmission.bytes,
      signal: requestAbort.signal,
    });
    if (!response.ok)
      throw Error("Local transcription failed: HTTP " + response.status);
    const bytes = new Uint8Array(await response.arrayBuffer());
    if (state.manifest.caseId !== caseId)
      throw Error("Case changed during transcription; result was not attached");
    await refresh(
      await call("providerResult", {
        bytes,
        transmissionId: transmission.artifact.id,
        transmissionHash: transmission.artifact.sha256,
      }),
      false,
    );
  } finally {
    clearTimeout(timer);
    requestAbort = null;
  }
});
action("cancel-api", async () => {
  requestAbort?.abort();
  message("Provider request cancellation requested.");
});
$("airgap").onchange = () => {
  if ($("airgap").checked) requestAbort?.abort();
  $("airgap-status").textContent = $("airgap").checked
    ? "Air-gapped mode — no external transcription performed."
    : "Local provider requests require explicit per-artifact approval.";
  $("mode-badge").textContent = $("airgap").checked
    ? "AIR-GAPPED MODE"
    : "LOCAL PROVIDER AVAILABLE";
};
$("airgap").dispatchEvent(new Event("change"));
action("verify-case", async () => refresh(await call("snapshot"), false));
action("copy-history", async () => {
  if (!state) throw Error("No case");
  const next = await call("snapshot");
  await navigator.clipboard.writeText(processingHistory(next.manifest));
  message("Processing history copied.");
});
action("print-history", async () => {
  if (!state) throw Error("No case");
  $("audit-log").textContent = processingHistory(
    (await call("snapshot")).manifest,
  );
  window.print();
});
action("download", async () => {
  const format = $("export-format").value;
  let fontBase64;
  if (format === "pdf") {
    const response = await fetch(
      new URL("./NotoSans-Regular.ttf", import.meta.url),
    );
    if (!response.ok) throw Error("Report font unavailable");
    const bytes = new Uint8Array(await response.arrayBuffer());
    let s = "";
    for (let i = 0; i < bytes.length; i += 8192)
      s += String.fromCharCode(...bytes.subarray(i, i + 8192));
    fontBase64 = btoa(s);
  }
  const file = await call("export", {
    format,
    passphrase: format === "case" ? $("export-password").value : "",
    fontBase64,
    bundle: $("export-bundle").checked,
  });
  if (exportObjectUrl) URL.revokeObjectURL(exportObjectUrl);
  exportObjectUrl = URL.createObjectURL(
    new Blob([file.bytes], { type: file.mime }),
  );
  $("save-export").href = exportObjectUrl;
  $("save-export").download = file.name;
  $("save-export").hidden = false;
  lastExport = { name: file.name, sha256: file.sha256 };
  state = await call("snapshot");
  $("audit-log").textContent = processingHistory(state.manifest);
  $("export-result").textContent =
    `${file.name} · ${file.bytes.length} bytes · SHA-256 ${file.sha256}`;
  message(
    "Export ready. Review its details and choose Save generated export. Retain the SHA-256 checkpoint independently.",
  );
});
action("download-hash", async () => {
  if (!lastExport) throw Error("Generate an export first");
  save(
    new TextEncoder().encode(`${lastExport.sha256}  ${lastExport.name}\n`),
    lastExport.name + ".sha256.txt",
    "text/plain",
  );
});
addEventListener("beforeunload", (e) => {
  if (state) {
    e.preventDefault();
    e.returnValue = "";
  }
});
