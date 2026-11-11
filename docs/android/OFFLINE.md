# EchoScript offline Android preview

EchoScript's Android workbench runs on Android 10 and later. GitHub builds and tests the APK. After installation, acquisition, processing, English speech recognition, transcript review, verification and export run in the app. The installer includes the speech and speaker models. No Termux, web UI, account, server, first-run model download or speech subscription is needed.

**Development preview. BETA GATE: NOT PASSED.** Evaluate the current build with synthetic, non-sensitive recordings. See [responsibility and limitations](../../DISCLAIMER.md) and [executed checks](../qa/STATUS.md).

Preview 0.5.1 includes protected transfers and device checkpoints. Use [the protected native workflow](../evidence/PROTECTED-TRANSFER.md): save revisions, explicitly create a device checkpoint in Export or Audit, retain its fingerprint independently, then acknowledge Export and enter/confirm an unpredictable passphrase for **Protected case package**. Supply the passphrase and independently retained ciphertext hash for intake. Legacy ZIP and individual audio/documents remain plaintext. A device signature is a historical byte commitment, not an authenticated examiner identity.

## Install and use the preview

1. Download the [Android APK or complete ZIP](../../downloads/README.md). For the ZIP, extract `EchoScript-Android` on the device. The folder includes the APK, `INSTALL.txt`, SHA-256 and source/run information.
2. Open the APK from Files and follow Android's installation prompt. Use an approved test device. The development APK is debuggable and uses `org.echoscript.app.preview`. Android may reject an update signed with a different CI key. Preserve originals and protected case exports before removing an earlier preview.
3. In **Cases**, select **Load synthetic speech sample** or **Choose audio file**. A successful audio import opens **Analyze**. **Record audio** opens **Acquire**, which offers an optional case title and microphone capture. Save the examiner identifier in **Settings**. Leaving the app stops recording and preserves the captured portion when possible.
4. **Analyze** opens with the waveform and spectral overview after import. Select a candidate or keep the full recording. Presets and the gain slider cover common adjustments. **Region and filter details** exposes the underlying values. **Render & transcribe** creates a derivative and opens its local draft. **Render enhanced revision** saves audio separately. **Audio revision history** selects earlier artifacts.
5. **Transcript** opens the saved draft. Use **Transcribe active audio on device** when a new transcription is needed. The first run verifies and unpacks bundled model files locally. Listen at the returned timestamps, open **Edit and verify segment**, and save examiner revisions. Unsaved review drafts stay in ViewModel memory across workspace navigation and rotation. Save a revision to retain it through a process restart. Transcript text is excluded from saved-instance bundles and preference files. Segment navigation requires saving or resetting those edits. Speaker labels entered by an examiner are recorded separately from acoustic groups. Mark a segment verified only after reviewing it.
6. The shield button opens **Audit** to verify SHA-256, provenance and custody linkage. **Export** offers the WAV and case package directly, with a separate format menu for transcript files and PDF reports. Choose an approved destination. Exported files are plaintext. Keep the SHA-256 in a separate trusted record.
7. On another device, select **Cases → Transfer case → Choose .echocase package**. Compare the package to an independently obtained hash when available. The app verifies the ZIP inventory, CRCs, artifact hashes, provenance and audit chain before committing an encrypted copy. It refuses to replace a case that already exists. Workload status travels with a case. Automatic network sync and conflict merging are separate future capabilities.

## Appearance and workspace recovery

The native interface defaults to dark graphite with cyan and violet Material accents. Settings offers Dark, Light and System appearance and a persistent local examiner identifier. Four bottom destinations keep Cases, Analyze, Transcript and Export visible. Cases provides file intake, microphone acquisition, search and explicit package transfer. The shield opens audit history.

Reopening a case loads its hash-checked saved analysis and transcript. An interrupted task marker is reported on the next vault open so completed artifacts can be reviewed before retrying. Automatic continuation after process death remains a release gate. Preferences contain appearance, the user-supplied examiner identifier and the operational task name. Evidence stays in the encrypted vault.

