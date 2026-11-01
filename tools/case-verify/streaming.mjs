import yauzl from "yauzl";
import { createReadStream } from "node:fs";
import { createHash } from "node:crypto";
import { crc32 } from "node:zlib";
import {
  safeJson,
  canonical,
  verifyManifest,
  digest,
} from "../../packages/evidence/index.mjs";
export async function fileHash(path) {
  const h = createHash("sha256");
  for await (const chunk of createReadStream(path, { highWaterMark: 65536 }))
    h.update(chunk);
  return h.digest("hex");
}
export async function verifyZipFile(path) {
  const zip = await new Promise((resolve, reject) =>
    yauzl.open(
      path,
      {
        lazyEntries: true,
        autoClose: true,
        validateEntrySizes: true,
        strictFileNames: true,
      },
      (error, file) => (error ? reject(error) : resolve(file)),
    ),
  );
  const hashes = new Map(),
    names = new Set(),
    metadata = new Map();
  let total = 0;
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
    zip.on("entry", (entry) => {
      (async () => {
        const name = entry.fileName;
        const allowed =
          /^artifacts\/[0-9a-f-]{36}$/.test(name) ||
          ["manifest.json", "SHA256SUMS.txt", "README.txt"].includes(name);
        if (
          !allowed ||
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
        const stream = await new Promise((resolve, reject) =>
          zip.openReadStream(entry, (error, s) =>
            error ? reject(error) : resolve(s),
          ),
        );
        const h = createHash("sha256"),
          chunks = [];
        let size = 0,
          crc = 0;
        for await (const chunk of stream) {
          size += chunk.length;
          if (size > entry.uncompressedSize)
            throw Error("Decompressed size mismatch");
          h.update(chunk);
          crc = crc32(chunk, crc);
          if (meta) chunks.push(chunk);
        }
        if (size !== entry.uncompressedSize || crc !== entry.crc32)
          throw Error("ZIP size/CRC mismatch");
        hashes.set(name, { sha256: h.digest("hex"), bytes: size });
        if (meta) metadata.set(name, new Uint8Array(Buffer.concat(chunks)));
        zip.readEntry();
      })().catch(fail);
    });
    zip.readEntry();
  });
  const manifestBytes = metadata.get("manifest.json");
  if (!manifestBytes) throw Error("Manifest missing");
  const manifest = safeJson(manifestBytes);
  if (
    canonical(manifest) !==
    new TextDecoder("utf-8", { fatal: true }).decode(manifestBytes)
  )
    throw Error("Manifest must be canonical JSON without duplicate keys");
  const result = verifyManifest(manifest, hashes),
    allowed = new Set([
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
  return { ...result, totalUncompressedBytes: total };
}
