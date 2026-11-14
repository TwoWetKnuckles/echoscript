# Current validation and release limits

**BETA GATE: NOT PASSED.** Tests below apply to the named source/APK and executed synthetic matrix. They do not establish defect absence or physical-device acceptance. [Downloads](../../downloads/README.md) identifies the current public installer, checksums and permanent validation archive.

## Published speech candidate

APK source `ef5da08a9be8a2cf5fd38e2668fcb2d510de9258`; APK SHA-256 `664854f670987ad3136e9030262861c194ba62fe877742ee009024e18a8b8f82`. Installer/source documentation may be newer than these unchanged runtime bytes.

| Check                                  | Executed result                                                                                                                                               |
| -------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Quality / CodeQL / docs                | All eight push/PR quality jobs, both CodeQL jobs and documentation workflow passed                                                                            |
| Android JVM / case-security JVM / Node | 35 / 10 / 37 tests passed                                                                                                                                     |
| Android 10 emulator                    | 28 ordinary instrumentation passes; two designated skips; focused Whisper and separate actual process-kill intake subset passed                               |
| Android 16 emulator                    | 29 ordinary passes; one designated skip; focused Whisper, process-kill intake and 750 MB decode/render subset passed                                          |
| Independent case verification          | 32 exported synthetic cases per emulator passed, including native ClearVoice and Whisper cases                                                                |
| Installer verification                 | Exact artifact hash, signature, package/version, ZIP/ELF alignment and pinned model checked; no 16 KiB-kernel or physical-phone pass claimed                  |
| Local ClearVoice / Whisper             | Pinned ONNX reproduced; seeded Python/ONNX maximum sample difference below `2.8e-7`; licensed synthetic live Whisper transport check returned word timestamps |

The runtime checks and permanent report hashes are in [the release validation record](../../downloads/android/VALIDATION-0.6.0.txt). PR #26 subsequently repaired the coexistence harness to use the retained rollback. Its source `385fc19cc125e1a285be4fbcb2764d7cb56dedd0` passed all eight quality jobs in [run 43548605334](https://github.com/TwoWetKnuckles/echoscript/actions/runs/43548605334), CodeQL and docs; both emulator jobs verified preservation of the older test app's synthetic private data.

## Persistent-processing revision

[PR #27](https://github.com/TwoWetKnuckles/echoscript/pull/27) adds encrypted local job requests, foreground WorkManager processing, persistent cancellation, verified completed-pass reuse and combined local render/transcribe continuation. It also shares card styling and brings speech controls nearer the timeline. Candidate 2 has a separate package/vault from the published candidate.

Added validation covers request/settings tampering, encryption and damaged latest-journal rejection, cancellation recovery, no duplicate/compounded completed passes, independent case export and actual process-kill render continuation on API 29/36. These checks remain **IN PROGRESS** until their exact-source jobs finish. Local Android compilation, instrumentation compilation, lint and all 38 JVM tests passed; browser/API build, lint and all 37 Node tests passed; the documentation suite passed all 26 routes. The first emulator run caught a stale app-label expectation and an Android 10 low-memory kill during ClearVoice. The repaired allocator policy reduced measured Linux synthetic peak RSS from 1,450,804 to 819,168 KiB with exactly identical output samples; see `clearvoice-allocator-comparison.json`. These desktop observations do not pass physical-device resource acceptance. No complete-recovery or beta gate is declared passed by adding a worker or test harness.

## Outstanding mandatory acceptance

| Gate                      | Remaining evidence or implementation                                                                                                             |
| ------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------ |
| DEVICE_UI / REAL_WORKFLOW | Exact final APK on physical phones/tablets, Galaxy S22+ picker/microphone, accessibility, large text, landscape and supported providers          |
| DEVICE_RESOURCES          | Agreed per-device memory/disk/duration/thermal/battery thresholds and sustained large-file/cancellation measurements                             |
| RECOVERY / EVIDENCE       | Full stage-by-stage process-kill/power-loss matrix, protected clean-device/key recovery and approved custody/trust policy                        |
| ENHANCEMENT               | Confirmed advanced spectral/noise-profile/restoration/chain parity and representative measured accuracy; current approximations remain disclosed |
| SPEECH                    | Labeled faint/multi-speaker/overlap corpus, agreed accuracy thresholds and exact provider/deployment qualification                               |
| TEAM_PORTABILITY          | Active authenticated deployment, approved role/metadata field policy, authorization/revocation/isolation/conflict tests; no evidence sync        |
| DISTRIBUTION              | Owner-controlled signer/certificate pin, key custody and tested vault-preserving production upgrade/distribution path                            |
| GOVERNANCE                | Case/jurisdiction authority, retention, agreements and organizational acceptance of the exact deployment                                         |
| SEVEN_FULL_RUNS           | Seven consecutive complete exact-build workflows after prerequisites pass; existing core/interface subsets do not count                          |

The [release gate specification](../../specs/release-gates.json), [acceptance instructions](RC-ACCEPTANCE.md) and [working scope](../WORKING-SCOPE.md) remain authoritative. No gate is waived. Missing access, deployment policy or physical-device evidence is unexecuted, never PASS.

## Historical records and distribution cleanup

The [historical validation log](archive/VALIDATION-THROUGH-2026-11-13.md) preserves earlier run dates, repairs and interrupted drafts. Read it as history; stale draft caveats are resolved only by a later named successful run. Engine/provider and desktop one-hour stress measurements remain in `local-seven-run-results.json` and `desktop-hour-results.json` and retain their original scope.

The owner received verified app/source/documentation and validation backups before releases 0.3.1, 0.4.0, 0.5.0 and 0.5.1 were removed. Only the current candidate and one rollback remain published; source tags and history remain. [Archive inventory](../../downloads/android/ARCHIVE-2026-11-13.json) records all original asset checks. Standalone documentation design/deployment remains paused; factual mirrors are synchronized with source.
