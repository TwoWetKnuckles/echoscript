"""Prepare the Android APK's pinned offline model assets at build time."""
import hashlib
import json
import pathlib
import shutil
import sys
import tempfile
import urllib.request
import zipfile

BASE = "https://alphacephei.com/vosk/models/"
MODELS = {
    "vosk-model-small-en-us-0.15": "30f26242c4eb449f948e42cb302dd7a686cb29a3423a8367f99ff41780942498",
    "vosk-model-spk-0.4": "a74d8f51144484813e16af689bb0f916b7a111e2347f467c4933c1166097b5a7",
}


def digest(path):
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def download(cache, name, expected):
    archive = cache / (name + ".zip")
    if not archive.exists():
        with tempfile.NamedTemporaryFile(dir=cache, delete=False) as output:
            temporary = pathlib.Path(output.name)
            try:
                with urllib.request.urlopen(BASE + archive.name, timeout=90) as response:
                    total = 0
                    while chunk := response.read(65536):
                        total += len(chunk)
                        if total > 80 * 1024 * 1024:
                            raise ValueError("Model download exceeds archive policy")
                        output.write(chunk)
                output.flush()
                if digest(temporary) != expected:
                    raise ValueError(f"Model archive hash mismatch: {name}")
                temporary.replace(archive)
            finally:
                temporary.unlink(missing_ok=True)
    if digest(archive) != expected:
        raise ValueError(f"Cached model archive hash mismatch: {name}")
    return archive


def build(destination, cache):
    cache.mkdir(parents=True, exist_ok=True)
    destination.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(dir=destination.parent) as directory:
        stage = pathlib.Path(directory) / "assets"
        models = stage / "offline-models"
        models.mkdir(parents=True)
        inventory = []
        for name, expected in MODELS.items():
            archive = download(cache, name, expected)
            files = []
            with zipfile.ZipFile(archive) as source:
                if len(source.infolist()) > 256 or sum(e.file_size for e in source.infolist()) > 160 * 1024 * 1024:
                    raise ValueError("Model extraction exceeds policy")
                for entry in source.infolist():
                    relative = pathlib.PurePosixPath(entry.filename)
                    if relative.is_absolute() or ".." in relative.parts or "\\" in entry.filename or relative.parts[0] != name:
                        raise ValueError("Unexpected model path")
                    if entry.is_dir():
                        continue
                    target = models / relative
                    target.parent.mkdir(parents=True, exist_ok=True)
                    with source.open(entry) as content, target.open("xb") as output:
                        shutil.copyfileobj(content, output, 65536)
                    files.append({"path": str(relative), "bytes": entry.file_size, "sha256": digest(target)})
            inventory.append({"name": name, "archiveSha256": expected, "files": files})
        (models / "inventory.json").write_text(json.dumps({"models": inventory}, separators=(",", ":")), encoding="utf-8")
        root = pathlib.Path(__file__).resolve().parents[1]
        shutil.copyfile(root / "LICENSE", models / "Apache-2.0.txt")
        (models / "NOTICE.txt").write_text(
            "Vosk Android and model assets by Alpha Cephei and their contributors.\n"
            "Models: vosk-model-small-en-us-0.15 and vosk-model-spk-0.4.\n"
            "License: Apache-2.0. Source catalog: https://alphacephei.com/vosk/models\n"
            "Runtime source: https://github.com/alphacep/vosk-api\n",
            encoding="utf-8",
        )
        if destination.exists():
            shutil.rmtree(destination)
        stage.replace(destination)
    print("Packaged two verified offline Vosk models")


if __name__ == "__main__":
    build(pathlib.Path(sys.argv[1]), pathlib.Path(sys.argv[2]))
