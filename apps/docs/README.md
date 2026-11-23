# EchoScript documentation app

Standalone Fumadocs/Next.js app, generated with the official `create-fumadocs-app@latest` Next.js/MDX template. Its package lock, layouts, global CSS and deployment are separate from Android and the existing browser companion. The generated Fumadocs search, `/llms.txt`, full Markdown export, content negotiation and OG image routes are retained.

## Develop and verify

Use Node.js 24. From this directory:

```sh
npm ci --ignore-scripts
npm run sync:content
npm run check:content
npm run dev
```

Open the printed local URL at `/docs`. In a workspace that cannot enumerate network interfaces, use `npm run dev -- --hostname 127.0.0.1`. The app defaults to a graphite/cyan dark theme and retains Fumadocs light/system controls.

```sh
npm run types:check
npm run build
npm run verify:site
npm audit --audit-level=high
```

The HTTP verification starts a temporary production server on loopback, checks every documentation page and internal navigation target, then exercises search, both LLM exports, Markdown negotiation/suffixes, the generated OG image and a missing-page response. It stops that server afterward. Browser visual/interaction checks are reported separately from these HTTP assertions.

## Edit content

`content/docs/index.mdx` is the introductory guide. Existing repository guides and policies remain canonical at their original paths so native development instructions and GitHub links keep working. `npm run sync:content` mirrors them into `content/docs`, moves each first heading into frontmatter, preserves its body and adjusts relative links. Angle-bracket URL links become equivalent standard Markdown links for MDX compatibility. `content-source-map.json` records each original path/hash; the GitHub page-view action opens that canonical source.

Edit canonical Markdown, run synchronization and commit the updated MDX/source map together. `check:content` detects drift without changing files. `meta.json` files explicitly order navigation. Add new hand-authored MDX pages with a title and description, and list them in the relevant metadata. Never edit the generated `.source/` folder.

Brand/navigation settings live in `lib/layout.shared.tsx`; repository, release and route settings live in `lib/shared.ts`. Pages stay under `/docs`. Download links point to the current verified Android 0.6 test candidate. All implemented tools are available.

## Deploy independently

Use [DEPLOYMENT.md](DEPLOYMENT.md): a dedicated Next.js project with Root Directory `apps/docs`, its own install/build settings and an approved commit. There are no required secrets. Optional `NEXT_PUBLIC_DOCS_URL` sets the real documentation origin; Vercel's deployment URL is used when available. No custom domain, production deployment or live URL is claimed until verified.

The app builds solely from its committed directory. Synchronization requires a full repository checkout, so it is explicit and checked by the separate documentation CI workflow rather than required at hosting build time.

## Executed validation

Local Node 24 checks passed: content synchronization/verification, route/TypeScript checking, the optimized production build, all 25 documentation page responses, internal navigation targets, examiner-note search, LLM index/full export, Markdown negotiation and suffix routes, OG image generation and a 404 response. `npm audit --audit-level=high` reported zero known findings. The starter dev server also rendered `/docs` with HTTP 200.

The remote browser could not connect to the workspace's loopback server, so these HTTP/build results are not a visual phone/browser acceptance claim. The existing protected Vercel deployment predates this candidate. Further standalone docs design/deployment work is paused; these source changes keep release links and factual content consistent.
