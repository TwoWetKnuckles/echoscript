import { readdir, stat, readFile } from "node:fs/promises";
import { join } from "node:path";
import assert from "node:assert/strict";
import {
  verifyZipFile,
  verifyProtectedFile,
  fileHash,
} from "./case-verify/streaming.mjs";
async function find(directory) {
  const matches = [];
  for (const entry of await readdir(directory, { withFileTypes: true })) {
    const path = join(directory, entry.name);
    if (entry.isDirectory()) matches.push(...(await find(path)));
    else if (
      entry.isFile() &&
      /^synthetic-(native|detail-spectrum|clearvoice|whisper|offline-run-[1-7]|ui-run-[1-7]|(?:ui-)?protected-run-[1-7])\.echocase(?:\.enc)?$/.test(
        entry.name,
      )
    )
      matches.push(path);
  }
  return matches;
}
const files = await find(process.argv[2]);
assert.ok(
  files.length > 0,
  "No native package in AGP additional test output; do not count this as a pass",
);
assert.ok(
  files.some((path) => path.endsWith("/synthetic-detail-spectrum.echocase")),
  "Missing the synthetic detailed-spectrum export",
);
for (const name of ["clearvoice", "whisper"]) {
  assert.ok(files.some((path) => path.endsWith(`/synthetic-${name}.echocase`)), `Missing ${name} integration case`);
}
for (let i = 1; i <= 7; i++) {
  assert.ok(
    files.some((path) =>
      path.endsWith(`/synthetic-protected-run-${i}.echocase.enc`),
    ),
    `Missing protected native export ${i}`,
  );
  assert.ok(
    files.some((path) =>
      path.endsWith(`/synthetic-ui-protected-run-${i}.echocase.enc`),
    ),
    `Missing protected interface export ${i}`,
  );
  for (const kind of ["offline", "ui"]) {
    assert.ok(
      files.some((path) =>
        path.endsWith(`/synthetic-${kind}-run-${i}.echocase`),
      ),
      `Missing ${kind} run ${i}`,
    );
  }
}
for (const path of files) {
  assert.ok(
    (await stat(path)).size > 100,
    "Native output package is empty/truncated",
  );
  const protectedPackage = path.endsWith(".enc");
  const fingerprint = protectedPackage
    ? (
        await readFile(path.replace(".echocase.enc", ".key-sha256.txt"), "utf8")
      ).trim()
    : null;
  const result = protectedPackage
    ? await verifyProtectedFile(
        path,
        path.endsWith("/synthetic-protected-run-1.echocase.enc")
          ? "SYNTHETIC test 密码 é 😀 42"
          : "SYNTHETIC test transfer password 42",
        {
          trustedKeys: new Set([fingerprint]),
        },
      )
    : await verifyZipFile(path);
  assert.equal(result.valid, true);
  if (protectedPackage)
    assert.equal(result.trustedCheckpointCoversAllEvidence, true);
  process.stdout.write(
    JSON.stringify({
      check: "ANDROID_NATIVE_EXPORT_INDEPENDENT_VERIFIER",
      result: "PASS",
      packageSha256: await fileHash(path),
      ...result,
    }) + "\n",
  );
}
