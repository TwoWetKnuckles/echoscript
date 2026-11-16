import { build } from "vite";
import { mkdir, writeFile, copyFile, cp, rm } from "node:fs/promises";
import Ajv from "ajv";
import standaloneCode from "ajv/dist/standalone/index.js";
import schema from "../specs/json-schema/case.schema.json" with { type: "json" };
// The worker must be reproducible in a fresh checkout, without downloaded weights.
import modelInventory from "../specs/models/vosk-small-en-us-0.15.json" with { type: "json" };
if (
  modelInventory.files.length !== 14 ||
  !/^[a-f0-9]{64}$/.test(modelInventory.archiveSha256)
)
  throw Error("Missing or invalid pinned speech-model inventory");
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
if (process.argv.includes("--schema-only")) process.exit(0);
await rm("apps/web/dist", { recursive: true, force: true });
await rm("dist", { recursive: true, force: true });
await mkdir("apps/web/public/fixtures", { recursive: true });
await copyFile(
  "apps/web/assets/NotoSans-Regular.ttf",
  "apps/web/public/NotoSans-Regular.ttf",
);
await copyFile("apps/web/assets/OFL.txt", "apps/web/public/OFL.txt");
await copyFile(
  "specs/fixtures/synthetic-speech.wav",
  "apps/web/public/fixtures/synthetic-speech.wav",
);
await writeFile(
  "apps/web/public/build.json",
  JSON.stringify(
    {
      version: "0.6.0-preview",
      evidenceFormat: "org.echoscript.case/1",
      betaGate: "NOT PASSED",
    },
    null,
    2,
  ) + "\n",
);
await build({ configFile: "apps/web/vite.config.ts" });
await cp("apps/web/dist", "dist", { recursive: true });
process.stdout.write(
  "Built browser workbench and independent schema validator.\n",
);
