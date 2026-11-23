import { sha256 } from "@noble/hashes/sha2.js";
import { bytesToHex } from "@noble/hashes/utils.js";
import { canonicalize } from "json-canonicalize";
import { zipSync, unzipSync, strToU8 } from "fflate";
import validateSchema from "./schema-validator.cjs";

export const VERSION = "0.6.0-preview";
export const FORMAT = "org.echoscript.case/1";
export const MAX_BROWSER_BYTES = 64 * 1024 * 1024;
export const ZERO = "0".repeat(64);
const encoder = new TextEncoder();
const decoder = new TextDecoder("utf-8", { fatal: true });
export function digest(bytes) {
  return bytesToHex(sha256(bytes));
}
export function jsonBytes(value) {
  return encoder.encode(canonical(value));
}
export function canonical(value) {
  checkJson(value);
  return canonicalize(value);
}
function checkJson(v, depth = 0) {
  if (depth > 48) throw Error("JSON nesting exceeds policy");
  if (typeof v === "string") {
    if (
      /[\uD800-\uDBFF](?![\uDC00-\uDFFF])|(?<![\uD800-\uDBFF])[\uDC00-\uDFFF]/u.test(
        v,
      )
    )
      throw Error("Invalid Unicode in canonical JSON");
    return;
  }
  if (v === null || typeof v === "boolean") return;
  if (typeof v === "number") {
    if (!Number.isFinite(v)) throw Error("Non-finite JSON number");
    return;
  }
  if (Array.isArray(v)) {
    if (v.length > 100000) throw Error("Array exceeds policy");
    v.forEach((x) => checkJson(x, depth + 1));
    return;
  }
  if (typeof v !== "object" || Object.getPrototypeOf(v) !== Object.prototype)
    throw Error("Unsupported JSON value");
  for (const [k, x] of Object.entries(v)) {
    checkJson(k, depth + 1);
    if (["__proto__", "constructor", "prototype"].includes(k))
      throw Error("Unsafe JSON key");
    checkJson(x, depth + 1);
  }
}
export function safeJson(bytes, max = 8 * 1024 * 1024) {
  if (bytes.byteLength > max) throw Error("JSON exceeds policy");
  const v = JSON.parse(decoder.decode(bytes));
  checkJson(v);
  return v;
}
export function id() {
  if (crypto.randomUUID) return crypto.randomUUID();
  const bytes = crypto.getRandomValues(new Uint8Array(16));
  bytes[6] = (bytes[6] & 15) | 64;
  bytes[8] = (bytes[8] & 63) | 128;
  const h = bytesToHex(bytes);
  return `${h.slice(0, 8)}-${h.slice(8, 12)}-${h.slice(12, 16)}-${h.slice(16, 20)}-${h.slice(20)}`;
}
const uuid =
  /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/;
