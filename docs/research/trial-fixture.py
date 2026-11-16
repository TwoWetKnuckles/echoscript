"""Regenerate the non-speech trial input. Requires NumPy; creates a new file only."""
import hashlib
from pathlib import Path
import wave
import numpy as np

output = Path('synthetic-tone-hum.wav')
if output.exists():
    raise FileExistsError('Refusing to replace an existing fixture')
sample_rate = 16000
t = np.arange(12 * sample_rate) / sample_rate
rng = np.random.default_rng(1234)
x = .05 * np.sin(2 * np.pi * 60 * t) + .012 * rng.normal(size=t.size)
x += ((t >= 2) & (t < 10)) * .12 * np.sin(2 * np.pi * 1000 * t)
pcm = np.rint(np.clip(x, -1, 1) * 32767).astype('<i2')
with wave.open(str(output), 'wb') as stream:
    stream.setnchannels(1)
    stream.setsampwidth(2)
    stream.setframerate(sample_rate)
    stream.writeframes(pcm.tobytes())
expected = '1809a959e2a9fd637eb947b9a48d7b2460aab6a3dfaf163ede9d99f2bfc41d36'
actual = hashlib.sha256(output.read_bytes()).hexdigest()
if actual != expected:
    raise RuntimeError('Fixture differs; record the NumPy/platform and inspect before use')
print(actual)
