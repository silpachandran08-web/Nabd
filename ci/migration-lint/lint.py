#!/usr/bin/env python3
"""Migration lint (OPS-002). Rules MIG-001..MIG-007, see docs/architecture/migration-playbook.md.

Usage: lint.py [--base REF] [--versions FILE] [FILE ...]
No FILE: lints migrations ADDED since REF (default origin/main, else develop-initial).
Env CONTRACT_REF=<earlier expand release/PR> lets destructive DDL pass (MIG-007).
shortcut: regex statement scan, not a SQL parser; upgrade to squawk if rules outgrow it.
"""
import os, re, subprocess, sys

DOC = "docs/architecture/migration-playbook.md"
LINK = {"MIG-001": "#concurrent-indexes", "MIG-002": "#check-constraints", "MIG-003": "#set-not-null",
        "MIG-004": "#lock-timeout", "MIG-005": "#version-numbers", "MIG-006": "#version-numbers",
        "MIG-007": "#expand-and-contract"}
VFILE = re.compile(r"^V(\d+)__.+\.sql$")
# A file may waive MIG-001 with "-- migration-lint: allow MIG-001 <reason>": CONCURRENTLY cannot run in our Flyway
# transaction (it hangs the migrate), so a plain index on a small/new-in-sprint table is a reviewed exception.
WAIVABLE = {"MIG-001"}
WAIVER = re.compile(r"--\s*migration-lint:\s*allow[ \t]+(MIG-\d{3})[ \t]+\S", re.I)


def statements(sql):
    sql = re.sub(r"/\*.*?\*/", " ", sql, flags=re.S)
    sql = re.sub(r"--[^\n]*", " ", sql)
    sql = re.sub(r"(\$\w*\$).*?\1", " ", sql, flags=re.S)  # function/DO bodies are not DDL we lint
    return [s.strip() for s in sql.split(";") if s.strip()]


def ident(n):
    return n.replace('"', "").split(".")[-1].lower()


def lint_sql(sql, contract=False):
    """Return [(rule, message)]."""
    waived = {r.upper() for r in WAIVER.findall(sql)} & WAIVABLE
    return [(r, m) for r, m in _lint_sql(sql, contract) if r not in waived]


def _lint_sql(sql, contract):
    out, stmts = [], statements(sql)
    created = {ident(m.group(1)) for s in stmts for m in
               [re.match(r"create\s+(?:unlogged\s+)?table\s+(?:if\s+not\s+exists\s+)?([\w\".]+)", s, re.I)] if m}
    if not any(re.match(r"set\s+(?:local\s+)?lock_timeout\b", s, re.I) for s in stmts):
        out.append(("MIG-004", "missing SET lock_timeout (e.g. SET lock_timeout = '5s';)"))
    seen_checks, validated = [], False  # IS NOT NULL checks added so far, and whether one was validated
    for s in stmts:
        low = " ".join(s.split())
        m = re.match(r"create\s+(?:unique\s+)?index\s+(?!concurrently)(?:if\s+not\s+exists\s+)?[\w\".]+\s+on\s+(?:only\s+)?([\w\".]+)", low, re.I)
        if m and ident(m.group(1)) not in created:
            out.append(("MIG-001", f"CREATE INDEX without CONCURRENTLY on existing table {ident(m.group(1))}"))
        m = re.match(r"alter\s+table\s+(?:if\s+exists\s+)?(?:only\s+)?([\w\".]+)\s+(.*)", low, re.I | re.S)
        if not m:
            if re.match(r"drop\s+table\b", low, re.I) and not contract:
                out.append(("MIG-007", "DROP TABLE needs the contract label (CONTRACT_REF)"))
            continue
        t, rest = ident(m.group(1)), m.group(2)
        if t in created:
            continue
        for c in re.finditer(r"add\s+constraint\s+\S+\s+check\s*\((.*?)\)\s*(not\s+valid)?\s*(?:,|$)", rest, re.I):
            if not c.group(2):
                out.append(("MIG-002", f"ADD CONSTRAINT CHECK without NOT VALID on {t}"))
            nn = re.fullmatch(r"\s*\"?(\w+)\"?\s+is\s+not\s+null\s*", c.group(1), re.I)
            if nn:
                seen_checks.append((t, nn.group(1).lower()))
        if re.search(r"validate\s+constraint", rest, re.I):
            validated = True
        for c in re.finditer(r"alter\s+column\s+\"?(\w+)\"?\s+set\s+not\s+null", rest, re.I):
            if not (validated and (t, c.group(1).lower()) in seen_checks):
                out.append(("MIG-003", f"SET NOT NULL on {t}.{c.group(1)} without a prior validated CHECK ({c.group(1)} IS NOT NULL)"))
        if not contract and re.search(r"drop\s+column\b|alter\s+column\s+\S+\s+(?:set\s+data\s+)?type\b", rest, re.I):
            out.append(("MIG-007", f"DROP COLUMN / ALTER TYPE on {t} needs the contract label (CONTRACT_REF)"))
    return out


def lint_versions(new_files, base_max, versions_text):
    """MIG-005 / MIG-006 over the set of added files: [(file, rule, msg)]."""
    listed = {int(m.group(1)) for l in versions_text.splitlines() if (m := re.match(r"\s*V?(\d+)\b", l)) and not l.lstrip().startswith("#")}
    out, seen = [], set()
    for f in new_files:
        v = int(VFILE.match(os.path.basename(f)).group(1))
        if v <= base_max:
            out.append((f, "MIG-005", f"version V{v} is not greater than highest version on base (V{base_max})"))
        if v in seen:
            out.append((f, "MIG-005", f"duplicate version V{v}"))
        seen.add(v)
        if v not in listed:
            out.append((f, "MIG-006", f"V{v} is not reserved in db/migrations/VERSIONS"))
    return out


def git(*a):
    return subprocess.run(["git", *a], capture_output=True, text=True, check=True).stdout


def main(argv):
    base, versions, files = None, "db/migrations/VERSIONS", []
    it = iter(argv)
    for a in it:
        if a == "--base": base = next(it)
        elif a == "--versions": versions = next(it)
        else: files.append(a)
    if not base:
        base = next((r for r in ("origin/main", "main", "develop-initial") if subprocess.run(["git", "rev-parse", "--verify", "-q", r], capture_output=True).returncode == 0), "HEAD")
    if not files:
        files = [f for f in git("diff", "--name-only", "--diff-filter=A", f"{base}...HEAD").split() if f.startswith("db/migrations/") and f.endswith(".sql")]
    files = [f for f in files if VFILE.match(os.path.basename(f))]
    base_names = [os.path.basename(l) for l in git("ls-tree", "--name-only", base, "db/migrations/").split()]
    base_max = max([int(m.group(1)) for n in base_names if (m := VFILE.match(n))] or [0])
    contract = bool(os.environ.get("CONTRACT_REF", "").strip())
    problems = []
    for f in files:
        problems += [(f, r, m) for r, m in lint_sql(open(f).read(), contract)]
    vt = open(versions).read() if os.path.exists(versions) else ""
    problems += lint_versions(files, base_max, vt)
    for f, r, m in problems:
        print(f"{f}: {r} {m}  -> {DOC}{LINK[r]}")
    print(f"migration-lint: {len(files)} file(s), {len(problems)} problem(s)")
    return 1 if problems else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
