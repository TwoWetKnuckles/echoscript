# Repository instructions

## Architecture and commands

`apps/web/` contains the browser companion. Run `npm test` for current tests. Contracts and source-of-truth schemas belong in `packages/` and `specs/json-schema/`; Android in `apps/android/`; provider mediation in `services/api/`; independent verification in `tools/case-verify/`. Keep `main` buildable, use focused conventional commits, and record executed checks in `docs/IMPLEMENTATION.md`.

## Evidence invariants

- Hash original bytes before decode; never overwrite an acquired source, derivative or transcript revision.
- Use maintained authenticated encryption and platform key storage, never custom cryptography or committed secrets.
- Authoritative evidence belongs in encrypted persistent private storage, never cache. Profile sync does not authorize evidence upload.
- Every transformation records parent/result hashes, actual parameters and algorithm/software versions. Undo changes an active pointer without deleting history.
- Audit/provenance/package changes require compatibility, round-trip and tamper tests, including the independent verifier.
- Treat provider output and imported archives as untrusted. Validate schemas, sizes, paths, timestamps and linkage.
- No client provider secrets, hidden uploads, audio/transcript logging, fabricated confidence/N-best or unsupported compliance/admissibility claims.
- Use only synthetic/licensed non-sensitive fixtures. Report only checks executed; never turn partial workflows into a full seven-run PASS.

Follow existing module conventions, use strict types where available, and test meaningful behavior rather than mirroring implementation.
