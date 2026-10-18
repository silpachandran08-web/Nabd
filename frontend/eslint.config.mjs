import { defineConfig, globalIgnores } from "eslint/config";
import nextVitals from "eslint-config-next/core-web-vitals";
import nextTs from "eslint-config-next/typescript";

const eslintConfig = defineConfig([
  ...nextVitals,
  ...nextTs,
  // Override default ignores of eslint-config-next.
  globalIgnores([
    // Default ignores of eslint-config-next:
    ".next/**",
    "out/**",
    "build/**",
    "next-env.d.ts",
  ]),
  { rules: { "no-restricted-globals": ["error", { name: "atob", message: "Use decodeJwtPayload from app/lib/jwt.ts (UTF-8 safe)." }] } },
  { files: ["app/lib/jwt.ts"], rules: { "no-restricted-globals": "off" } },
]);

export default eslintConfig;
