#!/usr/bin/env python3
"""OPS-060: the capacity review computes headroom, actions and trigger forecasts correctly (selftest on synthetic samples),
the Grafana dashboard is generated from the same queries as the review (fresh), and the runbook names every step."""
import pathlib, subprocess, sys

ROOT = pathlib.Path(__file__).resolve().parents[2]
TOOL = ROOT / "infra/observability/capacity/capacity_review.py"
for args in (["selftest"], ["dashboard", "--check"]):
    r = subprocess.run([sys.executable, str(TOOL), *args], capture_output=True, text=True)
    assert r.returncode == 0, f"{args}: {r.stdout}{r.stderr}"
rb = (ROOT / "docs/runbooks/capacity-review.md").read_text()
for must in ("capacity_review.py pull", "capacity_review.py report", "--ticket", "perf/run.sh rush", "Read replica", "Realtime extraction", "2x headroom"):
    assert must in rb, must
ds = (ROOT / "infra/observability/stack/grafana/provisioning/datasources/nabd.yaml").read_text()
assert "uid: cloudwatch" in ds  # the dashboard's CloudWatch panels need it
print("ops060 capacity review: ok")
