# EchoScript

Local-first audio evidence development platform, designed to support defensible forensic-audio analysis, transcription, evidence preservation, and chain-of-custody workflows.

**Development checkpoint — BETA GATE: NOT PASSED.** A working browser/local-provider workflow and encrypted Android intake vault are implemented. The complete Android forensic application described in the build specification is unfinished. Use synthetic material for evaluation. No admissibility, organizational compliance or certification claim is made.

## What works

| Component         | Implemented behavior                                                                                                                                                                                                                      |
| ----------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Android 10+       | Storage Access Framework file import, original-byte SHA-256, persistent Keystore/Tink encrypted storage, authenticated streaming playback, encrypted immutable manifest snapshots, audit/hash verification, case export with package hash |
| Browser workbench | PCM WAV import; waveform/spectrogram; conservative speech-event candidates; time/frequency regions; deterministic non-destructive DSP; revision branches, undo/redo and comparison playback                                               |
| DSP               | Gain/preamp, low/mid/high parametric EQ, HPF/LPF/band-pass, hum/harmonic notches, tonal suppression, noise profiling/reduction, spectral isolation, delay, limiter, estimated echo/reverb reduction and conservative de-clipping          |
| Transcription     | Real offline Vosk decoder through an authenticated loopback HTTP API; exact transmission/raw-result artifacts; word timing; returned decoder scores and genuine alternatives; acoustic review flags; immutable human-review revisions     |
| Export            | WAV, processing changelog, TXT/JSON/HTML/CSV/SRT/WebVTT/CTM/PDF/DOCX/XLSX; encrypted browser case packages; separate hashes for artifacts and exports                                                                                     |
| Independent tools | Streaming Node case verifier; bounded-buffer Rust PCM16 gain/filter renderer; synthetic end-to-end and hour-scale desktop resource checks                                                                                                 |

Automatic candidates and restoration estimates do not recover missing original information. Vosk N-best scores are not calibrated probabilities. This adapter has no speaker diarization; it never invents speakers, confidence or alternatives. CTM requires genuine word alignment; human text changes invalidate that alignment. Invalid timestamps remain flagged in JSON; subtitle/CTM exports require valid timing.

## Run locally

Use Node.js 24, npm, and Python 3.12 for the tested speech path:

```sh
npm ci --ignore-scripts
npm run build
npm run lint
npm test
npm run serve
```

Open the loopback URL printed by the server. Select **Cases → Load synthetic speech sample** to evaluate without real evidence. Browser evidence is session memory, with a **16 MiB source / 64 MiB workspace** policy. Export an encrypted case before closing; browser memory is not the Android vault.

For real local transcription:

```sh
python3 -m venv .venv
.venv/bin/pip install -r services/api/requirements.txt -r services/api/qa-requirements.txt
python3 scripts/setup-model.py
export VOSK_PYTHON="$PWD/.venv/bin/python"
export DOC_PYTHON="$PWD/.venv/bin/python"
export VOSK_MODEL_PATH="$PWD/models/vosk-model-small-en-us-0.15"
npm run qa:local
```

The model installer downloads weights from the official Vosk source and checks the pinned archive hash. The worker independently checks every model file against the committed inventory. It does not upload evidence.

For interactive transcription, run the API in another terminal after setting a **random session token of at least 32 characters** without committing or logging it:

```sh
read -r -s -p 'Local API token: ' API_TOKEN
export API_TOKEN
export ALLOWED_ORIGIN='http://127.0.0.1:4173'
npm run api
```

Set the matching API URL/token in Settings, explicitly disable the provider prohibition, then prepare and approve the exact transcription artifact. The service binds only to `127.0.0.1`, writes no evidence files and logs no audio/transcripts/tokens. Do not expose this development-token service to a team or the Internet. A hosted HTTPS companion may be unable to reach a local HTTP service because of browser network policy; use the local host for this workflow instead of disabling browser security.

## Android

The pinned build uses JDK 17, Gradle 9.8.2, AGP 9.5.0 and Android SDK/build tools 36.0.0. Android 10/API 29 is the minimum. With an installed SDK and accepted SDK license:

```sh
cd apps/android
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug :app:assembleRelease
./gradlew :app:connectedDebugAndroidTest
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

The debug APK is for development. The release APK is unsigned until an independently controlled signing configuration is supplied. CI publishes development APKs and reports with short retention; no signing key is committed.

Native source acquisition has a 4 GiB policy limit and bounded buffers; that limit is not an executed 4 GiB device stress claim. Playback codec support depends on Media3/device capabilities. Native analysis, DSP, transcription, microphone acquisition and job recovery are outstanding. No Internet permission is declared by the current native client.

Source keys are protected by Android Keystore; Tink Streaming AEAD encrypts persistent app-private files. Android cloud backup and device transfer are excluded. Device reset/key loss is unrecoverable without a separately preserved export. Native exports are explicitly **unencrypted and unsigned**; protect their destinations.

## Verification and QA

```sh
node tools/case-verify/index.mjs case.echocase --sha256 INDEPENDENTLY_RETAINED_PACKAGE_HASH
```

For encrypted browser packages, supply `ECHOSCRIPT_PACKAGE_PASSPHRASE` through the environment. The verifier never repairs packages. It checks schemas, canonical manifest bytes, SHA-256, inventory, ZIP CRC, provenance and audit linkage. Unsigned hashes do not establish identity or custody against an attacker who can rewrite an entire package. Keep trusted external checkpoints.

```sh
cargo fmt --all --check
cargo clippy --workspace --all-targets -- -D warnings
cargo test --workspace --locked
cargo build --workspace --release --locked
python3 tools/qa-stress.py
```

`qa:local` executes seven consecutive **local web-engine + real Vosk HTTP** workflows and fails on workflow/resource defects. `qa-stress.py` generates a one-hour synthetic PCM source, measures child maximum RSS, checks deterministic Rust renders/source preservation/failure cleanup, and streams a large package through the independent verifier. Neither is the full Android/application beta gate. See [executed results and remaining work](docs/qa/STATUS.md).

## Data, hosting and cost boundary

Source audio, derivatives, transcripts, notes, audit and manifests remain local. The current application sends no evidence to Supabase. OIDC/team roles and approved preference/workload synchronization are outstanding; the existing Supabase reference project is not an evidence store. External speech adapters stay disabled until deployment, retention, agreements, credentials and cost are qualified.

The tested path uses open-source local speech and no paid speech API. Workflows use standard public-repository runners; [GitHub documents these as free](https://docs.github.com/en/billing/concepts/product-billing/github-actions), with separate artifact/cache limits. No paid service, larger runner, billing change or domain registration was performed. Existing hosting/account quotas and ongoing operational support are not guaranteed by a free tier. [GitHub Pages usage limits](https://docs.github.com/en/pages/getting-started-with-github-pages/github-pages-limits) do not permit commercial SaaS hosting.

The existing companion is published through Sites with its existing owner-private audience. The public repository does not change that audience. Optional Pages publishing stays disabled until a repository administrator enables GitHub Actions as the Pages source and sets the non-secret repository variable `GITHUB_PAGES_ENABLED=true`.

## Project map and licensing

[Architecture](docs/architecture.md) · [File map](docs/FILE-TREE.md) · [Provider qualification](docs/providers/local-vosk.md) · [Security](SECURITY.md) · [Implementation record](docs/IMPLEMENTATION.md)

Original project code: Apache-2.0. Preserve dependency/model licenses and the bundled Noto font's SIL Open Font License. Fixture provenance is recorded in `specs/fixtures/`; it contains synthetic speech, not real evidence or a cloned person's voice.
