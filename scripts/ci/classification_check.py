#!/usr/bin/env python3
"""PLT-052 data-classification gate: every column in the public, identity and consent schemas carries a tier
in db/classification.yaml (standard: docs/security/data-classification.md).

Usage (repo root):
  classification_check.py [--base REF] [FILE ...]   static: columns that the migrations ADDED since REF (or FILEs) create
  classification_check.py --db POSTGRES_URL         full: information_schema of a migrated DB == the file (no untagged, no stale)
shortcut: regex DDL scan like ci/migration-lint/lint.py, not a SQL parser; the --db mode in CI is the backstop.
"""
import os, re, subprocess, sys
import yaml

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "..", "ci", "migration-lint"))
from lint import statements, git, VFILE  # same statement splitter and added-file logic as the migration lint

FILE = "db/classification.yaml"
DOC = "docs/security/data-classification.md"
TIERS = {"phi", "pii", "financial", "operational"}
SCHEMAS = ("public", "identity", "consent")
NOT_COLUMN = re.compile(r"(constraint|primary|unique|check|foreign|exclude|like)\b", re.I)


def load(path=FILE):
    """{schema.table.column: tier}; raises ValueError on an unknown tier."""
    doc = yaml.safe_load(open(path)) or {}
    out = {}
    for sch, tables in (doc.get("schemas") or {}).items():
        for t, cols in (tables or {}).items():
            for c, tier in (cols or {}).items():
                if tier not in TIERS:
                    raise ValueError(f"{sch}.{t}.{c}: unknown tier {tier!r} (allowed: {sorted(TIERS)})")
                out[f"{sch}.{t}.{c}"] = tier
    return out


def qualify(name):
    parts = name.replace('"', "").lower().split(".")
    return ("public", parts[0]) if len(parts) == 1 else (parts[-2], parts[-1])


def split_top(body):
    depth, cur, out = 0, "", []
    for ch in body:
        depth += ch == "("
        depth -= ch == ")"
        if ch == "," and depth == 0:
            out.append(cur); cur = ""
        else:
            cur += ch
    return out + [cur]


def added_columns(sql):
    """[(schema, table, column)] that CREATE TABLE / ALTER TABLE ADD COLUMN / RENAME COLUMN introduce."""
    out = []
    for s in statements(sql):
        low = " ".join(s.split())
        m = re.match(r"create\s+(?:(?:global|local)\s+)?(?:temp(?:orary)?\s+|unlogged\s+)?table\s+(?:if\s+not\s+exists\s+)?([\w\".]+)\s*(.*)", low, re.I | re.S)
        if m:
            sch, t = qualify(m.group(1))
            rest = m.group(2)
            if re.match(r"partition\s+of\b", rest, re.I) or not rest.startswith("("):
                continue  # partitions inherit the parent's columns (and tags)
            body, depth = "", 0
            for ch in rest:
                depth += ch == "("
                depth -= ch == ")"
                if depth == 0:
                    break
                body += ch
            for col in split_top(body[1:]):
                col = col.strip()
                if col and not NOT_COLUMN.match(col):
                    out.append((sch, t, col.split()[0].replace('"', "").lower()))
            continue
        m = re.match(r"alter\s+table\s+(?:if\s+exists\s+)?(?:only\s+)?([\w\".]+)\s+(.*)", low, re.I | re.S)
        if m:
            sch, t = qualify(m.group(1))
            for part in split_top(m.group(2)):
                a = re.match(r"\s*add\s+(?:column\s+)?(?:if\s+not\s+exists\s+)?(\"?\w+\"?)", part, re.I)
                if a and not NOT_COLUMN.match(a.group(1)):
                    out.append((sch, t, a.group(1).replace('"', "").lower()))
                r = re.match(r"\s*rename\s+(?:column\s+)?\"?\w+\"?\s+to\s+\"?(\w+)\"?", part, re.I)
                if r:
                    out.append((sch, t, r.group(1).lower()))
    return [c for c in out if c[0] in SCHEMAS]


def check_files(files, tags):
    return [f"{f}: {s}.{t}.{c} has no tier in {FILE}" for f in files for s, t, c in added_columns(open(f).read())
            if f"{s}.{t}.{c}" not in tags]


def db_columns(url):
    q = ("SELECT n.nspname||'.'||k.relname||'.'||a.attname FROM pg_attribute a JOIN pg_class k ON k.oid = a.attrelid "
         "JOIN pg_namespace n ON n.oid = k.relnamespace WHERE k.relkind IN ('r','p') AND NOT k.relispartition "
         f"AND a.attnum > 0 AND NOT a.attisdropped AND n.nspname IN ({','.join(repr(s) for s in SCHEMAS)})")
    return set(subprocess.run(["psql", url, "-Atc", q], capture_output=True, text=True, check=True).stdout.split())


def check_db(cols, tags):
    return ([f"{c}: column has no tier in {FILE}" for c in sorted(cols - tags.keys())] +
            [f"{c}: tagged in {FILE} but not in the schema (remove the stale tag)" for c in sorted(tags.keys() - cols)])


def main(argv):
    base, url, files = None, None, []
    it = iter(argv)
    for a in it:
        if a == "--base": base = next(it)
        elif a == "--db": url = next(it)
        else: files.append(a)
    tags = load()
    if url:
        problems = check_db(db_columns(url), tags)
    else:
        if not files:
            base = base or next((r for r in ("origin/main", "main", "develop-initial")
                                 if subprocess.run(["git", "rev-parse", "--verify", "-q", r], capture_output=True).returncode == 0), "HEAD")
            files = [f for f in git("diff", "--name-only", "--diff-filter=A", f"{base}...HEAD").split()
                     if f.startswith("db/migrations/") and VFILE.match(os.path.basename(f))]
        problems = check_files(files, tags)
    for p in problems:
        print(f"{p}  -> {DOC}#tagging-columns")
    print(f"classification-check: {len(tags)} tagged column(s), {len(problems)} problem(s)")
    return 1 if problems else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
