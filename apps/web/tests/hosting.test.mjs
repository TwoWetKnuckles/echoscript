import { test } from "node:test";
import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
test("entry point assets resolve beneath a nested GitHub Pages path", async () => {
  const html = await readFile(
    new URL("../dist/index.html", import.meta.url),
    "utf8",
  );
  const assets = [...html.matchAll(/(?:href|src)="([^"]+\.(?:css|mjs))"/g)].map(
    (x) => x[1],
  );
  assert.equal(assets.length, 2);
  for (const asset of assets)
    assert.ok(
      new URL(
        asset,
        "https://example.test/projects/echoscript/",
      ).pathname.startsWith("/projects/echoscript/"),
    );
  const js = await readFile(
    new URL("../dist/app.mjs", import.meta.url),
    "utf8",
  );
  assert.ok(js.includes("new URL('./catalog.json', import.meta.url)"));
  assert.ok(!js.includes("fetch('/catalog.json')"));
});
test("catalog snapshot contains eight unique, complete reference entries", async () => {
  const entries = JSON.parse(
    await readFile(new URL("../dist/catalog.json", import.meta.url), "utf8"),
  );
  assert.equal(entries.length, 8);
  assert.equal(new Set(entries.map((x) => x.id)).size, 8);
  for (const entry of entries) {
    assert.ok(["preset", "template"].includes(entry.kind));
    for (const field of ["title", "summary", "warning"])
      assert.equal(typeof entry[field], "string");
    assert.equal(typeof entry.parameters, "object");
  }
});