const hasControls = (s) => [...s].some((c) => c.codePointAt(0) < 32);
function requireId(s) {
  if (!uuid.test(s)) throw Error("Invalid identifier");
}
function label(s, name, max = 256) {
  if (typeof s !== "string" || !s.trim() || s.length > max || hasControls(s))
    throw Error("Invalid " + name);
  return s;
}
function timestamp(s) {
  if (
    typeof s !== "string" ||
    !/^\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d\.\d{3}Z$/.test(s) ||
    !Number.isFinite(Date.parse(s)) ||
    new Date(s).toISOString() !== s
  )
    throw Error("Invalid UTC timestamp");
}
export function verifyAudit(entries) {
  if (!Array.isArray(entries) || entries.length > 100000)
    throw Error("Invalid audit chain");
  let previous = ZERO;
  for (let i = 0; i < entries.length; i++) {
    const e = entries[i];
    const { entryHash, ...body } = e;
    if (
      e.seq !== i + 1 ||
      e.prevHash !== previous ||
      entryHash !== digest(jsonBytes(body))
    )
      throw Error("Audit linkage/hash mismatch at " + (i + 1));
    timestamp(e.timestampUtc);
    label(e.actor, "actor", 128);
    label(e.deviceId, "device", 128);
    label(e.action, "action", 128);
    const fields = [
      "seq",
      "timestampUtc",
      "actor",
      "deviceId",
      "action",
      "details",
      "prevHash",
      "entryHash",
    ];
    if (Object.keys(e).some((k) => !fields.includes(k)))
      throw Error("Unknown audit field");
    previous = entryHash;
  }
  return { entries: entries.length, head: previous };
}
export function verifyManifest(manifest, hashes) {
  if (!validateSchema(manifest))
    throw Error(
      "Case schema validation failed: " + validateSchema.errors[0].instancePath,
    );
  if (manifest?.format !== FORMAT || manifest.schemaVersion !== 1)
    throw Error("Unsupported case format/version");
  requireId(manifest.caseId);
  label(manifest.title, "case title");
  timestamp(manifest.createdAtUtc);
  if (
    !Array.isArray(manifest.artifacts) ||
    !manifest.artifacts.length ||
    manifest.artifacts.length > 10000
  )
    throw Error("Invalid artifact inventory");
  const seen = new Map();
  let sources = 0,
    revision = 0;
  for (const a of manifest.artifacts) {
    requireId(a.id);
    if (a.caseId !== manifest.caseId || seen.has(a.id))
      throw Error("Artifact case/identifier mismatch");
    timestamp(a.timestampUtc);
    label(a.actor, "actor", 128);
    label(a.type, "artifact type", 128);
    if (
      !/^[a-f0-9]{64}$/.test(a.sha256) ||
      !Number.isSafeInteger(a.bytes) ||
      a.bytes < 0
    )
      throw Error("Invalid artifact digest/length");
    if (
      !Array.isArray(a.parents) ||
      new Set(a.parents).size !== a.parents.length ||
      a.parents.some((p) => !seen.has(p))
    )
      throw Error("Missing, cyclic or out-of-order provenance parent");
    if (a.type === "SOURCE") {
      sources++;
      if (a.parents.length) throw Error("Source has parents");
    } else if (!a.parents.length) throw Error("Derivative lacks a parent");
    if (a.revision !== ++revision) throw Error("Invalid artifact revision");
    if (a.path !== `artifacts/${a.id}`) throw Error("Unsafe artifact path");
    if (
      typeof a.operation !== "object" ||
      typeof a.operation.algorithm !== "string" ||
      typeof a.operation.version !== "string"
    )
      throw Error("Missing processing provenance");
    const actual = hashes.get(a.path);
    if (!actual || actual.sha256 !== a.sha256 || actual.bytes !== a.bytes)
      throw Error("Missing or modified artifact " + a.id);
    seen.set(a.id, a);
  }
  if (sources !== 1) throw Error("Case must have exactly one immutable source");
  if (manifest.activeArtifact && !seen.has(manifest.activeArtifact))
    throw Error("Invalid active revision");
  verifyAudit(manifest.audit);
  const commitments = new Map();
  for (const e of manifest.audit)
    if (e.action === "ARTIFACT_COMMIT") {
      if (commitments.has(e.details.artifactId))
        throw Error("Duplicate immutable artifact commitment");
      commitments.set(e.details.artifactId, e.details.sha256);
    }
  for (const a of manifest.artifacts)
    if (commitments.get(a.id) !== a.sha256)
      throw Error("Artifact missing audit commitment");
  return {
    valid: true,
    artifacts: seen.size,
    auditEntries: manifest.audit.length,
    signatures: "NOT_CHECKED",
    authenticity:
      "Hash consistency only; no independent origin or custody authentication",
  };
}

