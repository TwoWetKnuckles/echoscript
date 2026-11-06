# EchoScript offline Android preview

EchoScript's Android workbench runs on Android 10 and later. GitHub builds and tests the APK. After installation, acquisition, processing, English speech recognition, transcript review, verification and export run in the app. The installer includes the speech and speaker models. No Termux, web UI, account, server, first-run model download or speech subscription is needed.

**Development preview. BETA GATE: NOT PASSED.** Evaluate the current build with synthetic, non-sensitive recordings. See [responsibility and limitations](../../DISCLAIMER.md) and [executed checks](../qa/STATUS.md).

## Install and use the preview

1. Download the [Android preview ZIP](../../downloads/README.md) and extract `EchoScript-Android` on the device. The folder includes the APK, `INSTALL.txt`, SHA-256 and source/run information.
2. Open the APK from Files and follow Android's installation prompt. Use an approved test device. The development APK is debuggable and uses `org.echoscript.app.preview`. Android may reject an update signed with a different CI key. Preserve originals and protected case exports before removing an earlier preview.
3. In **Cases**, select **Load synthetic speech sample** or **Choose audio file**. A successful audio import opens **Analyze**. **Acquire** offers a custom case title, examiner identifier and microphone capture. Leaving the app stops recording and preserves the captured portion when possible.
4. Open **Analyze**. View the waveform and spectral overview. Select a candidate region or enter times in milliseconds, choose filter settings and render a revision. Use the revision list to return to an earlier version or compare playback.
5. Open **Transcript** and select **Transcribe active audio on device**. The first run verifies and unpacks bundled model files locally. Listen at the returned timestamps, review the text and draft speaker groups, and save examiner revisions. Speaker labels entered by an examiner are recorded separately from acoustic groups. Mark a segment verified only after reviewing it.
6. Open **Audit** to verify SHA-256, provenance and custody linkage. **Export** offers the WAV and case package directly, with a separate format menu for transcript files and PDF reports. Choose an approved destination. Exported files are plaintext. Keep the SHA-256 in a separate trusted record.
7. On another device, select **Cases → Choose .echocase package**. Compare the package to an independently obtained hash when available. The app verifies the ZIP inventory, CRCs, artifact hashes, provenance and audit chain before committing an encrypted copy. It refuses to replace a case that already exists. Workload status travels with a case. Automatic network sync and conflict merging are separate future capabilities.

## Processing limits

- The vault retains original bytes up to 4 GiB. The processing limit is 750,000,000 source bytes. A small compressed recording can expand substantially. Decoding also needs private disk space for encrypted staging and a canonical PCM16 WAV. An oversized decode, unsupported track or incomplete file fails with the source preserved.
- PCM16 WAV uses the native streaming reader. Other supported tracks use Android's media extractor and decoder. Compatibility depends on the exact codec, container and device. The emulator matrix uses pinned synthetic MP3, AAC/M4A, FLAC, Ogg/Vorbis and Ogg/Opus fixtures. See the CI result for the executed matrix.
- The preview supports region gain, three-band EQ, high/low/band-pass, hum harmonics, narrow tonal notches, quiet-region attenuation and a limiter. It shows a bounded spectral overview and candidate regions. Full time-frequency editing, restoration, noise-profile tooling and native A/B controls remain release work.
- English speech uses Vosk Android 0.3.75 with `vosk-model-small-en-us-0.15`. The app hashes exact local recognition audio and preserves raw decoder output, word timing, returned alternatives and score semantics. The `vosk-model-spk-0.4` speaker model supplies utterance embeddings for provisional groups. Overlap, short utterances, speaker changes within an utterance and fixed recognition-window boundaries require review. Groups do not establish identity.
- Keep the app open during long operations. Work is cancellable, and source and completed derivative snapshots persist. Automatic restart of an interrupted processing job, full hardware/thermal validation, trusted evidence signatures and encrypted cross-device transport remain release work.
- Cases, revisions and model files are in app-private storage. Device reset, uninstall or key loss can make the vault unavailable. Preserve originals and separately protected exports. APK signing protects installation and updates. The current case format does not have evidence signatures.

## Build and verify in GitHub

The pinned Gradle project targets API 36 with minimum API 29. Use JDK 17, SDK/build tools 36 and Python 3.11 or later on the build host. `prepareOfflineModels` downloads or uses cached official archives, verifies their pinned SHA-256 values and registers generated APK assets. Archive downloads happen on the builder. Runtime permissions exclude Internet access. The provider catalog lists both bundled models under Apache 2.0, and the APK retains model notices.

```sh
cd apps/android
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug :app:assembleRelease
```

For emulator checks, run `:app:connectedDebugAndroidTest` from `apps/android`. The small codec fixtures and their provenance are committed under `specs/fixtures/android-codecs`. To regenerate them on a build host with FFmpeg, run `python3 scripts/android-qa-fixtures.py` and review the new hashes. The workflow exercises API 29 and 36 with real bundled Vosk recognition, native vault and case transfer tests, and independent verification of all seven synthetic package outputs. The API 36 job also requests a 750 MB streaming test. A result is recorded only after a run completes. The complete product beta gate includes additional device, workflow, recovery and policy checks.

A public release needs an owner-controlled signing key, verified upgrade behavior and acceptance of the complete release matrix. The downloadable ZIP contains a development-signed preview for synthetic evaluation. Signing keys stay outside Git, and APK users do not need access to the source tree or build tools to run the installed app.
