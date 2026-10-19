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
  // FEF-011: dates and times in migrated files go through app/lib/clinicTime.ts (clinic timezone, one locale).
  { files: ["app/components/myday/**", "app/components/myschedule/**", "app/components/consult/FollowUpPanel.tsx", "app/components/consult/DocumentSheet.tsx"],
    rules: { "no-restricted-syntax": ["error",
      { selector: "CallExpression[callee.property.name=/^toLocale(Date|Time)String$/]", message: "Use formatClinicDate/formatClinicTime from app/lib/clinicTime.ts (clinic timezone)." },
      { selector: ":matches(NewExpression, CallExpression)[callee.object.name='Intl'][callee.property.name='DateTimeFormat']", message: "Use app/lib/clinicTime.ts; it is the only place that builds Intl.DateTimeFormat." },
    ] } },
]);

export default eslintConfig;
