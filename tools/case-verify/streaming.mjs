import yauzl from "yauzl";
import { open } from "node:fs/promises";
import { createHash } from "node:crypto";
import { crc32 } from "node:zlib";
import { Readable } from "node:stream";
import {
  safeJson,
  canonical,
  verifyManifest,
  digest,
  MAX_BROWSER_BYTES,
} from "../../packages/evidence/index.mjs";
import {
  CHECKPOINT_ALGORITHM,
  MAX_CHECKPOINT_BYTES,
  MAX_CHECKPOINTS,
  verifyCheckpoint,
} from "./checkpoints.mjs";
import { ProtectedReader } from "./protected-reader.mjs";

const ZIP_OPTIONS = {
  lazyEntries: true,
  autoClose: true,
  validateEntrySizes: true,
  strictFileNames: true,
};
async function hashHandle(handle, size) {
  const h = createHash("sha256"),
    bytes = Buffer.alloc(65536);
  for (let position = 0; position < size;) {
    const { bytesRead } = await handle.read(
      bytes,
      0,
      Math.min(bytes.length, size - position),
      position,
    );
    if (!bytesRead) throw Error("Package changed or truncated while hashing");
    h.update(bytes.subarray(0, bytesRead));
    position += bytesRead;
  }
  return h.digest("hex");
}

export async function fileHash(path) {
  const handle = await open(path);
  try {
    return await hashHandle(handle, Number((await handle.stat()).size));
  } finally {
    await handle.close();
  }
}

function fileReader(handle) {
  return new (class extends yauzl.RandomAccessReader {
    _readStreamForRange(start, end) {
      return Readable.from(
        (async function* () {
          for (let position = start; position < end;) {
            const bytes = Buffer.allocUnsafe(Math.min(65536, end - position));
            const { bytesRead } = await handle.read(
              bytes,
              0,
              bytes.length,
              position,
            );
            if (!bytesRead)
              throw Error("Package changed or truncated during verification");
            position += bytesRead;
            yield bytes.subarray(0, bytesRead);
          }
        })(),
        { objectMode: false },
      );
    }
    close(callback) {
      callback();
    }
  })();
}

function openReader(reader, size) {
  return new Promise((resolve, reject) =>
    yauzl.fromRandomAccessReader(reader, size, ZIP_OPTIONS, (error, zip) =>
      error ? reject(error) : resolve(zip),
    ),
  );
}

async function walkZip(openZip, entry) {
  const zip = await openZip();
  await new Promise((resolve, reject) => {
    let failed = false;
    const fail = (error) => {
      if (failed) return;
      failed = true;
      clearTimeout(timer);
      zip.close();
      reject(error);
    };
    const timer = setTimeout(
      () => fail(Error("Verifier time limit exceeded")),
      300000,
    );
    zip.on("error", fail);
    zip.on("end", () => {
      if (!failed) {
        clearTimeout(timer);
        resolve();
      }
    });
    zip.on("entry", (value) => {
      (async () => {
        await entry(zip, value);
        if (!failed) zip.readEntry();
      })().catch(fail);
    });
    zip.readEntry();
  });
}

async function contents(zip, entry, collect = false) {
  const stream = await new Promise((resolve, reject) =>
    zip.openReadStream(entry, (error, s) =>
      error ? reject(error) : resolve(s),
    ),
  );
  const hash = createHash("sha256"),
    chunks = [];
  let size = 0,
    crc = 0;
  for await (const chunk of stream) {
    size += chunk.length;
    if (size > entry.uncompressedSize)
      throw Error("Decompressed size mismatch");
    hash.update(chunk);
    crc = crc32(chunk, crc);
    if (collect) chunks.push(chunk);
  }
  if (size !== entry.uncompressedSize || crc !== entry.crc32)
    throw Error("ZIP size/CRC mismatch");
  return {
    sha256: hash.digest("hex"),
    bytes: size,
    ...(collect ? { data: new Uint8Array(Buffer.concat(chunks)) } : {}),
  };
}

