#!/usr/bin/env python3
"""COM-007.5: the alert file parses, every alert has a runbook that exists, the board JSON parses and shows the four required panels."""
import json, pathlib, sys, yaml
root = pathlib.Path(__file__).resolve().parents[3]
rules = yaml.safe_load((root / "infra/observability/comms-gateway.rules.yml").read_text())
alerts = [r for g in rules["groups"] for r in g["rules"] if "alert" in r]
need = {"CommsSendSloFastBurn", "CommsWebhookAckSlow", "CommsConsumerLagging", "CommsDeadLetter"}
assert need <= {a["alert"] for a in alerts}, f"missing alerts {need - {a['alert'] for a in alerts}}"
for a in alerts:
    assert (root / a["annotations"]["runbook"]).is_file(), f"{a['alert']}: runbook missing"
board = json.loads((root / "infra/observability/slo/dashboards/comms-gateway.json").read_text())
titles = " ".join(p["title"] for p in board["panels"]).lower()
for t in ("send outcomes", "webhook ack", "consumer lag", "dead letters"):
    assert t in titles, f"board lacks panel '{t}'"
print("ok", len(alerts), "alerts,", len(board["panels"]), "panels")
