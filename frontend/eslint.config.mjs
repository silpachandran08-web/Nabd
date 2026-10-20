// FEF-012: lint for packages/* (each package's `eslint .` finds this file; apps/* carry their own config).
// Boundaries: ui/tokens/domain never import api-client; only api-client calls fetch.
import tseslint from "typescript-eslint";
import boundaries from "eslint-plugin-boundaries";

const noFetch = {
  "no-restricted-globals": ["error", { name: "fetch", message: "Only packages/api-client calls fetch." }],
  "no-restricted-properties": ["error",
    { object: "globalThis", property: "fetch", message: "Only packages/api-client calls fetch." },
    { object: "window", property: "fetch", message: "Only packages/api-client calls fetch." },
    { object: "self", property: "fetch", message: "Only packages/api-client calls fetch." }],
};

export default tseslint.config(
  { ignores: ["**/node_modules/**", "**/src/generated/**", "**/lint-fixtures/**"] },
  { files: ["**/*.{ts,tsx,mts,js,mjs}"], languageOptions: { parser: tseslint.parser } },
  {
    files: ["**/*.{ts,tsx,mts,js,mjs}"],
    plugins: { boundaries },
    settings: {
      "boundaries/root-path": import.meta.dirname,
      "boundaries/elements": [
        { type: "ui", pattern: "packages/ui" },
        { type: "tokens", pattern: "packages/tokens" },
        { type: "domain", pattern: "packages/domain" },
        { type: "api-client", pattern: "packages/api-client" },
      ],
    },
    rules: {
      "boundaries/dependencies": ["error", {
        default: "allow",
        checkAllOrigins: true, // also check bare "@nabd/api-client" imports, not only relative ones
        policies: [
          { from: { element: { type: "!api-client" } }, disallow: { to: { element: { type: "api-client" } } } },
          { from: { element: { type: "!api-client" } }, disallow: { to: { module: { source: ["@nabd/api-client", "@nabd/api-client/**"] } } } },
          { from: { element: { types: { anyOf: ["tokens", "domain"] } } }, disallow: { to: { element: { type: "ui" } } } },
        ],
      }],
    },
  },
  { files: ["packages/{ui,tokens,domain}/**", "ui/**", "tokens/**", "domain/**"], rules: noFetch },
);
