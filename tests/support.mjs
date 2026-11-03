import { encodeWav } from "../packages/audio/index.mjs";
export function toneBytes() {
  return encodeWav({
    sampleRate: 16000,
    channels: [
      Float32Array.from(
        { length: 16000 },
        (_, i) => 0.1 * Math.sin((2 * Math.PI * 300 * i) / 16000),
      ),
    ],
  });
}
