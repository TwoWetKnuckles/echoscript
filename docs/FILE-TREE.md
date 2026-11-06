# Source map

All paths are relative to the public monorepo. Generated output, downloaded models and QA artifacts are ignored.

| Path                                                    | Purpose                                                                                                            |
| ------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------ |
| `AGENTS.md`, `.github/copilot-instructions.md`          | Evidence invariants and agent build/test instructions                                                              |
| `LICENSE`, `NOTICE`, `DISCLAIMER.md`, `CONTRIBUTING.md` | Software terms, attribution, case-specific responsibilities and contribution policy                                |
| `SECURITY.md`, `docs/security/`, `docs/legal/`          | Private reporting procedure, current threat model, operator guidance and license explanation                       |
| `downloads/`                                            | End-user release link and plain-text Android installation instructions, also included in the ZIP                   |
| `apps/android/`                                         | Kotlin/Compose vault; Keystore/Tink encryption; SAF intake/export; Media3 authenticated playback; native tests     |
| `apps/web/src/index.html`, `main.mjs`, `workbench.css`  | Seven-workspace browser UI and local workflow                                                                      |
| `apps/web/src/processing.worker.mjs`                    | Serialized worker operations, DSP revisions, transcription artifacts/review and exports                            |
| `apps/web/assets/`                                      | Pinned Noto Sans font and SIL Open Font License                                                                    |
| `packages/evidence/index.mjs`                           | Canonical JSON, SHA-256, immutable artifacts, audit/provenance, case packages and authenticated package encryption |
| `packages/evidence/schema-validator.cjs`                | Generated standalone schema validator, avoiding browser unsafe-eval                                                |
| `packages/evidence/transcript.mjs`                      | Provider normalization, uncertainty, acoustic warnings and immutable human review                                  |
| `packages/audio/index.mjs`                              | PCM WAV, FFT/Hann analysis, conservative event candidates, deterministic region DSP and resampling                 |
| `packages/exports/index.mjs`                            | Processing changelog and native transcript/report document formats                                                 |
| `services/api/src/`                                     | Strict TypeScript loopback API; token/origin/consent/hash/replay controls; bounded decoder process                 |
| `services/api/vosk_worker.py`                           | Real local decoder with pinned weight verification; stdin/stdout, no evidence files                                |
| `services/api/requirements.txt`, `qa-requirements.txt`  | Pinned speech runtime and native document validation dependencies                                                  |
| `specs/json-schema/case.schema.json`                    | Case-format source-of-truth schema                                                                                 |
| `specs/models/`                                         | Committed exact model integrity inventory; separate from ignored weight downloads                                  |
| `specs/fixtures/`                                       | Controlled synthetic speech and reference transcript/provenance                                                    |
| `crates/audio-core/`                                    | Safe Rust bounded-buffer PCM renderer and filter/math tests                                                        |
| `tools/case-verify/`                                    | Independent CLI and streaming archive/hash/CRC/schema/provenance/audit verifier                                    |
| `tools/build.mjs`, `serve.mjs`                          | Reproducible browser/schema build and restricted loopback static host                                              |
| `tools/qa-local.mjs`, `worker-node.mjs`, `qa-stress.py` | Real local-provider seven-run workflow and hour-scale desktop resource checks                                      |
| `tests/`                                                | Measured DSP, integrity/tamper/crypto/archive, transcript/export/API/hosting/verifier behavioral tests             |
| `.github/workflows/`, `dependabot.yml`                  | Pinned least-privilege quality, emulator, Pages/CodeQL and dependency monitoring configuration                     |
| `docs/`                                                 | Boundaries, provider qualification, executed checks, failure repairs and outstanding release requirements          |

The v0.1.1 intake-only companion and its tests remain in Git history. They are not tests of the new workbench. Run commands from the monorepo root unless Android instructions specify otherwise. The existing Sites deployment owns its separate hosting manifest; no Site credential belongs in this public tree.
