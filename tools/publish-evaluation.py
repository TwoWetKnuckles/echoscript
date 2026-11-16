"""Publish the exact staged emulator APK as an evaluation prerelease, never a beta."""
from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import zipfile

REPOSITORY = "TwoWetKnuckles/echoscript"
APK = "EchoScript-Android10-Offline-Test-Candidate-0.6.apk"


def digest(path: Path) -> str:
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def prepare(folder: Path, output: Path, source: str, run_url: str) -> list[Path]:
    if not re.fullmatch(r"[a-f0-9]{40}", source):
        raise ValueError("A full exact source commit is required")
    if not re.fullmatch(r"https://github\.com/TwoWetKnuckles/echoscript/actions/runs/[0-9]+", run_url):
        raise ValueError("The exact repository quality-run URL is required")
    expected = {APK, "BUILD.txt", "INSTALL.txt", "LICENSE.txt", "NOTICE.txt", "SHA256SUMS.txt"}
    if {p.name for p in folder.iterdir()} != expected or any(p.is_symlink() or not p.is_file() for p in folder.iterdir()):
        raise ValueError("Unexpected or missing staged package members")
    records = {}
    for line in (folder / "SHA256SUMS.txt").read_text().splitlines():
        match = re.fullmatch(r"([a-f0-9]{64})  ([A-Za-z0-9_.-]+)", line)
        if not match or match[2] in records:
            raise ValueError("Malformed checksum inventory")
        records[match[2]] = match[1]
    if set(records) != expected - {"SHA256SUMS.txt"}:
        raise ValueError("Incomplete checksum inventory")
    for name, sha in records.items():
        if digest(folder / name) != sha:
            raise ValueError("Staged file checksum mismatch: " + name)
    build = (folder / "BUILD.txt").read_text()
    if f"Source commit {source}\n" not in build or f"Quality checks {run_url}\n" not in build:
        raise ValueError("The staged APK belongs to another source or workflow run")
    if "BETA GATE: NOT PASSED" not in (folder / "INSTALL.txt").read_text():
        raise ValueError("Evaluation limitations must remain in the installer")
    output.mkdir(parents=True, exist_ok=True)
    package = output / "EchoScript-Android10-Offline-Test-Candidate-0.6-R2.zip"
    with zipfile.ZipFile(package, "w", zipfile.ZIP_DEFLATED, compresslevel=6) as archive:
        for name in sorted(expected):
            info = zipfile.ZipInfo("EchoScript-Android/" + name, (2000, 1, 1, 0, 0, 0))
            info.compress_type = zipfile.ZIP_DEFLATED
            info.external_attr = 0o100644 << 16
            with (folder / name).open("rb") as incoming, archive.open(info, "w") as outgoing:
                shutil.copyfileobj(incoming, outgoing, 1024 * 1024)
    with zipfile.ZipFile(package) as archive:
        if archive.testzip() is not None:
            raise ValueError("The installer ZIP failed its CRC check")
    apk = output / "EchoScript-Android10-Offline-Test-Candidate-0.6-R2.apk"
    shutil.copyfile(folder / APK, apk)
    checksums = output / "SHA256SUMS.txt"
    checksums.write_text("".join(f"{digest(path)}  {path.name}\n" for path in [apk, package]))
    record = output / "BUILD-AND-VALIDATION.json"
    record.write_text(json.dumps({"sourceCommit": source, "qualityRun": run_url,
        "apkSha256": digest(apk), "installerSha256": digest(package),
        "betaGate": "NOT PASSED", "signing": "Development evaluation key; separate candidate vault",
        "scope": "Executed synthetic emulator/host checks; physical-device and complete release acceptance remain open"}, indent=2) + "\n")
    return [apk, package, checksums, record]


def gh(*args: str) -> str:
    result = subprocess.run(["gh", *args], capture_output=True, text=True)
    if result.returncode:
        raise RuntimeError("GitHub release operation failed; retain the draft and inspect the workflow")
    return result.stdout


def remote_release(tag: str) -> dict | None:
    result = subprocess.run(["gh", "api", f"repos/{REPOSITORY}/releases/tags/{tag}"], capture_output=True, text=True)
    if result.returncode == 0:
        return json.loads(result.stdout)
    if "404" in result.stderr:
        return None
    raise RuntimeError("Cannot inspect the release safely; no release was changed")


def matches_archive(release: dict, inventory: dict) -> bool:
    expected = {a["name"]: (a["bytes"], "sha256:" + a["sha256"]) for a in inventory["assets"]}
    actual = {a["name"]: (a["size"], a.get("digest")) for a in release["assets"]}
    return release["id"] == inventory["releaseId"] and actual == expected and len(actual) == len(release["assets"])


