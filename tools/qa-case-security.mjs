import { mkdir, writeFile, readFile, readdir } from "node:fs/promises";
import { resolve, join } from "node:path";
import { spawnSync } from "node:child_process";
import assert from "node:assert/strict";
import { unzipSync } from "fflate";
import { EvidenceCase, digest } from "../packages/evidence/index.mjs";
import { CHECKPOINT_ALGORITHM } from "./case-verify/checkpoints.mjs";
import {
  verifyZipFile,
  verifyProtectedFile,
} from "./case-verify/streaming.mjs";

const directory = resolve(process.argv[3] || "qa-output/case-security");
await mkdir(directory, { recursive: true });
if (process.argv[2] === "prepare") {
  const c = new EvidenceCase({
    title: "SYNTHETIC TEST DATA — NOT EVIDENCE",
    actor: "SYNTHETIC_QA",
  });
  const bytes = new Uint8Array(2 * 1024 * 1024 + 971);
  for (let i = 0; i < bytes.length; i++) bytes[i] = (i * 31 + (i >>> 7)) & 255;
  c.add(bytes, {
    type: "SOURCE",
    name: "SYNTHETIC-NOT-EVIDENCE.bin",
    operation: {
      algorithm: "synthetic-test-pattern",
      version: "1",
      parameters: {},
    },
  });
  const archive = c.exportPackage();
  await writeFile(join(directory, "unsigned.echocase"), archive);
  await writeFile(
    join(directory, "snapshot.json"),
    unzipSync(archive)["manifest.json"],
  );
  process.stdout.write("Prepared synthetic cross-runtime snapshot.\n");
} else if (process.argv[2] === "verify") {
  const key = (
    await readFile(join(directory, "jvm-key-sha256.txt"), "utf8")
  ).trim();
  const c = EvidenceCase.importPackage(
    new Uint8Array(await readFile(join(directory, "unsigned.echocase"))),
    { actor: "SYNTHETIC_QA" },
  );
  c.add(
    new Uint8Array(await readFile(join(directory, "jvm-checkpoint.json"))),
    {
      type: "REPORT",
      parents: [c.manifest.artifacts[0].id],
      name: "JVM synthetic checkpoint.json",
      operation: {
        algorithm: CHECKPOINT_ALGORITHM,
        version: "1",
        parameters: { keySha256: key },
      },
    },
  );
  const archive = join(directory, "signed.echocase"),
    protectedPath = join(directory, "signed.echocase.enc");
  await writeFile(archive, c.exportPackage());
  const options = { trustedKeys: new Set([key]) };
  assert.equal(
    (await verifyZipFile(archive, options)).trustedCheckpointCoversAllEvidence,
    true,
  );
  const library =
    process.env.ECHOSCRIPT_CASE_PROTECT_LIB ||
    resolve("tools/case-protect/build/install/echoscript-case-protect/lib");
  const distribution = resolve(library, "..");
  for (const file of [
    "LICENSE",
    "NOTICE",
    "DISCLAIMER.md",
    "THIRD_PARTY_NOTICES.txt",
    "VERIFY.txt",
    "licenses/PROTOBUF-BSD-3-Clause.txt",
  ])
    assert.ok(
      (await readFile(join(distribution, file))).length > 0,
      `Missing distribution notice ${file}`,
    );
  assert.equal(
    digest(await readFile(join(distribution, "LICENSE"))),
    digest(await readFile("LICENSE")),
  );
  const inventory = (
    await readFile(join(distribution, "runtime-components.tsv"), "utf8")
  )
    .trimEnd()
    .split("\n");
  assert.equal(inventory.shift(), "file\tsha256");
  const actualJars = (await readdir(library))
    .filter((name) => name.endsWith(".jar"))
    .sort();
  assert.deepEqual(
    inventory.map((line) => line.split("\t")[0]).sort(),
    actualJars,
  );
  for (const line of inventory) {
    const [file, expected] = line.split("\t");
    assert.equal(digest(await readFile(join(library, file))), expected);
  }
  // Public test fixture password only. Never use real evidence/credentials in QA.
  const password = "SYNTHETIC test transfer password 42";
  const invoke = (args, passphrase = password) =>
    spawnSync(
      "java",
      ["-cp", `${library}/*`, "org.echoscript.cli.CaseProtect", ...args],
      {
        encoding: "utf8",
        timeout: 60000,
        env: {
          ...process.env,
          LANG: "C.UTF-8",
          LC_ALL: "C.UTF-8",
          ECHOSCRIPT_PACKAGE_PASSPHRASE: passphrase,
        },
      },
    );
  assert.equal(
    invoke(["encrypt", archive, protectedPath]).status,
    0,
    "JVM protected export failed",
  );
  const result = await verifyProtectedFile(protectedPath, password, options);
  assert.equal(result.encrypted, true);
  assert.equal(result.trustedCheckpointCoversAllEvidence, true);
  assert.equal(result.packageSha256, digest(await readFile(protectedPath)));
  await assert.rejects(
    verifyProtectedFile(protectedPath, "WRONG synthetic password", options),
  );
  // Fixture generation has an explicit UTF-8 locale. The isolated verifier JVM
  // receives no locale or credential environment: its private pipe must preserve
  // non-ASCII/astral characters exactly, without normalization.
  const unicodePassword = "SYNTHETIC test 密码 é 😀 42";
  const unicodePath = join(directory, "unicode.echocase.enc");
  assert.equal(
    invoke(["encrypt", archive, unicodePath], unicodePassword).status,
    0,
  );
  const unicodeResult = await verifyProtectedFile(
    unicodePath,
    unicodePassword,
    options,
  );
  assert.equal(unicodeResult.trustedCheckpointCoversAllEvidence, true);
  await assert.rejects(
    verifyProtectedFile(unicodePath, unicodePassword.normalize("NFD"), options),
  );
  const tampered = new Uint8Array(await readFile(protectedPath));
  tampered[tampered.length - 1] ^= 1;
  const alteredPath = join(directory, "tampered.echocase.enc");
  await writeFile(alteredPath, tampered);
  await assert.rejects(verifyProtectedFile(alteredPath, password, options));
  const noOutput = join(directory, "wrong-password-must-not-exist.zip");
  assert.notEqual(
    invoke(["decrypt", protectedPath, noOutput], "WRONG synthetic password")
      .status,
    0,
  );
  await assert.rejects(readFile(noOutput), { code: "ENOENT" });
  const before = digest(await readFile(protectedPath));
  assert.notEqual(
    invoke(["encrypt", archive, protectedPath]).status,
    0,
    "Existing destination must never be replaced",
  );
  assert.equal(digest(await readFile(protectedPath)), before);
  await writeFile(
    join(directory, "result.json"),
    JSON.stringify(
      {
        check: "SYNTHETIC_JVM_NODE_PROTECTED_CHECKPOINT_INTEROP",
        result: "PASS",
        packageSha256: result.packageSha256,
        wrongPasswordRejected: true,
        unicodePassphrasePipeVerified: true,
        silentNormalizationRejected: true,
        tagTamperRejected: true,
        existingDestinationPreserved: true,
        plaintextStaging: false,
        scope: "Crypto/container subset; not the complete product release gate",
      },
      null,
      2,
    ) + "\n",
  );
  process.stdout.write(
    "PASS: synthetic JVM/Node signed protected package, wrong password, tamper and no-clobber checks.\n",
  );
} else
  throw Error(
    "Usage: node tools/qa-case-security.mjs prepare|verify [OUTPUT_DIRECTORY]",
  );
