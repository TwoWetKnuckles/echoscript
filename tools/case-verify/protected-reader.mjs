import yauzl from "yauzl";
import { spawn } from "node:child_process";
import { Readable } from "node:stream";
import { fileURLToPath } from "node:url";
import { stat } from "node:fs/promises";

/** Tink runs in the JVM helper; authenticated plaintext ranges stay in bounded pipes. */
export class ProtectedReader {
  #child;
  #pending = [];
  #buffer = "";
  #failure;
  #exit;
  #timer;
  #ready;
  #readyResolve;
  #readyReject;
  #hello = false;
  #finishing = false;
  constructor(library, path, passphrase) {
    this.#ready = new Promise((resolve, reject) => {
      this.#readyResolve = resolve;
      this.#readyReject = reject;
    });
    this.#child = spawn(
      "java",
      ["-cp", `${library}/*`, "org.echoscript.cli.CaseProtect", "serve", path],
      {
        env: {
          PATH: process.env.PATH,
          ...(process.env.JAVA_HOME
            ? { JAVA_HOME: process.env.JAVA_HOME }
            : {}),
          ...(process.env.SystemRoot
            ? { SystemRoot: process.env.SystemRoot }
            : {}),
          ECHOSCRIPT_PACKAGE_PASSPHRASE: passphrase,
        },
        stdio: ["pipe", "pipe", "pipe"],
      },
    );
    this.#exit = new Promise((resolve) =>
      this.#child.once("close", (code) => {
        if (
          code !== 0 ||
          !this.#hello ||
          !this.#finishing ||
          this.#pending.length
        )
          this.#fail(
            Error(
              "Protected-case reader failed; check the passphrase and input integrity",
            ),
          );
        resolve(code);
      }),
    );
    this.#child.on("error", () =>
      this.#fail(Error("The independent Java reader could not start")),
    );
    this.#child.stdin.on("error", () =>
      this.#fail(Error("Protected-case reader input failed")),
    );
    // Do not relay JVM diagnostics that could contain paths or sensitive values.
    this.#child.stderr.on("data", () => {});
    this.#child.stdout.on("data", (chunk) => {
      try {
        this.#buffer += chunk.toString("utf8");
        let end;
        while ((end = this.#buffer.indexOf("\n")) >= 0) {
          const line = this.#buffer.slice(0, end);
          this.#buffer = this.#buffer.slice(end + 1);
          if (line.length > 131072)
            throw Error("Oversized protected-reader response");
          const response = JSON.parse(line);
          if (!this.#hello) {
            if (
              !Number.isSafeInteger(response.size) ||
              response.size < 22 ||
              response.size > 16 * 1024 ** 3 ||
              !/^[a-f0-9]{64}$/.test(response.packageSha256)
            )
              throw Error("Invalid protected-reader header");
            this.#hello = true;
            this.#readyResolve(response);
          } else {
            const waiting = this.#pending.shift();
            if (!waiting) throw Error("Unexpected protected-reader response");
            waiting.resolve(response);
          }
        }
        if (this.#buffer.length > 131072)
          throw Error("Oversized protected-reader response");
      } catch {
        this.#fail(Error("Malformed protected-reader protocol"));
      }
    });
    this.#timer = setTimeout(
      () =>
        this.#fail(Error("Protected-case verification time limit exceeded")),
      300000,
    );
  }
  #fail(error) {
    if (this.#failure) return;
    this.#failure = error;
    this.#readyReject(error);
    for (const waiting of this.#pending.splice(0)) waiting.reject(error);
    this.#child.kill("SIGTERM");
    clearTimeout(this.#timer);
  }
  static async open(path, passphrase) {
    if (
      typeof passphrase !== "string" ||
      !passphrase.isWellFormed() ||
      passphrase.length < 16 ||
      passphrase.length > 1024
    )
      throw Error(
        "Protected case requires a transfer passphrase of 16 to 1024 characters",
      );
    const library =
      process.env.ECHOSCRIPT_CASE_PROTECT_LIB ||
      fileURLToPath(
        new URL(
          "../case-protect/build/install/echoscript-case-protect/lib",
          import.meta.url,
        ),
      );
    if (!(await stat(library).catch(() => null))?.isDirectory())
      throw Error(
        "Build or install the independent Java case-protect tool first",
      );
    const reader = new ProtectedReader(library, path, passphrase);
    const header = await reader.#ready;
    reader.size = header.size;
    reader.packageSha256 = header.packageSha256;
    return reader;
  }
  #send(request) {
    if (this.#failure) return Promise.reject(this.#failure);
    if (this.#pending.length >= 32)
      return Promise.reject(Error("Protected-reader queue limit exceeded"));
    return new Promise((resolve, reject) => {
      this.#pending.push({ resolve, reject });
      this.#child.stdin.write(JSON.stringify(request) + "\n");
    });
  }
  _readStreamForRange(start, end) {
    const reader = this;
    return Readable.from(
      (async function* () {
        for (let offset = start; offset < end;) {
          const length = Math.min(65536, end - offset);
          const response = await reader.#send({ offset, length });
          if (
            typeof response.base64 !== "string" ||
            response.base64.length > 87384
          )
            throw Error("Invalid protected-reader byte range");
          const bytes = Buffer.from(response.base64, "base64");
          if (
            bytes.length !== length ||
            bytes.toString("base64") !== response.base64
          )
            throw Error("Protected-reader byte range differs");
          offset += length;
          yield bytes;
        }
      })(),
      { objectMode: false },
    );
  }
  zipReader() {
    const owner = this;
    return new (class extends yauzl.RandomAccessReader {
      _readStreamForRange(start, end) {
        return owner._readStreamForRange(start, end);
      }
      close(callback) {
        callback();
      }
    })();
  }
  async finish() {
    this.#finishing = true;
    const reply = await this.#send({ finish: true });
    if (reply.finished !== true)
      throw Error("Protected-file final integrity check failed");
    this.#child.stdin.end();
    const code = await this.#exit;
    clearTimeout(this.#timer);
    if (code !== 0 || this.#failure)
      throw this.#failure || Error("Protected reader failed");
  }
  abort() {
    clearTimeout(this.#timer);
    this.#child.kill("SIGTERM");
  }
}
