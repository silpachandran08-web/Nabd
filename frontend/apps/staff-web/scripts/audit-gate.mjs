// Fails on high/critical npm advisories unless allowlisted with an unexpired date (SC-32).
import { readFileSync } from "node:fs";
import { spawnSync } from "node:child_process";
import { fileURLToPath } from "node:url";
import { dirname, join } from "node:path";

const BLOCK = new Set(["high", "critical"]);

// audit: `npm audit --json` or `pnpm audit --json` (FEF-012) output; allow: [{id, expires: "YYYY-MM-DD", reason}]
export function evaluate(audit, allow, today = new Date().toISOString().slice(0, 10)) {
  const live = new Set(allow.filter((a) => a.expires >= today).map((a) => a.id));
  const expired = allow.filter((a) => a.expires < today).map((a) => a.id);
  const failures = [];
  for (const [pkg, v] of Object.entries(audit.vulnerabilities ?? {})) {
    for (const via of v.via) {
      if (typeof via !== "object" || !BLOCK.has(via.severity)) continue;
      const id = String(via.url).split("/").pop();
      if (!live.has(id)) failures.push({ pkg, id, severity: via.severity, title: via.title });
    }
  }
  for (const a of Object.values(audit.advisories ?? {})) {
    if (!BLOCK.has(a.severity)) continue;
    const id = a.github_advisory_id ?? String(a.url).split("/").pop();
    if (!live.has(id)) failures.push({ pkg: a.module_name, id, severity: a.severity, title: a.title });
  }
  return { failures, expired };
}

if (process.argv[1] === fileURLToPath(import.meta.url)) {
  const dir = dirname(fileURLToPath(import.meta.url));
  const allow = JSON.parse(readFileSync(join(dir, "..", "audit-allowlist.json"), "utf8"));
  const r = spawnSync("pnpm", ["audit", "--json"], { encoding: "utf8", maxBuffer: 64e6 });
  const { failures, expired } = evaluate(JSON.parse(r.stdout), allow);
  for (const id of expired) console.error(`allowlist entry expired: ${id}`);
  for (const f of failures) console.error(`${f.severity.toUpperCase()} ${f.id} ${f.pkg}: ${f.title}`);
  if (failures.length) process.exit(1);
  console.log("audit gate: no unallowlisted high/critical advisories");
}
