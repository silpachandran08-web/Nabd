// FEF-049 pure helpers (no browser): role permissions, axe filtering/report, sharding. Unit-tested in tests/unit.
import { readFileSync } from 'node:fs';

export const ROLES = JSON.parse(readFileSync(new URL('./roles.json', import.meta.url)));
const FLAGS = ['view', 'create', 'edit', 'delete', 'approve', 'refundDiscount', 'export'];

/** Permission strings a role's JWT carries (same flattening as GrantsFlattener: module:flag). */
export function permsFor(role) {
  const r = ROLES[role];
  if (!r) throw new Error(`unknown role ${role}`);
  if (r.full) return r.full.flatMap((m) => FLAGS.map((f) => `${m}:${f}`));
  return Object.entries(r.grants).flatMap(([m, fs]) => fs.map((f) => `${m}:${f}`));
}

export const BLOCKING_IMPACTS = ['critical', 'serious'];
/** Keeps only blocking violations, trimmed to what a reader needs. */
export function blockingViolations(violations) {
  return violations.filter((v) => BLOCKING_IMPACTS.includes(v.impact))
    .map((v) => ({ id: v.id, impact: v.impact, help: v.help, helpUrl: v.helpUrl, nodes: v.nodes.slice(0, 5).map((n) => `${n.target.join(' ')}${n.any?.[0]?.message ? ` (${n.any[0].message})` : ''}`) }));
}

/**
 * Known pre-existing violations (axe-known.json: rule, selector substring, owner, expires <= 30 days out, reason) do not
 * block until they expire, then they block again. Returns { blocking, known } with node lists split accordingly.
 */
export function applyKnown(violations, known, today) {
  const live = known.filter((k) => k.expires >= today);
  const blocking = [], kept = [];
  for (const v of violations) {
    const isKnown = (n) => live.some((k) => k.rule === v.id && n.includes(k.selector));
    const k = v.nodes.filter(isKnown), b = v.nodes.filter((n) => !isKnown(n));
    if (b.length) blocking.push({ ...v, nodes: b });
    if (k.length) kept.push({ ...v, nodes: k });
  }
  return { blocking, known: kept };
}

/** Human-readable report of the steps that have blocking violations ('' when clean). */
export function axeReport(caseId, entries) {
  const bad = entries.filter((e) => e.violations.length);
  if (!bad.length) return '';
  return bad.map((e) => [`${caseId} · step "${e.step}" (${e.url}): ${e.violations.length} critical/serious axe violation(s)`,
    ...e.violations.map((v) => `  - [${v.impact}] ${v.id}: ${v.help}\n      ${v.nodes.join('\n      ')}\n      ${v.helpUrl}`)].join('\n')).join('\n');
}

/** Round-robin split of cases into shard `index` (1-based) of `total`. */
export function shard(cases, index, total) {
  if (!(index >= 1 && index <= total)) throw new Error(`bad shard ${index}/${total}`);
  return cases.filter((_, i) => i % total === index - 1);
}
