# OpenAI Whisper adapter — development only

This opt-in local mediation endpoint is a work in progress. The Android client remains offline and does not yet connect to it; no end-to-end OpenAI transcription is claimed. It is not an authorized cloud-evidence deployment.

The server accepts only 16 kHz mono PCM16 WAV, at most 24 MiB and 750 seconds, after a short-lived one-use consent bound to the exact SHA-256, duration, provider `openai-whisper`, model `whisper-1` and explicit `acknowledgedExternal: true`. It sends a multipart request to OpenAI's HTTPS transcription endpoint with `verbose_json` and word timestamps. Provider output remains raw and unverified: absent confidence, alternatives and speakers must not be invented; human review is required. Model availability, limits, data handling, retention and processing agreements must be checked against the exact organization/project configuration before use. Audio leaves the local machine; API usage can incur charges.

The service is disabled by default and binds to loopback only. Enable it only after reviewing the deployment and disclosure, by setting `OPENAI_PROVIDER_ENABLED=true`, the server-side `OPENAI_API_KEY` and a separate strong local `API_TOKEN`. The key must not be placed in the Android APK, browser bundle, Git, logs or case packages. The approved development env file must be sourced into the service process explicitly; the server does not automatically load dotenv files. Never expose this development-token server to a network or team. A production design still requires HTTPS, real user authentication/authorization, bounded streaming, exact artifact custody recording, Android transport, consent UI, independently approved deployment and provider qualification.

Provider network errors are returned as bounded generic error codes; audio and transcript text are not deliberately logged. No live provider call or billing validation has been performed. Synthetic tests inject a fake transport and do not contact the provider.

Official API: https://developers.openai.com/api/docs/guides/speech-to-text
