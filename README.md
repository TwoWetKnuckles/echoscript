# EchoScript

Local-first forensic audio platform under development, designed to support defensible forensic-audio analysis, transcription, evidence preservation, and chain-of-custody workflows.

The restored browser companion currently supports local file intake, playback, SHA-256 manifests and integrity rechecking. The full Android evidence application and mandatory seven-run release gate are not complete. No beta, admissibility or compliance claim is made.

## Development

Node.js 24 LTS: `npm test`. Serve `apps/web/dist/` over HTTPS or localhost to use browser file hashing. Use only synthetic, non-sensitive test material.

## Repository map

| Path                              | Purpose                                                 |
| --------------------------------- | ------------------------------------------------------- |
| `apps/web/`                       | Browser companion, tests and prior implementation notes |
| `docs/IMPLEMENTATION.md`          | Scope, stages and executed verification record          |
| `AGENTS.md`                       | Coding-agent architecture and evidence invariants       |
| `.github/copilot-instructions.md` | Copilot repository instructions                         |

Public GitHub contains application source and synthetic fixtures only. Evidence, audio, transcripts, notes, audit records and case manifests remain local by default. Supabase is limited to approved non-evidence metadata; provider credentials stay server-side.

Original project code is Apache-2.0. Third-party libraries, codecs and models retain their separate licenses.
