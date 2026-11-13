# EchoScript

Local-first audio evidence development platform, designed to support defensible forensic-audio analysis, transcription, evidence preservation, and chain-of-custody workflows.

## Download for Android

**[Download the latest APK](https://github.com/TwoWetKnuckles/echoscript/releases/download/android-preview-0.6.0-dev.20261113/EchoScript-offline-preview.apk)** · **[Download ZIP with instructions](https://github.com/TwoWetKnuckles/echoscript/releases/download/android-preview-0.6.0-dev.20261113/EchoScript-Android-preview.zip)** · [Release notes and verified checksums](https://github.com/TwoWetKnuckles/echoscript/releases/tag/android-preview-0.6.0-dev.20261113) · [Installation guide](downloads/android/INSTALL-CANDIDATE.txt)

**EchoScript 0.6 Test** is the latest development preview for synthetic evaluation. Android 10 or later. It includes explicit selected-file confirmation/import/retry, the Material-dark workbench, waveform/spectrum selection and playback, five removable presets, 16 signed effect controls, ordered overlapping regions, clips and restorable render history. Searchable examiner notes stay separate from spoken text. The bundled English speech model runs on the device without an account or server.

The APK was built from `cc19e7a3c49eed1fdc1b32064fb27a21531f87df`. Android 10/16 emulator suites, all seven quality jobs and both CodeQL jobs passed. [The validation record](downloads/android/VALIDATION-0.6.0.txt) distinguishes those subsets from the open physical-device and complete-product gates. **BETA GATE: NOT PASSED.** This is a debuggable, development-signed test app.

It uses `org.echoscript.app.preview06` and a separate, initially empty vault alongside 0.5.1. Separate 0.6 CI builds can have different signing keys. Do not remove or reset any app until needed cases have been exported, independently verified and safely stored; uninstalling can erase its vault. See [downloads and checksums](downloads/README.md). [Previous 0.5.1 preview](https://github.com/TwoWetKnuckles/echoscript/releases/tag/android-preview-0.5.1) remains available.

### Workbench and proposed plans

The optional full-recording scan is a coarse spectral overview; short-range inspection provides separate FFT/hop/window controls. It is not a high-resolution full-file spectrogram. Restoration, noise-floor gating and clipping counts retain their documented limits. [Workbench guide](docs/android/ENHANCEMENT-WORKBENCH.md) · [Design direction](docs/android/WORKBENCH-DESIGN.md).

All implemented tools are available without artificial usage restrictions. External provider billing and technical resource limits still apply.

The [owner-confirmed scope](docs/WORKING-SCOPE.md) includes items 1–20 and 23–28; browser parity and Termux are deferred. Formal exact-build Galaxy S22+ acceptance, outstanding feature work, owner signing/upgrades and the [complete release gates](docs/RELEASE-CANDIDATE.md) remain open. Protected case transfer and device checkpoints are available; [trust and recovery boundaries](docs/evidence/PROTECTED-TRANSFER.md) still apply.

### Local file selection

In **Cases**, choose a recording, confirm its name under **Selected recording**, then tap **Import selected audio**. The panel shows progress and keeps a retry action on failure. **Choose with another file picker** offers Android's alternative picker. All openable files are shown, including recordings with incorrect MIME labels; the decoder determines supported audio from its contents. Emulator system-picker tests pass. The original Galaxy-specific cause is not established by the owner's preliminary positive report.

## Documentation site

The separate [Fumadocs app](apps/docs/README.md) lives in `apps/docs`, with its own Next.js layout, styles, dependencies and deployment. It retains the default `/docs` route, search, Markdown/LLM endpoints and generated social images. Its guides mirror the canonical repository Markdown with frontmatter and link adjustments; the existing Android and browser apps do not import its styles.

From `apps/docs`, run `npm ci --ignore-scripts`, `npm run sync:content` and `npm run dev`. Build with `npm run build` and validate the served pages/search/exports with `npm run verify:site`. Separate hosting setup is documented in the app README; a live documentation deployment is not yet claimed.

## Responsibility and limitations

**The examiner and deploying organization are responsible for lawful use and case-specific evidence handling.** Before each use, determine the recording/access authority, consent requirements, jurisdiction, agency and court rules, confidentiality obligations, retention and legal holds applicable to that particular matter. Preserve originals and independent custody records; control devices, keys and exports; validate the exact build and workflow; independently review all processing, transcription and reports before relying on or submitting them. A hash match establishes byte consistency against a trusted reference, not lawful acquisition, speaker identity, completeness or legal admissibility.

Original project code is provided **AS IS, without warranties**, with the limitations and exceptions in [Apache License 2.0](LICENSE), especially sections 7–9. Contributors do not undertake your case management or forensic duties merely by distributing this software. No guarantee is made about fitness for a particular case, accuracy, security, uninterrupted service or an evidentiary outcome. Applicable law and separately authorized written agreements control; these notices cannot exclude non-waivable rights or guarantee immunity from liability.

Read the [responsibility and disclaimer notice](DISCLAIMER.md), [licensing guide](docs/legal/LICENSING.md), [security policy](SECURITY.md) and [operator responsibilities](docs/security/OPERATIONS.md). These notices do not replace engineering safeguards, independent examination or jurisdiction-specific legal advice. They do not change the Apache license or create an additional use restriction.

## What works

| Component                   | Available in the development preview                                                                                                                                                                                                                                |
| --------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Android 10+                 | Encrypted original intake, waveform/spectral overview, speech-region candidates, non-destructive WAV rendering, offline English transcription, draft speaker groups, examiner revisions, audit and explicit case transfer                                           |
| Browser companion           | Session-only PCM WAV workbench with time-frequency selections, deterministic DSP and revision comparisons. Real Vosk speech is available through the separately started loopback service                                                                            |
| Android and browser exports | WAV, transcript and document formats, case package and SHA-256. Native protected case containers and optional device-signed historical checkpoints; individual files and legacy ZIPs remain plaintext. Browser encrypted case export and processing-history bundles |
| Independent tools           | Streaming Node case verifier and bounded-buffer Rust PCM16 renderer for development and independent checks                                                                                                                                                          |

The [Android guide](docs/android/OFFLINE.md) lists native filters, codecs and device limits. Draft speaker groups are local acoustic estimates. The loopback web adapter leaves speaker identity unassigned. Both paths preserve returned score semantics and alternatives when available. CTM requires genuine word alignment. Human text edits invalidate that alignment.

## Run the browser companion locally

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

## Build Android from source

For installation without development tools, use the [Android download](downloads/README.md).

The pinned build uses JDK 17, Gradle 9.8.2, AGP 9.5.0 and Android SDK/build tools 36.0.0. Android 10/API 29 is the minimum. With an installed SDK, accepted SDK license and Python 3.11 or later on the build host:

```sh
cd apps/android
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug :app:assembleRelease
./gradlew :app:connectedDebugAndroidTest
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

The 0.6 debug draft uses `org.echoscript.app.preview06`, separate from the 0.5.1 preview and its vault. Side-by-side installation passed an Android 16 emulator check; physical-device validation remains open. The release APK is unsigned until an independently controlled signing configuration is supplied. The manual [owner-signing workflow](docs/android/OWNER-SIGNING.md) verifies a pinned certificate and produces a non-debug evaluation artifact, without publishing or declaring readiness. CI stages a development-signed APK and SHA-256 after the Android 16 emulator checks. Published install ZIPs are linked from [downloads](downloads/README.md). Signing keys stay outside Git.

Native acquisition has a 4 GiB preservation policy. Processing accepts sources up to 750 MB, with a separate decoded WAV and disk-space limit. The app streams source/derivative bytes and stores new revisions separately. Vosk and draft speaker grouping run in-process. Vosk remains on device by default. Optional companion processing requires explicit payload approval and network access. See the offline guide for codec, editing, transfer and recovery limits.

Source keys are protected by Android Keystore; Tink Streaming AEAD encrypts persistent app-private files. Android cloud backup and device transfer are excluded. Device reset/key loss is unrecoverable without a separately preserved export. Preview 0.5.1 offers an opt-in streaming protected case container and device-signed historical checkpoints. Individual audio/documents and legacy case ZIPs remain plaintext. Protect destinations and retain hashes, passwords and independently trusted signer fingerprints separately.

## Verification and QA

```sh
node tools/case-verify/index.mjs case.echocase --sha256 INDEPENDENTLY_RETAINED_PACKAGE_HASH
```

For encrypted browser packages, supply `ECHOSCRIPT_PACKAGE_PASSPHRASE` through the environment. The verifier never repairs packages. It checks schemas, canonical manifest bytes, SHA-256, inventory, ZIP CRC, provenance and audit linkage. Unsigned hashes do not establish identity or custody against an attacker who can rewrite an entire package. Keep trusted external checkpoints.

For native protected containers, build the [independent Java tool](tools/case-protect/README.md) and supply the same environment variable. Add `--trusted-key-sha256 INDEPENDENT_SIGNER_FINGERPRINT` to require a checkpoint covering all current evidence from an independently trusted key. Signature validity, unsigned tail events, examiner identity and organizational trust are reported separately. Verification does not stage a plaintext native ZIP.

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

[Architecture](docs/architecture.md) · [File map](docs/FILE-TREE.md) · [Provider qualification](docs/providers/local-vosk.md) · [Threat model](docs/security/THREAT_MODEL.md) · [Contributing](CONTRIBUTING.md) · [Implementation record](docs/IMPLEMENTATION.md) · [Release candidate gates](docs/RELEASE-CANDIDATE.md)

Original project code: Apache-2.0; see [LICENSE](LICENSE) and [NOTICE](NOTICE). Preserve dependency/model licenses and the bundled Noto font's SIL Open Font License. Third-party services have separate terms. Fixture provenance is recorded in `specs/fixtures/`; it contains synthetic speech, not real evidence or a cloned person's voice. Open-source availability does not include an ongoing support, hosting or service-level commitment.