def retire_archived_rollback() -> None:
    """Retire only the exact previously backed-up release, preserving its git tag."""
    inventory = json.loads(Path("specs/archived-rollback-retirement.json").read_text())
    if inventory["tag"] != "android-preview-0.6.0-dev.20261113" or inventory["releaseId"] != 443777297:
        raise ValueError("Only the individually verified historical rollback is eligible")
    rollback = remote_release("android10-offline-test-candidate-0.6.0-dev.20261113")
    if not rollback or rollback["draft"]:
        raise RuntimeError("Revision 1 must remain published as the single rollback")
    old = remote_release(inventory["tag"])
    if old is None:
        return
    if not matches_archive(old, inventory):
        raise RuntimeError("Historical assets changed since backup; retain the old release")
    gh("api", "--method", "DELETE", f"repos/{REPOSITORY}/releases/{inventory['releaseId']}")
    print(json.dumps({"retiredArchivedRelease": inventory["tag"], "gitTagPreserved": True}))


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("folder", type=Path)
    parser.add_argument("output", type=Path)
    parser.add_argument("--source", required=True)
    parser.add_argument("--run-url", required=True)
    parser.add_argument("--prepare-only", action="store_true")
    args = parser.parse_args()
    if args.prepare_only:
        files = prepare(args.folder, args.output, args.source, args.run_url)
        print(json.dumps({"prepared": [p.name for p in files], "betaGate": "NOT PASSED"}))
        return
    if os.environ.get("GITHUB_REPOSITORY") != REPOSITORY or os.environ.get("GITHUB_REF") != "refs/heads/main" or os.environ.get("GITHUB_EVENT_NAME") != "push" or os.environ.get("GITHUB_SHA") != args.source:
        raise RuntimeError("Publication is restricted to the exact reviewed main-branch push")
    request = json.loads(Path("specs/evaluation-release.json").read_text())
    if request.get("betaGate") != "NOT PASSED" or not re.fullmatch(r"android10-offline-test-candidate-[a-z0-9.-]+", request.get("tag", "")):
        raise ValueError("Only the declared evaluation candidate may be published")
    existing = remote_release(request["tag"])
    if existing:
        target = json.loads(gh("api", f"repos/{REPOSITORY}/git/ref/tags/{request['tag']}"))["object"]
        if target["type"] != "commit":
            raise RuntimeError("Unexpected existing tag type; no release was changed")
        changed = subprocess.run(["git", "diff", "--quiet", target["sha"], args.source, "--", *request["nativeInputs"]])
        if changed.returncode:
            raise RuntimeError("Native inputs changed: declare a new approved candidate tag instead of replacing a release")
        if not existing["draft"]:
            retire_archived_rollback()
            print(json.dumps({"release": existing["html_url"], "status": "Existing verified candidate retained"}))
            return
        if target["sha"] != args.source:
            raise RuntimeError("Resume the draft from its exact original source/run")
    runs = json.loads(gh("api", f"repos/{REPOSITORY}/actions/runs?head_sha={args.source}&event=push&per_page=100"))["workflow_runs"]
    codeql = [run for run in runs if run["name"] == "CodeQL"]
    if not codeql or codeql[0]["conclusion"] != "success":
        raise RuntimeError("The exact-source CodeQL push run must pass before publication")
    files = prepare(args.folder, args.output, args.source, args.run_url)
    notes = args.output / "RELEASE-NOTES.md"
    notes.write_text(f"# {request['title']}\n\nDevelopment-signed synthetic-evaluation candidate. **BETA GATE: NOT PASSED.**\n\n"
        "Bundled local ClearVoice before/after effects, default Vosk and selectable Whisper with exact-payload approval, encrypted background local jobs, durable cancellation/recovery and the graphite/cyan workbench. All implemented tools are available.\n\n"
        f"Exact APK source: `{args.source}`. [Executed quality matrix]({args.run_url}). CodeQL passed on this source.\n\n"
        "Physical Galaxy S22+ acceptance, owner production signing/upgrades, the full accuracy/resource/recovery/security/team/trust matrix and seven complete accepted workflows remain open. Emulator subsets are not full beta acceptance.\n\n"
        "The separate candidate package preserves older vaults. Export and independently verify protected cases before uninstall/reset. See the included installation guide and checksums.\n")
    files.append(notes)
    if existing is None:
        gh("release", "create", request["tag"], "--repo", REPOSITORY, "--target", args.source,
            "--title", request["title"], "--notes-file", str(notes), "--draft", "--prerelease", "--latest=false")
    release = remote_release(request["tag"])
    if release is None:
        raise RuntimeError("The draft release could not be verified")
    for path in files:
        asset = next((a for a in release["assets"] if a["name"] == path.name), None)
        if asset:
            if asset["size"] != path.stat().st_size or asset.get("digest") != "sha256:" + digest(path):
                raise RuntimeError("An existing draft asset differs; it was not overwritten")
        else:
            gh("release", "upload", request["tag"], str(path), "--repo", REPOSITORY)
    release = remote_release(request["tag"])
    for path in files:
        asset = next((a for a in release["assets"] if a["name"] == path.name), None)
        if not asset or asset["size"] != path.stat().st_size or asset.get("digest") != "sha256:" + digest(path):
            raise RuntimeError("Uploaded asset verification failed; release remains a draft")
    gh("release", "edit", request["tag"], "--repo", REPOSITORY, "--draft=false", "--prerelease", "--latest=false")
    retire_archived_rollback()
    print(json.dumps({"release": release["html_url"], "source": args.source, "betaGate": "NOT PASSED"}))


if __name__ == "__main__":
    main()
