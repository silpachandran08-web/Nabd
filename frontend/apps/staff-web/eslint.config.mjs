import { defineConfig, globalIgnores } from "eslint/config";
import nextVitals from "eslint-config-next/core-web-vitals";
import nextTs from "eslint-config-next/typescript";
import boundaries from "eslint-plugin-boundaries";
import i18next from "eslint-plugin-i18next";

// FEF-037: money and number formatting exists once, in @nabd/i18n (formatMoney / formatNumber / moneyFormatter).
const MONEY_MESSAGE = "Use formatMoney / formatNumber / moneyFormatter from @nabd/i18n (ISO-4217 minor units, one grouping).";
const MONEY_RULES = [
  { selector: ":matches(NewExpression, CallExpression)[callee.object.name='Intl'][callee.property.name='NumberFormat']", message: MONEY_MESSAGE },
  { selector: "CallExpression[callee.property.name='toLocaleString'][arguments.1.properties.0.key.name=/FractionDigits$/]", message: MONEY_MESSAGE },
  { selector: "CallExpression[callee.property.name='toFixed'][callee.object.name=/^(amount|price|total|subtotal|fee|charge|balance|paid|due|tax|discount|money|cost|revenue|payment|outstanding)/i]", message: "Do not hand-round money with toFixed. " + MONEY_MESSAGE },
  { selector: "CallExpression[callee.property.name='toFixed'][callee.object.type='MemberExpression'][callee.object.property.name=/^(amount|price|total|subtotal|fee|charge|balance|paid|due|tax|discount|money|cost|revenue|payment|outstanding)/i]", message: "Do not hand-round money with toFixed. " + MONEY_MESSAGE },
];

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
    "lint-fixtures/**", // FEF-012: must-fail samples, linted explicitly by tests/unit/s12-fef012-boundaries.test.mjs
  ]),
  { rules: { "no-restricted-globals": ["error", { name: "atob", message: "Use decodeJwtPayload from app/lib/jwt.ts (UTF-8 safe)." }] } },
  { files: ["app/lib/jwt.ts"], rules: { "no-restricted-globals": "off" } },
  { files: ["app/**/*.{ts,tsx}", "lint-fixtures/**/*.{ts,tsx}"], rules: { "no-restricted-syntax": ["error", ...MONEY_RULES] } },
  // FEF-036: migrated folders/files may not carry a literal UI string in JSX; text comes from next-intl catalogues (packages/i18n/messages).
  { files: ["app/TopBar.tsx", "app/LanguagePicker.tsx", "lint-fixtures/i18n/**/*.tsx"], plugins: { i18next },
    rules: { "i18next/no-literal-string": ["error", { mode: "jsx-text-only", words: { exclude: ["[^a-zA-Z]+", "[A-Z_-]+", "^[⌘⌥⌃⇧]\\w*$"] } }] } },
  // FEF-011: dates and times in migrated files go through app/lib/clinicTime.ts (clinic timezone, one locale).
  { files: ["app/components/myday/**", "app/components/myschedule/**", "app/components/consult/FollowUpPanel.tsx", "app/components/consult/DocumentSheet.tsx"],
    rules: { "no-restricted-syntax": ["error", ...MONEY_RULES,
      { selector: "CallExpression[callee.property.name=/^toLocale(Date|Time)String$/]", message: "Use formatClinicDate/formatClinicTime from app/lib/clinicTime.ts (clinic timezone)." },
      { selector: ":matches(NewExpression, CallExpression)[callee.object.name='Intl'][callee.property.name='DateTimeFormat']", message: "Use app/lib/clinicTime.ts; it is the only place that builds Intl.DateTimeFormat." },
    ] } },
  // FEF-012: a feature folder (app/components/<name>) imports only packages/*, shared app code and itself.
  { files: ["app/**/*.{ts,tsx}", "lint-fixtures/**/*.{ts,tsx}"],
    plugins: { boundaries },
    settings: {
      "boundaries/root-path": import.meta.dirname,
      "boundaries/elements": [
        { type: "shared", pattern: "app/components/ui" },
        { type: "feature", pattern: "app/components/*", capture: ["name"] },
        { type: "feature", pattern: "lint-fixtures/features/*", capture: ["name"] },
      ],
    },
    rules: { "boundaries/dependencies": ["error", { default: "allow", policies: [
      // FEF-013 moved the last cross-feature edges (arrivals->checkout, myday->consult, templates->consult) to @nabd/domain / app/lib; no exceptions.
      { from: { element: { type: "feature" } }, disallow: { to: { element: { type: "feature" } } } },
    ] }] } },
]);

export default eslintConfig;
