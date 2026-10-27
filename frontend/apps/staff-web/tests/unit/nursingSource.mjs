// FEF-031: the nursing screen is split across app/(clinic)/nursing/page.tsx and app/components/nursing/**; source-grep tests
// that used to read the one big page read all of it through this helper.
import { readdirSync, readFileSync, statSync } from "node:fs";
import { join } from "node:path";
import { fileURLToPath } from "node:url";

const APP = fileURLToPath(new URL("../../app/", import.meta.url));
const walk = (d) => readdirSync(d).flatMap((n) => (statSync(join(d, n)).isDirectory() ? walk(join(d, n)) : [join(d, n)]));

export const nursingFiles = () => [join(APP, "(clinic)/nursing/page.tsx"), ...walk(join(APP, "components/nursing")).filter((f) => /\.tsx?$/.test(f) && !f.endsWith("VaccinePanel.tsx"))]; // VaccinePanel is an older file with its own fetcher prop
export const nursingSource = () => nursingFiles().map((f) => readFileSync(f, "utf8")).join("\n");
