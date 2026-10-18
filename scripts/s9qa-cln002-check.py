#!/usr/bin/env python3
"""S9 independent QA for CLN-002. Prints PASS/FAIL/INFO per check; exit 1 if any FAIL. Run from repo root."""
import csv, fnmatch, pathlib, re, subprocess, sys, tempfile, shutil
import yaml

root = pathlib.Path(".")
fails = []
def check(name, ok, note=""):
    print(("PASS " if ok else "FAIL ") + name + (f"  [{note}]" if note else ""))
    if not ok: fails.append(name)
def info(msg): print("INFO " + msg)

# --- CODEOWNERS resolver (GitHub semantics: last matching pattern wins; leading / anchors; trailing / = directory) ---
def parse_owners():
    rules = []
    for l in (root / ".github/CODEOWNERS").read_text().splitlines():
        l = l.strip()
        if not l or l.startswith("#"): continue
        pat, *owners = l.split()
        rules.append((pat, owners))
    return rules
def matches(pat, path):
    if pat == "*": return True
    anchored = pat.startswith("/")
    p = pat.lstrip("/")
    if p.endswith("/"):
        return path.startswith(p) if anchored else ("/" + p) in ("/" + path)
    return fnmatch.fnmatchcase(path, p) if anchored else fnmatch.fnmatchcase(path.split("/")[-1], p)
def owners_of(path):
    res = None
    for pat, o in parse_owners():
        if matches(pat, path): res = o
    return res

SAFE = "@nabd/clinical-safety"
for f in ["backend/src/main/java/com/nabd/hms/clinical/RxSafetyChecks.java",
          "backend/src/main/java/com/nabd/hms/clinical/AllergyDrugClasses.java",
          "backend/src/main/resources/clinical-safety/dictionary.json",
          "backend/src/main/java/com/nabd/hms/clinical/cds/Rule1.java",
          "docs/clinical-safety/hazard-log.csv"]:
    check(f"AC2 CODEOWNERS resolves {f.split('/')[-1]} to clinical-safety", owners_of(f) == [SAFE], str(owners_of(f)))
check("AC2 other clinical file is NOT forced to CSO", owners_of("backend/src/main/java/com/nabd/hms/clinical/PrescriptionService.java") == ["@nabd/clinical-owners"])
# real files exist where the patterns point
for f in ["backend/src/main/java/com/nabd/hms/clinical/RxSafetyChecks.java", "backend/src/main/java/com/nabd/hms/clinical/AllergyDrugClasses.java"]:
    check(f"AC2 safety path exists on disk: {f.split('/')[-1]}", (root / f).exists())
check("AC2 CODEOWNERS is in .github/ (GitHub-recognised location)", (root / ".github/CODEOWNERS").exists())
info("CODEOWNERS enforcement needs branch protection 'Require review from Code Owners' + the team to exist: Needs external action")
bp = (root / ".github/branch-protection.json")
if bp.exists():
    import json
    j = json.loads(bp.read_text()); txt = json.dumps(j)
    check("AC2 branch-protection config requires code-owner review", "require_code_owner_reviews" in txt and '"require_code_owner_reviews": true' in txt.replace("true", "true"), "file only; applying it is external")

# --- label rule ---
lab = yaml.safe_load((root / ".github/clin-safety-labeler.yml").read_text())
check("label config has [CLIN-SAFETY] key", "[CLIN-SAFETY]" in lab)
globs = lab["[CLIN-SAFETY]"][0]["changed-files"][0]["any-glob-to-any-file"]
for f in ["backend/src/main/java/com/nabd/hms/clinical/RxSafetyChecks.java", "backend/src/main/resources/clinical-safety/x.json",
          "backend/src/main/java/com/nabd/hms/clinical/cds/R.java", "docs/clinical-safety/hazard-log.csv"]:
    ok = any(fnmatch.fnmatch(f, g.replace("**", "*")) for g in globs)
    check(f"label globs cover {f.split('/')[-1]}", ok)
check("label globs do not cover unrelated file", not any(fnmatch.fnmatch("frontend/app/page.tsx", g.replace("**", "*")) for g in globs))
for wf in (root / ".github/workflows").glob("*.yml"):
    try: yaml.safe_load(wf.read_text()); ok = True
    except Exception as e: ok = False
    check(f"workflow parses: {wf.name}", ok)
wfl = yaml.safe_load((root / ".github/workflows/clin-safety-label.yml").read_text())
step = wfl["jobs"]["label"]["steps"][0]
check("label workflow points at the existing config", (root / step["with"]["configuration-path"]).exists())
check("label workflow does not check out untrusted PR code (pull_request_target safety)", all("checkout" not in s.get("uses", "") for s in wfl["jobs"]["label"]["steps"]))

