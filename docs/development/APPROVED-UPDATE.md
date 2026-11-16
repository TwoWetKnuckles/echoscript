# Approved workbench and site update

Owner approval: November 15, 2026, America/Los_Angeles. The owner authorized previously discussed improvements, Symfony/importmap setup, the recommended theme/stack direction and codebase refactoring. This supersedes the pending-approval wording in the November 14 research baseline; it does not declare the beta gates passed.

## Implemented direction

- **E01:** Android cancellation commits WorkManager cancellation before raising the cooperative stop flag. The prior notification test reproduced `FAILED` instead of `CANCELLED`; its existing assertions are retained. Encrypted plans, durable cancellation, recovery and verified completed-pass reuse remain in the native work queue.
- **E03/E05:** The native ClearVoice before/after toggle, Vosk default and Whisper selection remain implemented. The browser now also selects Vosk/Whisper before a provider-specific, hashed transmission approval. Its key stays on the loopback service. Raw results and normalization retain their exact route; Whisper does not acquire invented confidence, alternatives or speaker identity.
- **E09:** Vue 3 and TypeScript own the browser shell and appearance/offline controls; compiled Tailwind and shared graphite/cyan tokens align the web companion, PHP portal and retained Fumadocs documentation. Native Compose already uses the matching palette and remains native. GitHub renders Markdown and an SVG banner, rather than running application CSS.
- **E10:** Vite explicitly targets Chrome/Edge 111+, Firefox 128+ and Safari 16.4+, matching Tailwind 4's documented CSS minimums. Android OS version does not establish WebView/browser compatibility. Browser processing remains a bounded session workspace; 750 MB/native codec parity remains deferred.
- **E10:** Offline app caching is opt-in, restricted to a build-time allowlist of application assets and the licensed synthetic sample. No runtime caching, audio persistence, provider replay or silent update/reload is configured. Users export case packages separately and explicitly reload for updates.
- **E13:** The Symfony/Twig public portal uses `downloads/current.json` for the verified existing APK, checksum and release links. New source and the currently available APK remain visibly distinguished until a replacement qualifies. Previous owner backups and the retained rollback stay recorded.

## Refactoring and dependency choices

The existing framework-independent audio worker and evidence packages retain their contracts. Vue mounts the DOM/canvas adapter with explicit disposal of worker, event listeners, requests and object URLs. Processing-history text now lives in a small dedicated module shared with existing exporters; importing it no longer pulls PDF export machinery into the main interface. The observed production interface bundle fell from approximately 535 kB to 136 kB before compression.

TypeScript 6.0.3 is pinned because the current Vue checker requires the JavaScript compiler API absent from TypeScript 7's package. Both strict API compilation and Vue checks run with that supported compiler, without ignored peer errors or disabled checking. The docs app retains its independently locked toolchain. The PWA toolchain's existing development-only advisories remain subject to audit; no suppression or forced incompatible dependency override was added.

Symfony 7.4 LTS and PHP 8.4 live in `apps/site`, with real Composer dependencies, Twig routes/templates, AssetMapper and a versioned Stimulus importmap entry. Site CI and scheduled/manual importmap maintenance are separate from the native/API build. The site never mediates evidence or requests provider credentials.

## Verification and release boundary

Local checks include 38 Node behavioral tests, strict API/Vue checks, browser/site production builds, ESLint, Actionlint, PHP/container/Twig validation, real Symfony kernel route/method/download/importmap checks and importmap/Composer advisory scans. Browser synthetic inspection covers source loading, rendering, history, theme and provider consent. Exact final commit and hosted results are recorded in the associated implementation PR; observations from an earlier commit are not promoted automatically.

Physical Galaxy S22+ acceptance, owner-controlled signing and upgrade continuity, the full interruption/resource/security/accuracy matrix, trust/team/metadata deployment decisions and seven complete accepted exact-build workflows remain separate obligations in the release gates. No beta-ready claim or owner-key substitute follows from this stack update.
