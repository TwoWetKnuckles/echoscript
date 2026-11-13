# Deploy EchoScript documentation separately

Use a dedicated hosting project named `echoscript-docs` in the authorized owner account. This is a Next.js documentation app, separate from the Android APK, browser companion and transcription service. No provider credentials, signing keys or evidence files belong in this project.

| Setting                  | Value                                                     |
| ------------------------ | --------------------------------------------------------- |
| Git repository           | `TwoWetKnuckles/echoscript`                               |
| Root Directory           | `apps/docs`                                               |
| Framework preset         | Next.js                                                   |
| Node.js                  | 24.x                                                      |
| Install command          | `npm ci --ignore-scripts`                                 |
| Build command            | `npm run build`                                           |
| Output directory         | Framework default; leave unset                            |
| Preview source           | Exact reviewed commit on `feature/release-gate-hardening` |
| Production source        | Approved main-branch commit after repository review       |
| Required secrets         | None                                                      |
| Optional public variable | `NEXT_PUBLIC_DOCS_URL`, the actual HTTPS docs origin      |

The committed MDX mirrors make the app independently buildable without importing files outside its root. Synchronization/checking runs from a full repository checkout before publication. The hosting build uses the committed content and never reads the Android vault or local API environment.

Keep the default `/docs` route. Use the provider's assigned HTTPS hostname first. A custom `docs.example.com` needs an owner-selected domain and DNS configuration; no domain purchase or DNS change is implied. A main-site `/docs/*` proxy can be configured later using the host's documented multi-zone routing.

Before publishing, run the app's type, production-build and HTTP checks. Inspect the preview on a phone and desktop, including search, sidebar, theme toggle, Markdown views, download links and social images. Promote only the intended tested commit. The current Vercel connection requires team authorization before project lookup/deployment can proceed; no public docs deployment has yet been verified.

Official references: [Fumadocs deploying](https://fumadocs.dev/docs/next/deploying), [Next.js deployment](https://nextjs.org/docs/app/getting-started/deploying), [Vercel monorepos](https://vercel.com/docs/monorepos).
