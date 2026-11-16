import { cp, mkdir, readFile, rm } from "node:fs/promises";
import { resolve, sep } from "node:path";

const root = resolve(import.meta.dirname, "..");
const portal = resolve(root, process.argv[2] ?? "qa-output/site-static");
if (!portal.startsWith(root + sep) || portal === resolve(root, "dist"))
  throw Error(
    "Use a separate generated portal directory beneath the repository",
  );
const html = await readFile(resolve(portal, "index.html"), "utf8");
if (!html.includes('href="/workbench/"') || !html.includes('type="importmap"'))
  throw Error(
    "The generated portal is missing the workbench link or importmap",
  );
const destination = resolve(root, "dist");
await rm(destination, { recursive: true, force: true });
await mkdir(destination);
await cp(portal, destination, { recursive: true });
await cp(resolve(root, "apps/web/dist"), resolve(destination, "workbench"), {
  recursive: true,
});
console.log(
  "Packaged Symfony portal at / and the Vue workbench at /workbench/.",
);
