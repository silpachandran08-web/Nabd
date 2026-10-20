#!/usr/bin/env python3
"""OPS-033 stage 2 architecture checks (run from repo root).
  arch.py scan                     SQL scans only, one line per violation with file:line
  arch.py summary JAVA_JSON [OUT]  markdown report (counts by module) for the PR comment; OUT = full JSON report
  arch.py gate    JAVA_JSON        exit 1 when the current mode blocks (see ci/architecture-gate.json)
  arch.py freeze  JAVA_JSON        rewrite ci/architecture-baseline.json from the current violations
JAVA_JSON is backend/target/architecture-violations.json written by ArchitectureRulesTest (Modulith + ArchUnit).
SQL scans: tenant-predicate (SQL on a tenant_id/RLS table without a tenant_id predicate) and sql-ownership
(writes only own tables, reads only own + allow-listed tables, docs/architecture/table-ownership.yaml; PLT-033: every
table in public/identity/consent has exactly one owner, a module may span several packages via `packages:`).
shortcut: regex over Java string literals, not a SQL parser; SQL built across methods or via StringBuilder is not
followed. Upgrade to a JSqlParser-based scan in the ArchUnit test if false positives/negatives start to matter.
"""
import collections, datetime as dt, glob, json, os, re, sys
from pathlib import Path

SRC = "backend/src/main/java/com/nabd/hms"
MIG = "db/migrations"
OWNERSHIP = "docs/architecture/table-ownership.yaml"
EXEMPT = "backend/src/main/resources/rls-exemptions.txt"
GATE = "ci/architecture-gate.json"
BASELINE = "ci/architecture-baseline.json"
CHECKS = ("modulith", "archunit", "sql-ownership", "tenant-predicate")
today = lambda: dt.date.fromisoformat(os.environ.get("GATE_TODAY", dt.date.today().isoformat()))
I = re.I | re.S


# ---------- schema from migrations ----------
def schema(root="."):
    """(all tables, tenant tables): tenant = public table with a tenant_id column, minus rls-exemptions.txt."""
    files = sorted(glob.glob(f"{root}/{MIG}/V*__*.sql"), key=lambda p: int(re.match(r"V(\d+)", os.path.basename(p)).group(1)))
    tables, tenant = set(), set()
    for f in files:
        sql = re.sub(r"--[^\n]*", " ", open(f).read())
        for m in re.finditer(r"create\s+table\s+(?:if\s+not\s+exists\s+)?([\w.\"]+)\s*\((.*?)\)\s*(?:partition[^;]*)?;", sql, I):
            t = norm(m.group(1)); tables.add(t)
            if re.search(r"(^|[(,\s])tenant_id\s", m.group(2), I): tenant.add(t)
        for m in re.finditer(r"alter\s+table\s+(?:if\s+exists\s+)?(?:only\s+)?([\w.\"]+)\s+add\s+column\s+(?:if\s+not\s+exists\s+)?tenant_id\b", sql, I):
            tenant.add(norm(m.group(1)))
        for m in re.finditer(r"drop\s+table\s+(?:if\s+exists\s+)?([\w.\"]+)", sql, I):
            tables.discard(norm(m.group(1))); tenant.discard(norm(m.group(1)))
    exempt = set()
    if os.path.exists(f"{root}/{EXEMPT}"):
        for ln in open(f"{root}/{EXEMPT}"):
            ln = ln.split("#")[0].strip()
            if ln: exempt.add(norm(ln))
    return tables, {t for t in tenant if "." not in t} - exempt


def norm(name):
    n = name.replace('"', "").lower()
    return n[7:] if n.startswith("public.") else n


# ---------- Java string literals -> SQL statements ----------
TOK = re.compile(r'//[^\n]*|/\*.*?\*/|"""(.*?)"""|"((?:\\.|[^"\\\n])*)"|\'(?:\\.|[^\'\\])*\'|([A-Za-z_]\w*)|(\+=|[;{}=])|\s+|.', re.S)


def statements(src):
    """Yield (line, sql_text) for each Java statement whose concatenated literals form SQL. `String X = ...` declarations
    are not reported themselves; X is inlined where it is used (so `"SELECT ..." + WHERE_TENANT` and `sql += ...` work)."""
    toks, line = [], 1
    for m in TOK.finditer(src):
        s = m.group(0)
        if m.group(1) is not None:  # text block: report the line its SQL starts on
            toks.append(("str", m.group(1), line + m.group(1)[:len(m.group(1)) - len(m.group(1).lstrip())].count("\n")))
        elif m.group(2) is not None:
            toks.append(("str", m.group(2), line))
        elif m.group(3): toks.append(("id", s, line))
        elif m.group(4): toks.append(("op", s, line))
        line += s.count("\n")
    stmts, cur = [], []
    for t in toks:
        if t[0] == "op" and t[1] in ";{}":
            if cur: stmts.append(cur)
            cur = []
        else: cur.append(t)
    if cur: stmts.append(cur)
    consts = {}
    for _ in range(2):  # pass 1 learns constants declared below their use
        out = []
        for st in stmts:
            decl = next((i for i in range(len(st) - 2) if st[i][:2] == ("id", "String") and st[i + 1][0] == "id" and st[i + 2][1] == "="), None)
            app = next((i for i in range(len(st) - 1) if st[i][0] == "id" and st[i + 1][1] == "+="), None)
            body = st[decl + 3:] if decl is not None else st[app + 2:] if app is not None else st
            text = "".join(v if k == "str" else consts.get(v, "") for k, v, ln in body if k == "str" or (k == "id" and v in consts))
            lines = [ln for k, v, ln in body if k == "str" or (k == "id" and v in consts)]
            if decl is not None: consts[st[decl + 1][1]] = text
            elif app is not None: consts[st[app][1]] = consts.get(st[app][1], "") + text
            elif lines and is_sql(text): out.append((lines[0], text))
    return out


