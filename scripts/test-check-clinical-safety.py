#!/usr/bin/env python3
"""Self-test: real files pass; bad samples fail."""
import importlib.util, pathlib, tempfile, os, sys
root = pathlib.Path(__file__).resolve().parent.parent
spec = importlib.util.spec_from_file_location("c", root / "scripts/check-clinical-safety.py"); c = importlib.util.module_from_spec(spec); spec.loader.exec_module(c)
assert c.check_log(root / "docs/clinical-safety/hazard-log.csv") == []
assert c.check_owners(root / ".github/CODEOWNERS") == []
d = tempfile.mkdtemp()
good = open(root / "docs/clinical-safety/hazard-log.csv").read().splitlines()
bad = "\n".join(good[:1] + [l.replace("CSO (unnamed)", "") if l.startswith("H-03,") else l for l in good[1:] if not l.startswith("H-05,")]) + "\n"
open(d + "/l.csv", "w").write(bad)
e = c.check_log(d + "/l.csv"); assert "missing H-05" in e and "H-03: empty owner" in e, e
bad_ctl = "\n".join(good[:1] + [l.replace("TO TRANSCRIBE (none verified)", "", 1) if l.startswith("H-01,") else l for l in good[1:]]) + "\n"
open(d + "/l2.csv", "w").write(bad_ctl); assert "H-01: empty existing_control" in c.check_log(d + "/l2.csv")
open(d + "/CO", "w").write("/docs/ @x\n"); assert len(c.check_owners(d + "/CO")) == 3
print("self-test OK")
