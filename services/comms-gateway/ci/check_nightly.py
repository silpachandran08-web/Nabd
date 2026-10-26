#!/usr/bin/env python3
"""COM-010.3: the nightly workflow parses, is scheduled, and every job posts to the squad channel on failure (never a pager)."""
import pathlib, sys, yaml
root = pathlib.Path(__file__).resolve().parents[3]
text = (root / ".github/workflows/comms-sandbox-nightly.yml").read_text()
wf = yaml.safe_load(text)
assert wf[True]["schedule"], "no schedule"  # PyYAML reads the key 'on' as True
for name, job in wf["jobs"].items():
    notify = [s for s in job["steps"] if s.get("if") == "failure()"]
    assert notify, f"{name}: no failure notification"
    assert "SQUAD_CHANNEL_WEBHOOK" in str(notify), f"{name}: does not post to the squad channel"
assert "pagerduty" not in text.lower() and "oncall" not in text.lower(), "a nightly failure must not page on-call"
assert "COMMS_LIVE_SANDBOX" in wf["jobs"]["live-sandbox"]["if"], "live job must stay gated until the Meta client exists"
print("ok", len(wf["jobs"]), "jobs")
