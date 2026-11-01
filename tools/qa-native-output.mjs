import { readdir, stat } from "node:fs/promises";
import { join } from "node:path";
import assert from "node:assert/strict";
import { verifyZipFile, fileHash } from "./case-verify/streaming.mjs";
async function find(directory) {
  const matches = [];
  for (const entry of await readdir(directory, { withFileTypes: true })) {
    const path = join(directory, entry.name);
    if (entry.isDirectory()) matches.push(...(await find(path)));
    else if (entry.isFile() && entry.name === "synthetic-native.echocase")
      matches.push(path);
  }
  return matches;
}
const files = await find(process.argv[2]);
assert.ok(
  files.length > 0,
  "No native package in AGP additional test output; do not count this as a pass",
);
for (const path of files) {
  assert.ok(
    (await stat(path)).size > 100,
    "Native output package is empty/truncated",
  );
  const result = await verifyZipFile(path);
  assert.equal(result.valid, true);
  process.stdout.write(
    JSON.stringify({
      check: "ANDROID_NATIVE_EXPORT_INDEPENDENT_VERIFIER",
      result: "PASS",
      packageSha256: await fileHash(path),
      ...result,
    }) + "\n",
  );
}
