import { buildServer } from "./server.js";
const app = buildServer({
  token: process.env.API_TOKEN || "",
  python: process.env.VOSK_PYTHON || "python3",
  modelPath: process.env.VOSK_MODEL_PATH || "",
  allowedOrigin: process.env.ALLOWED_ORIGIN,
  providerEnabled: process.env.LOCAL_PROVIDER_ENABLED !== "false",
});
await app.listen({ host: "127.0.0.1", port: Number(process.env.PORT || 8787) });
process.stdout.write(
  "EchoScript local provider listening on 127.0.0.1. No evidence logging is enabled.\n",
);
for (const signal of ["SIGTERM", "SIGINT"])
  process.on(signal, () => {
    void app.close();
  });
