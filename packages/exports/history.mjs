import { canonical } from "../evidence/index.mjs";
export const REPORT_NOTICE =
  "This report was produced using computer-assisted audio processing and/or transcription. AI-generated transcription requires independent human examiner verification.";
export function processingHistory(manifest) {
  return [
    "EchoScript processing history",
    `Case: ${manifest.caseId}`,
    `Title: ${manifest.title}`,
    `Software: ${manifest.software.version}`,
    `Generated UTC: ${new Date().toISOString()}`,
    `Source SHA-256: ${manifest.artifacts.find((a) => a.type === "SOURCE")?.sha256}`,
    REPORT_NOTICE,
    "",
    ...manifest.artifacts.flatMap((a) => [
      `Revision ${a.revision}: ${a.id} (${a.type})`,
      `Parents: ${a.parents.join(", ")}`,
      `SHA-256: ${a.sha256}`,
      `Operator: ${a.actor}; UTC: ${a.timestampUtc}`,
      `Algorithm: ${a.operation.algorithm}/${a.operation.version}`,
      `Parameters: ${canonical(a.operation.parameters)}`,
      `Warnings: ${a.warnings.join("; ")}`,
      "",
    ]),
    ...manifest.audit.map(
      (e) =>
        `${e.seq} ${e.timestampUtc} ${e.actor} ${e.action} ${canonical(e.details)} [${e.entryHash}]`,
    ),
    "",
    "Unsigned hash/audit consistency does not independently authenticate origin or legal admissibility.",
  ].join("\n");
}
