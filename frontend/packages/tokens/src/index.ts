// FEF-022: design tokens. Source of truth is src/source/*.json; `pnpm build` (build.mjs) emits dist/{tokens.css,custom-media.css,theme.ts,contrast-report.json}.
export { theme } from "../dist/theme";
export type { NabdTheme } from "../dist/theme";
