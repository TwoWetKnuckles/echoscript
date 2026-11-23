# EchoScript unofficial evolution checklist

**Research ideas for owner review, 14 November 2026.** This is not an approved release scope, roadmap promise or substitute for [mandatory acceptance](../../specs/release-gates.json). Review the [eight-product analysis](COMPETITIVE-ANALYSIS-2026-11-14.md) and [mindmap](https://github.com/TwoWetKnuckles/echoscript/blob/docs/product-discovery-20261114/docs/research/EVOLUTION-MINDMAP.svg) before proposing additions. [GitHub issue #28](https://github.com/TwoWetKnuckles/echoscript/issues/28) tracks decisions; this document records the proposal baseline.

Owner approval on November 15, 2026 authorized the recommended stack/theme direction, previously discussed additions, separate Symfony/importmap site and refactoring. See [the approved implementation record](https://github.com/TwoWetKnuckles/echoscript/blob/feature/approved-beta-update-20261115/docs/development/APPROVED-UPDATE.md). This historical research checklist remains separate from exact-build beta acceptance; check items only when linked evidence qualifies them. All implemented EchoScript features remain available without payment gates.

## P0 - correctness and release obligations

- [ ] **E01:** Diagnose the exact-source API 29/36 failures in PR #27. Validate durable cancellation, idempotent scheduling, retry and process-death recovery; preserve source and avoid duplicate artifacts.
- [ ] **E02:** Finish pinned model-build dependency updates and advisory review. Re-export, verify numerical/graph parity, record the actual new model hash and rerun native tests. Prior toolchain observations are not exact-build acceptance.
- [ ] **E13:** Align release name, tested APK, SHA-256, certificate, source and download links. Qualify owner-controlled signing and upgrade, archive the replaced build and retain one rollback. New permanent deletion requires its own concrete review.
- [ ] **E14:** Implement and satisfy the remaining eleven mandatory gates, including physical-device resources, complete enhancement/accuracy/recovery/team/trust work and seven full accepted runs on the exact APK.

## P1 - core workflow and design proposals

- [ ] **E03:** ClearVoice enable + Before effects / After effects with an exact visible rendered chain; test bypass, both orders, immutable history and payload approval invalidation.
- [ ] **E04:** Aligned level-matched A/B and removed-signal listening; test delay, clipping and the difference between loudness and intelligibility.
- [ ] **E05:** Segment-linked transcript correction, uncertainty queue and reviewer revisions; no invented confidence or automatic human-verification labels.
- [ ] **E06:** Readable summary plus complete machine-readable processing report, linked to source/derivative hashes, selections and independent verification.
- [ ] **E07:** Persist time/frequency/channel selections with feathering and render/export parity; preserve overlap and phase semantics.
- [ ] **E08:** Measured, source-linked noise profiles with clear selection and mismatch checks; evaluate the risk of suppressing quiet speech.
- [ ] **E09:** Owner stack AOK received November 15; align visual tokens, concise actions and navigation across native/web/docs/repository assets. Check dark/light, contrast, large text, touch and keyboard behavior.
- [ ] **E10:** Define and test actual browser/WebView minimums for Tailwind 4, local/offline assets and caching. Do not infer browser support from Android OS version.
- [ ] **E16:** Provide a synthetic tutorial case demonstrating import, inspection, rendering, review and independently verified export.

## P2 - later experiments

- [ ] **E11:** Similar-event search with labeled-event precision/recall; never infer speakers or intent from repeated sounds.
- [ ] **E12:** Searchable actions and explainable starting presets; measure findability, expose settings, preserve reset/undo/history.
- [ ] **E15:** Evaluate additional local speech models for license, package size, RAM, battery, thermals and accuracy; no silent downloads or provider fallback.

## Decision log

| Decision                                                              | State                                                            | Required next input                                                      |
| --------------------------------------------------------------------- | ---------------------------------------------------------------- | ------------------------------------------------------------------------ |
| A: Vue/TypeScript/Tailwind companion + native Compose + shared tokens | Approved November 15; earlier docs choice superseded November 16 | Performance qualification before further styling/refactoring             |
| B: Migrate documentation to Vue/VitePress                             | Approved November 16                                             | Performance qualification, then content/deep-link/search/download checks |
| New beta release                                                      | Not ready                                                        | Exact-build gate evidence and qualified signing                          |
| Competitor-inspired additions                                         | Research only                                                    | Select idea IDs, acceptance criteria and owner scope decision            |

When promoting an item, create a focused implementation issue or PR, cite its E-ID and record the owner decision. Check an item only after linked evidence supports completion. Keep this research list separate from required release-gate records.

Implementation: [PR TwoWetKnuckles/echoscript#32](https://github.com/TwoWetKnuckles/echoscript/pull/32) integrates the approved Vue/shared theme, separate Symfony/importmap portal, PWA/tooling and native processing recovery. Runtime acceptance and research ideas remain distinct. The hosted portal is deployed with its existing access policy; the new docs preview is ready. The integration remains in draft behind performance qualification; current public downloads are unchanged.

November 16 steering: The owner selected Vue/VitePress for the documentation migration and prioritized long-audio performance before additional styling/refactoring. [Performance PR TwoWetKnuckles/echoscript#33](https://github.com/TwoWetKnuckles/echoscript/pull/33) adds larger verified Whisper requests, bounded PCM copying, reusable ClearVoice tensors, stage timing and exact-source checks. The two-thread implementation passed all eight quality jobs on both PR/push runs for runtime source `edd162a41dff33cb7aa35e7ccef32ce1b06a6319`, including API 29/36, 43 Android JVM tests and independent synthetic case verification. The short emulator inference measurements still exceeded real time; target-recording throughput is not qualified. Free remote compute and the automatic Workbench connection still require a qualified operator service. No beta gate or large-file completion-time claim is passed by these source changes.
