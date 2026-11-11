# Owner-controlled Android signing

The installable 0.5.1 preview is development-signed and debuggable. Production uses the distinct `org.echoscript.app` ID; preview uses `org.echoscript.app.preview`. A production APK does not overwrite the preview or automatically inherit its Keystore/vault. Preserve a completed protected case export and independently verify clean-install import before any uninstall/reset. A future same-ID update must preserve the owner's signing identity and increase `versionCode`; changing a certificate is not a routine update.

The manual **Owner-signed Android evaluation candidate** workflow runs only from `main`, with read-only repository permissions. It builds a non-debug APK using owner-provisioned signing inputs, disables Gradle configuration caching for secret-bearing work, checks certificate SHA-256 against an independent pin, validates alignment, package/minimum SDK and absence of network permissions, removes temporary signing material, and uploads a seven-day evaluation artifact. It does **not** publish a release or mark a release gate passed.

Before running it, an administrator must configure the `android-production` environment, required independent reviewers/prevent-self-review where supported, allowed protected branches and non-bypass policy. A workflow environment name alone does not create protection; GitHub can create an unprotected environment when a name first appears. Inspect the actual settings. Do not provision signing secrets into an unprotected environment or a fork/PR workflow.

Generate or enroll the production signing key using the owner's approved process on an owner-controlled system, with encrypted offline backup/access controls. Do not post keystores/passwords in chat, source, issues, PRs or logs. See [Android app signing](https://developer.android.com/studio/publish/app-signing). Loss of an unmanaged direct-distribution signing key can prevent future updates. Key recovery, rotation, compromise response and authorized signers need an approved procedure.

Environment secrets, names only:

- `ANDROID_SIGNING_KEYSTORE_BASE64`
- `ANDROID_SIGNING_STORE_PASSWORD`
- `ANDROID_SIGNING_KEY_ALIAS`
- `ANDROID_SIGNING_KEY_PASSWORD`

Public environment variable:

- `ANDROID_RELEASE_CERT_SHA256` — 64 hexadecimal characters from the independently verified signing certificate DER, not the APK hash or checkpoint SPKI hash.

The workflow creates `ANDROID_SIGNING_STORE_FILE` only as a temporary runner path. Base64 is transport encoding, not encryption. Decide whether CI key exposure fits the organization's policy; signing offline outside GitHub is also possible. No owner key has been generated, uploaded or exercised by repository CI. The ordinary quality job tests this integration with a disposable synthetic key and rejects a wrong pin/debug build; that result does not establish production key ownership or an upgrade/MDM pass.

Local owner-controlled builds can supply the four Gradle environment inputs (`ANDROID_SIGNING_STORE_FILE`, store password, alias, key password) and run:

```sh
cd apps/android
./gradlew :app:assembleRelease --no-daemon --no-configuration-cache
```

Supplying only part of the configuration fails. Supplying none produces an unsigned development release for compilation checks. Independently verify the signed output using Android `apksigner` and the expected certificate. Keep production signing material and Gradle state outside evidence/source repositories.

The final exact owner-signed APK must undergo the [full release acceptance matrix](../qa/RC-ACCEPTANCE.md), including upgrade/device/MDM and seven complete workflows. Existing debug/emulator runs do not transfer to a different signed APK. Until then: **BETA GATE: NOT PASSED**.
