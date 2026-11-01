# Development status

## Implemented

- Six browser views with responsive navigation.
- Local file selection, browser playback and SHA-256 hashing.
- Basic versioned JSON intake manifest export.
- Manifest comparison, mismatch detection and renamed-file distinction.
- Processing-reference and documentation-template catalog with eight entries.
- Non-sensitive Supabase catalog storage, RLS and denied client access.
- Node tests for integrity functions, catalog and nested asset references.

## Usability defects corrected in this review

- Root-relative CSS, script and catalog paths would fail when hosted beneath `/projects/echoscript/`; changed to relative resolution.
- Playback object URLs were revoked on `pagehide`, which could break playback on back/forward-cache restoration; revocation now occurs on explicit release or replacement.
- Skip navigation changed the route and returned users to the overview; it now focuses main content without changing routes.
- File changes during an in-progress verification could show stale results; selection changes now invalidate old results.
- Insecure-context hashing errors now explain the HTTPS/localhost requirement.
- Invalid manifest JSON now produces a readable error message.

## Not implemented / release blockers

Android client, encrypted persistent vault, acquisition, chunked encryption/hashing, deterministic DSP, waveform/spectrogram, VAD, processing history, undo/redo, provenance DAG, audit chain, transcription providers, qualified retention agreements, consent/audit workflow, full evidence exports, independent audit verification and seven consecutive full-application E2E release runs.

## Known limitations

Files are fully buffered in memory; 128 MiB may still be excessive for low-memory devices. Browser codec support varies. The app does not persist selected files or state across reloads. Manifests are unencrypted and unsigned, contain filenames, and use the device clock. No enhanced audio is rendered. Reference presets do not apply effects. Database catalog changes require a deployment snapshot update.

## GitHub handoff

The requested destination `TwoWetKnuckles/projects` is public. GitHub does not provide private subfolders or branches. Uploading app source there requires explicit approval to publish incomplete source, or a private development repository followed by a later import. The connector reads this repository but a Git tree write returned HTTP 403 `Resource not accessible by integration`; local Git push also lacked authentication. No GitHub publication is verified.
