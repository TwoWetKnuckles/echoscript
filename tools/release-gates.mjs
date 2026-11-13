import { readFile, realpath, stat } from "node:fs/promises";
import { resolve, relative, isAbsolute, dirname } from "node:path";
import { fileURLToPath } from "node:url";
import { createReadStream } from "node:fs";
import { createHash } from "node:crypto";
import requirements from "../specs/release-gates.json" with { type: "json" };

const sha = /^[a-f0-9]{64}$/,
  commit = /^[a-f0-9]{40}$/;
const states = new Set(["PASS", "FAIL", "NOT EXECUTABLE"]);
const assertion = (condition, message) => {
  if (!condition) throw Error(message);
};
function exact(value, fields) {
  assertion(
    value &&
      typeof value === "object" &&
      !Array.isArray(value) &&
      Object.keys(value).sort().join("|") === fields.toSorted().join("|"),
    "Unexpected release-record fields",
  );
}
function text(value, max = 4096) {
  assertion(
    typeof value === "string" && value.trim() && value.length <= max,
    "Missing or excessive observation/identity",
  );
}
function utc(value) {
  assertion(
    typeof value === "string" &&
      /^\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d\.\d{3}Z$/.test(value) &&
      Number.isFinite(Date.parse(value)) &&
      new Date(value).toISOString() === value,
    "Invalid acceptance UTC timestamp",
  );
  return Date.parse(value);
}
async function hashFile(path) {
  const h = createHash("sha256");
  for await (const bytes of createReadStream(path)) h.update(bytes);
  return h.digest("hex");
}