/** In-memory browser workspace. It is deliberately not an authoritative device vault. */
export class EvidenceCase {
  #files = new Map();
  #m;
  #actor;
  #device;
  #clock;
  constructor({
    title,
    actor,
    deviceId = id(),
    clock = () => new Date().toISOString(),
  }) {
    label(title, "title");
    label(actor, "actor", 128);
    label(deviceId, "device", 128);
    this.#actor = actor;
    this.#device = deviceId;
    this.#clock = clock;
    this.#m = {
      format: FORMAT,
      schemaVersion: 1,
      caseId: id(),
      title,
      createdAtUtc: clock(),
      software: { name: "EchoScript", version: VERSION },
      artifacts: [],
      audit: [],
      activeArtifact: null,
    };
    this.log("CASE_CREATE", {});
  }
  get manifest() {
    return structuredClone(this.#m);
  }
  log(action, details) {
    label(action, "action", 128);
    checkJson(details);
    if (!details || Array.isArray(details) || typeof details !== "object")
      throw Error("Audit details must be an object");
    const entries = this.#m.audit;
    const body = {
      seq: entries.length + 1,
      timestampUtc: this.#clock(),
      actor: this.#actor,
      deviceId: this.#device,
      action,
      details: structuredClone(details),
      prevHash: entries.at(-1)?.entryHash || ZERO,
    };
    timestamp(body.timestampUtc);
    const entry = { ...body, entryHash: digest(jsonBytes(body)) };
    entries.push(entry);
    return structuredClone(entry);
  }
  add(
    bytes,
    { type, parents = [], operation, name = "artifact", warnings = [] },
  ) {
    if (!(bytes instanceof Uint8Array) || bytes.length > MAX_BROWSER_BYTES)
      throw Error("Artifact exceeds browser workspace policy");
    if (
      [...this.#files.values()].reduce((n, b) => n + b.length, bytes.length) >
      MAX_BROWSER_BYTES
    )
      throw Error(
        "Workspace exceeds 64 MiB browser policy; export and close this case",
      );
    if (type === "SOURCE" && this.#m.artifacts.length)
      throw Error(
        "Source is immutable; create a new case for a different source",
      );
    if (
      type !== "SOURCE" &&
      (!parents.length || parents.some((p) => !this.#files.has(p)))
    )
      throw Error("Invalid derivative parents");
    label(name, "artifact name");
    checkJson(operation);
    checkJson(warnings);
    const artifactId = id(),
      copy = bytes.slice();
    const artifact = {
      id: artifactId,
      caseId: this.#m.caseId,
      type,
      parents: [...parents],
      name,
      path: `artifacts/${artifactId}`,
      bytes: copy.length,
      sha256: digest(copy),
      timestampUtc: this.#clock(),
      actor: this.#actor,
      revision: this.#m.artifacts.length + 1,
      softwareVersion: VERSION,
      operation: structuredClone(operation),
      warnings: [...warnings],
    };
    timestamp(artifact.timestampUtc);
    if (
      !validateSchema({
        ...this.#m,
        artifacts: [...this.#m.artifacts, artifact],
      })
    )
      throw Error("Artifact schema validation failed");
    this.#files.set(artifactId, copy);
    this.#m.artifacts.push(artifact);
    if (
      ["SOURCE", "DECODE", "ENHANCED_AUDIO"].includes(type) ||
      !this.#m.activeArtifact
    )
      this.#m.activeArtifact = artifactId;
    this.log("ARTIFACT_COMMIT", {
      artifactId,
      type,
      sha256: artifact.sha256,
      parents,
    });
    return structuredClone(artifact);
  }
  bytes(artifactId) {
    const bytes = this.#files.get(artifactId);
    if (!bytes) throw Error("Artifact not found");
    return bytes.slice();
  }
  activate(artifactId, action = "REVISION_ACTIVATE") {
    if (!this.#files.has(artifactId)) throw Error("Revision not found");
    const previous = this.#m.activeArtifact;
    this.#m.activeArtifact = artifactId;
    this.log(action, { previous, activeArtifact: artifactId });
  }
  verify() {
    const hashes = new Map(
      this.#m.artifacts.map((a) => [
        a.path,
        {
          sha256: digest(this.#files.get(a.id)),
          bytes: this.#files.get(a.id).length,
        },
      ]),
    );
    return verifyManifest(this.#m, hashes);
  }
  exportPackage() {
    this.log("EXPORT_PREPARE", { format: FORMAT });
    this.verify();
    const files = {};
    let size = 0;
    for (const a of this.#m.artifacts) {
      size += a.bytes;
      if (size > MAX_BROWSER_BYTES)
        throw Error(
          "Package exceeds browser policy; use desktop/Android tools",
        );
      files[a.path] = this.#files.get(a.id);
    }
    const manifest = jsonBytes(this.#m);
    files["manifest.json"] = manifest;
    files["SHA256SUMS.txt"] = strToU8(
      [
        ...this.#m.artifacts.map((a) => `${a.sha256}  ${a.path}`),
        `${digest(manifest)}  manifest.json`,
      ].join("\n") + "\n",
    );
    files["README.txt"] = strToU8(
      "EchoScript case format 1. Verify with node tools/case-verify/index.mjs case.echocase. Hashes are tamper-evident only relative to independently retained trusted checkpoints. Unsigned manifests can be rewritten together with their hashes.\n",
    );
    const output = zipSync(files, { level: 0 });
    this.log("EXPORT_COMPLETE", {
      format: FORMAT,
      packageSha256: digest(output),
      exportedAuditHead: this.#m.audit.at(-1).entryHash,
    });
    return output;
  }
  static importPackage(
    bytes,
    { actor, deviceId = id(), clock = () => new Date().toISOString() } = {},
  ) {
    const names = inspectZip(bytes);
    const files = unzipSync(bytes);
    const manifest = safeJson(files["manifest.json"] || new Uint8Array());
    if (canonical(manifest) !== decoder.decode(files["manifest.json"]))
      throw Error("Manifest must be canonical JSON without duplicate keys");
    const hashes = new Map();
    for (const [name, b] of Object.entries(files))
      if (name.startsWith("artifacts/"))
        hashes.set(name, { sha256: digest(b), bytes: b.length });
    verifyManifest(manifest, hashes);
    const allowed = new Set([
      "manifest.json",
      "SHA256SUMS.txt",
      "README.txt",
      ...manifest.artifacts.map((a) => a.path),
    ]);
    if (
      names.some((n) => !allowed.has(n)) ||
      hashes.size !== manifest.artifacts.length
    )
      throw Error("Unlisted package entry");
    const sums = decoder
      .decode(files["SHA256SUMS.txt"] || new Uint8Array())
      .trim()
      .split("\n");
    const expected = [
      ...manifest.artifacts.map((a) => `${a.sha256}  ${a.path}`),
      `${digest(files["manifest.json"])}  manifest.json`,
    ];
    if (canonical(sums) !== canonical(expected))
      throw Error("Checksum sidecar mismatch");
    const c = new EvidenceCase({
      title: manifest.title,
      actor: actor || "IMPORT_REVIEWER",
      deviceId,
      clock,
    });
    c.#m = structuredClone(manifest);
    c.#files = new Map(
      manifest.artifacts.map((a) => [a.id, files[a.path].slice()]),
    );
    c.log("IMPORT", {
      packageSha256: digest(bytes),
      previousAuditHead: manifest.audit.at(-1)?.entryHash || ZERO,
    });
    return c;
  }
}

/** Inspect the central directory before any decompression or allocation by the ZIP library. */
export function inspectZip(bytes) {
  if (
    !(bytes instanceof Uint8Array) ||
    bytes.length > MAX_BROWSER_BYTES + 4 * 1024 * 1024 ||
    bytes.length < 22
  )
    throw Error("Package size outside browser policy");
  const v = new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength);
  let end = -1;
  for (let i = bytes.length - 22; i >= Math.max(0, bytes.length - 65557); i--)
    if (
      v.getUint32(i, true) === 0x06054b50 &&
      i + 22 + v.getUint16(i + 20, true) === bytes.length
    ) {
      end = i;
      break;
    }
  if (end < 0 || v.getUint16(end + 4, true) || v.getUint16(end + 6, true))
    throw Error("Invalid or split ZIP");
  const count = v.getUint16(end + 10, true),
    length = v.getUint32(end + 12, true),
    offset = v.getUint32(end + 16, true);
  if (count < 3 || count > 10003 || count === 65535 || offset + length !== end)
    throw Error("Unsupported ZIP directory");
  const names = [],
    seen = new Set();
  let pos = offset,
    total = 0;
  for (let i = 0; i < count; i++) {
    if (pos + 46 > end || v.getUint32(pos, true) !== 0x02014b50)
      throw Error("Malformed ZIP directory");
    const flags = v.getUint16(pos + 8, true),
      method = v.getUint16(pos + 10, true),
      compressed = v.getUint32(pos + 20, true),
      size = v.getUint32(pos + 24, true),
      n = v.getUint16(pos + 28, true),
      extra = v.getUint16(pos + 30, true),
      comment = v.getUint16(pos + 32, true),
      attr = v.getUint32(pos + 38, true),
      local = v.getUint32(pos + 42, true);
    if (pos + 46 + n + extra + comment > end || local + 30 > offset)
      throw Error("Malformed ZIP bounds");
    const name = decoder.decode(bytes.subarray(pos + 46, pos + 46 + n));
    if (
      !name ||
      name.length > 256 ||
      name !== name.normalize("NFC") ||
      name.startsWith("/") ||
      name.includes("\\") ||
      name.includes(":") ||
      hasControls(name) ||
      name.split("/").some((p) => !p || p === "." || p === "..") ||
      seen.has(name)
    )
      throw Error("Unsafe/duplicate ZIP path");
    if (
      flags & 1 ||
      ![0, 8].includes(method) ||
      ((attr >>> 16) & 0xf000) === 0xa000 ||
      size === 0xffffffff ||
      compressed === 0xffffffff
    )
      throw Error("Unsupported ZIP entry");
    total += size;
    if (
      total > MAX_BROWSER_BYTES + 1024 * 1024 ||
      size > MAX_BROWSER_BYTES ||
      (size > 1024 * 1024 && size / Math.max(1, compressed) > 200)
    )
      throw Error("Decompression policy exceeded");
    if (v.getUint32(local, true) !== 0x04034b50)
      throw Error("Invalid ZIP local header");
    const ln = v.getUint16(local + 26, true),
      le = v.getUint16(local + 28, true);
    if (
      decoder.decode(bytes.subarray(local + 30, local + 30 + ln)) !== name ||
      local + 30 + ln + le + compressed > offset ||
      v.getUint16(local + 8, true) !== method ||
      v.getUint16(local + 6, true) !== flags
    )
      throw Error("ZIP header disagreement");
    seen.add(name);
    names.push(name);
    pos += 46 + n + extra + comment;
  }
  if (pos !== end || !seen.has("manifest.json"))
    throw Error("Incomplete package");
  return names;
}

export async function encryptPackage(bytes, passphrase) {
  if (!crypto.subtle)
    throw Error("Encrypted package export requires HTTPS or localhost");
  if (typeof passphrase !== "string" || passphrase.length < 12)
    throw Error("Use a passphrase of at least 12 characters");
  const salt = crypto.getRandomValues(new Uint8Array(16)),
    iv = crypto.getRandomValues(new Uint8Array(12));
  const material = await crypto.subtle.importKey(
    "raw",
    encoder.encode(passphrase),
    "PBKDF2",
    false,
    ["deriveKey"],
  );
  const key = await crypto.subtle.deriveKey(
    { name: "PBKDF2", hash: "SHA-256", salt, iterations: 600000 },
    material,
    { name: "AES-GCM", length: 256 },
    false,
    ["encrypt"],
  );
  const header = new Uint8Array(36);
  header.set(encoder.encode("ECHENC01"));
  header.set(salt, 8);
  header.set(iv, 24);
  const ciphertext = new Uint8Array(
    await crypto.subtle.encrypt(
      { name: "AES-GCM", iv, additionalData: header },
      key,
      bytes,
    ),
  );
  const output = new Uint8Array(header.length + ciphertext.length);
  output.set(header);
  output.set(ciphertext, 36);
  return output;
}
export async function decryptPackage(bytes, passphrase) {
  if (!crypto.subtle)
    throw Error("Encrypted package import requires HTTPS or localhost");
  if (
    bytes.length < 52 ||
    bytes.length > MAX_BROWSER_BYTES + 5 * 1024 * 1024 ||
    decoder.decode(bytes.subarray(0, 8)) !== "ECHENC01"
  )
    throw Error("Unsupported encrypted package");
  const header = bytes.subarray(0, 36),
    salt = header.subarray(8, 24),
    iv = header.subarray(24, 36);
  const material = await crypto.subtle.importKey(
    "raw",
    encoder.encode(passphrase),
    "PBKDF2",
    false,
    ["deriveKey"],
  );
  const key = await crypto.subtle.deriveKey(
    { name: "PBKDF2", hash: "SHA-256", salt, iterations: 600000 },
    material,
    { name: "AES-GCM", length: 256 },
    false,
    ["decrypt"],
  );
  try {
    return new Uint8Array(
      await crypto.subtle.decrypt(
        { name: "AES-GCM", iv, additionalData: header },
        key,
        bytes.subarray(36),
      ),
    );
  } catch {
    throw Error("Authentication failed: wrong passphrase or modified package");
  }
}