## Processing limits

- The vault retains original bytes up to 4 GiB. The processing limit is 750,000,000 source bytes. A small compressed recording can expand substantially. Decoding also needs private disk space for encrypted staging and a canonical PCM16 WAV. An oversized decode, unsupported track or incomplete file fails with the source preserved.
- PCM16 WAV uses the native streaming reader. Other supported tracks use Android's media extractor and decoder. Compatibility depends on the exact codec, container and device. The emulator matrix uses pinned synthetic MP3, AAC/M4A, FLAC, Ogg/Vorbis and Ogg/Opus fixtures. See the CI result for the executed matrix.
- The preview supports region gain, three-band EQ, high/low/band-pass, hum harmonics, narrow tonal notches, quiet-region attenuation and a limiter. It shows a bounded spectral overview and candidate regions. Full time-frequency editing, restoration, noise-profile tooling and native A/B controls remain release work.
- English speech uses Vosk Android 0.3.75 with `vosk-model-small-en-us-0.15`. The app hashes exact local recognition audio and preserves raw decoder output, word timing, returned alternatives and score semantics. The `vosk-model-spk-0.4` speaker model supplies utterance embeddings for provisional groups. Overlap, short utterances, speaker changes within an utterance and fixed recognition-window boundaries require review. Groups do not establish identity.
- Keep the app open during long operations. Work is cancellable, and source and completed derivative snapshots persist. Automatic restart, physical power-loss/resource validation and organizational trust/recovery policy remain release work. Source 0.5 adds streaming protected transfer and P-256 historical checkpoint verification; their existence does not approve those policies.
- Cases, revisions and model files are in app-private storage. Device reset, uninstall or key loss can make the vault unavailable. Preserve originals and separately protected, verified exports; recovery covers only the exported snapshot. APK signing protects installation/updates and is separate from evidence checkpoints. Source 0.5 can verify checkpoint REPORT artifacts; the published 0.4 preview predates them.

## Build and verify in GitHub

The pinned Gradle project targets API 36 with minimum API 29. Use JDK 17, SDK/build tools 36 and Python 3.11 or later on the build host. `prepareOfflineModels` downloads or uses cached official archives, verifies their pinned SHA-256 values and registers generated APK assets. Archive downloads happen on the builder. Runtime permissions exclude Internet access. The provider catalog lists both bundled models under Apache 2.0, and the APK retains model notices.

```sh
cd apps/android
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug :app:assembleRelease
```

For emulator checks, run `:app:connectedDebugAndroidTest` from `apps/android`. The small codec fixtures and their provenance are committed under `specs/fixtures/android-codecs`. To regenerate them on a build host with FFmpeg, run `python3 scripts/android-qa-fixtures.py` and review the new hashes. The workflow exercises API 29 and 36 with real bundled Vosk recognition, native vault and case transfer tests, seven repeated interface workflows with controlled platform-picker results, and independent verification of all seven core and seven interface package outputs. The interface test includes real file ingestion, automatic analysis, rendering, bundled recognition, examiner revision, export, re-import and verification. Screenshots are taken from the running app. Platform-picker behavior on physical devices remains separate validation. The API 36 job also requests a 750 MB streaming test. A result is recorded only after a run completes. The complete product beta gate includes additional device, workflow, recovery and policy checks.

Production distribution needs an owner-controlled signing key, verified upgrade behavior and acceptance of the complete release matrix. The downloadable ZIP contains a development-signed preview for synthetic evaluation. Signing keys stay outside Git, and APK users do not need access to the source tree or build tools to run the installed app.

See [owner-signing setup](OWNER-SIGNING.md) and [the exact-build acceptance record](../qa/RC-ACCEPTANCE.md). The manual signing workflow produces an evaluation artifact only; it does not publish a release candidate or count debug/emulator results as tests of a different signed APK.
