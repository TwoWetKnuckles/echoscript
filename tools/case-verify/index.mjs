#!/usr/bin/env node
import { readFile, open, stat } from "node:fs/promises";
import {
  decryptPackage,
  digest,
  MAX_BROWSER_BYTES,
} from "../../packages/evidence/index.mjs";
import {
  verifyZipFile,
  verifyZipBuffer,
  verifyProtectedFile,
} from "./streaming.mjs";

try {
  const path = process.argv[2];
  if (!path)
    throw Error(
      "Usage: node tools/case-verify/index.mjs CASE [--sha256 EXPECTED] [--trusted-key-sha256 FINGERPRINT]",
    );
  const trustedKeys = new Set();
  let expected;
  for (let i = 3; i < process.argv.length; i += 2) {
    const option = process.argv[i],
      value = process.argv[i + 1]?.toLowerCase();
    if (!/^[a-f0-9]{64}$/.test(value ?? ""))
      throw Error("Expected a 64-character SHA-256 value");
    if (option === "--sha256" && expected === undefined) expected = value;
    else if (option === "--trusted-key-sha256" && trustedKeys.size < 32)
      trustedKeys.add(value);
    else throw Error("Unknown, duplicate or excessive verifier option");
  }
  const size = (await stat(path)).size;
  if (size > 16 * 1024 ** 3 + 2 * 1024 * 1024)
    throw Error("Package exceeds the desktop size policy");
  const handle = await open(path),
    prefix = Buffer.alloc(8);
  try {
    await handle.read(prefix, 0, 8, 0);
  } finally {
    await handle.close();
  }
  let verification, packageHash, plaintextPackageSha256;
  if (prefix.toString("ascii") === "ECHENC01") {
    if (size > MAX_BROWSER_BYTES + 5 * 1024 * 1024)
      throw Error(
        "Encrypted browser package exceeds its 64 MiB workspace policy",
      );
    if (!process.env.ECHOSCRIPT_PACKAGE_PASSPHRASE)
      throw Error("Encrypted package requires ECHOSCRIPT_PACKAGE_PASSPHRASE");
    const encrypted = new Uint8Array(await readFile(path));
    packageHash = digest(encrypted);
    if (expected && expected !== packageHash)
      throw Error("Independently supplied ciphertext hash does not match");
    const decoded = await decryptPackage(
      encrypted,
      process.env.ECHOSCRIPT_PACKAGE_PASSPHRASE,
    );
    verification = await verifyZipBuffer(decoded, { trustedKeys });
    plaintextPackageSha256 = verification.packageSha256;
    verification.encrypted = true;
    verification.protection = "Browser AES256-GCM package";
  } else if (prefix.toString("ascii") === "ECHOCS01") {
    verification = await verifyProtectedFile(
      path,
      process.env.ECHOSCRIPT_PACKAGE_PASSPHRASE,
      { trustedKeys },
    );
    packageHash = verification.packageSha256;
  } else {
    verification = await verifyZipFile(path, { trustedKeys });
    packageHash = verification.packageSha256;
  }
  if (expected && expected !== packageHash)
    throw Error(
      "Independently supplied package hash does not match the verified input",
    );
  const { packageSha256: ignored, ...checks } = verification;
  void ignored;
  process.stdout.write(
    JSON.stringify(
      {
        check: "case-package",
        result: "PASS",
        ...checks,
        packageSha256: packageHash,
        ...(plaintextPackageSha256 ? { plaintextPackageSha256 } : {}),
        trustedExternalHash: expected !== undefined,
        limitations: [
          "Device-key signatures cover the stated snapshot prefixes, not examiner identity or legal admissibility.",
          "Embedded public keys become trusted only through an independently supplied fingerprint.",
          "Later audit events and any artifacts outside a signed snapshot require separate review.",
          "Native protected verification requires the maintained Tink JVM helper; no plaintext temporary case is created.",
        ],
      },
      null,
      2,
    ) + "\n",
  );
} catch (error) {
  process.stderr.write(
    JSON.stringify({
      check: "case-package",
      result: "FAIL",
      error: error.message,
    }) + "\n",
  );
  process.exitCode = 1;
}
