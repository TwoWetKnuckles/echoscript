# Executed validation and release limits

Development checkpoint. Historical measurements retain their original run dates. Results apply only to the executed matrix; they do not establish defect absence.

| Check                                   | Observed checkpoint                                                                                                                                                                                                                                |
| --------------------------------------- | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| GitHub repository                       | Public `TwoWetKnuckles/echoscript`; application source and synthetic fixtures only; no case data or credentials authorized for publication                                                                                                         |
| JavaScript/TypeScript                   | Browser/API production build and strict TypeScript compilation pass                                                                                                                                                                                |
| Node behavioral tests                   | 25 local pass: DSP, integrity, canonical JSON, audit/provenance, package round trip, tampering, archive safety, encryption authentication, native document structure, API authorization/consent/replay/error paths, independent streaming verifier |
| Production npm audit                    | 0 findings reported by registry at checkpoint; scan scope is known advisories, not proof of safety                                                                                                                                                 |
| Rust                                    | Three unit tests, clippy and release build executed; a clippy modernization finding was repaired                                                                                                                                                   |
| Browser QA                              | Actual synthetic file loaded; waveform/spectrogram and speech candidates shown; selection rendered to the same derivative hash as Node; playback reached end without media error; undo preserved derivative history                                |
| Local Vosk HTTP seven-run sequence      | Seven consecutive local web-engine + real local API workflows passed; zero word error on the one controlled clean fixture; enhanced hash identical across runs                                                                                     |
| PDF/DOCX/XLSX                           | Generated documents were opened by PDF inspection and Python document parsers during the executed local sequence                                                                                                                                   |
| Android                                 | Debug APK, two JVM tests, native lint, instrumentation APK compilation and unsigned release build passed after scoped Media3 API opt-in repair; lint warnings include older compatible dependencies and an obsolete lifecycle custom check         |
| Android emulators                       | Android 10 and 16 passed vault, UI, codec and seven core offline workflows per emulator on `1ab80e0d`. See the native preview run below.                                                                                                           |
| Full application seven-run release gate | NOT PASSED; the local sequence is a subset                                                                                                                                                                                                         |

The local sequence performs source import/hash → decode → waveform/spectrogram data → speech-event selection → deterministic region render/preview bytes → transmission artifact → explicit synthetic approval → real Vosk API → exact raw result → normalized timestamps/score → revision exercise → WAV/documents/changelog → encrypted case export → import → independent verifier. It does not exercise Android UI, diarization, microphone, all codecs, full identity/sync or deployed external providers.

## Material outstanding release work

Native feature parity and physical-device validation; resumable jobs and crash/restart recovery; biometric unlock/key recovery; digital signatures and trusted checkpoints; diarization accuracy on overlap and multiple speakers; qualified external adapters; OIDC, multi-user roles, approved preference/workflow sync; convenience encoders; complete cross-user/security/resource/device matrix; seven consecutive complete application runs.

## Failure repairs recorded

- Build output used `.js` while HTML/worker URLs expected `.mjs`: set the bundler output extension explicitly.
- Runtime schema compilation would need browser `unsafe-eval`: replaced it with a generated standalone validator.
- Package import needed stricter canonical/duplicate-key and archive integrity handling: enforce canonical manifest bytes and independent ZIP CRC validation.
- Rust lint required the current array-chunk API: updated without suppressing the check.
- Native build environment lacked compiler/proxy/certificate setup: installed a verified JDK, retained certificate verification and used the executor's trusted CA store.
- Media3 unstable API use was not explicitly opted into: added scoped opt-in annotations, without disabling lint.
- Preview revealed low-contrast status/notice text: corrected foreground colors.

No real evidence or paid provider API was used. No compliance, admissibility, production-readiness or beta-readiness claim is made.

## Fresh-checkout failure and resource checkpoint

