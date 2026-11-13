import assert from "node:assert/strict";
import { spawn } from "node:child_process";
import { readFile, open } from "node:fs/promises";
import { fileURLToPath } from "node:url";
import { setTimeout as pause } from "node:timers/promises";

const root = fileURLToPath(new URL("../", import.meta.url));
const port = Number(process.env.DOCS_SMOKE_PORT ?? 3120);
assert(Number.isInteger(port) && port >= 1024 && port <= 65535);
const base = `http://127.0.0.1:${port}`;
const log = await open(
  new URL("../.next/docs-smoke.log", import.meta.url),
  "w",
);
const child = spawn(
  process.execPath,
  [
    "node_modules/next/dist/bin/next",
    "start",
    "--hostname",
    "127.0.0.1",
    "--port",
    String(port),
  ],
  {
    cwd: root,
    stdio: ["ignore", log.fd, log.fd],
    env: { ...process.env, NEXT_TELEMETRY_DISABLED: "1" },
  },
);
const exited = new Promise((resolve) => {
  child.once("exit", resolve);
  child.once("error", resolve);
});
async function request(path, headers = {}) {
  const response = await fetch(base + path, {
    headers,
    signal: AbortSignal.timeout(15000),
  });
  assert.equal(response.status, 200, `${path}: HTTP ${response.status}`);
  return response;
}
try {
  let ready = false;
  for (let attempt = 0; attempt < 60; attempt++) {
    if (child.exitCode !== null)
      throw new Error(
        "Documentation server exited; inspect .next/docs-smoke.log",
      );
    try {
      await request("/docs");
      ready = true;
      break;
    } catch {
      await pause(250);
    }
  }
  assert(ready, "Documentation server did not become ready");
  const mapping = JSON.parse(
    await readFile(
      new URL("../content-source-map.json", import.meta.url),
      "utf8",
    ),
  );
  const routes = new Set([
    "/docs",
    ...Object.keys(mapping).map(
      (path) => "/docs/" + path.replace(/(?:\/index)?\.mdx$/, ""),
    ),
  ]);
  const internal = new Set();
  for (const path of ["/", ...routes]) {
    const html = await (await request(path)).text();
    assert(html.includes("<h1"), `${path}: missing rendered title`);
    assert(
      html.includes("BETA GATE: NOT PASSED"),
      `${path}: missing development status`,
    );
    for (const match of html.matchAll(/href="(\/docs[^"?#]*)[^"\n]*"/g))
      internal.add(match[1]);
  }
  for (const path of internal) if (!routes.has(path)) await request(path);
  const search = await (
    await request("/api/search?query=examiner%20notes")
  ).json();
  assert(
    Array.isArray(search) && search.length > 0,
    "Search returned no examiner-note results",
  );
  const index = await (await request("/llms.txt")).text();
  assert(
    index.includes("/docs/android/offline"),
    "LLM index is missing the Android guide",
  );
  const full = await (await request("/llms-full.txt")).text();
  assert(
    full.includes("Import selected audio") && full.includes("examiner"),
    "Full Markdown export is incomplete",
  );
  const markdown = await request("/docs/android/offline", {
    Accept: "text/markdown",
  });
  assert(
    !markdown.headers.get("content-type")?.includes("text/html"),
    "Markdown negotiation returned HTML",
  );
  assert((await markdown.text()).includes("Import selected audio"));
  const suffixed = await (await request("/docs/plans.md")).text();
  assert(
    suffixed.includes("Free and premium access"),
    "Markdown suffix route is missing",
  );
  const image = await request("/og/docs/image.png");
  assert(image.headers.get("content-type")?.includes("image/png"));
  assert((await image.arrayBuffer()).byteLength > 1000);
  const missing = await fetch(base + "/docs/this-page-does-not-exist");
  assert.equal(missing.status, 404);
  console.log(
    JSON.stringify({
      result: "PASS",
      pages: routes.size,
      search: true,
      llms: true,
      markdown: true,
      ogImage: true,
      missingPage: 404,
    }),
  );
} finally {
  child.kill("SIGTERM");
  await Promise.race([exited, pause(5000)]);
  if (child.exitCode === null) child.kill("SIGKILL");
  await log.close();
}