def is_sql(t):
    return bool(re.match(r"\s*(?:(?:select|with)\b.*\bfrom\b|insert\s+into\b|update\s+[\w.\"]+\s+(?:\w+\s+)?set\b|delete\s+from\b|merge\s+into\b)", t, I))


def touched(sql, tables):
    w = {norm(m.group(1)) for m in re.finditer(r"\b(?:insert\s+into|merge\s+into|delete\s+from|(?<!do\s)(?<!for\s)update)\s+([\w.\"]+)", sql, I)}
    r = {norm(m.group(1)) for m in re.finditer(r"\b(?:from|join|using)\s+([\w.\"]+)(?!\s*\()", sql, I)}
    return w & tables, (r - w) & tables


def has_tenant_predicate(sql):
    if re.match(r"\s*insert\b", sql, I): return re.search(r"\btenant_id\b", sql, I) is not None
    return re.search(r"\btenant_id\s*(?:=|in\b|is\s+not\s+distinct\s+from)", sql, I) is not None


# ---------- ownership yaml ----------
def ownership(root="."):
    """(owner, reads, owners, package_module): owners lists every module claiming a table (more than one is a violation);
    package_module maps a Java package under com.nabd.hms to its module (`packages:`, default the module name)."""
    import yaml
    mods = (yaml.safe_load(open(f"{root}/{OWNERSHIP}")) or {}).get("modules") or {}
    owner, owners, pkg = {}, collections.defaultdict(list), {}
    for mod, spec in mods.items():
        for t in (spec or {}).get("owns") or []: owner[norm(t)] = mod; owners[norm(t)].append(mod)
        for p in (spec or {}).get("packages") or [mod]: pkg[p] = mod
    reads = {mod: {norm(t) for t in ((spec or {}).get("reads") or [])} for mod, spec in mods.items()}
    return owner, reads, owners, pkg


MAPPED_SCHEMAS = ("public", "identity", "consent")  # PLT-033: every table here needs exactly one owner


def map_gaps(tables, owners):
    """PLT-033: tables in public/identity/consent with no owner or several owners in table-ownership.yaml."""
    out = []
    for t in sorted(tables):
        if (t.split(".")[0] if "." in t else "public") not in MAPPED_SCHEMAS: continue
        o = owners.get(t, [])
        if len(o) != 1:
            out.append(v("sql-ownership", o[0] if o else "app", OWNERSHIP, 0,
                         f"table {t} " + (f"has {len(o)} owners in table-ownership.yaml: {', '.join(o)}" if o else "has no owner in table-ownership.yaml")))
    return out


def scan(root="."):
    tables, tenant = schema(root)
    owner, reads, owners, pkg = ownership(root)
    out = map_gaps(tables, owners)
    for f in sorted(glob.glob(f"{root}/{SRC}/**/*.java", recursive=True)):
        rel = os.path.relpath(f, root)
        parts = os.path.relpath(f, f"{root}/{SRC}").split(os.sep)
        mod = pkg.get(parts[0], parts[0]) if len(parts) > 1 else "app"
        for line, sql in statements(Path(f).read_text(encoding="utf-8")):
            w, r = touched(sql, tables)
            tt = sorted((w | r) & tenant)
            if tt and not has_tenant_predicate(sql):
                out.append(v("tenant-predicate", mod, rel, line, "no tenant_id predicate on " + ",".join(tt)))
            for t in sorted(w):
                if owner.get(t) != mod:
                    out.append(v("sql-ownership", mod, rel, line, f"writes {t} " + (f"owned by {owner[t]}" if t in owner else "(not in table-ownership.yaml)")))
            for t in sorted(r):
                if owner.get(t) != mod and t not in reads.get(mod, set()):
                    out.append(v("sql-ownership", mod, rel, line, f"reads {t} " + (f"owned by {owner[t]}, not in {mod} reads allow-list" if t in owner else "(not in table-ownership.yaml)")))
    return out


