# EchoScript file inventory

The future location in the portfolio repository is `echoscript/`. All paths below are relative to that folder. The currently deployed private Site uses the same application files at its source root.

| File | Description |
| --- | --- |
| `README.md` | Purpose, setup, data boundary, limitations and development status |
| `package.json` | Dependency-free Node test entry point and application version |
| `dist/index.html` | Six accessible sections: overview, intake, verification, reference library, protocol and status |
| `dist/style.css` | Responsive visual styles, mobile navigation and focus indicators |
| `dist/app.mjs` | Routing, local file lifecycle, playback, export, verification UI and reference filtering |
| `dist/core.mjs` | SHA-256, input limits, manifest creation and manifest comparison |
| `dist/catalog.json` | Snapshot of eight non-sensitive Supabase reference entries |
| `tests/core.test.mjs` | Hash vector, immutability, manifest roundtrip, mismatch and invalid-input checks |
| `tests/hosting.test.mjs` | Nested-hosting asset resolution and reference-catalog integrity checks |
| `docs/FILE-TREE.md` | This inventory of individual files |
| `docs/ARCHITECTURE.md` | Current architecture, trust boundaries and target components |
| `docs/STATUS.md` | Implemented features, remaining work and release limits |

The `.openai/hosting.json` file belongs to the private Sites deployment and should not be copied to the public portfolio repository. Build output is already in `dist/`; no package installation or compilation is required.
