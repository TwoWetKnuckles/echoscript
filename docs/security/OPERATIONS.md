# Operator and examiner responsibilities

**Current status: development evaluation with synthetic data; BETA GATE: NOT PASSED.** This guide defines operational duties for planning and evaluation. It does not approve use on live cases or replace an organization's procedure, professional judgment or applicable law. Read [DISCLAIMER.md](../../DISCLAIMER.md) and [known trust boundaries](THREAT_MODEL.md).

## Before a case or deployment

- Assign the responsible examiner, evidence custodian and system/security operator. Identify the relevant jurisdiction, case authority, agency/court policy, confidentiality/privilege, disclosure duties, residency, retention and legal holds. Obtain appropriate legal/security review for the actual matter; a generic profile cannot decide these rules.
- Validate the exact source commit, build, device and workflow with controlled fixtures. Review [QA results and unresolved gates](../qa/STATUS.md), third-party licenses and component advisories. Keep an independent record of approval, limitations and testing; do not turn the development checkpoint into a production or certification claim.
- Establish device access control, updates, physical protection, export storage, independently controlled checkpoints and recovery procedures. Test recovery using synthetic material. Current Keystore reset/key loss cannot be reversed by a maintainer; excluded Android backup is not a recovery plan.
- Budget and assign maintenance, support and incident ownership. Free hosting/compute quotas do not guarantee service continuity, regulatory suitability or ongoing support. Keep evidence and credentials out of source control, CI, issues, support attachments and deployment logs.

## Acquisition and examination

Record lawful acquisition context and the original hash; preserve original bytes independently. Verify intake against a trusted checkpoint and document transfers outside the application. Use derivatives for analysis; retain the actual operation/algorithm/version, parameters, parent/result hashes and examiner notes. Do not erase history to correct a narrative.

Compare processed results with originals. Review speech candidates, restoration estimates, timestamps, neutral speaker labels, confidence semantics and uncertain words. Explicitly review each affected segment before marking a transcript human-verified. Preserve earlier revisions and disclose estimates/uncertainty; do not infer identity or legal authenticity from a provider result or hash.

The current loopback speech API uses a development bearer token, not team OIDC/role controls. Do not bind or reverse-proxy it to an organization or Internet endpoint. The native preview uses bundled speech/speaker models by default; optional companion processing adds network permission and explicit payload approval. Browser provider prohibition is an application control, not OS-enforced air-gap isolation. For genuinely isolated evaluation, provision dependencies/models in advance, run the companion locally and enforce network isolation outside the application.

## Providers and information leaving the device

Evidence, transcripts, reports, notes, audit and case manifests must not become cloud data merely because profiles or workload assignments are synchronized. The current application sends no evidence to Supabase; team/profile/workload synchronization is unfinished.

Before enabling or using the development-only external provider adapter, qualify the exact service/model/region, data flow, retention/deletion, training use, subprocessors, security controls and required agreements against the case and organizational policy. Record what is verified and what remains unknown. Do not submit when a mandatory condition is unknown or unmet. Approval of a hash/duration-bound submission is an additional control, not a substitute for lawful authority or an agreement. Review changes before further use. No remote-provider approval is implied by selecting a provider name.

Avoid sensitive filenames, query strings and credentials in hosted URLs. A web host or other infrastructure may process request metadata under its own settings/terms even when audio processing remains local. Qualify those services separately; do not infer zero retention from this application's logging behavior.

## Export, retention and incident handling

Choose the correct artifact/revision and accurately labeled format. Legacy case ZIPs and individual audio/document exports are **plaintext**. Android includes an opt-in [streaming protected native transfer](../evidence/PROTECTED-TRANSFER.md). Use unpredictable passphrases and separate authorized password custody; no password reset is available. Explicit device checkpoints sign historical prefixes; enroll their public-key fingerprints independently and review unsigned later events. Choose protected destinations/authorized recipients, retain the outer package hash through an independent trusted channel and verify the exact received package with the CLI before reliance. Hash/signature agreement alone does not establish identity, acquisition or legal custody.

Document release/transfer, processing history, transcript review status and limitations. Determine retention, legal holds and permitted destruction for every copy, including exports and recipient/provider copies. Deleting an app or local file does not guarantee destruction elsewhere; do not destroy preserved material to address a software defect.

On suspected exposure or integrity failure, stop affected reliance/transmission, preserve originals and incident records, isolate affected systems according to procedure, and involve responsible security/legal staff. Revoke exposed credentials through their issuer, assess downstream artifacts and revalidate corrected workflows. Follow [SECURITY.md](../../SECURITY.md) for a sanitized private report; never upload real case material to the project. The operator determines applicable notification and preservation obligations.
