#!/usr/bin/env python3
"""PLT-019 CI metric: explicit `UUID tenantId` parameters on service methods (*Service.java under backend/src/main).
The tenant is bound at transaction start (shared/tenancy), so these parameters are being removed module by module.
  tenant_params.py [SRC_ROOT] [--zero MODULE ...]   prints {"total": n, "by_module": {...}}; exit 1 if a --zero module > 0
"""
import json, pathlib, re, sys

# a method declaration (not a call): modifiers/return type, name, '(' params ')' then '{' or 'throws'
DECL = re.compile(r"^\s*(?:(?:public|protected|private|static|final|synchronized)\s+)*(?:<[^>]+>\s+)?[\w.<>\[\], ?]+\s+(\w+)\s*\(([^)]*)\)\s*(?:throws [\w., ]+)?\s*\{", re.M)
PARAM = re.compile(r"\bUUID\s+tenantId\b")


def count(src):
    by = {}
    for f in sorted(pathlib.Path(src).rglob("*Service.java")):
        n = sum(1 for m in DECL.finditer(f.read_text()) if m.group(1) not in ("if", "for", "while", "switch", "catch") and PARAM.search(m.group(2)))
        mod = f.relative_to(src).parts[0] if len(f.relative_to(src).parts) > 1 else "(root)"
        by[mod] = by.get(mod, 0) + n
    return {"total": sum(by.values()), "by_module": by}


def main(argv):
    zero = argv[argv.index("--zero") + 1:] if "--zero" in argv else []
    pos = argv[:argv.index("--zero")] if "--zero" in argv else argv
    src = pos[0] if pos else "backend/src/main/java/com/nabd/hms"
    r = count(src)
    print(json.dumps(r, indent=1, sort_keys=True))
    bad = [m for m in zero if r["by_module"].get(m, 0) > 0]
    if bad:
        print("modules migrated to @CurrentTenant still take tenantId: " + ", ".join(bad), file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
