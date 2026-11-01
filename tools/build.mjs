import { build } from "esbuild";
import { mkdir, writeFile, copyFile, cp } from "node:fs/promises";
import Ajv from "ajv";
import standaloneCode from "ajv/dist/standalone/index.js";
import schema from "../specs/json-schema/case.schema.json" with { type: "json" };
const ajv = new Ajv({
  allErrors: true,
  strict: true,
  code: { source: true, esm: false },
});
const validator = ajv.compile(schema);
await writeFile(
  "packages/evidence/schema-validator.cjs",
  standaloneCode(ajv, validator),
);
await mkdir("apps/web/dist", { recursive: true });
if (process.argv.includes("--schema-only")) process.exit(0);
await build({
  entryPoints: ["apps/web/src/main.mjs", "apps/web/src/processing.worker.mjs"],
  outdir: "apps/web/dist",
  outExtension: { ".js": ".mjs" },
  bundle: true,
  format: "esm",
  target: "es2023",
  sourcemap: true,
  minify: false,
  legalComments: "linked",
});
await copyFile("apps/web/src/index.html", "apps/web/dist/index.html");
await copyFile("apps/web/src/workbench.css", "apps/web/dist/workbench.css");
await copyFile(
  "apps/web/assets/NotoSans-Regular.ttf",
  "apps/web/dist/NotoSans-Regular.ttf",
);
await copyFile("apps/web/assets/OFL.txt", "apps/web/dist/OFL.txt");
await mkdir("apps/web/dist/fixtures", { recursive: true });
await copyFile(
  "specs/fixtures/synthetic-speech.wav",
  "apps/web/dist/fixtures/synthetic-speech.wav",
);
await writeFile(
  "apps/web/dist/build.json",
  JSON.stringify(
    {
      version: "0.2.0-dev",
      evidenceFormat: "org.echoscript.case/1",
      betaGate: "NOT PASSED",
    },
    null,
    2,
  ) + "\n",
);
await cp("apps/web/dist", "dist", { recursive: true });
process.stdout.write(
  "Built browser workbench and independent schema validator.\n",
);
