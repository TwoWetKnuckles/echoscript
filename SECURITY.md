# Security policy

**Development software — BETA GATE: NOT PASSED.** No production, admissibility or regulatory certification is claimed. Evaluate with synthetic, non-sensitive material. Security controls and executed tests reduce specific risks; they do not guarantee that a system is secure or suitable for a case. Read the [responsibility notice](DISCLAIMER.md) and [QA limits](docs/qa/STATUS.md).

## Versions and maintenance

| Version                                                | Status                                                                                            |
| ------------------------------------------------------ | ------------------------------------------------------------------------------------------------- |
| Current `0.6.0` development candidates and main source | Reports may be submitted for review; no stable or production-supported release has been announced |
| Historical commits, forks and third-party deployments  | No maintenance or backport commitment from this project; contact their operator for its policy    |

No guaranteed acknowledgement, remediation deadline, continued support, bounty or service level is offered. An operator is responsible for monitoring its dependencies and advisories and choosing a maintenance plan. Separate authorized written support agreements, if any, govern their own obligations.

## Report a vulnerability privately

**Never post evidence, audio, transcript text, case identifiers, personal information, secrets or exploitable vulnerability details in public issues, discussions, pull requests or Actions logs.** This repository is public. Use only a minimal synthetic reproducer that you are authorized to share.

1. Open the repository's [Security page](https://github.com/TwoWetKnuckles/echoscript/security). If **Report a vulnerability** is available, use GitHub's private vulnerability reporting form. A `SECURITY.md` file does not itself enable that feature.
2. If private reporting is unavailable and no verified private contact is published, open a public issue containing **only a request for a private security contact**, without technical details or attachments. Wait for a confirmed private channel before providing a report. Do not guess an email address or send secrets through a public channel.
3. In the private report, include the affected commit/version, component, synthetic reproduction steps, expected/observed behavior, potential impact and environment. Redact credentials and case content. Do not attach real evidence even privately; arrange a sanitized reproduction instead.

Testing must stay within systems/data you own or have permission to assess. Do not probe another user's device, hosted service or case, access third-party data, disrupt availability or publish sensitive information. This policy provides a reporting route, not a legal safe-harbor promise, third-party authorization or a bounty agreement. See [GitHub's private reporting guidance](https://docs.github.com/en/code-security/how-tos/report-and-fix-vulnerabilities/report-privately).

## Controls and trust boundaries

- Android source bytes are hashed before decode and stored using maintained Tink Streaming AEAD with a Keystore-protected keyset in persistent app-private storage. Backup/device transfer are excluded. Loss of keys/device reset can make the vault unrecoverable. A compromised unlocked device remains outside the vault's protection.
- Browser working evidence is bounded session memory, not a persistent Android-equivalent vault. Encrypted case exports depend on the chosen passphrase. Android includes opt-in Tink streaming protected native case export; legacy ZIPs and individual audio/documents are plaintext. Protect destinations, recipients and passphrase custody; no password reset or key escrow is provided.
- Artifacts, revisions, canonical audit linkage and the independent verifier detect defined inconsistencies. Unsigned hashes/chains can be rewritten together; preserve trusted external references. Android device-key P-256 checkpoints authenticate historical prefixes and disclose later unsigned events. They are not examiner identities, attestation or trusted timestamps. Independently enroll/revoke fingerprints and validate the recovery policy. See [protected transfer/signature scope](docs/evidence/PROTECTED-TRANSFER.md).
- The browser companion uses an authenticated loopback-only Vosk service, with origin/consent/replay controls and bounded jobs. Android runs bundled Vosk models in-process by default; optional companion transport requires exact-payload approval and retains the OpenAI key server-side. The loopback bearer token is for development. The downloadable Android preview is debuggable and development-signed. Evaluate it on approved test devices with synthetic material. OIDC/roles, cloud adapters and approved profile/workload sync remain unfinished. No evidence is synchronized to Supabase by the current application.
- Local render/Vosk job settings are encrypted in the private vault. WorkManager stores only opaque UUIDs and generic progress; cancellation preferences hold opaque scheduled IDs. Foreground notifications omit case names, examiner identifiers and transcript text. Completed outputs are verified before reuse. Remote credentials and approval are not persisted as jobs.
- Imported archives, media and provider responses are untrusted. The implemented paths enforce schema/path/size/hash checks and finite job limits. Those checks do not establish safety against every malformed input or host compromise.

CI includes behavioral/tamper tests, Android checks, Rust checks, npm advisory scanning and CodeQL configuration for JavaScript/TypeScript/Python plus manual-build Java/Kotlin analysis. A configured job is not an executed successful scan; inspect the exact commit's result. Scans and dependency-update configuration are not a penetration test or certification. Secret scanning, push protection, private reporting and branch protections are separate GitHub settings; their presence is not guaranteed by these files.

## Suspected integrity failure or exposure

Stop affected processing, transmission and reliance. Preserve originals, trusted checkpoints and a minimal incident record; isolate affected devices/services through your organization's procedure. Revoke or rotate exposed credentials through their issuer, assess affected derivatives/transcripts/exports, and notify responsible security/legal personnel under applicable incident and disclosure requirements. Do not upload the incident's evidence to this repository or destroy material subject to preservation duties.

Maintainer triage should reproduce with synthetic material, assess affected versions and evidence impact, add a meaningful regression test, and document the fix and remaining limits. Publication/disclosure coordination must avoid case data and premature exposure of exploit details. An integrity-affecting fix requires revalidation of affected workflows; changing a disclaimer is not a remediation.

The [threat model](docs/security/THREAT_MODEL.md) and [operator guide](docs/security/OPERATIONS.md) describe the current boundaries and organizational duties. Apache [LICENSE](LICENSE) governs the software's warranty/liability terms; non-waivable legal rights and obligations remain applicable.
