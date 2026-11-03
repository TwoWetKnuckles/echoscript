import js from "@eslint/js";
import globals from "globals";
export default [
  {
    ignores: [
      "node_modules/**",
      "dist/**",
      "apps/web/dist/**",
      "build/**",
      "target/**",
      "models/**",
      "qa-output/**",
      "packages/evidence/schema-validator.cjs",
    ],
  },
  {
    files: ["**/*.mjs"],
    languageOptions: {
      ecmaVersion: "latest",
      sourceType: "module",
      globals: { ...globals.browser, ...globals.node },
    },
    rules: {
      ...js.configs.recommended.rules,
      "no-unused-vars": [
        "error",
        { argsIgnorePattern: "^_", varsIgnorePattern: "^_" },
      ],
      "no-empty": ["error", { allowEmptyCatch: true }],
    },
  },
];
