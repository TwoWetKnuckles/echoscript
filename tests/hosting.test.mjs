import { test } from "node:test";
import assert from "node:assert/strict";
import { mkdtemp, writeFile, symlink, rm } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { request } from "node:http";
import { createStaticServer } from "../tools/serve.mjs";

test("static host serves modules and rejects traversal, symlink escape and writes", async () => {
  const root = await mkdtemp(join(tmpdir(), "echoscript-static-"));
  const outside = await mkdtemp(join(tmpdir(), "echoscript-outside-"));
  await writeFile(
    join(root, "index.html"),
    "SYNTHETIC TEST DATA — NOT EVIDENCE",
  );
  await writeFile(join(root, "main.mjs"), "export const synthetic = true;");
  await writeFile(join(outside, "private.txt"), "synthetic secret");
  await symlink(join(outside, "private.txt"), join(root, "escape.txt"));
  const server = await createStaticServer(root);
  await new Promise((done) => server.listen(0, "127.0.0.1", done));
  const port = server.address().port;
  const get = (path, method = "GET") =>
    new Promise((resolve, reject) => {
      const req = request({ host: "127.0.0.1", port, path, method }, (res) => {
        const chunks = [];
        res.on("data", (b) => chunks.push(b));
        res.on("end", () =>
          resolve({
            status: res.statusCode,
            headers: res.headers,
            body: Buffer.concat(chunks).toString(),
          }),
        );
      });
      req.on("error", reject);
      req.end();
    });
  try {
    const page = await get("/");
    assert.equal(page.status, 200);
    assert.match(page.headers["content-security-policy"], /object-src 'none'/);
    const module = await get("/main.mjs");
    assert.equal(module.status, 200);
    assert.match(module.headers["content-type"], /javascript/);
    for (const path of [
      "/%2e%2e/private.txt",
      "/%2e%2e%2fprivate.txt",
      "/%2f..%2fprivate.txt",
      "/%252e%252e/private.txt",
      "/%5c../private.txt",
      "/escape.txt",
      "/.env",
      "/%00",
      "/%invalid",
    ])
      assert.equal((await get(path)).status, 404, path);
    assert.equal((await get("/", "POST")).status, 405);
    assert.equal((await get("/", "HEAD")).body, "");
  } finally {
    await new Promise((done) => server.close(done));
    await rm(root, { recursive: true, force: true });
    await rm(outside, { recursive: true, force: true });
  }
});
