"""Explicit model preparation, before processing any evidence. No inference downloads."""
import argparse
import hashlib
import json
from pathlib import Path
from huggingface_hub import snapshot_download

parser = argparse.ArgumentParser()
parser.add_argument("home", type=Path)
parser.add_argument("--revision", required=True, help="Reviewed 40-character Hugging Face model commit")
args = parser.parse_args()
if len(args.revision) != 40 or any(c not in "0123456789abcdef" for c in args.revision):
    parser.error("an exact model commit is required")
model = "FRCRN_SE_16K"
destination = args.home / "checkpoints" / model
snapshot_download(repo_id=f"alibabasglab/{model}", revision=args.revision, local_dir=destination)
marker = (destination / "last_best_checkpoint").read_text().strip()
if Path(marker).name != marker or marker in (".", ".."):
    raise ValueError("invalid checkpoint filename")
files = []
for name in ["last_best_checkpoint", marker]:
    path = destination / name
    with path.open("rb") as stream:
        files.append({"path": name, "bytes": path.stat().st_size, "sha256": hashlib.file_digest(stream, "sha256").hexdigest()})
inventory = {"model": model, "revision": args.revision, "runtime": "clearvoice/0.1.2", "files": files}
args.home.mkdir(parents=True, exist_ok=True)
(args.home / "inventory.json").write_text(json.dumps(inventory, sort_keys=True, separators=(",", ":")))
print("Prepared pinned ClearVoice checkpoint and SHA-256 inventory. No evidence was processed.")
