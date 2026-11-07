# Independent protected-case tool

Java 17+, no Android SDK or device required. Uses maintained Tink and platform cryptography. Build from repository root:

```sh
apps/android/gradlew -p tools/case-protect :case-security:test installDist --no-daemon
```

The installed distribution carries the project license/responsibility notices, exact-version third-party notices including the Protobuf BSD notice, verification instructions and a SHA-256 inventory of its actual runtime JARs. Review dependency/license changes before redistributing it. The JVM itself is not bundled. This is not the complete application license/compliance audit.

For independent verification of a protected transfer without a plaintext temporary case:

```sh
npm ci --ignore-scripts
node tools/build.mjs --schema-only
read -r -s -p 'Transfer passphrase: ' ECHOSCRIPT_PACKAGE_PASSPHRASE
export ECHOSCRIPT_PACKAGE_PASSPHRASE
node tools/case-verify/index.mjs CASE.echocase.enc --sha256 INDEPENDENT_CIPHERTEXT_SHA256 --trusted-key-sha256 INDEPENDENT_SIGNER_SPKI_SHA256
unset ECHOSCRIPT_PACKAGE_PASSPHRASE
```

Replace the hash placeholders with independently obtained 64-character lowercase hexadecimal values. Do not use a fingerprint supplied only inside the untrusted package as independent trust. The passphrase is never a command-line argument. Environment variables are visible to the process and potentially privileged local users; use a trusted verification host. The helper inherits only required runtime variables and the passphrase. `ECHOSCRIPT_CASE_PROTECT_LIB` optionally selects an installed tool's `lib` directory.

The independent Node verifier validates ZIP inventory/CRC, source and derivative hashes, case/checkpoint schemas, canonical bytes, provenance/audit linkage and P-256 signatures. The JVM authenticates bounded plaintext byte ranges into pipes and checks the unchanged ciphertext at completion. No automatic repairs. A nonzero result means validation did not complete. A matching signature does not identify an examiner, validate a recording or establish legal admissibility. Read all unsigned-tail/identity limitations in the JSON result.

Explicit conversion is also available:

```sh
tools/case-protect/build/install/echoscript-case-protect/bin/echoscript-case-protect encrypt INPUT.echocase OUTPUT.echocase.enc
tools/case-protect/build/install/echoscript-case-protect/bin/echoscript-case-protect decrypt INPUT.echocase.enc OUTPUT.echocase
```

An interactive console prompts for the passphrase. Without a console, supply the environment variable. Decryption deliberately creates plaintext at the explicitly chosen output destination; use protected storage and independently verify it. Existing destinations are never overwritten, including a racing creator. Conversion uses a same-directory temporary file followed by atomic no-clobber hard-link creation; filesystems without hard-link support fail safely. Partial files are removed on handled failures. This is not a power-loss or secure-erasure guarantee. Password loss is unrecoverable.

See [the exact container/checkpoint specification](../../docs/evidence/PROTECTED-TRANSFER.md) and [release gates](../../docs/RELEASE-CANDIDATE.md).