/** Checks a declared acceptance record and its files. It cannot replace independent review. */
export async function evaluateRelease(
  record,
  { sourceCommit, apkSha256, evidenceRoot },
) {
  exact(record, [
    "schema",
    "sourceCommit",
    "apkSha256",
    "deployment",
    "gates",
    "runs",
  ]);
  assertion(
    record.schema === "org.echoscript.release-record/1",
    "Unsupported release record",
  );
  assertion(
    commit.test(sourceCommit) &&
      sha.test(apkSha256) &&
      record.sourceCommit === sourceCommit &&
      record.apkSha256 === apkSha256,
    "Record must match the exact source and APK being evaluated",
  );
  exact(record.deployment, [
    "organization",
    "jurisdiction",
    "policyRevision",
    "distribution",
  ]);
  for (const field of [
    "organization",
    "jurisdiction",
    "policyRevision",
    "distribution",
  ])
    text(record.deployment[field], 1024);
  assertion(
    Array.isArray(record.gates) &&
      record.gates.length === requirements.gates.length,
    "Every mandatory release gate must appear exactly once",
  );
  const required = new Set(requirements.gates.map((g) => g.id)),
    seen = new Set(),
    verifiedFiles = new Map();
  const root = await realpath(evidenceRoot);
  async function evidence(files, requiredCount) {
    assertion(
      Array.isArray(files) &&
        files.length >= requiredCount &&
        files.length <= 1000,
      "Passed checks require bounded, independently reviewable evidence files",
    );
    for (const file of files) {
      exact(file, ["path", "sha256"]);
      assertion(
        typeof file.path === "string" &&
          file.path.length <= 1024 &&
          !isAbsolute(file.path) &&
          !file.path.split(/[\\/]/).includes("..") &&
          sha.test(file.sha256),
        "Invalid evidence path/hash",
      );
      const path = await realpath(resolve(root, file.path)),
        rel = relative(root, path);
      assertion(
        rel && !rel.startsWith("..") && !isAbsolute(rel),
        "Evidence escaped its declared root",
      );
      assertion((await stat(path)).isFile(), "Evidence must be a regular file");
      const actual = verifiedFiles.get(path) || (await hashFile(path));
      verifiedFiles.set(path, actual);
      assertion(actual === file.sha256, "Acceptance evidence hash mismatch");
    }
  }
  for (const gate of record.gates) {
    exact(gate, [
      "id",
      "result",
      "observed",
      "reviewer",
      "acceptedAtUtc",
      "evidence",
    ]);
    assertion(
      required.has(gate.id) && !seen.has(gate.id) && states.has(gate.result),
      "Missing, duplicate, unknown or invalid gate",
    );
    seen.add(gate.id);
    text(gate.observed);
    text(gate.reviewer, 128);
    if (gate.result === "PASS") utc(gate.acceptedAtUtc);
    else
      assertion(
        gate.acceptedAtUtc === null,
        "An unpassed gate cannot have an acceptance timestamp",
      );
    await evidence(gate.evidence, gate.result === "PASS" ? 1 : 0);
  }
  assertion(
    Array.isArray(record.runs) && record.runs.length <= 1000,
    "Invalid full-workflow run history",
  );
  let consecutive = 0,
    attempt = 0;
  const prerequisites = record.gates.filter((g) => g.id !== "SEVEN_FULL_RUNS");
  const prerequisitesPassed = prerequisites.every((g) => g.result === "PASS");
  const acceptedAfter = Math.max(
    0,
    ...prerequisites
      .filter((g) => g.result === "PASS")
      .map((g) => utc(g.acceptedAtUtc)),
  );
  let previousTime = 0;
  for (const run of record.runs) {
    exact(run, [
      "attempt",
      "sourceCommit",
      "apkSha256",
      "scope",
      "result",
      "completedAtUtc",
      "observed",
      "stages",
      "evidence",
    ]);
    assertion(
      run.attempt === ++attempt && states.has(run.result),
      "Invalid run order/result",
    );
    text(run.observed);
    const completed = utc(run.completedAtUtc);
    assertion(
      completed > previousTime,
      "Workflow completion timestamps must increase",
    );
    previousTime = completed;
    assertion(
      commit.test(run.sourceCommit) && sha.test(run.apkSha256),
      "Invalid run build identity",
    );
    assertion(
      Array.isArray(run.stages) && run.stages.length <= 100,
      "Invalid stage results",
    );
    const stages = new Map();
    for (const stage of run.stages) {
      exact(stage, ["id", "result", "evidence"]);
      assertion(
        requirements.fullWorkflowStages.includes(stage.id) &&
          !stages.has(stage.id) &&
          states.has(stage.result),
        "Invalid workflow stage",
      );
      stages.set(stage.id, stage.result);
      await evidence(stage.evidence, stage.result === "PASS" ? 1 : 0);
    }
    await evidence(run.evidence, run.result === "PASS" ? 1 : 0);
    const complete =
      run.result === "PASS" &&
      run.scope === "FULL_ACCEPTED_WORKFLOW" &&
      run.sourceCommit === sourceCommit &&
      run.apkSha256 === apkSha256 &&
      prerequisitesPassed &&
      completed >= acceptedAfter &&
      requirements.fullWorkflowStages.every((id) => stages.get(id) === "PASS");
    // Subsets, other builds and incomplete claims reset rather than advance the counter.
    consecutive = complete ? consecutive + 1 : 0;
    if (run.result === "PASS")
      assertion(
        complete,
        "A partial, different-build, unapproved or subset run cannot be labeled a full workflow PASS",
      );
  }
  const seven = record.gates.find((g) => g.id === "SEVEN_FULL_RUNS");
  if (seven.result === "PASS")
    assertion(
      consecutive >= 7 && utc(seven.acceptedAtUtc) >= previousTime,
      "Seven consecutive complete runs have not been substantiated",
    );
  const outstanding = record.gates
    .filter((g) => g.result !== "PASS")
    .map((g) => ({ id: g.id, result: g.result, observed: g.observed }));
  const passed = outstanding.length === 0 && consecutive >= 7;
  return {
    check: "DECLARED_RELEASE_ACCEPTANCE_RECORD",
    sourceCommit,
    apkSha256,
    result: passed ? "PASS" : "FAIL",
    betaGate: passed ? "PASSED" : "NOT PASSED",
    consecutiveFullRuns: consecutive,
    checkedEvidenceFiles: verifiedFiles.size,
    outstanding,
    limitations:
      "Validates declared results, exact build identity and evidence-file consistency. Independent technical, device, organizational and jurisdiction-specific approval remain the responsibility of the qualified reviewers; this tool cannot establish compliance or admissibility.",
  };
}

if (
  process.argv[1] &&
  resolve(process.argv[1]) === fileURLToPath(import.meta.url)
) {
  try {
    const [recordPath, sourceCommit, apk] = process.argv.slice(2);
    assertion(
      recordPath && sourceCommit && apk && process.argv.length === 5,
      "Usage: node tools/release-gates.mjs RECORD.json SOURCE_COMMIT APK",
    );
    assertion(
      (await stat(recordPath)).size <= 8 * 1024 * 1024,
      "Release record exceeds 8 MiB",
    );
    const report = await evaluateRelease(
      JSON.parse(await readFile(recordPath, "utf8")),
      {
        sourceCommit,
        apkSha256: await hashFile(apk),
        evidenceRoot: dirname(resolve(recordPath)),
      },
    );
    process.stdout.write(JSON.stringify(report, null, 2) + "\n");
    if (report.result !== "PASS") process.exitCode = 1;
  } catch (error) {
    process.stderr.write(
      JSON.stringify({
        check: "DECLARED_RELEASE_ACCEPTANCE_RECORD",
        result: "FAIL",
        betaGate: "NOT PASSED",
        observed: error.message,
      }) + "\n",
    );
    process.exitCode = 1;
  }
}