def v(rule, mod, file, line, detail):
    return {"tool": "sql", "rule": rule, "module": mod, "file": file, "line": line, "detail": detail}


# ---------- merge, baseline, gate ----------
def check_of(x): return x["tool"] if x["tool"] in ("modulith", "archunit") else x["rule"]
def key(x): return "|".join([check_of(x), x["rule"], x["module"], x.get("file", ""), re.sub(r"\.java:\d+", ".java", x["detail"])])


def collect(java_json, root="."):
    java = json.load(open(java_json)) if java_json and os.path.exists(java_json) else \
        [{"tool": "archunit", "rule": "report-missing", "module": "app", "detail": f"{java_json} not found: ArchitectureRulesTest did not run"}]
    return java + scan(root)


def load_baseline(root="."):
    p = f"{root}/{BASELINE}"
    return collections.Counter(json.load(open(p))["violations"]) if os.path.exists(p) else collections.Counter()


def mode(root="."):
    g = json.load(open(f"{root}/{GATE}"))
    on = lambda d: d is not None and dt.date.fromisoformat(d) <= today()
    return "all" if on(g["all_blocking_from"]) else "new" if on(g["new_code_blocking_from"]) else "report"


def evaluate(vs, base, m):
    cur = collections.Counter(key(x) for x in vs)
    seen, new = collections.Counter(), []
    for x in vs:  # the n-th occurrence of a key beyond its baseline count is new
        k = key(x); seen[k] += 1
        if seen[k] > base.get(k, 0): new.append(x)
    fixed = sum((base - cur).values())
    fails = []
    if m == "all":
        if vs: fails.append(f"{len(vs)} violation(s); mode 'all' allows none")
        if base: fails.append(f"baseline still lists {sum(base.values())} violation(s); it must be empty at the Phase 1 exit")
    elif m == "new" and new:
        fails.append(f"{len(new)} new violation(s) not in {BASELINE}")
    return new, fixed, fails


def where(x): return f"{x['file']}:{x['line']}" if x.get("file") else x["module"]
LABEL = {"report": "report only (S2-S4), never fails", "new": "new violations fail (S5-S7)", "all": "any violation fails (Phase 1 exit, S8)"}


def summary(vs, base, m):
    new, fixed, fails = evaluate(vs, base, m)
    by = collections.defaultdict(collections.Counter)
    for x in vs: by[x["module"]][check_of(x)] += 1
    o = ["<!-- architecture-report -->", f"### Architecture checks (stage 2): mode {m}, {LABEL[m]}", "",
         "| module | " + " | ".join(CHECKS) + " | total |", "|---" * (len(CHECKS) + 2) + "|"]
    for mod in sorted(by): o.append(f"| {mod} | " + " | ".join(str(by[mod][c]) for c in CHECKS) + f" | {sum(by[mod].values())} |")
    tot = collections.Counter(check_of(x) for x in vs)
    o.append("| **all** | " + " | ".join(f"**{tot[c]}**" for c in CHECKS) + f" | **{len(vs)}** |")
    o += ["", f"Trend: baseline {sum(base.values())} -> now {len(vs)} (new {len(new)}, fixed {fixed}). "
          "Run `python3 scripts/ci/arch.py freeze backend/target/architecture-violations.json` to shrink the baseline after fixes.", ""]
    if new:
        o.append(f"New violations ({len(new)}, first 50):")
        o += [f"- `{x['rule']}` {where(x)}: {x['detail'][:300]}" for x in new[:50]]
    o.append("\n" + ("Gate: FAIL: " + "; ".join(fails) if fails else "Gate: pass."))
    return "\n".join(o), fails


def main(a):
    if not a: sys.exit(__doc__)
    if a[0] == "scan":
        vs = scan()
        for x in vs: print(f"{x['rule']}\t{where(x)}\t{x['detail']}")
        print(f"{len(vs)} SQL violation(s)", file=sys.stderr)
        return
    vs = collect(a[1])
    if a[0] == "summary":
        md, _ = summary(vs, load_baseline(), mode())
        print(md)
        if len(a) > 2: json.dump({"date": today().isoformat(), "mode": mode(), "violations": vs}, open(a[2], "w"), indent=1)
    elif a[0] == "gate":
        _, _, fails = evaluate(vs, load_baseline(), mode())
        print("\n".join(fails) or "architecture gate: pass"); sys.exit(1 if fails else 0)
    elif a[0] == "freeze":
        json.dump({"_note": "OPS-033 frozen baseline of existing architecture violations. Shrinks only; must be empty at Phase 1 exit (S8). "
                   "Keys: check|rule|module|file|detail (line numbers stripped); value = occurrences.",
                   "frozen": today().isoformat(), "violations": dict(sorted(collections.Counter(key(x) for x in vs).items()))},
                  open(BASELINE, "w"), indent=1)
        print(f"baseline: {len(vs)} violation(s)")
    else: sys.exit(__doc__)


if __name__ == "__main__": main(sys.argv[1:])
