export const AUDIO_VERSION = "echoscript-classical-dsp/1";
const finite = (v, min, max, name) => {
  if (!Number.isFinite(v) || v < min || v > max) throw Error(`Invalid ${name}`);
  return v;
};
export function decodeWav(bytes) {
  const v = new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength),
    text = (p, n) => String.fromCharCode(...bytes.subarray(p, p + n));
  if (
    bytes.length < 44 ||
    text(0, 4) !== "RIFF" ||
    text(8, 4) !== "WAVE" ||
    v.getUint32(4, true) + 8 !== bytes.length
  )
    throw Error(
      "Not a supported RIFF WAV; use a supported decoder for this codec",
    );
  let fmt = null,
    data = null;
  for (let p = 12; p + 8 <= bytes.length;) {
    const name = text(p, 4),
      size = v.getUint32(p + 4, true),
      start = p + 8;
    if (start + size > bytes.length) throw Error("Truncated WAV chunk");
    if (name === "fmt ") {
      if (size < 16 || fmt) throw Error("Invalid WAV format");
      fmt = {
        kind: v.getUint16(start, true),
        channels: v.getUint16(start + 2, true),
        sampleRate: v.getUint32(start + 4, true),
        byteRate: v.getUint32(start + 8, true),
        align: v.getUint16(start + 12, true),
        bits: v.getUint16(start + 14, true),
      };
    }
    if (name === "data") {
      if (data) throw Error("Multiple WAV data chunks");
      data = { start, size };
    }
    p = start + size + (size & 1);
  }
  if (
    !fmt ||
    !data ||
    ![1, 3].includes(fmt.kind) ||
    ![16, 24, 32].includes(fmt.bits) ||
    (fmt.kind === 3 && fmt.bits !== 32) ||
    fmt.channels < 1 ||
    fmt.channels > 8 ||
    fmt.sampleRate < 8000 ||
    fmt.sampleRate > 192000
  )
    throw Error("Unsupported WAV encoding");
  const width = fmt.bits / 8;
  if (
    fmt.align !== width * fmt.channels ||
    fmt.byteRate !== fmt.sampleRate * fmt.align ||
    data.size % fmt.align
  )
    throw Error("Inconsistent WAV layout");
  const frames = data.size / fmt.align;
  const channels = Array.from(
    { length: fmt.channels },
    () => new Float32Array(frames),
  );
  for (let i = 0; i < frames; i++)
    for (let c = 0; c < fmt.channels; c++) {
      const p = data.start + i * fmt.align + c * width;
      let x;
      if (fmt.kind === 3) x = v.getFloat32(p, true);
      else if (fmt.bits === 16) x = v.getInt16(p, true) / 32768;
      else if (fmt.bits === 32) x = v.getInt32(p, true) / 2147483648;
      else {
        x = bytes[p] | (bytes[p + 1] << 8) | (bytes[p + 2] << 16);
        if (x & 0x800000) x -= 0x1000000;
        x /= 8388608;
      }
      if (!Number.isFinite(x)) throw Error("Non-finite audio sample");
      channels[c][i] = x;
    }
  return {
    sampleRate: fmt.sampleRate,
    channels,
    frames,
    duration: frames / fmt.sampleRate,
    sourceEncoding: {
      codec: fmt.kind === 1 ? "PCM" : "IEEE_FLOAT",
      bitDepth: fmt.bits,
    },
  };
}
export function encodeWav(pcm) {
  const { sampleRate, channels } = pcm;
  const frames = channels[0]?.length;
  if (!frames || channels.some((x) => x.length !== frames))
    throw Error("Invalid PCM layout");
  const size = frames * channels.length * 2;
  if (size > 0xffffffff - 36) throw Error("WAV requires RF64 above this size");
  const b = new Uint8Array(44 + size),
    v = new DataView(b.buffer),
    put = (p, s) => {
      for (let i = 0; i < s.length; i++) b[p + i] = s.charCodeAt(i);
    };
  put(0, "RIFF");
  v.setUint32(4, 36 + size, true);
  put(8, "WAVEfmt ");
  v.setUint32(16, 16, true);
  v.setUint16(20, 1, true);
  v.setUint16(22, channels.length, true);
  v.setUint32(24, sampleRate, true);
  v.setUint32(28, sampleRate * channels.length * 2, true);
  v.setUint16(32, channels.length * 2, true);
  v.setUint16(34, 16, true);
  put(36, "data");
  v.setUint32(40, size, true);
  for (let i = 0; i < frames; i++)
    for (let c = 0; c < channels.length; c++) {
      const x = channels[c][i];
      if (!Number.isFinite(x)) throw Error("Non-finite render output");
      v.setInt16(
        44 + (i * channels.length + c) * 2,
        Math.max(-32768, Math.min(32767, Math.round(x * 32768))),
        true,
      );
    }
  return b;
}
export function mixMono(pcm, targetRate = 16000) {
  finite(targetRate, 8000, 192000, "sample rate");
  const input = pcm.channels,
    n = input[0].length;
  const length = Math.floor((n * targetRate) / pcm.sampleRate),
    out = new Float32Array(length);
  // Windowed-sinc resampling with a 32-sample radius and anti-alias cutoff.
  const ratio = pcm.sampleRate / targetRate,
    cutoff = Math.min(1, 1 / ratio) * 0.94,
    radius = 32;
  for (let i = 0; i < length; i++) {
    const at = i * ratio;
    let sum = 0,
      weight = 0;
    for (
      let k = Math.max(0, Math.ceil(at - radius));
      k <= Math.min(n - 1, Math.floor(at + radius));
      k++
    ) {
      const d = at - k,
        x = Math.PI * d * cutoff,
        w =
          (x === 0 ? 1 : Math.sin(x) / x) *
          (0.5 + 0.5 * Math.cos((Math.PI * d) / radius)) *
          cutoff;
      let a = 0;
      for (const ch of input) a += ch[k];
      sum += (a / input.length) * w;
      weight += w;
    }
    out[i] = weight ? sum / weight : 0;
  }
  return {
    sampleRate: targetRate,
    channels: [out],
    frames: length,
    duration: length / targetRate,
  };
}
export function meters(pcm) {
  let peak = 0,
    sum = 0,
    clipped = 0,
    count = 0;
  for (const ch of pcm.channels)
    for (const x of ch) {
      peak = Math.max(peak, Math.abs(x));
      sum += x * x;
      clipped += Math.abs(x) >= 32767 / 32768 ? 1 : 0;
      count++;
    }
  return {
    peak,
    rms: Math.sqrt(sum / Math.max(1, count)),
    clippedSamples: clipped,
    clippedPercent: (100 * clipped) / Math.max(1, count),
  };
}
export function fft(real, imag, inverse = false) {
  const n = real.length;
  if (n !== imag.length || n < 2 || n & (n - 1))
    throw Error("FFT length must be a power of two");
  for (let i = 1, j = 0; i < n; i++) {
    let bit = n >> 1;
    for (; j & bit; bit >>= 1) j ^= bit;
    j ^= bit;
    if (i < j) {
      [real[i], real[j]] = [real[j], real[i]];
      [imag[i], imag[j]] = [imag[j], imag[i]];
    }
  }
  for (let len = 2; len <= n; len <<= 1) {
    const angle = ((inverse ? 2 : -2) * Math.PI) / len,
      wr = Math.cos(angle),
      wi = Math.sin(angle);
    for (let p = 0; p < n; p += len) {
      let r = 1,
        im = 0;
      for (let j = 0; j < len / 2; j++) {
        const a = p + j,
          b = a + len / 2,
          tr = r * real[b] - im * imag[b],
          ti = r * imag[b] + im * real[b];
        real[b] = real[a] - tr;
        imag[b] = imag[a] - ti;
        real[a] += tr;
        imag[a] += ti;
        const nr = r * wr - im * wi;
        im = r * wi + im * wr;
        r = nr;
      }
    }
  }
  if (inverse)
    for (let i = 0; i < n; i++) {
      real[i] /= n;
      imag[i] /= n;
    }
}
export function spectrum(samples, size = 512) {
  if (!Number.isInteger(size) || size < 64 || size > 8192 || size & (size - 1))
    throw Error("Invalid FFT size");
  const r = new Float64Array(size),
    im = new Float64Array(size);
  for (let i = 0; i < Math.min(size, samples.length); i++)
    r[i] = samples[i] * (0.5 - 0.5 * Math.cos((2 * Math.PI * i) / (size - 1)));
  fft(r, im);
  return Float32Array.from(
    { length: size / 2 + 1 },
    (_, k) => (r[k] * r[k] + im[k] * im[k]) / (size * size),
  );
}
export function analyze(
  pcm,
  { fftSize = 512, hopSize = 256, thresholdDb = -42, minSpeechMs = 120 } = {},
) {
  finite(hopSize, 1, fftSize, "hop");
  const samples = pcm.channels[0],
    frames = [],
    energy = [];
  let prior = null;
  for (let p = 0; p < samples.length; p += hopSize) {
    const frame = samples.subarray(p, p + fftSize),
      power = spectrum(frame, fftSize);
    let e = 0,
      z = 0,
      flux = 0;
    for (let i = 0; i < frame.length; i++) {
      e += frame[i] ** 2;
      if (i && frame[i] * frame[i - 1] < 0) z++;
    }
    if (prior)
      for (let k = 0; k < power.length; k++)
        flux += Math.max(0, power[k] - prior[k]);
    const db = 10 * Math.log10(e / Math.max(1, frame.length) + 1e-12);
    energy.push(db);
    frames.push({
      start: p / pcm.sampleRate,
      end: Math.min(samples.length, p + hopSize) / pcm.sampleRate,
      db,
      zcr: z / Math.max(1, frame.length),
      flux,
      power,
    });
    prior = power;
  }
  const floor =
    [...energy].sort((a, b) => a - b)[Math.floor(energy.length * 0.15)] ?? -100;
  const cutoff = Math.max(thresholdDb, Math.min(-24, floor + 6));
  let active = null;
  const events = [];
  const flush = () => {
    if (active && (active.end - active.start) * 1000 >= minSpeechMs) {
      active.eventId = `event-${events.length + 1}`;
      active.speechLikelihood = null;
      active.sourceAlgorithm = "energy-zcr-positive-spectral-flux/1";
      active.warnings = [
        "CANDIDATE_ONLY",
        "NOT_A_CALIBRATED_SPEECH_PROBABILITY",
      ];
      events.push(active);
    }
    active = null;
  };
  for (const f of frames) {
    const speech =
      f.db > cutoff &&
      f.zcr > 0.01 &&
      f.zcr < 0.5 &&
      (f.flux > 1e-9 || f.db > cutoff + 6);
    if (speech) {
      if (!active)
        active = {
          start: f.start,
          end: f.end,
          energySummary: { thresholdDb: cutoff },
          frequencySummary: { sampleRate: pcm.sampleRate, fftSize },
        };
      active.end = f.end;
    } else if (active && f.start - active.end > 0.16) flush();
  }
  flush();
  return {
    parameters: {
      fftSize,
      hopSize,
      window: "Hann",
      frequencyScale: "linear",
      minDb: -100,
      maxDb: 0,
      sampleRate: pcm.sampleRate,
      thresholdDb: cutoff,
    },
    frames,
    events,
  };
}
function biquad(samples, rate, type, frequency, q, gainDb = 0) {
  finite(frequency, 1, rate / 2 - 1, "frequency");
  finite(q, 0.05, 100, "Q");
  const w = (2 * Math.PI * frequency) / rate,
    c = Math.cos(w),
    s = Math.sin(w),
    alpha = s / (2 * q),
    A = 10 ** (gainDb / 40);
  let b0, b1, b2, a0, a1, a2;
  if (type === "highpass") {
    b0 = (1 + c) / 2;
    b1 = -(1 + c);
    b2 = b0;
    a0 = 1 + alpha;
    a1 = -2 * c;
    a2 = 1 - alpha;
  } else if (type === "lowpass") {
    b0 = (1 - c) / 2;
    b1 = 1 - c;
    b2 = b0;
    a0 = 1 + alpha;
    a1 = -2 * c;
    a2 = 1 - alpha;
  } else if (type === "bandpass") {
    b0 = alpha;
    b1 = 0;
    b2 = -alpha;
    a0 = 1 + alpha;
    a1 = -2 * c;
    a2 = 1 - alpha;
  } else if (type === "notch") {
    b0 = 1;
    b1 = -2 * c;
    b2 = 1;
    a0 = 1 + alpha;
    a1 = -2 * c;
    a2 = 1 - alpha;
  } else if (type === "peak") {
    b0 = 1 + alpha * A;
    b1 = -2 * c;
    b2 = 1 - alpha * A;
    a0 = 1 + alpha / A;
    a1 = -2 * c;
    a2 = 1 - alpha / A;
  } else throw Error("Unsupported biquad");
  let x1 = 0,
    x2 = 0,
    y1 = 0,
    y2 = 0;
  const out = new Float32Array(samples.length);
  for (let i = 0; i < samples.length; i++) {
    const x = samples[i],
      y = (b0 * x + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2) / a0;
    out[i] = y;
    x2 = x1;
    x1 = x;
    y2 = y1;
    y1 = y;
  }
  return out;
}
function spectralProcess(samples, rate, op) {
  const n = 512,
    hop = 128,
    out = new Float64Array(samples.length),
    norm = new Float64Array(samples.length),
    profile = op.profile || Array(n / 2 + 1).fill(10 ** (-65 / 10));
  for (let p = -n + hop; p < samples.length; p += hop) {
    const r = new Float64Array(n),
      im = new Float64Array(n);
    for (let i = 0; i < n; i++) {
      const at = p + i,
        w = 0.5 - 0.5 * Math.cos((2 * Math.PI * i) / (n - 1));
      if (at >= 0 && at < samples.length) r[i] = samples[at] * w;
    }
    fft(r, im);
    for (let k = 0; k < n; k++) {
      const b = Math.min(k, n - k),
        hz = (b * rate) / n,
        power = (r[k] ** 2 + im[k] ** 2) / (n * n);
      let mask;
      if (op.type === "isolate")
        mask =
          hz >= op.lowHz && hz <= op.highHz
            ? 10 ** (op.gainDb / 20)
            : 10 ** (op.surroundDb / 20);
      else
        mask = Math.max(
          10 ** (-op.reductionDb / 20),
          Math.sqrt(
            Math.max(
              0,
              1 - (op.strength * (profile[b] || 0)) / (power + 1e-20),
            ),
          ),
        );
      r[k] *= mask;
      im[k] *= mask;
    }
    fft(r, im, true);
    for (let i = 0; i < n; i++) {
      const at = p + i;
      if (at >= 0 && at < out.length) {
        const w = 0.5 - 0.5 * Math.cos((2 * Math.PI * i) / (n - 1));
        out[at] += r[i] * w;
        norm[at] += w * w;
      }
    }
  }
  return Float32Array.from(out, (x, i) =>
    norm[i] > 1e-10 ? x / norm[i] : samples[i],
  );
}
export function noiseProfile(pcm, start, end) {
  finite(start, 0, pcm.duration, "noise start");
  finite(end, start + 1 / pcm.sampleRate, pcm.duration, "noise end");
  const samples = pcm.channels[0],
    sum = new Float64Array(257);
  let count = 0;
  for (
    let p = Math.floor(start * pcm.sampleRate);
    p + 512 <= Math.floor(end * pcm.sampleRate);
    p += 256
  ) {
    const f = spectrum(samples.subarray(p, p + 512));
    for (let k = 0; k < f.length; k++) sum[k] += f[k];
    count++;
  }
  if (!count) throw Error("Noise profile needs at least 512 samples");
  return Array.from(sum, (x) => x / count);
}
export function render(pcm, graph, selections = []) {
  if (
    !Array.isArray(graph) ||
    graph.length > 64 ||
    !Array.isArray(selections) ||
    selections.length > 100
  )
    throw Error("Invalid DSP graph");
  const frames = pcm.channels[0].length,
    rate = pcm.sampleRate;
  for (const r of selections) {
    finite(r.start, 0, frames / rate, "region start");
    finite(r.end, r.start + 1 / rate, frames / rate, "region end");
  }
  let channels = pcm.channels.map((x) => x.slice());
  const warnings = [];
  for (const op of graph) {
    if (!op || typeof op.type !== "string")
      throw Error("Invalid DSP operation");
    let processed = channels.map((input) => {
      if (op.type === "gain") {
        finite(op.gainDb, -60, 40, "gain");
        const gain = 10 ** (op.gainDb / 20);
        return Float32Array.from(input, (x) => x * gain);
      }
      if (
        ["highpass", "lowpass", "bandpass", "notch", "peak"].includes(op.type)
      ) {
        finite(op.gainDb ?? 0, -24, 24, "EQ gain");
        return biquad(input, rate, op.type, op.frequency, op.q, op.gainDb || 0);
      }
      if (op.type === "hum") {
        finite(op.frequency, 20, 400, "hum");
        finite(op.harmonics, 1, 12, "harmonics");
        finite(op.q, 1, 100, "hum Q");
        if (!Number.isInteger(op.harmonics))
          throw Error("Integer harmonics required");
        let x = input;
        for (
          let h = 1;
          h <= op.harmonics && h * op.frequency < rate / 2 - 1;
          h++
        )
          x = biquad(x, rate, "notch", h * op.frequency, op.q);
        return x;
      }
      if (op.type === "noise") {
        finite(op.strength, 0, 1, "noise strength");
        finite(op.reductionDb, 0, 24, "noise reduction");
        if (
          op.profile &&
          (!Array.isArray(op.profile) ||
            op.profile.length !== 257 ||
            op.profile.some((x) => !Number.isFinite(x) || x < 0))
        )
          throw Error("Invalid noise profile");
        return spectralProcess(input, rate, op);
      }
      if (op.type === "isolate") {
        finite(op.lowHz, 0, rate / 2, "lower frequency");
        finite(op.highHz, op.lowHz + 1, rate / 2, "upper frequency");
        finite(op.gainDb, -24, 24, "isolation gain");
        finite(op.surroundDb, -60, 0, "surround attenuation");
        return spectralProcess(input, rate, op);
      }
      if (op.type === "delay") {
        finite(op.seconds, -5, 5, "delay");
        const offset = Math.round(op.seconds * rate);
        return Float32Array.from(input, (_, i) => input[i - offset] || 0);
      }
      if (op.type === "echoReduction" || op.type === "dereverb") {
        finite(op.delayMs, 1, 500, "estimated delay");
        finite(op.strength, 0, 0.5, "restoration strength");
        const lag = Math.max(1, Math.round((rate * op.delayMs) / 1000));
        warnings.push("ESTIMATED_RESTORATION_NOT_ORIGINAL_INFORMATION");
        return Float32Array.from(
          input,
          (x, i) => x - (i >= lag ? op.strength * input[i - lag] : 0),
        );
      }
      if (op.type === "declipping") {
        finite(op.threshold, 0.5, 1, "clipping threshold");
        warnings.push("CLIPPED_PEAK_RECONSTRUCTION_IS_AN_ESTIMATE");
        const x = input.slice();
        let i = 0;
        while (i < x.length) {
          if (Math.abs(input[i]) < op.threshold) {
            i++;
            continue;
          }
          const start = i;
          while (i < x.length && Math.abs(input[i]) >= op.threshold) i++;
          if (start === 0 || i === x.length || i - start > 4096) continue;
          const left = input[start - 1],
            right = input[i];
          for (let k = start; k < i; k++)
            x[k] = left + ((right - left) * (k - start + 1)) / (i - start + 1);
        }
        return x;
      }
      if (op.type === "limiter") {
        finite(op.ceilingDb, -12, 0, "limiter ceiling");
        const limit = 10 ** (op.ceilingDb / 20);
        return Float32Array.from(input, (x) =>
          Math.max(-limit, Math.min(limit, x)),
        );
      }
      throw Error("Unsupported DSP operation: " + op.type);
    });
    if (selections.length)
      processed = processed.map((out, c) =>
        Float32Array.from(out, (x, i) => {
          const selected = selections.some(
            (r) =>
              i >= Math.floor(r.start * rate) && i < Math.ceil(r.end * rate),
          );
          return selected ? x : channels[c][i];
        }),
      );
    channels = processed;
  }
  const output = {
    sampleRate: rate,
    channels,
    frames,
    duration: frames / rate,
  };
  if (meters(output).peak > 1)
    warnings.push("OUTPUT_WILL_CLIP_IN_PCM16_WITHOUT_LIMITER");
  return {
    pcm: output,
    graph: structuredClone(graph),
    selections: structuredClone(selections),
    warnings: [...new Set(warnings)],
    algorithm: AUDIO_VERSION,
    meters: meters(output),
  };
}
