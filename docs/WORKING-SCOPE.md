# Working release scope — owner decision

Decision recorded November 11, 2026 (America/Los_Angeles), after the numbered historical feature reconciliation. The published 0.5.1 development preview remains the download until a replacement is validated. The 0.6.0 workbench is an unfinished candidate, not an accepted release candidate.

## Scope decisions

| IDs   | Decision                 | Meaning                                                                                                                                                  |
| ----- | ------------------------ | -------------------------------------------------------------------------------------------------------------------------------------------------------- |
| 1–20  | CONFIRMED                | Include the features below in the working release scope.                                                                                                 |
| 21    | DEFERRED                 | Browser-companion feature/format/750 MB parity is outside this update. Maintain existing behavior.                                                       |
| 22    | DEFERRED                 | Supported packaged Termux operation is outside this update.                                                                                              |
| 23–28 | CONFIRMED                | Include background/recovery, team/metadata synchronization, trust lifecycle and production distribution work.                                            |
| 29–30 | PENDING OWNER ACCEPTANCE | Complete and validate the physical-device and final seven-workflow checks, then present results to the owner for acceptance. They remain unpassed gates. |

## Confirmed feature inventory

1. Zoomable waveform/spectrogram with short-event inspection.
2. Combined time/frequency selections and frequency isolation.
3. FFT/hop/window/frequency-scale/dB-range controls.
4. Looped selection playback.
5. Synchronized original/parent/processed A/B comparison.
6. Markers/bookmarks/timestamped notes.
7. Editable speech-event candidates.
8. Measured noise-profile selection and reduction.
9. More capable speech/noise isolation.
10. Parametric EQ with frequency/Q/gain.
11. Improved measured echo/reverb reduction with explicit limitations.
12. De-clipping review and before/after visualization.
13. Reorderable processing chains and alternative-chain comparison.
14. Standalone processing-changelog export and bundled handoff.
15. Selectable local/self-hosted/cloud transcription providers.
16. Whisper/OpenAI transcription option.
17. Provider qualification matrix and exact-payload submission consent.
18. Measured multi-speaker/overlap handling.
19. Transcript search/uncertainty filtering and examiner-managed annotations.
20. Explicit broader codec/container support and failure matrix.
21. Background processing and resumable jobs.
22. Full interruption/recovery coverage.
23. Team identity/roles/authorization/revocation/isolation.
24. Explicitly approved preferences/workload metadata synchronization and conflicts; no evidence upload by default.
25. Trusted signer enrollment/revocation/key-custody/recovery lifecycle and trusted timestamping.
26. Owner-controlled production signing, certificate continuity and vault-preserving upgrades.

## Requests already included before reconciliation

Five single-action togglable presets; individual enable/disable effects with signed −100…0…+100 sliders and editable numerical values; gain/EQ, hum/buzz, reverb/echo, time shift, declip, denoise, pitch and stereo width; top-of-workbench waveform/spectrogram with playback/seek and drag selection; multiple ordered and overlapping time regions; separate clips; immutable render recipes/changelog with restore/undo/redo without history deletion. These remain subject to measured algorithm and UI validation.

## Implementation and acceptance boundaries

Original bytes, encrypted local storage, complete provenance/audit linkage and explicit examiner review remain invariants. Restoration estimates do not recover uncaptured/lost speech or establish identity. Existing non-sensitive synthetic fixtures only; no provider credentials, owner signing keys or real case data in Git/CI.

Feature confirmation does not supply provider API credentials/credits, a selected hosted deployment, organization roles and metadata field allowlist, trusted timestamp authority, owner signing material, physical device access, quantitative accuracy/resource thresholds or policy acceptance. Build and test the concrete implementation first; request only the necessary missing deployment decisions when their dependent integration is ready for review. No external processing or evidence sync is authorized merely by enabling a provider option.

Galaxy S22+ / Android 16 is the user-reported physical device. Emulator results must be labeled separately. IDs 29/30 require evidence against the exact final APK/source, followed by owner acceptance; they cannot be silently marked passed or waived. See specs/release-gates.json and docs/qa/RC-ACCEPTANCE.md.