GitHub run 41342952340 isolated the decoder failure to a missing committed model inventory. The `models/` ignore pattern unintentionally matched `specs/models/`. The repaired commit 80ced026 anchors the ignored weight directory at the root and imports the inventory at build time. All five CI jobs passed in [run 41343155744](https://github.com/TwoWetKnuckles/echoscript/actions/runs/41343155744), including the real decoder sequence and Android API 29/36 instrumentation. This does not pass unfinished application requirements.

The latest local sequence uses real loopback HTTP, mandatory DOCX/XLSX/PDF parsers and post-worker resource thresholds (64 MiB heap, 512 MiB RSS, descriptor growth at most four). Seven consecutive runs passed; exact observations are in `local-seven-run-results.json`. The controlled clean fixture had zero WER in those runs; this is not a real-world accuracy claim.

A 3,600-second / 115,200,044-byte generated PCM fixture passed two deterministic streaming renders, source-preservation, existing-output refusal, truncated-render cleanup and large-package independent verification. Measured child maximum RSS was about 9 MiB for the Rust commands and 106 MiB for verification. See `desktop-hour-results.json`; the test is desktop-only and does not cover full Android memory, battery or crash recovery.

Further repairs enforce complete explicit segment review before full human verification, reject invalid confidence semantics, retain timestamp anomalies, refuse invalid subtitle/CTM timing, commit analysis summaries as hashed provenance artifacts, report corrupted native snapshots, and retain export hashes in native audit records. These additional tests are recorded as executed only after their respective checks run.

The native-to-desktop transfer check initially failed after instrumentation had succeeded. Internal app-cache output was not a valid durable test handoff. The repaired job uses AGP additional test output, then verifies that exact native ZIP independently on the host. All six quality jobs passed on fb024605 in run 41345124675, including API 29/36 and the hour stress job; CodeQL analysis also completed successfully. The latest local browser regression confirmed that case-specific selections reset when another package opens.

Browser export generation succeeded, but managed-preview binary download capture timed out; do not count browser file delivery as verified. ZIP bundles now keep the requested file, export manifest, timestamped processing history and checksums in one explicit download, with a separate checkpoint button. Native keysets now use explicit authenticated encrypted persistence through Tink/Keystore APIs; encrypted legacy migration and tamper rejection have additional device tests.

On `025986b8dee656371119124d9e2ef4330ffaadae`, all six quality jobs passed in [run 41347672603](https://github.com/TwoWetKnuckles/echoscript/actions/runs/41347672603): web/API/evidence including the real local decoder sequence, Rust, Android compilation/unit/lint/release, API 29 and 36 instrumentation, and desktop hour stress. [CodeQL run 41347672653](https://github.com/TwoWetKnuckles/echoscript/actions/runs/41347672653) also succeeded. This includes the playback repair that authenticates the first byte before querying the encrypted seekable source length; the retained device regression plays the controlled WAV through completion. It does not pass the unfinished full application beta gate.

## Responsibility-documentation validation

The November 3 documentation update preserves canonical Apache-2.0 terms and adds prominent case-specific user/deployer responsibilities, security reporting, operational guidance and agent rules. The local `LICENSE` was compared byte-for-byte with the Apache Software Foundation's current text (SHA-256 `cfc7749b96f63bd31c3c42b5c471bf756814053e847c10f3eb003417bc523d30`); npm/lockfile/Rust license metadata agree. Relative document links, touched-file formatting and whitespace checks passed. A fresh lockfile install, browser/API build, lint and all 25 Node behavioral tests passed. No runtime code was changed by this documentation update. These are technical/document checks, not legal review, a third-party license audit or a compliance assessment.

## Offline Android preview

[Quality run 42154154005](https://github.com/TwoWetKnuckles/echoscript/actions/runs/42154154005) passed on `1ab80e0d`, including API 29 and 36 with the bundled English and speaker models. Each emulator completed seven core synthetic workflows and the independent verifier checked all seven exported case packages. The app UI test exercised sample acquisition, analysis and transcription. The API 36 job processed an exact 750,000,000-byte synthetic PCM WAV. MP3, AAC/M4A, FLAC, Ogg/Vorbis and Ogg/Opus codec fixtures passed in the emulator matrix. See [the Android guide](../android/OFFLINE.md) for device and format limits. Physical-device QA and the complete application release gate remain outstanding.

The [preview delivery change](https://github.com/TwoWetKnuckles/echoscript/pull/13) keeps all workspace tabs visible and groups transcript/report formats. The first UI run on each emulator found that the test still invoked scrolling on a fixed tab. The regression now taps the visible tabs and checks the export choices and acknowledgement gate. See the [download hub](../../downloads/README.md) for the published build record and exact checksums.
