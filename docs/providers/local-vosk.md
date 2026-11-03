# Local Vosk qualification

| Fact | Status | Observed configuration |
| --- | --- | --- |
| Provider/runtime | VERIFIED | Local Vosk Python API 0.3.45 |
| Model | VERIFIED | `vosk-model-small-en-us-0.15` |
| Model archive SHA-256 | VERIFIED | `30f26242c4eb449f948e42cb302dd7a686cb29a3423a8367f99ff41780942498` |
| Exact extracted weights | VERIFIED | Inventory in `specs/models/vosk-small-en-us-0.15.json`; worker validates every file |
| Model license | VERIFIED | Apache-2.0 in official Vosk model catalog; retain notices for redistribution |
| Language | VERIFIED | English (US model) |
| Processing location | VERIFIED for executed tests | This development host, in a short-lived local subprocess |
| Third-party audio/transcript transport | NOT APPLICABLE for executed local path | No external speech service called |
| Adapter evidence retention | VERIFIED by implementation/tests | No evidence files written; bounded request/output buffers discarded after request/process termination |
| Host swap, snapshots, crash dumps, administrator access | UNKNOWN | Deployment/operational policy is required; application disposal is not a secure-erasure guarantee |
| Word timestamps | VERIFIED | Actual returned Vosk word boundaries |
| Alternatives | VERIFIED capability | Provider-returned decoder alternatives only; the controlled clean fixture returned one primary candidate |
| Confidence | VERIFIED semantics | Returned N-best `confidence` field is a decoder score, not a calibrated probability; word confidence may be absent in this mode |
| Diarization | NOT AVAILABLE in current adapter | Speaker remains unassigned; no identity attributes inferred |
| Request limit | VERIFIED local policy | Canonical PCM16 mono 16 kHz WAV, max 24 MiB / 750 seconds; provider chunking remains outstanding |
| At-rest source encryption | NOT APPLICABLE to adapter | Device vault handles storage; service must not become the evidence repository |
| Production authentication | NOT AVAILABLE yet | Loopback development token; OIDC/team authorization remains outstanding |
| SOC 2 / ISO 27001 / FedRAMP / CJIS / HIPAA-BAA / FIPS / NIST certification | UNKNOWN or NOT APPLICABLE | No provider or deployment certification claim; exact organizational/legal requirements remain unresolved |
| Cloud regions, subprocessors, ZDR, government cloud | NOT APPLICABLE for local path | External adapters are not configured |

Primary references: <https://alphacephei.com/vosk/models>, <https://alphacephei.com/vosk/install>, <https://github.com/alphacep/vosk-api>. The package pin is an observed tested version, not a claim that its native code has no vulnerabilities.

To configure local testing:

```sh
python3 -m venv .venv
.venv/bin/pip install -r services/api/requirements.txt
python3 scripts/setup-model.py
export VOSK_PYTHON="$PWD/.venv/bin/python"
export VOSK_MODEL_PATH="$PWD/models/vosk-model-small-en-us-0.15"
npm run build
npm run qa:local
```

For an interactive local API, separately set a random `API_TOKEN` of at least 32 characters and an exact `ALLOWED_ORIGIN`, then run `npm run api`. Keep tokens out of source, URLs, logs and browser persistent storage. The service binds only to 127.0.0.1. The browser asks approval for each artifact before submission. HTTPS/private-network browser policies may require an appropriate local deployment; do not disable browser security controls.
