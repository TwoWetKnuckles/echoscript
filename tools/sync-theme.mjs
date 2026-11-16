import { readFile, writeFile } from "node:fs/promises";

const source = new URL("../packages/ui/theme.css", import.meta.url);
const target = new URL("../apps/docs/app/shared-theme.css", import.meta.url);
const theme = await readFile(source, "utf8");
if (process.argv.includes("--check")) {
  if ((await readFile(target, "utf8")) !== theme)
    throw Error("Documentation theme is stale: run node tools/sync-theme.mjs");
  console.log("Shared web/site/documentation theme agrees.");
} else await writeFile(target, theme);
