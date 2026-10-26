#!/usr/bin/env python3
"""ACC-019: static checks of the ETA drift alert rule, its routing and the nightly gate workflow (promtool is not available here).
The firing test in staging is Needs external action."""
import pathlib, re, sys
import yaml

root = pathlib.Path(__file__).resolve().parents[2]
rules = yaml.safe_load((root / "infra/observability/eta-accuracy.rules.yml").read_text())["groups"][0]["rules"]
assert len(rules) == 1
r = rules[0]
assert r["alert"] == "NabdEtaP80CoverageDrift"
assert re.search(r"nabd_eta_p80_coverage\{tenant!=\"other\"\}\s*<\s*0\.70", r["expr"]), r["expr"]
assert re.search(r"nabd_eta_visits_today\{tenant!=\"other\"\}\s*>=\s*30", r["expr"]), "low-confidence clinics (<30 visits) must not alert"
assert r["labels"]["severity"] == "warning" and r["labels"]["team"] == "patient-access", "an alert, not a page"
# detection budget: 5 min metric refresh + for: must stay inside 15 minutes
assert int(re.match(r"(\d+)m", r["for"]).group(1)) + 5 <= 15
am = yaml.safe_load((root / "infra/observability/alertmanager/alertmanager.in.yml").read_text())
assert any(rt["matchers"] == ['severity="warning"'] and rt["receiver"] == "ticket" for rt in am["route"]["routes"]), "warning must route to a ticket, never oncall"
wf = yaml.safe_load((root / ".github/workflows/eta-accuracy.yml").read_text())
assert "schedule" in wf[True] and "Acc019EtaSimulatorTest" in (root / ".github/workflows/eta-accuracy.yml").read_text()
print("ok: ACC-019 alert rule, routing and nightly workflow")
