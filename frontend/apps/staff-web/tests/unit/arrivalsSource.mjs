// FEF-028: the Arrivals page is split across app/arrivals/page.tsx and app/components/arrivals/**; source-grep tests
// that used to read the one big page read all of it through this helper (CheckInDialog is a separate, older file).
import { readdirSync, readFileSync, statSync } from "node:fs";
import { join } from "node:path";
import { fileURLToPath } from "node:url";

const APP = fileURLToPath(new URL("../../app/", import.meta.url));
const walk = (d) => readdirSync(d).flatMap((n) => (statSync(join(d, n)).isDirectory() ? walk(join(d, n)) : [join(d, n)]));

export const arrivalsFiles = () => [
  join(APP, "arrivals/page.tsx"),
  ...walk(join(APP, "components/arrivals")).filter((f) => /\.tsx?$/.test(f) && !f.endsWith("CheckInDialog.tsx")),
];
export const arrivalsSource = () => arrivalsFiles().map((f) => readFileSync(f, "utf8")).join("\n");
export const walkInDialogSource = () => readFileSync(join(APP, "components/arrivals/walk-in/WalkInDialog.tsx"), "utf8");
