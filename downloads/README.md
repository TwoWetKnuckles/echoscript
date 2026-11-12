# EchoScript downloads

## Temporary test package for local audio selection

**[Download the 0.6.0 development test package](https://github.com/TwoWetKnuckles/echoscript/actions/runs/43284529949/artifacts/13974164615)** (GitHub sign-in required; expires November 19, 2026). The ZIP includes the APK, installation instructions and checksums. After picking a recording, verify its name under **Selected recording**, then tap **Import selected audio**. This is a debug-signed synthetic-evaluation build from commit `92c26790920e75429f6bdd33aefa4d395a94f8f4`, not a production release. It includes picker-interruption handling, detailed spectrum and measured-noise controls, but not the subsequent side-by-side installation or layout changes.

| File | SHA-256 |
| --- | --- |
| Downloaded Actions ZIP | `9370330ec65e5a7e7a823d8cfd8686f93e3602bfd7e755f8442af5a910681171` |
| APK inside ZIP | `75c8ff92100a4b90926e21cee6e36bac9bada09facdd884c1680b2ed216ab2e6` |

All seven quality jobs passed for this source in [the test run](https://github.com/TwoWetKnuckles/echoscript/actions/runs/43284529949). The originally reported Galaxy S22+ behavior has not been reproduced or verified fixed on that physical device. This package uses the 0.5.1 preview's app ID, and separate debug builds may use different signing keys. If Android rejects an update, do not remove the older app without first exporting and independently verifying any cases you need, because uninstalling can erase its vault. A separately installed 0.6 test build is being validated.

## Android preview 0.5.1

**[Download APK](https://github.com/TwoWetKnuckles/echoscript/releases/download/android-preview-0.5.1/EchoScript-offline-preview.apk)** · **[Download ZIP with instructions](https://github.com/TwoWetKnuckles/echoscript/releases/download/android-preview-0.5.1/EchoScript-Android-preview.zip)** · [Release and checksums](https://github.com/TwoWetKnuckles/echoscript/releases/tag/android-preview-0.5.1) · [Plain-text install instructions](android/INSTALL.txt)

Android 10 or later. Extract the `EchoScript-Android` folder, open `EchoScript-offline-preview.apk`, then choose **Load synthetic speech sample** in **Cases**. The APK includes its speech models. The dark Material interface offers automatic import analysis, **Render & transcribe**, saved examiner settings and review edits that survive navigation and rotation. Create device-signed checkpoints directly in Export and choose a passphrase-protected case package; passphrase fields have Show/Hide controls. Individual files and legacy ZIPs remain plaintext. The ZIP includes `INSTALL.txt`, `SHA256SUMS.txt`, build provenance and license notices.

**Development preview for synthetic evaluation. BETA GATE: NOT PASSED.** The APK is debuggable and development-signed. Verify the checksum before installing. Preserve test case exports before removing an older preview if Android rejects the update. Retain transfer passphrases, package hashes and independently trusted signer fingerprints separately. Use protected storage for plaintext exports.

See the [Android workflow](../docs/android/OFFLINE.md), [executed checks](../docs/qa/STATUS.md) and [operator responsibilities](../DISCLAIMER.md). Repository ZIPs contain source code. The installable APK is in the linked release asset.

[Release candidate gates](../docs/RELEASE-CANDIDATE.md) describe the remaining work. The published release records the exact tested commit, run and checksums.
