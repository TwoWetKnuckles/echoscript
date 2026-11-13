# EchoScript downloads

## Current: Android 10 Offline Test Candidate 0.6

**[Download APK](https://github.com/TwoWetKnuckles/echoscript/releases/download/android10-offline-test-candidate-0.6.0-dev.20261113/EchoScript-Android10-Offline-Test-Candidate-0.6.apk)** · **[Download ZIP with instructions](https://github.com/TwoWetKnuckles/echoscript/releases/download/android10-offline-test-candidate-0.6.0-dev.20261113/EchoScript-Android10-Offline-Test-Candidate-0.6.zip)** · [Release](https://github.com/TwoWetKnuckles/echoscript/releases/tag/android10-offline-test-candidate-0.6.0-dev.20261113) · [Installation](android/INSTALL.txt) · [Release notes](android/RELEASE-NOTES-0.6.0.md) · [Validation](android/VALIDATION-0.6.0.txt)

Android 10/API 29 or later. **Development evaluation candidate. BETA GATE: NOT PASSED.** All implemented tools are available. Vosk and ClearVoice run on the phone with bundled models; Whisper is selected before transcription and requires a configured companion and explicit payload approval. The OpenAI key stays on that server.

| File             | SHA-256                                                            |
| ---------------- | ------------------------------------------------------------------ |
| APK              | `664854f670987ad3136e9030262861c194ba62fe877742ee009024e18a8b8f82` |
| Installation ZIP | `77bb3d9507c232a334c61e8f845e8f30312a55d42b2cd40603b43fc048ea9bbf` |
| Validation ZIP   | `d016671b03278d9bf2899365c48b7d5238f8741872ac7e6de1cb284a2a99d80a` |

The unchanged APK comes from source `ef5da08a9be8a2cf5fd38e2668fcb2d510de9258` in [the passing installer run](https://github.com/TwoWetKnuckles/echoscript/actions/runs/43442474145). Installer documents and package checksums were added after CI. The [validation archive](https://github.com/TwoWetKnuckles/echoscript/releases/download/android10-offline-test-candidate-0.6.0-dev.20261113/EchoScript-Candidate-0.6-validation.zip) preserves original synthetic report archives, independent-verifier results and acceptance limits; [SHA256SUMS.txt](https://github.com/TwoWetKnuckles/echoscript/releases/download/android10-offline-test-candidate-0.6.0-dev.20261113/SHA256SUMS.txt) covers release assets. Permanent release downloads replace expiring Actions artifacts.

Install `EchoScript-Android10-Offline-Test-Candidate-0.6.apk`, or extract the dedicated `EchoScript-Android` folder from the ZIP. The app label is **EchoScript 0.6 Candidate**, ID `org.echoscript.app.candidate06`, with its own empty vault. Preserve and independently verify protected exports before removing/resetting an earlier app. Development signing does not establish production upgrade continuity.

## One previous rollback: 0.6 Test

[Previous release, APK, ZIP and checksums](https://github.com/TwoWetKnuckles/echoscript/releases/tag/android-preview-0.6.0-dev.20261113). Its APK source is `cc19e7a3c49eed1fdc1b32064fb27a21531f87df`, APK SHA-256 `0efbfe18fffda6b0141cb13cdeb56e301ff12fa617539e47042b57a37fba50d3`, application ID `org.echoscript.app.preview06`. This is the older test build; use the candidate above for the new speech workflow. Older immutable GitHub releases still require a separate permanent-removal decision; they are not current download recommendations.

Read the [Android workflow](../docs/android/OFFLINE.md), [executed checks](../docs/qa/STATUS.md), [request reconciliation](../docs/qa/REQUEST-RECONCILIATION.md) and [release gates](../docs/RELEASE-CANDIDATE.md). Physical-device/resource/recovery, remaining scope, owner signing and trust/deployment acceptance remain open. Individual exports and legacy ZIPs are plaintext; protected case containers have a different confidentiality boundary.
