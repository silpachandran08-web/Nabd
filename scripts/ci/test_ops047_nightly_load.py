#!/usr/bin/env python3
"""OPS-047: the nightly load workflow parses, only runs outside OPD hours, and every profile it names exists and passes node's syntax check."""
import pathlib, re, subprocess, sys, shutil, tempfile
import yaml

ROOT = pathlib.Path(__file__).resolve().parents[2]
wf = yaml.safe_load((ROOT / ".github/workflows/nightly-load.yml").read_text())
on = wf.get("on", wf.get(True))
crons = [c["cron"] for c in on["schedule"]]
assert crons == ["0 19 * * 1-6", "0 19 * * 0"], crons  # 19:00 UTC = 00:30 IST / 22:00 KSA
assert "pull_request" not in on
profiles = wf["jobs"]["profiles"]["strategy"]["matrix"]["profile"]
assert wf["jobs"]["profiles"]["strategy"]["max-parallel"] == 1
for p in profiles + ["soak", "wa_burst"]:
    f = ROOT / "perf/k6" / f"{p}.js"
    assert f.exists(), f
    tmp = pathlib.Path(tempfile.mkdtemp()) / "p.mjs"
    shutil.copy(f, tmp)
    node = shutil.which("node") or "/opt/node22/bin/node"
    r = subprocess.run([node, "--check", str(tmp)], capture_output=True, text=True)
    assert r.returncode == 0, f"{f}: {r.stderr}"
body = (ROOT / ".github/workflows/nightly-load.yml").read_text()
assert "performance-defect" in body and "failure()" in body  # a red run opens a defect
assert not re.search(r"(?i)pin:\s*['\"]?\d{4,}", body)  # no literal PINs
print("ops047 nightly load: ok,", len(profiles) + 2, "profiles")
