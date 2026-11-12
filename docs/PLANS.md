# Free and premium access — product draft

The owner requested a useful free product and premium access available through a subscription or a one-time purchase. The following division is a proposal for review, not a published offer. No prices, billing account, product IDs, payment processing or production entitlement service have been configured.

| Capability | Proposed free access | Proposed premium access |
| --- | --- | --- |
| Local acquisition and storage | Import supported recordings, preserve originals, open saved cases and play audio | Same core access |
| Basic editing | Waveform, playback, one selected region, clip, basic gain/EQ, standard hum/buzz and gentle noise-gate presets | Same, plus advanced effect controls and ordered/overlapping regions |
| Spectral tools | Quick sampled overview | Full-recording coarse scan, detailed time/frequency inspection and measured noise-floor tools |
| Speech and review | On-device transcription where supported, examiner review and notes | Same, plus any future advanced/provider features that are actually offered |
| History and evidence | View and restore saved revisions, verify hashes and export audio, documents and protected cases | Same core access, plus advanced waveform/clipping comparison |

The same premium capability set is proposed for a subscription and a non-consumable one-time purchase. Price, billing periods, trial terms, one-time update/support scope, tax handling, distribution channels and any metered third-party transcription charges still need explicit decisions before sales. No external transcription usage is implicitly included or authorized.

## Evaluation build

In debug builds, **Settings → Explore possible plans** provides in-memory **Free preview**, **Subscription preview** and **One-time preview** choices, plus the default **All tools** evaluation mode. These switches simulate the table above and never create, represent or persist a purchase. Free preview blocks new advanced operations before they change a revision, while keeping existing recordings, history, verification and exports available. Changes reset to All tools when the app process restarts. The release build has no preview selector or active commercial gate. Do not use the preview switch as a license or security mechanism.

The provisional free/premium split can be changed before release. Tests cover the policy, the two premium preview modes, basic clipping, blocked premium analysis, preserved source hashes and protected export in free preview. The newest changes need a completed CI/device run before a pass is recorded.

## Production billing and trust boundaries

- Play-distributed digital features must follow [Google Play's payments policy](https://support.google.com/googleplay/android-developer/answer/9858738), including any applicable regional alternative-billing program requirements. A sideloaded distribution needs its own approved purchase and recovery flow; do not add external checkout links to a Play build without verifying applicable rules.
- Fetch actual price, recurrence and offer terms from the relevant store at purchase time. Never present placeholders as real offers. Offer a clear restore-purchases and subscription-management path, and distinguish pending, canceled, expired, revoked and completed purchases.
- Verify purchase state and authenticity using a secure service before issuing a premium entitlement; do not grant access for pending purchases. Follow the provider's acknowledgement and refund/revocation lifecycle. See [Play Billing integration](https://developer.android.com/google/play/billing/integrate), [one-time purchase lifecycle](https://developer.android.com/google/play/billing/lifecycle/one-time) and [subscription lifecycle](https://developer.android.com/google/play/billing/lifecycle/subscriptions).
- A future offline license must be independently verifiable, bound to an appropriate product/account, have a defined offline grace/recovery policy and handle expiration or revocation. A local UI flag is not a verified entitlement. This repository is open source; client-side checks alone cannot prevent a modified build from bypassing a gate.
- Entitlement checks must not upload recordings, transcripts, notes or case metadata. A lapse, refund dispute, network outage or store failure must not delete evidence or prevent users from opening and exporting material already created. Advanced new processing may be restricted according to the ultimately approved offer.
- The current offline preview has no Internet permission and no payment SDK. Shipping real billing, restoring purchases and cross-device entitlements requires a separately designed and tested distribution build, backend and owner-controlled configuration.
