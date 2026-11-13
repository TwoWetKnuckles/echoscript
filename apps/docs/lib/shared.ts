import { createGetUrl } from "fumadocs-core/source";

export const appName = "EchoScript";
export const docsRoute = "/docs";
export const docsImageRoute = "/og/docs";
export const docsContentRoute = "/llms.mdx/docs";

export const gitConfig = {
  user: "TwoWetKnuckles",
  repo: "echoscript",
  branch: "feature/release-gate-hardening",
};

export const releaseUrl =
  "https://github.com/TwoWetKnuckles/echoscript/releases/tag/android-preview-0.6.0-dev.20261113";
export const apkUrl =
  "https://github.com/TwoWetKnuckles/echoscript/releases/download/android-preview-0.6.0-dev.20261113/EchoScript-offline-preview.apk";

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
