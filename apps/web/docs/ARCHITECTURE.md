# Architecture and boundaries

## Current implementation

The static browser companion imports a pure JavaScript integrity module. A user selects a local file (maximum 128 MiB); the app reads it into memory, computes SHA-256 with Web Crypto, exposes an object URL for browser playback, and can download a basic intake manifest. Selecting another source or releasing it revokes the previous playback URL. Asynchronous operations suppress stale UI results when a newer selection replaces them.

The verifier recomputes a selected file's hash and compares SHA-256 and byte count with a versioned EchoScript manifest. A renamed file can match by content. Matching a mutable JSON record does not prove acquisition origin, timestamp, identity or chain of custody.

No audio, transcript, hash or case metadata is uploaded. Application state is held in memory. Browser codecs control playback support. Web Crypto requires HTTPS or localhost. Asset references resolve relative to the app's nested hosting path.

Supabase contains eight non-sensitive catalog entries only. The app loads a checked-in deployment snapshot. It has no Supabase credentials or evidence-storage API. Catalog RLS is enabled and client access is denied; updating the database does not automatically refresh the deployed snapshot.

## Target architecture, not yet implemented

- Android 10+ Kotlin/Compose client with app-private encrypted vault and Keystore isolation.
- Immutable source artifacts, deterministic derived renders and SHA-256 provenance DAG.
- Append-only canonical audit hash chain and independent verifier.
- Waveform, spectrogram, region selection and non-destructive processing history.
- DSP and separate transcription-transmission artifacts.
- Server-mediated provider adapters with explicit consent, qualified retention terms and no evidence-content logging.
- Optional preferences/preset sync; evidence remains local by default.

The browser companion is an early interface and integrity utility, not an implementation of the entire architecture.
