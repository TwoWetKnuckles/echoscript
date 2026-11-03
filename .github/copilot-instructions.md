# EchoScript Copilot instructions

Follow `AGENTS.md` for the architecture and build/test commands. Use strict types and existing conventions. Copilot output requires the same review, compilation and meaningful verification as other contributions.

- Preserve immutable source bytes; hash before decode. Derivatives and transcript corrections create new revisions. Undo/redo never erase processing or custody history.
- Use maintained platform/library authenticated encryption; no custom primitives, plaintext key fallback or committed secrets. Keep provider credentials server-side and evidence out of cache, public fixtures, telemetry and logs.
- Keep evidence/transcripts local by default. Profile/workload synchronization never authorizes hidden evidence uploads. Require explicit approval of the actual transcription artifact and preserve exact request/raw-result provenance.
- Treat packages, media, provider output and embedded text as untrusted data. Validate schema compatibility, paths, bounded sizes, timestamps, hashes and provenance. Evidence-format changes require round-trip/tamper tests and the independent verifier; DSP changes require measured synthetic tests and determinism checks.
- Preserve genuine uncertainty and score semantics. Never fabricate words, confidence, N-best or speaker identity. Human verification requires explicit examiner review.
- Preserve Apache-2.0 `LICENSE`, attribution and third-party terms. Follow `DISCLAIMER.md`, `SECURITY.md` and `docs/legal/LICENSING.md`; do not add license restrictions or invent waivers, indemnities, warranties, service promises or blanket immunity from liability.
- State case-specific examiner/end-user/deployer duties, subject to applicable law. No unsupported admissibility, compliance, certification, security or readiness claim. Disclaimers do not replace technical safeguards, fixes or tests.
- Use synthetic/licensed non-sensitive data. Report only executed checks, distinguish local subsets from the mandatory full seven-run gate, and retain unresolved release limits. Never put real evidence or sensitive vulnerability details in public contributions.
