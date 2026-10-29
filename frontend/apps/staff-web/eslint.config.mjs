import { defineConfig, globalIgnores } from "eslint/config";
import nextVitals from "eslint-config-next/core-web-vitals";
import nextTs from "eslint-config-next/typescript";
import boundaries from "eslint-plugin-boundaries";
import jsxA11y from "eslint-plugin-jsx-a11y";
import i18next from "eslint-plugin-i18next";

// FEF-037: money and number formatting exists once, in @nabd/i18n (formatMoney / formatNumber / moneyFormatter).
const MONEY_MESSAGE = "Use formatMoney / formatNumber / moneyFormatter from @nabd/i18n (ISO-4217 minor units, one grouping).";
const MONEY_RULES = [
  { selector: ":matches(NewExpression, CallExpression)[callee.object.name='Intl'][callee.property.name='NumberFormat']", message: MONEY_MESSAGE },
  { selector: "CallExpression[callee.property.name='toLocaleString'][arguments.1.properties.0.key.name=/FractionDigits$/]", message: MONEY_MESSAGE },
  { selector: "CallExpression[callee.property.name='toFixed'][callee.object.name=/^(amount|price|total|subtotal|fee|charge|balance|paid|due|tax|discount|money|cost|revenue|payment|outstanding)/i]", message: "Do not hand-round money with toFixed. " + MONEY_MESSAGE },
  { selector: "CallExpression[callee.property.name='toFixed'][callee.object.type='MemberExpression'][callee.object.property.name=/^(amount|price|total|subtotal|fee|charge|balance|paid|due|tax|discount|money|cost|revenue|payment|outstanding)/i]", message: "Do not hand-round money with toFixed. " + MONEY_MESSAGE },
];

// FEF-038: Login, MFA, accept-invite and Arrivals are fully extracted (ADR-11 order: Login -> Arrivals -> Consult -> Checkout).
const MIGRATED = ["app/(auth)/login/**/*.tsx", "app/(auth)/accept-invite/**/*.tsx", "app/(clinic)/arrivals/**/*.tsx", "app/components/arrivals/**/*.tsx", "app/components/MfaEnrolCard.tsx", "app/(clinic)/consult/**/*.tsx", "app/components/consult/**/*.tsx"];
const TEXT_TEMPLATE_RULES = [
  { selector: "JSXElement > JSXExpressionContainer > :matches(TemplateLiteral, ConditionalExpression > TemplateLiteral, LogicalExpression > TemplateLiteral)", message: "A template literal builds user-facing text. Use a next-intl ICU message with placeholders." },
  { selector: "JSXAttribute[name.name=/^(aria-label|aria-description|title|placeholder|alt|label|message|hint)$/] > JSXExpressionContainer > TemplateLiteral", message: "A template literal builds user-facing text. Use a next-intl ICU message with placeholders." },
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
  // FEF-027: jsx-a11y strict rule set (the plugin itself is already registered by eslint-config-next).
  // Rules that still have pre-existing findings (a11y debt, FEF-027 report) stay warnings so they are listed but do not block; every other strict rule is an error.
  { files: ["app/**/*.{ts,tsx}"], rules: { ...jsxA11y.flatConfigs.strict.rules,
    ...Object.fromEntries(["label-has-associated-control", "no-autofocus", "click-events-have-key-events", "no-static-element-interactions",
      "no-noninteractive-element-interactions", "no-noninteractive-element-to-interactive-role", "no-noninteractive-tabindex"].map((r) => [`jsx-a11y/${r}`, "warn"])) } },
  { rules: { "no-restricted-globals": ["error", { name: "atob", message: "Use decodeJwtPayload from app/lib/jwt.ts (UTF-8 safe)." }] } },
  { files: ["app/lib/jwt.ts"], rules: { "no-restricted-globals": "off" } },
  { files: ["app/**/*.{ts,tsx}", "lint-fixtures/**/*.{ts,tsx}"], rules: { "no-restricted-syntax": ["error", ...MONEY_RULES] } },
  // FEF-036: migrated folders/files may not carry a literal UI string in JSX; text comes from next-intl catalogues (packages/i18n/messages).
  { files: ["app/TopBar.tsx", "app/LanguagePicker.tsx", ...MIGRATED, "lint-fixtures/i18n/**/*.tsx"], plugins: { i18next },
    rules: { "i18next/no-literal-string": ["error", { mode: "jsx-text-only", words: { exclude: ["[^a-zA-Z]+", "[A-Z_-]+", "^[⌘⌥⌃⇧]\\w*$"] } }] } },
  // FEF-038: in migrated folders no template literal may build user-facing text (JSX child or text attribute): use an ICU message.
  { files: [...MIGRATED, "lint-fixtures/i18n/**/*.tsx"], rules: { "no-restricted-syntax": ["error", ...MONEY_RULES, ...TEXT_TEMPLATE_RULES] } },
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
