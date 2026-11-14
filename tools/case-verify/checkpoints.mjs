import { createPublicKey, createHash, verify } from "node:crypto";
import Ajv from "ajv";
import checkpointSchema from "../../specs/json-schema/checkpoint.schema.json" with { type: "json" };
import caseSchema from "../../specs/json-schema/case.schema.json" with { type: "json" };
import {
  canonical,
  digest,
  safeJson,
  verifyManifest,
} from "../../packages/evidence/index.mjs";

export const CHECKPOINT_ALGORITHM = "ecdsa-p256-case-checkpoint";
export const MAX_CHECKPOINT_BYTES = 16 * 1024 * 1024;
export const MAX_CHECKPOINTS = 128;
const validateCheckpoint = new Ajv({ strict: true })
  .addSchema(caseSchema)
  .compile(checkpointSchema);

function fields(value, names) {
  if (
    !value ||
    typeof value !== "object" ||
    Array.isArray(value) ||
    Object.keys(value).sort().join("\0") !== names.toSorted().join("\0")
  )
    throw Error("Unexpected checkpoint fields");
}

function binary(text, max) {
  if (typeof text !== "string" || text.length < 1 || text.length > max * 2)
    throw Error("Invalid checkpoint encoding");
  const bytes = Buffer.from(text, "base64");
  if (bytes.length > max || bytes.toString("base64") !== text)
    throw Error("Noncanonical checkpoint encoding");
  return bytes;
}

export function verifyCheckpoint(
  bytes,
  artifact,
  current,
  hashes,
  trustedKeys = new Set(),
) {
  if (bytes.length < 1 || bytes.length > MAX_CHECKPOINT_BYTES)
    throw Error("Checkpoint exceeds the metadata policy");
  const envelope = safeJson(bytes, MAX_CHECKPOINT_BYTES);
  if (!validateCheckpoint(envelope))
    throw Error("Checkpoint schema validation failed");
  if (
    canonical(envelope) !==
    new TextDecoder("utf-8", { fatal: true }).decode(bytes)
  )
    throw Error("Checkpoint must be canonical JSON");
  fields(envelope, ["schema", "payload", "publicKeySpki", "signature"]);
  if (envelope.schema !== "org.echoscript.checkpoint/1")
    throw Error("Unsupported checkpoint schema");
  const p = envelope.payload;
  fields(p, [
    "algorithm",
    "actor",
    "createdAtUtc",
    "caseId",
    "keySha256",
    "snapshotSha256",
    "snapshot",
  ]);
  if (
    p.algorithm !== "ECDSA_P256_SHA256" ||
    p.caseId !== current.caseId ||
    typeof p.actor !== "string" ||
    !p.actor.trim() ||
    p.actor.length > 128 ||
    Array.from(p.actor).some((c) => c.codePointAt(0) < 32) ||
    typeof p.createdAtUtc !== "string" ||
    !Number.isFinite(Date.parse(p.createdAtUtc))
  )
    throw Error("Invalid checkpoint parameters");
  const spki = binary(envelope.publicKeySpki, 1024);
  const key = createPublicKey({ key: spki, format: "der", type: "spki" });
  if (
    key.asymmetricKeyType !== "ec" ||
    key.asymmetricKeyDetails?.namedCurve !== "prime256v1"
  )
    throw Error("Checkpoint requires P-256");
  const fingerprint = createHash("sha256").update(spki).digest("hex");
  if (
    p.keySha256 !== fingerprint ||
    !verify(
      "sha256",
      Buffer.from(canonical(p)),
      key,
      binary(envelope.signature, 80),
    )
  )
    throw Error("Checkpoint signature or public-key commitment is invalid");
  const snapshot = p.snapshot;
  if (
    Buffer.byteLength(canonical(snapshot)) > 8 * 1024 * 1024 ||
    digest(new TextEncoder().encode(canonical(snapshot))) !== p.snapshotSha256
  )
    throw Error("Checkpoint snapshot hash differs");
  verifyManifest(snapshot, hashes);
  for (const field of [
    "format",
    "schemaVersion",
    "caseId",
    "title",
    "createdAtUtc",
    "software",
  ])
    if (
      canonical({ value: snapshot[field] }) !==
      canonical({ value: current[field] })
    )
      throw Error("Signed case header differs");
  for (const field of ["artifacts", "audit"]) {
    if (snapshot[field].length > current[field].length)
      throw Error("Signed history is not a prefix of this case");
    for (let i = 0; i < snapshot[field].length; i++)
      if (canonical(snapshot[field][i]) !== canonical(current[field][i]))
        throw Error("Signed history or artifact metadata was modified");
  }
  if (
    artifact.type !== "REPORT" ||
    artifact.operation.version !== "1" ||
    artifact.revision !== snapshot.artifacts.length + 1 ||
    artifact.operation.parameters.keySha256 !== fingerprint
  )
    throw Error("Checkpoint provenance metadata differs");
  return {
    artifactId: artifact.id,
    result: "VALID_SIGNATURE",
    keySha256: fingerprint,
    trustedExternalKey: trustedKeys.has(fingerprint),
    coveredArtifacts: snapshot.artifacts.length,
    coveredAuditEntries: snapshot.audit.length,
    artifactsOutsideSignedSnapshot:
      current.artifacts.length - snapshot.artifacts.length,
    auditEntriesOutsideSignedSnapshot:
      current.audit.length - snapshot.audit.length,
    coversAllCurrentEvidence: current.artifacts
      .slice(snapshot.artifacts.length)
      .every((a) => a.operation.algorithm === CHECKPOINT_ALGORITHM),
    identity:
      "Device-key signature; examiner identity and custody require independent qualification",
  };
}
