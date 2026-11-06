"""Create codec variants from the committed, non-sensitive synthetic speech fixture."""
import hashlib
import json
import pathlib
import subprocess

root = pathlib.Path(__file__).resolve().parents[1]
source = root / "specs/fixtures/synthetic-speech.wav"
output = root / "apps/android/app/build/codec-fixtures"
output.mkdir(parents=True, exist_ok=True)
formats = {
    "mp3": ["-c:a", "libmp3lame", "-b:a", "96k"],
    "m4a": ["-c:a", "aac", "-b:a", "96k"],
    "flac": ["-c:a", "flac"],
    "ogg": ["-c:a", "libvorbis", "-q:a", "4"],
    "opus": ["-c:a", "libopus", "-b:a", "40k"],
}
provenance = {
    "classification": "SYNTHETIC TEST DATA — NOT EVIDENCE",
    "sourceSha256": hashlib.sha256(source.read_bytes()).hexdigest(),
    "generator": subprocess.check_output(["ffmpeg", "-version"], text=True).splitlines()[0],
    "files": [],
}
for extension, options in formats.items():
    target = output / f"synthetic-codec.{extension}"
    subprocess.run(["ffmpeg", "-nostdin", "-hide_banner", "-loglevel", "error", "-y", "-i", str(source), "-map_metadata", "-1", "-metadata", "comment=SYNTHETIC TEST DATA - NOT EVIDENCE", *options, str(target)], check=True)
    provenance["files"].append({"name": target.name, "sha256": hashlib.sha256(target.read_bytes()).hexdigest(), "options": options})
(output / "codec-fixtures.json").write_text(json.dumps(provenance), encoding="utf-8")
print("Generated five synthetic codec fixtures")
