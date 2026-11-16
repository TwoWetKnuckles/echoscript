import { createApp } from "vue";
import Workbench from "./Workbench.vue";
import "./workbench.css";

const target = document.getElementById("app");
if (!target) throw Error("Workbench mount is unavailable");
if (
  !window.isSecureContext ||
  !globalThis.crypto?.subtle ||
  !globalThis.Worker ||
  !CSS.supports("color", "color-mix(in oklab, white, black)")
) {
  target.textContent =
    "Open EchoScript over HTTPS or localhost in a current browser: Chrome/Edge 111+, Firefox 128+, or Safari 16.4+. The Android app works independently of browser support.";
} else createApp(Workbench).mount(target);
