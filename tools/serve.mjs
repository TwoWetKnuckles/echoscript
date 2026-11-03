import { createServer } from "node:http";
import { createReadStream } from "node:fs";
import { realpath, stat } from "node:fs/promises";
import { resolve, sep, extname } from "node:path";
import { pipeline } from "node:stream/promises";
import { pathToFileURL } from "node:url";

const types = new Map([
  [".html", "text/html; charset=utf-8"],
  [".mjs", "text/javascript; charset=utf-8"],
  [".js", "text/javascript; charset=utf-8"],
  [".css", "text/css; charset=utf-8"],
  [".json", "application/json; charset=utf-8"],
  [".ttf", "font/ttf"],
  [".txt", "text/plain; charset=utf-8"],
  [".wav", "audio/wav"],
]);

/** Loopback-only static development host. It has no evidence upload endpoint. */
export async function createStaticServer(directory = "dist") {
  const root = await realpath(resolve(directory));
  return createServer(
    { requestTimeout: 15000, headersTimeout: 10000 },
    async (req, res) => {
      res.setHeader("X-Content-Type-Options", "nosniff");
      res.setHeader("Referrer-Policy", "no-referrer");
      res.setHeader("Cache-Control", "no-store");
      res.setHeader("Cross-Origin-Resource-Policy", "same-origin");
      res.setHeader(
        "Content-Security-Policy",
        "default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' blob: data:; media-src 'self' blob:; font-src 'self'; worker-src 'self'; connect-src 'self' http://127.0.0.1:* http://localhost:*; object-src 'none'; base-uri 'none'; frame-ancestors 'none'; form-action 'none'",
      );
      if (req.method !== "GET" && req.method !== "HEAD") {
        res.writeHead(405, { Allow: "GET, HEAD" }).end();
        return;
      }
      try {
        const raw = (req.url || "/").split("?")[0];
        const name = decodeURIComponent(raw);
        if (
          !name.startsWith("/") ||
          name.includes("\\") ||
          name.includes("\0") ||
          name.split("/").some((p) => p.startsWith("."))
        )
          throw Error("BAD_PATH");
        const file = await realpath(
          resolve(root, "." + (name === "/" ? "/index.html" : name)),
        );
        if (!file.startsWith(root + sep) || !types.has(extname(file)))
          throw Error("BAD_PATH");
        const info = await stat(file);
        if (!info.isFile()) throw Error("BAD_PATH");
        res.writeHead(200, {
          "Content-Type": types.get(extname(file)),
          "Content-Length": info.size,
        });
        if (req.method === "HEAD") res.end();
        else await pipeline(createReadStream(file), res);
      } catch {
        if (!res.headersSent) res.writeHead(404).end();
        else res.destroy();
      }
    },
  );
}

if (
  process.argv[1] &&
  import.meta.url === pathToFileURL(resolve(process.argv[1])).href
) {
  const port = Number(process.env.PORT || "4173");
  if (!Number.isInteger(port) || port < 1 || port > 65535)
    throw Error("Invalid PORT");
  const server = await createStaticServer();
  server.listen(port, "127.0.0.1", () =>
    process.stdout.write(
      `EchoScript development workbench: http://127.0.0.1:${port}/\n`,
    ),
  );
  for (const signal of ["SIGINT", "SIGTERM"])
    process.once(signal, () => server.close());
}
