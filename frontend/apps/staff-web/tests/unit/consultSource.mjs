// FEF-030: the consult screen is split across app/(clinic)/consult/page.tsx and app/components/consult/**; source-grep tests that used to
// read the one big page read the page and the feature folders through this helper.
import { readdirSync, readFileSync, statSync } from "node:fs";
import { join } from "node:path";
import { fileURLToPath } from "node:url";

export const APP = fileURLToPath(new URL("../../app/", import.meta.url));
export const FEATURE_DIRS = ["visit", "note", "rx", "queue", "closeout", "amend", "transfer", "timeline", "workflow", "specialty", "orders"];
const walk = (d) => readdirSync(d).flatMap((n) => (statSync(join(d, n)).isDirectory() ? walk(join(d, n)) : [join(d, n)]));

export const consultPage = () => join(APP, "(clinic)/consult/page.tsx");
/** The page plus every file of the split feature folders. */
export const consultFiles = () => [consultPage(), ...FEATURE_DIRS.flatMap((d) => walk(join(APP, "components/consult", d)))].filter((f) => /\.tsx?$/.test(f));
export const consultSource = () => consultFiles().map((f) => readFileSync(f, "utf8")).join("\n");
