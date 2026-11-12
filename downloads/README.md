# EchoScript downloads

## Temporary test package for local audio selection

**[Download the 0.6.0 development test package](https://github.com/TwoWetKnuckles/echoscript/actions/runs/43184211337/artifacts/13928227300)** (GitHub sign-in required; expires November 19, 2026). The ZIP includes the APK, installation instructions and checksums. After picking a recording, verify its name under **Selected recording**, then tap **Import selected audio**. This is a debug-signed synthetic-evaluation build from commit `1ba4de6c8d70bc224845bdc481308811a568f269`, not a production release. It predates the later detailed-spectrum and measured-noise work.

| File | SHA-256 |
| --- | --- |
| Downloaded Actions ZIP | `855e308859b0b8a389e30a44a42b275503a27e2e745e097e19a86e1055bbe401` |
| APK inside ZIP | `ea3deea6d6cb135faa8294f78a815ec9bc9dfa1fe67288bc3c5bedef16e0693c` |

All seven quality jobs passed for this source in [the test run](https://github.com/TwoWetKnuckles/echoscript/actions/runs/43184211337). The originally reported Galaxy S22+ behavior has not been reproduced or verified fixed on that physical device. Separate debug builds may use different signing keys: if Android rejects an update, export and independently verify any cases you need before removing the older app, because uninstalling can erase its vault.

## Android preview 0.5.1

**[Download APK](https://github.com/TwoWetKnuckles/echoscript/releases/download/android-preview-0.5.1/EchoScript-offline-preview.apk)** · **[Download ZIP with instructions](https://github.com/TwoWetKnuckles/echoscript/releases/download/android-preview-0.5.1/EchoScript-Android-preview.zip)** · [Release and checksums](https://github.com/TwoWetKnuckles/echoscript/releases/tag/android-preview-0.5.1) · [Plain-text install instructions](android/INSTALL.txt)

Android 10 or later. Extract the `EchoScript-Android` folder, open `EchoScript-offline-preview.apk`, then choose **Load synthetic speech sample** in **Cases**. The APK includes its speech models. The dark Material interface offers automatic import analysis, **Render & transcribe**, saved examiner settings and review edits that survive navigation and rotation. Create device-signed checkpoints directly in Export and choose a passphrase-protected case package; passphrase fields have Show/Hide controls. Individual files and legacy ZIPs remain plaintext. The ZIP includes `INSTALL.txt`, `SHA256SUMS.txt`, build provenance and license notices.

**Development preview for synthetic evaluation. BETA GATE: NOT PASSED.** The APK is debuggable and development-signed. Verify the checksum before installing. Preserve test case exports before removing an older preview if Android rejects the update. Retain transfer passphrases, package hashes and independently trusted signer fingerprints separately. Use protected storage for plaintext exports.

See the [Android workflow](../docs/android/OFFLINE.md), [executed checks](../docs/qa/STATUS.md) and [operator responsibilities](../DISCLAIMER.md). Repository ZIPs contain source code. The installable APK is in the linked release asset.

[Release candidate gates](../docs/RELEASE-CANDIDATE.md) describe the remaining work. The published release records the exact tested commit, run and checksums.
