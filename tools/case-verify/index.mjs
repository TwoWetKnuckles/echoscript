#!/usr/bin/env node
import { readFile } from "node:fs/promises";
import {
  EvidenceCase,
  decryptPackage,
  MAX_BROWSER_BYTES,
} from "../../packages/evidence/index.mjs";
import { stat } from "node:fs/promises";
import { open } from "node:fs/promises";
import { fileHash, verifyZipFile } from "./streaming.mjs";
const path = process.argv[2];
try {
  if (!path)
    throw Error(
      "Usage: node tools/case-verify/index.mjs case.echocase [--sha256 EXPECTED]",
    );
  const size = (await stat(path)).size;
  if (size > 16 * 1024 ** 3)
    throw Error("Package exceeds the 16 GiB desktop policy");
  const handle = await open(path);
  const prefix = Buffer.alloc(8);
  await handle.read(prefix, 0, 8, 0);
  await handle.close();
  const hash = await fileHash(path);
  const expected = process.argv.indexOf("--sha256");
  if (expected >= 0 && process.argv[expected + 1] !== hash)
    throw Error("Independently supplied package hash does not match");
  let verification;
  if (prefix.toString("ascii") === "ECHENC01") {
    if (size > MAX_BROWSER_BYTES + 5 * 1024 * 1024)
      throw Error("Encrypted browser packages currently have a 64 MiB policy");
    if (!process.env.ECHOSCRIPT_PACKAGE_PASSPHRASE)
      throw Error("Encrypted package requires ECHOSCRIPT_PACKAGE_PASSPHRASE");
    const bytes = await decryptPackage(
      new Uint8Array(await readFile(path)),
      process.env.ECHOSCRIPT_PACKAGE_PASSPHRASE,
    );
    verification = EvidenceCase.importPackage(bytes, {
      actor: "INDEPENDENT_VERIFIER",
    }).verify();
  } else verification = await verifyZipFile(path);
  process.stdout.write(
    JSON.stringify(
      {
        check: "case-package",
        result: "PASS",
        packageSha256: hash,
        ...verification,
        trustedExternalHash: expected >= 0,
        limitations: [
          "Unsigned manifests do not independently establish authenticity.",
          "Unencrypted ZIP verification streams up to a 16 GiB policy; encrypted browser packages remain limited to 64 MiB.",
        ],
      },
      null,
      2,
    ) + "\n",
  );
} catch (e) {
  process.stderr.write(
    JSON.stringify({
      check: "case-package",
      result: "FAIL",
      error: e.message,
    }) + "\n",
  );
  process.exitCode = 1;
}