async function inspect(openZip, options) {
  const trustedKeys = options.trustedKeys ?? new Set();
  if (
    !(trustedKeys instanceof Set) ||
    trustedKeys.size > 32 ||
    [...trustedKeys].some((k) => !/^[a-f0-9]{64}$/.test(k))
  )
    throw Error("Invalid trusted checkpoint fingerprint policy");
  const hashes = new Map(),
    names = new Set(),
    metadata = new Map();
  let total = 0;
  await walkZip(openZip, async (zip, entry) => {
    const name = entry.fileName;
    if (
      !(
        /^artifacts\/[0-9a-f-]{36}$/.test(name) ||
        ["manifest.json", "SHA256SUMS.txt", "README.txt"].includes(name)
      ) ||
      names.has(name) ||
      name !== name.normalize("NFC") ||
      names.size >= 10003 ||
      ((entry.externalFileAttributes >>> 16) & 0xf000) === 0xa000 ||
      entry.generalPurposeBitFlag & 1 ||
      ![0, 8].includes(entry.compressionMethod)
    )
      throw Error("Unsafe/duplicate/unsupported package entry");
    total += entry.uncompressedSize;
    if (
      !Number.isSafeInteger(total) ||
      total > 16 * 1024 ** 3 ||
      entry.uncompressedSize > 8 * 1024 ** 3 ||
      (entry.uncompressedSize > 1024 * 1024 &&
        entry.uncompressedSize / Math.max(1, entry.compressedSize) > 200)
    )
      throw Error("Package decompression policy exceeded");
    const meta = !name.startsWith("artifacts/");
    if (meta && entry.uncompressedSize > 8 * 1024 * 1024)
      throw Error("Oversized package metadata");
    names.add(name);
    const result = await contents(zip, entry, meta);
    hashes.set(name, { sha256: result.sha256, bytes: result.bytes });
    if (meta) metadata.set(name, result.data);
  });
  const manifestBytes = metadata.get("manifest.json");
  if (!manifestBytes) throw Error("Manifest missing");
  const manifest = safeJson(manifestBytes);
  if (
    canonical(manifest) !==
    new TextDecoder("utf-8", { fatal: true }).decode(manifestBytes)
  )
    throw Error("Manifest must be canonical JSON without duplicate keys");
  const result = verifyManifest(manifest, hashes);
  const allowed = new Set([
    "manifest.json",
    "SHA256SUMS.txt",
    "README.txt",
    ...manifest.artifacts.map((a) => a.path),
  ]);
  if ([...names].some((n) => !allowed.has(n)) || names.size !== allowed.size)
    throw Error("Missing/unlisted package entries");
  const expected =
    [
      ...manifest.artifacts.map((a) => `${a.sha256}  ${a.path}`),
      `${digest(manifestBytes)}  manifest.json`,
    ].join("\n") + "\n";
  if (new TextDecoder().decode(metadata.get("SHA256SUMS.txt")) !== expected)
    throw Error("Checksum sidecar mismatch");
  const checkpoints = manifest.artifacts.filter(
    (a) => a.operation.algorithm === CHECKPOINT_ALGORITHM,
  );
  if (checkpoints.length > MAX_CHECKPOINTS)
    throw Error("Too many checkpoint signatures");
  const wanted = new Map(checkpoints.map((a) => [a.path, a])),
    signatures = [];
  if (wanted.size)
    await walkZip(openZip, async (zip, entry) => {
      const artifact = wanted.get(entry.fileName);
      if (!artifact) return;
      if (
        artifact.bytes < 1 ||
        artifact.bytes > MAX_CHECKPOINT_BYTES ||
        entry.uncompressedSize !== artifact.bytes
      )
        throw Error("Checkpoint exceeds its metadata limit");
      const data = await contents(zip, entry, true);
      if (data.sha256 !== artifact.sha256)
        throw Error("Checkpoint changed during verification");
      signatures.push(
        verifyCheckpoint(data.data, artifact, manifest, hashes, trustedKeys),
      );
      wanted.delete(entry.fileName);
    });
  if (wanted.size) throw Error("Checkpoint disappeared during verification");
  if (
    trustedKeys.size &&
    !signatures.some((s) => s.trustedExternalKey && s.coversAllCurrentEvidence)
  )
    throw Error(
      "No independently trusted checkpoint covers all current non-checkpoint artifacts",
    );
  return {
    ...result,
    totalUncompressedBytes: total,
    signatures: signatures.length
      ? "VERIFIED_CHECKPOINT_PREFIXES"
      : "NO_CHECKPOINTS",
    checkpoints: signatures,
    trustedCheckpointCoversAllEvidence:
      trustedKeys.size > 0 &&
      signatures.some(
        (s) => s.trustedExternalKey && s.coversAllCurrentEvidence,
      ),
    authenticity: signatures.length
      ? "Device-key signatures cover stated historical prefixes; examiner identity, key trust and later events require independent validation"
      : result.authenticity,
  };
}

export async function verifyZipFile(path, options = {}) {
  const handle = await open(path);
  try {
    const before = await handle.stat({ bigint: true });
    if (
      !before.isFile() ||
      before.size < 22n ||
      before.size > 16n * 1024n ** 3n
    )
      throw Error("Unsupported package size");
    const size = Number(before.size);
    const result = await inspect(
      () => openReader(fileReader(handle), size),
      options,
    );
    const packageSha256 = await hashHandle(handle, size);
    const after = await handle.stat({ bigint: true });
    if (
      before.size !== after.size ||
      before.mtimeNs !== after.mtimeNs ||
      before.ctimeNs !== after.ctimeNs
    )
      throw Error("Package changed during verification");
    return { ...result, packageSha256, encrypted: false };
  } finally {
    await handle.close();
  }
}

export async function verifyZipBuffer(input, options = {}) {
  if (input.length > MAX_BROWSER_BYTES + 5 * 1024 * 1024)
    throw Error("Buffered verification exceeds browser-package policy");
  const bytes = Buffer.from(input);
  const openZip = () =>
    new Promise((resolve, reject) =>
      yauzl.fromBuffer(bytes, ZIP_OPTIONS, (error, zip) =>
        error ? reject(error) : resolve(zip),
      ),
    );
  const result = await inspect(openZip, options);
  return { ...result, packageSha256: digest(bytes), encrypted: false };
}

export async function verifyProtectedFile(path, passphrase, options = {}) {
  const source = await ProtectedReader.open(path, passphrase);
  try {
    const result = await inspect(
      () => openReader(source.zipReader(), source.size),
      options,
    );
    await source.finish();
    return {
      ...result,
      packageSha256: source.packageSha256,
      encrypted: true,
      protection: "Tink AES256_GCM_HKDF_1MB; PBKDF2-wrapped random file key",
    };
  } finally {
    source.abort();
  }
}
