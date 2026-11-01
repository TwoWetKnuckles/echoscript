import { test } from "node:test";
import assert from "node:assert/strict";
import {
  decodeWav,
  encodeWav,
  render,
  meters,
  fft,
  analyze,
  mixMono,
  noiseProfile,
} from "../packages/audio/index.mjs";
import { digest } from "../packages/evidence/index.mjs";
function tone(hz, seconds = 1, amplitude = 0.1) {
  const rate = 16000,
    channels = [
      Float32Array.from(
        { length: rate * seconds },
        (_, i) => amplitude * Math.sin((2 * Math.PI * hz * i) / rate),
      ),
    ];
  return {
    sampleRate: rate,
    channels,
    frames: channels[0].length,
    duration: seconds,
  };
}
test("PCM WAV roundtrip and malformed headers", () => {
  const x = tone(1000),
    bytes = encodeWav(x),
    d = decodeWav(bytes);
  assert.equal(d.frames, x.frames);
  assert.equal(d.sampleRate, 16000);
  assert.ok(Math.abs(meters(d).rms - meters(x).rms) < 0.0001);
  assert.throws(() => decodeWav(bytes.slice(0, -1)));
});
test("gain, region bounds and deterministic rendering preserve parent samples", () => {
  const x = tone(1000),
    original = encodeWav(x),
    g = [{ type: "gain", gainDb: 6.020599913279624 }],
    a = render(x, g),
    b = render(x, g);
  assert.ok(Math.abs(meters(a.pcm).rms / meters(x).rms - 2) < 1e-6);
  assert.equal(digest(encodeWav(a.pcm)), digest(encodeWav(b.pcm)));
  assert.deepEqual(encodeWav(x), original);
  const r = render(x, g, [{ start: 0.25, end: 0.5 }]);
  assert.equal(r.pcm.channels[0][100], x.channels[0][100]);
  assert.throws(() => render(x, [{ type: "gain", gainDb: Infinity }]));
});
test("HPF, LPF, notch and harmonic hum suppress known synthetic components", () => {
  for (const [hz, op] of [
    [60, { type: "highpass", frequency: 300, q: 0.707 }],
    [6000, { type: "lowpass", frequency: 1000, q: 0.707 }],
    [60, { type: "notch", frequency: 60, q: 10 }],
    [120, { type: "hum", frequency: 60, harmonics: 3, q: 15 }],
  ]) {
    const input = tone(hz, 2),
      out = render(input, [op]);
    const late = { channels: [out.pcm.channels[0].subarray(16000)] };
    assert.ok(meters(late).rms / meters(input).rms < 0.12, JSON.stringify(op));
  }
});
test("FFT inverse vector and band isolation are numerically stable", () => {
  const r = Float64Array.from([1, 2, 3, 4, 5, 6, 7, 8]),
    im = new Float64Array(8),
    before = r.slice();
  fft(r, im);
  fft(r, im, true);
  r.forEach((x, i) => assert.ok(Math.abs(x - before[i]) < 1e-10));
  const input = tone(6000),
    out = render(input, [
      { type: "isolate", lowHz: 100, highHz: 2000, gainDb: 0, surroundDb: -40 },
    ]);
  assert.ok(meters(out.pcm).rms < meters(input).rms * 0.03);
});
test("delay, limiter, clipping visualization and restoration disclose estimates", () => {
  const input = tone(200, 0.5, 2),
    out = render(input, [{ type: "limiter", ceilingDb: -1 }]);
  assert.ok(meters(out.pcm).peak <= 10 ** (-1 / 20) + 1e-6);
  assert.ok(meters(input).clippedSamples > 0);
  const d = render(input, [{ type: "delay", seconds: 0.01 }]);
  assert.equal(d.pcm.channels[0][0], 0);
  const r = render(input, [
    { type: "declipping", threshold: 0.98 },
    { type: "dereverb", delayMs: 30, strength: 0.1 },
  ]);
  assert.ok(r.warnings.length >= 2);
});
test("silence is not declared speech; spectral VAD exposes candidates only", () => {
  const input = tone(0);
  assert.equal(analyze(input).events.length, 0);
  const speech = tone(300);
  const a = analyze(speech);
  for (const e of a.events) {
    assert.equal(e.speechLikelihood, null);
    assert.ok(e.warnings.includes("CANDIDATE_ONLY"));
  }
});
test("noise profiling and resampling have bounded valid output", () => {
  const input = tone(500),
    profile = noiseProfile(input, 0, 0.1);
  const out = render(input, [
    { type: "noise", strength: 0.5, reductionDb: 12, profile },
  ]);
  assert.ok(meters(out.pcm).rms < meters(input).rms);
  const resampled = mixMono(input, 8000);
  assert.equal(resampled.channels[0].length, 8000);
  assert.ok(Math.abs(meters(resampled).rms - meters(input).rms) < 0.001);
});