# --- hazard log ---
rows = list(csv.DictReader(open(root / "docs/clinical-safety/hazard-log.csv", newline="")))
ids = [r["id"] for r in rows]
for n in range(1, 9): check(f"AC1 hazard log has H-0{n}", f"H-0{n}" in ids)
srcs = " ".join(r["source"] for r in rows)
for q in ["QA-05", "QA-14", "QA-16", "QA-17"]: check(f"AC1 hazard log has an entry for {q}", q in srcs)
cols = ["hazard", "cause", "existing_control", "planned_control", "verifying_test", "residual_risk", "owner"]
check("AC3 every row has all columns non-empty", all((r[c] or "").strip() for r in rows for c in cols))
ph = re.compile(r"TO TRANSCRIBE|UNRATED|unnamed|NOT YET", re.I)
placeholder_rows = [r["id"] for r in rows if any(ph.search(r[c] or "") for c in ["hazard", "cause", "existing_control"])]
check("AC3 rows carry real content (no TO TRANSCRIBE placeholders in hazard/cause/control)", not placeholder_rows, f"{len(placeholder_rows)}/{len(rows)} rows are placeholders: {placeholder_rows}")
unrated = [r["id"] for r in rows if "UNRATED" in r["residual_risk"]]
info(f"residual risk unrated on {len(unrated)}/{len(rows)} rows (CSO action)")
check("AC3 owner is a person/role not 'unnamed'", not any("unnamed" in r["owner"].lower() for r in rows), "owner is placeholder 'CSO (unnamed)'")
check("AC5 closed rows must name closing_pr (data)", all(r["closing_pr"].strip() for r in rows if r["status"] == "closed"))

# --- AC1 CSO named ---
readme = (root / "docs/clinical-safety/README.md").read_text()
named = "NOT YET NAMED" not in readme
check("AC1 CSO is named with contract dates", named, "README says NOT YET NAMED; external action (hire/contract)")

# --- AC4 minutes ---
check("AC4 minutes template exists with decisions + hazard-log diff sections", (root / "docs/clinical-safety/reviews/TEMPLATE.md").exists()
      and "Hazard-log diff" in (root / "docs/clinical-safety/reviews/TEMPLATE.md").read_text())
info("AC4 fortnightly review actually occurring + minutes within 2 working days: Needs external action (a review must happen)")

# --- lint behaviour on bad/good samples ---
def lint(mutator):
    d = pathlib.Path(tempfile.mkdtemp())
    (d / "docs/clinical-safety").mkdir(parents=True); (d / ".github").mkdir()
    shutil.copy(root / ".github/CODEOWNERS", d / ".github/CODEOWNERS")
    fieldnames = list(rows[0].keys()); rs = [dict(r) for r in rows]
    rs = mutator(rs)
    with open(d / "docs/clinical-safety/hazard-log.csv", "w", newline="") as f:
        w = csv.DictWriter(f, fieldnames=fieldnames); w.writeheader(); w.writerows(rs)
    p = subprocess.run([sys.executable, str(root / "scripts/check-clinical-safety.py"), str(d)], capture_output=True, text=True)
    shutil.rmtree(d); return p.returncode, p.stdout
rc, _ = lint(lambda r: r); check("lint passes on current log", rc == 0)
rc, out = lint(lambda r: [x for x in r if x["id"] != "H-03"]); check("lint fails when H-03 removed", rc != 0, out.strip())
rc, out = lint(lambda r: [dict(x, owner="") if x["id"] == "H-09" else x for x in r]); check("lint fails on empty owner", rc != 0, out.strip())
rc, out = lint(lambda r: [dict(x, verifying_test=" ") if x["id"] == "H-09" else x for x in r]); check("lint fails on blank verifying_test", rc != 0, out.strip())
rc, out = lint(lambda r: [dict(x, status="closed", closing_pr="") if x["id"] == "H-09" else x for x in r]); check("lint fails closed without PR", rc != 0, out.strip())
rc, out = lint(lambda r: r + [dict(r[0])]); check("lint fails on duplicate id", rc != 0, out.strip())
rc, out = lint(lambda r: [dict(x, hazard="") if x["id"] == "H-10" else x for x in r]); check("lint fails on empty hazard", rc != 0, out.strip())
rc, out = lint(lambda r: [dict(x, cause="") if x["id"] == "H-10" else x for x in r]); check("lint fails on empty cause", rc != 0, out.strip())
# weakness probe: does lint accept pure placeholder text as 'filled'?
rc, out = lint(lambda r: [dict(x, cause="TO TRANSCRIBE", existing_control="TBD") if x["id"] == "H-10" else x for x in r])
check("lint rejects placeholder-only content", rc != 0, "lint accepts 'TO TRANSCRIBE'/'TBD' as filled (AC3 is only structural)")

print("\nFAILS:", len(fails)); [print(" -", f) for f in fails]
sys.exit(1 if fails else 0)
