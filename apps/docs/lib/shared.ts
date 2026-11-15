import { createGetUrl } from "fumadocs-core/source";

export const appName = "EchoScript";
export const docsRoute = "/docs";
export const docsImageRoute = "/og/docs";
export const docsContentRoute = "/llms.mdx/docs";

export const gitConfig = {
  user: "TwoWetKnuckles",
  repo: "echoscript",
  branch: "main",
};

export const releaseUrl =
  "https://github.com/TwoWetKnuckles/echoscript/releases/tag/android10-offline-test-candidate-0.6.0-dev.20261113";
export const apkUrl =
  "https://github.com/TwoWetKnuckles/echoscript/releases/download/android10-offline-test-candidate-0.6.0-dev.20261113/EchoScript-Android10-Offline-Test-Candidate-0.6.apk";

const getContentUrl = createGetUrl(docsContentRoute);

export function getPageMarkdownUrl(page: { slugs: string[]; locale?: string }) {
  const segments = [...page.slugs, "content.md"];

  return { segments, url: getContentUrl(segments, page.locale) };
}

const getImageUrl = createGetUrl(docsImageRoute);

export function getPageImageUrl(page: { slugs: string[]; locale?: string }) {
  const segments = [...page.slugs, "image.png"];

  return { segments, url: getImageUrl(segments, page.locale) };
}
