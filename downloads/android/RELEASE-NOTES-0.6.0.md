# EchoScript 0.6 Test

Development preview for synthetic evaluation. **BETA GATE: NOT PASSED.** This is a debuggable, development-signed APK, not an accepted release candidate or an owner-signed production build.

## Changes in this test build

- File selection shows the recording name and an explicit **Import selected audio** action. Cancellation, invalid results, interrupted selections and failed imports produce feedback and retry options. Emulator system-picker tests pass; the original Galaxy S22+ report still requires exact-build physical validation.
- The Material-dark workbench places the recording waveform/spectrum and playback/selection controls above the processing panels. Presets, effect groups and individual effect choices use compact horizontal strips; swipe to reveal more. Group changes reveal the newly selected control.
- Five removable presets and 16 individually enabled signed effect controls provide sliders and numerical entry. Renders, overlapping region passes, clips, recipes, undo and redo retain earlier artifacts.
- Optional full-recording analysis saves a coarse 128-column, 32-band overview. Short-range inspection has separate FFT/hop/window/display controls. The full scan can be slow and is not a high-resolution full-file spectrogram. Noise-floor gating, echo/reverb subtraction and de-clipping remain bounded estimates with the limitations shown in the app.
- Segment examiner notes are searchable and create new transcript revisions. Notes remain separate from spoken words; note-only edits preserve word timing, scores and verification. Native JSON/TXT/CSV/HTML/PDF/DOCX/XLSX retain labelled notes; SRT/VTT/CTM omit them.
- All implemented tools are available without access-tier selection.

## Installation and existing cases

Android 10 or later. Read `INSTALL.txt`, verify `SHA256SUMS.txt`, then open `EchoScript-offline-preview.apk`. This build appears as **EchoScript 0.6 Test** and uses `org.echoscript.app.preview06`, separate from the 0.5.1 preview and its vault. It starts with an empty vault.

Separate 0.6 CI builds can use different signing keys. Android may refuse an update. Do not uninstall or reset any app until needed cases have been exported, independently verified and safely stored; uninstalling can erase that app's vault. Cases are not automatically copied between apps.

## Validation and limits

APK source: `cc19e7a3c49eed1fdc1b32064fb27a21531f87df`. All seven [push quality jobs](https://github.com/TwoWetKnuckles/echoscript/actions/runs/43358193917), all seven [PR quality jobs](https://github.com/TwoWetKnuckles/echoscript/actions/runs/43358201796), and both [CodeQL jobs](https://github.com/TwoWetKnuckles/echoscript/actions/runs/43358201829) passed. `VALIDATION.txt` records counts, exact-build provenance and the boundaries of those checks. The APK is unchanged from the tested Android 16 job; this release package adds updated installation/release documentation and regenerated package checksums.

No payment SDK or Internet permission is present. The experimental server-side Whisper adapter is not connected to this Android APK. Formal physical-device acceptance, the remaining [confirmed feature scope](https://github.com/TwoWetKnuckles/echoscript/blob/cc19e7a3c49eed1fdc1b32064fb27a21531f87df/docs/WORKING-SCOPE.md), production signing/upgrades and the [complete release gates](https://github.com/TwoWetKnuckles/echoscript/blob/cc19e7a3c49eed1fdc1b32064fb27a21531f87df/docs/RELEASE-CANDIDATE.md) remain open.
