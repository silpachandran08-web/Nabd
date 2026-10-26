// FEF-018 / T-114: no copy of the API base URL, no page-level authedFetch and no hand-parsed `await res.json()` in app/.
// Everything goes through packages/api-client (lib/api.ts, platform/platformSession.ts). Files that still carry
// one of them are listed in raw-api-allowlist.json: the list may only shrink (a listed file without a hit also fails).
import { readFileSync, readdirSync, statSync } from "node:fs";
import { dirname, join, relative } from "node:path";
import { fileURLToPath } from "node:url";

const PATTERNS = [/\bAPI_BASE\b/, /\bauthedFetch\b/, /await res\.json\(\)/];

function* walk(dir) {
  for (const n of readdirSync(dir)) {
    if (n === "node_modules" || n === ".next") continue;
    const p = join(dir, n);
    if (statSync(p).isDirectory()) yield* walk(p);
    else if (/\.(ts|tsx)$/.test(n)) yield p;
  }
}

/** Files under `root` with a hit, as paths relative to `base`. Comment-only lines do not count. */
export function scan(root, base = root) {
  const hits = [];
  for (const f of walk(root)) {
    const bad = readFileSync(f, "utf8").split("\n").some((l) => !/^\s*(\/\/|\*|\/\*)/.test(l) && PATTERNS.some((re) => re.test(l)));
    if (bad) hits.push(relative(base, f).split("\\").join("/"));
  }
  return hits.sort();
}

export function evaluate(hits, allow) {
  return {
    newHits: hits.filter((h) => !allow.includes(h)),
    stale: allow.filter((a) => !hits.includes(a)),
  };
}

if (process.argv[1] === fileURLToPath(import.meta.url)) {
  const dir = dirname(fileURLToPath(import.meta.url));
  const app = join(dir, "..", "app");
  const allow = JSON.parse(readFileSync(join(dir, "raw-api-allowlist.json"), "utf8")).files;
  const { newHits, stale } = evaluate(scan(app, join(dir, "..")), allow);
  for (const f of newHits) console.error(`raw API access (API_BASE / authedFetch / await res.json()): ${f}. Use lib/api.ts (api) instead.`);
  for (const f of stale) console.error(`${f} is clean now: remove it from scripts/raw-api-allowlist.json.`);
  if (newHits.length || stale.length) process.exit(1);
  console.log(`check:raw-api OK (${allow.length} files still on the allowlist)`);
}
