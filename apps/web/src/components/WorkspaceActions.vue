<script setup lang="ts">
import { onMounted, onBeforeUnmount, ref } from "vue";

type Appearance = "dark" | "light" | "system";
const appearance = ref<Appearance>("dark");
const offline = ref(false);
const offlineStatus = ref("");
const updateAvailable = ref(false);
let registration: ServiceWorkerRegistration | undefined;
let updateWorker: ((reloadPage?: boolean) => Promise<void>) | undefined;
const systemAppearance = matchMedia("(prefers-color-scheme: dark)");
function systemChanged() {
  if (appearance.value === "system") applyAppearance();
}

function applyAppearance() {
  const dark =
    appearance.value === "dark" ||
    (appearance.value === "system" &&
      matchMedia("(prefers-color-scheme: dark)").matches);
  document.documentElement.dataset.theme = dark ? "dark" : "light";
  try {
    localStorage.setItem("echoscript-appearance", appearance.value);
  } catch {
    /* Optional preference only. */
  }
}
async function enableOffline() {
  if (!("serviceWorker" in navigator)) {
    offlineStatus.value = "Offline app caching is unavailable in this browser.";
    return;
  }
  try {
    const { registerSW } = await import("virtual:pwa-register");
    updateWorker = registerSW({
      immediate: true,
      onOfflineReady() {
        offline.value = true;
        offlineStatus.value =
          "App cached. Export cases separately; transcription still needs its selected service.";
      },
      onNeedRefresh() {
        updateAvailable.value = true;
        offlineStatus.value =
          "Update ready. Export your case before reloading.";
      },
      onRegisteredSW(_url, next) {
        registration = next;
      },
      onRegisterError() {
        offlineStatus.value =
          "Offline app caching failed. Your open case is unchanged.";
      },
    });
    try {
      localStorage.setItem("echoscript-offline-shell", "enabled");
    } catch {
      /* Optional preference only. */
    }
    offlineStatus.value =
      "Preparing the offline app. Audio, tokens and provider responses are never cached by this worker.";
  } catch {
    offlineStatus.value = "Offline app caching could not start.";
  }
}
async function disableOffline() {
  try {
    const candidates = registration
      ? [registration]
      : await navigator.serviceWorker.getRegistrations();
    const expected = new URL(`${import.meta.env.BASE_URL}sw.js`, location.href)
      .href;
    for (const candidate of candidates) {
      const worker =
        candidate.active ?? candidate.waiting ?? candidate.installing;
      if (worker?.scriptURL === expected) await candidate.unregister();
    }
    for (const name of await caches.keys())
      if (name.startsWith("echoscript-shell-")) await caches.delete(name);
    try {
      localStorage.removeItem("echoscript-offline-shell");
    } catch {
      /* Optional preference only. */
    }
    offline.value = false;
    offlineStatus.value =
      "Offline app cache removed. The current page stops being controlled after it closes.";
  } catch {
    offlineStatus.value =
      "Cache removal failed; review this site's browser storage settings.";
  }
}
async function applyUpdate() {
  if (confirm("Export your open case before updating. Reload now?"))
    await updateWorker?.(true);
}
onMounted(() => {
  try {
    const saved = localStorage.getItem("echoscript-appearance");
    if (saved === "dark" || saved === "light" || saved === "system")
      appearance.value = saved;
    if (localStorage.getItem("echoscript-offline-shell") === "enabled")
      void enableOffline();
  } catch {
    /* Browser storage is optional. */
  }
  applyAppearance();
  systemAppearance.addEventListener("change", systemChanged);
});
onBeforeUnmount(() =>
  systemAppearance.removeEventListener("change", systemChanged),
);
</script>

<template>
  <div class="workspace-actions">
    <label class="sr-only" for="appearance">Appearance</label>
    <select
      id="appearance"
      v-model="appearance"
      aria-label="Appearance"
      @change="applyAppearance"
    >
      <option value="dark">Dark</option>
      <option value="light">Light</option>
      <option value="system">System</option>
    </select>
    <button v-if="!offline" type="button" @click="enableOffline">
      Enable offline app
    </button>
    <button v-else type="button" @click="disableOffline">
      Remove offline cache
    </button>
    <button v-if="updateAvailable" type="button" @click="applyUpdate">
      Update app
    </button>
    <p v-if="offlineStatus" class="offline-status" role="status">
      {{ offlineStatus }}
    </p>
  </div>
</template>
