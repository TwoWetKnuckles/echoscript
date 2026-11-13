import { createHash } from "node:crypto";
import { readFile, writeFile, readdir, mkdir, stat } from "node:fs/promises";
import { dirname, join, relative, resolve, posix } from "node:path";
import { fileURLToPath } from "node:url";

const app = fileURLToPath(new URL("../", import.meta.url));
const repo = resolve(app, "../..");
const content = join(app, "content/docs");
const check = process.argv.includes("--check");
const branch = "feature/release-gate-hardening";
const repository = "https://github.com/TwoWetKnuckles/echoscript";
const entries = [];

async function collect(folder) {
  for (const item of (
    await readdir(join(repo, folder), { withFileTypes: true })
  ).sort((a, b) => a.name.localeCompare(b.name))) {
    const name = posix.join(folder, item.name);
    if (item.isDirectory()) await collect(name);
    else if (item.isFile() && name.endsWith(".md"))
      entries.push({
        source: name,
        target:
          name.slice("docs/".length, -3).toLowerCase().replaceAll("_", "-") +
          ".mdx",
      });
  }
}
await collect("docs");
entries.push(
  { source: "downloads/README.md", target: "downloads/index.mdx" },
  {
    source: "downloads/android/RELEASE-NOTES-0.6.0.md",
    target: "releases/0.6-test.mdx",
  },
  { source: "DISCLAIMER.md", target: "legal/responsibilities.mdx" },
  { source: "SECURITY.md", target: "security/reporting.mdx" },
  { source: "CONTRIBUTING.md", target: "development/contributing.mdx" },
);
const route = (target) => "/docs/" + target.replace(/(?:\/index)?\.mdx$/, "");
const routes = new Map(
  entries.map((entry) => [entry.source, route(entry.target)]),
);
const targets = new Set(entries.map((entry) => entry.target));
if (targets.size !== entries.length)
  throw new Error("Duplicate documentation destination");

const descriptions = {
  "docs/android/OFFLINE.md":
    "Install EchoScript 0.6 Test and use local acquisition, analysis, transcription, examiner review and exports.",
  "docs/android/ENHANCEMENT-WORKBENCH.md":
    "Presets, signed controls, time/frequency inspection, overlapping regions and non-destructive render history.",
  "docs/android/WORKBENCH-DESIGN.md":
    "The Material-dark workbench direction, compact controls and documented inspection limits.",
  "docs/RELEASE-CANDIDATE.md":
    "The current development preview, executed checks and outstanding release acceptance gates.",
  "docs/WORKING-SCOPE.md":
    "The owner-confirmed working release inventory, deferred features and pending acceptance.",
  "docs/PLANS.md":
    "Proposed Free, subscription and one-time Premium access; current previews do not accept payments.",
  "docs/evidence/PROTECTED-TRANSFER.md":
    "Encrypted case transfers, device checkpoints, independent verification and recovery/trust boundaries.",
  "docs/providers/openai-whisper.md":
    "The experimental opt-in server adapter, consent boundaries and unfinished Android/provider integration.",
  "docs/providers/local-vosk.md":
    "The local speech runtime, pinned model provenance and observed capabilities and limits.",
  "docs/qa/STATUS.md":
    "Recorded development checks, regression repairs and remaining exact-build validation work.",
  "downloads/README.md":
    "Download the verified EchoScript 0.6 Test APK, installation ZIP, checksums and validation records.",
  "DISCLAIMER.md":
    "Case-specific examiner responsibilities, technical verification limits and software warranty boundaries.",
};

const map = {};
const changed = [];
async function output(path, value) {
  const old = await readFile(path, "utf8").catch((error) => {
    if (error.code === "ENOENT") return null;
    throw error;
  });
  if (old === value) return;
  changed.push(relative(app, path));
  if (!check) {
    await mkdir(dirname(path), { recursive: true });
    await writeFile(path, value);
  }
}

for (const { source, target } of entries) {
  const original = await readFile(join(repo, source), "utf8");
  const heading = original.match(/^# (.+)\r?\n/);
  const title = heading?.[1] ?? posix.basename(source, ".md");
  const body = heading
    ? original.slice(heading[0].length).replace(/^\r?\n/, "")
    : original;
  let fence = null;
  const migrated = [];
  for (const line of body.split("\n")) {
    const marker = line.match(/^\s*(`{3,}|~{3,})/);
    if (marker) {
      if (!fence) fence = marker[1];
      else if (marker[1][0] === fence[0] && marker[1].length >= fence.length)
        fence = null;
      migrated.push(line);
      continue;
    }
    if (fence) {
      migrated.push(line);
      continue;
    }
    // MDX does not accept Markdown's angle-bracket autolinks as JSX.
    // Preserve the displayed URL and destination using normal link syntax.
    let transformed = line.replace(/<(https?:\/\/[^<>\s]+)>/g, "[$1]($1)");
    const links = [...transformed.matchAll(/(!?\[[^\]\n]*\])\(([^\s)]+)\)/g)];
    for (const match of links.reverse()) {
      const href = match[2];
      if (/^(?:[a-z][a-z\d+.-]*:|#|\/)/i.test(href)) continue;
      const [file, fragment] = href.split("#", 2);
      const resolved = posix.normalize(
        posix.join(posix.dirname(source), decodeURIComponent(file)),
      );
      if (resolved.startsWith("../"))
        throw new Error(`Link escapes repository: ${source}: ${href}`);
      const info = await stat(join(repo, resolved)).catch(() => null);
      if (!info) throw new Error(`Broken repository link: ${source}: ${href}`);
      const url =
        routes.get(resolved) ??
        `${repository}/${info.isDirectory() ? "tree" : "blob"}/${branch}/${resolved}`;
      const replacement = `${match[1]}(${url}${fragment ? "#" + fragment : ""})`;
      transformed =
        transformed.slice(0, match.index) +
        replacement +
        transformed.slice(match.index + match[0].length);
    }
    migrated.push(transformed);
  }
  const description =
    descriptions[source] ??
    `EchoScript reference: ${title.replace(/[`*]/g, "")}.`;
  await output(
    join(content, target),
    `---\ntitle: ${JSON.stringify(title)}\ndescription: ${JSON.stringify(description)}\n---\n\n${migrated.join("\n")}`,
  );
  map[target] = {
    source,
    sha256: createHash("sha256").update(original).digest("hex"),
  };
}
await output(
  join(app, "content-source-map.json"),
  JSON.stringify(map, null, 2) + "\n",
);
if (check && changed.length) {
  console.error("Documentation mirrors are stale:", changed.join(", "));
  process.exitCode = 1;
} else
  console.log(
    `${check ? "Verified" : "Synchronized"} ${entries.length} canonical Markdown pages; headings moved to frontmatter and relative links adjusted.`,
  );
