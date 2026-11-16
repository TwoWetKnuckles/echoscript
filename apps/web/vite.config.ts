import { defineConfig } from "vite";
import vue from "@vitejs/plugin-vue";
import tailwindcss from "@tailwindcss/vite";
import { VitePWA } from "vite-plugin-pwa";
import { fileURLToPath } from "node:url";

export default defineConfig({
  root: fileURLToPath(new URL("./src", import.meta.url)),
  base: "./",
  publicDir: fileURLToPath(new URL("./public", import.meta.url)),
  plugins: [
    vue(),
    tailwindcss(),
    VitePWA({
      registerType: "prompt",
      injectRegister: null,
      manifest: {
        name: "EchoScript audio workbench",
        short_name: "EchoScript",
        description:
          "Local audio inspection, transcript review and verifiable exports.",
        theme_color: "#0b1219",
        background_color: "#0b1219",
        display: "standalone",
        start_url: "./",
        scope: "./",
        icons: [
          {
            src: "app-icon.svg",
            sizes: "any",
            type: "image/svg+xml",
            purpose: "any",
          },
        ],
      },
      workbox: {
        cacheId: "echoscript-shell",
        globPatterns: [
          "**/*.{js,mjs,css,html,svg,ttf,webmanifest}",
          "fixtures/synthetic-speech.wav",
        ],
        navigateFallback: "index.html",
        navigateFallbackDenylist: [/^\/api(?:\/|$)/, /^\/v1(?:\/|$)/],
        cleanupOutdatedCaches: true,
        // No runtime cache, background sync or provider-request replay.
        runtimeCaching: [],
      },
    }),
  ],
  worker: { format: "es" },
  build: {
    outDir: fileURLToPath(new URL("./dist", import.meta.url)),
    emptyOutDir: true,
    target: ["chrome111", "firefox128", "safari16.4"],
    sourcemap: true,
  },
});
