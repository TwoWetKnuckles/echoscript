import { test } from "node:test";
import assert from "node:assert/strict";
import { mkdtemp, writeFile, rm } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { createHash } from "node:crypto";
import { evaluateRelease } from "../tools/release-gates.mjs";
import requirements from "../specs/release-gates.json" with { type: "json" };

async function fixture(action) {
  const root = await mkdtemp(join(tmpdir(), "echoscript-gate-unit-"));
  try {
    // This is a unit-test record, never an application acceptance result.
    const bytes = Buffer.from(
      "SYNTHETIC UNIT TEST RECORD — NOT RELEASE EVIDENCE",
    );
    await writeFile(join(root, "synthetic.txt"), bytes);
    const ref = {
      path: "synthetic.txt",
      sha256: createHash("sha256").update(bytes).digest("hex"),
    };
    const sourceCommit = "1".repeat(40),
      apkSha256 = "2".repeat(64);
    const record = {
      schema: "org.echoscript.release-record/1",
      sourceCommit,
      apkSha256,
      deployment: {
        organization: "SYNTHETIC UNIT TEST",
        jurisdiction: "SYNTHETIC",
        policyRevision: "TEST ONLY",
        distribution: "TEST ONLY",
      },
      gates: requirements.gates.map((g) => ({
        id: g.id,
        result: "PASS",
        observed: "Synthetic parser test",
        reviewer: "SYNTHETIC",
        acceptedAtUtc:
          g.id === "SEVEN_FULL_RUNS"
            ? "2026-11-07T00:01:00.000Z"
            : "2026-11-07T00:00:00.000Z",
        evidence: [ref],
      })),
      runs: [],
    };
    const run = (attempt) => ({
      attempt,
      sourceCommit,
      apkSha256,
      scope: "FULL_ACCEPTED_WORKFLOW",
      result: "PASS",
      completedAtUtc: new Date(
        Date.parse("2026-11-07T00:00:00.000Z") + attempt * 1000,
      ).toISOString(),
      observed: "Synthetic parser test",
      stages: requirements.fullWorkflowStages.map((id) => ({
        id,
        result: "PASS",
        evidence: [ref],
      })),
      evidence: [ref],
    });
    await action({
      record,
      run,
      options: { sourceCommit, apkSha256, evidenceRoot: root },
      ref,
      root,
    });
  } finally {
    await rm(root, { recursive: true, force: true });
  }
}
test("release checker requires every gate and seven complete exact-build records", () =>
  fixture(async ({ record, run, options }) => {
    record.runs = Array.from({ length: 7 }, (_, i) => run(i + 1));
    assert.equal((await evaluateRelease(record, options)).betaGate, "PASSED");
    record.gates.pop();
    await assert.rejects(evaluateRelease(record, options), /Every mandatory/);
  }));
test("a failed run resets the consecutive counter and subsets cannot count", () =>
  fixture(async ({ record, run, options }) => {
    record.runs = Array.from({ length: 7 }, (_, i) => run(i + 1));
    record.runs[3].result = "FAIL";
    record.gates.find((g) => g.id === "SEVEN_FULL_RUNS").result =
      "NOT EXECUTABLE";
    record.gates.find((g) => g.id === "SEVEN_FULL_RUNS").acceptedAtUtc = null;
    const result = await evaluateRelease(record, options);
    assert.equal(result.consecutiveFullRuns, 3);
    assert.equal(result.betaGate, "NOT PASSED");
    record.runs[6].scope = "EMULATOR_SUBSET";
    await assert.rejects(evaluateRelease(record, options), /subset/);
  }));
test("release checker rejects changed builds, partial workflows, forged pass and evidence tamper", () =>
  fixture(async ({ record, run, options, root }) => {
    record.runs = Array.from({ length: 7 }, (_, i) => run(i + 1));
    await assert.rejects(
      evaluateRelease(record, { ...options, apkSha256: "3".repeat(64) }),
      /exact source and APK/,
    );
    record.runs[6].stages.pop();
    await assert.rejects(evaluateRelease(record, options), /partial/);
    record.runs[6] = run(7);
    record.runs.pop();
    await assert.rejects(evaluateRelease(record, options), /Seven consecutive/);
    record.runs.push(run(7));
    await writeFile(join(root, "synthetic.txt"), "tampered");
    await assert.rejects(evaluateRelease(record, options), /hash mismatch/);
  }));
test("full workflow claims before prerequisite approval are rejected", () =>
  fixture(async ({ record, run, options }) => {
    record.runs = Array.from({ length: 7 }, (_, i) => run(i + 1));
    record.gates.find((g) => g.id === "GOVERNANCE").acceptedAtUtc =
      "2026-11-07T01:00:00.000Z";
    await assert.rejects(evaluateRelease(record, options), /unapproved/);
    const gate = record.gates.find((g) => g.id === "GOVERNANCE");
    gate.result = "NOT EXECUTABLE";
    gate.acceptedAtUtc = null;
    await assert.rejects(evaluateRelease(record, options), /unapproved/);
  }));
