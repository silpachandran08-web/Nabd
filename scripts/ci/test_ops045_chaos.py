#!/usr/bin/env python3
"""OPS-045: the artefacts around the WhatsApp chaos suite are sound. Rules parse and name the real metrics, the runbook exists, the nightly
workflow parses, is scheduled, stays inside 20 minutes and runs the suite; the suite's known-defect tag is registered; the metrics the
alerts read are the ones the backend publishes. A deliberately bad rule file must be refused by the same checks."""
import pathlib, re, sys, yaml

root = pathlib.Path(__file__).resolve().parents[2]


def check_rules(doc, base=root):
    alerts = [r for g in doc["groups"] for r in g["rules"] if "alert" in r]
    assert alerts, "no alerts"
    for a in alerts:
        assert a["labels"]["severity"] in ("page", "ticket"), a["alert"]
        assert (base / a["annotations"]["runbook"]).is_file(), f"{a['alert']}: runbook missing"
        assert a["annotations"]["summary"].strip(), a["alert"]
    return {a["alert"]: a for a in alerts}


def main():
    rules = yaml.safe_load((root / "infra/observability/whatsapp-dead-letter.rules.yml").read_text())
    by = check_rules(rules)
    assert "outbox_dead_events" in by["NabdOutboxDeadLetters"]["expr"] and by["NabdOutboxDeadLetters"]["for"] == "0m"
    assert "whatsapp_inbound_dead" in by["NabdWhatsAppInboundDeadLetters"]["expr"]
    # the metric names are the Prometheus spelling of what the backend registers
    outbox = (root / "backend/src/main/java/com/nabd/hms/shared/ops/OutboxMetrics.java").read_text()
    inbound = (root / "backend/src/main/java/com/nabd/hms/messaging/WhatsAppInboundProcessor.java").read_text()
    assert '"outbox.dead.events"' in outbox and '"whatsapp.inbound.dead"' in inbound
    test = yaml.safe_load((root / "infra/observability/whatsapp-dead-letter.rules.test.yml").read_text())
    assert test["rule_files"] == ["whatsapp-dead-letter.rules.yml"] and len(test["tests"][0]["alert_rule_test"]) == 4

    wf = yaml.safe_load((root / ".github/workflows/ops045-whatsapp-chaos-nightly.yml").read_text())
    trig = wf.get(True) or wf.get("on")  # PyYAML reads the key `on` as True
    assert trig["schedule"][0]["cron"], "no nightly schedule"
    job = wf["jobs"]["chaos"]
    assert job["timeout-minutes"] <= 20, "nightly run must stay under 20 minutes"
    steps = " ".join(s.get("run", "") for s in job["steps"])
    assert "Ops045WhatsAppChaosApiTest" in steps and "test_ops045_chaos.py" in steps and "promtool" in steps

    suite = (root / "backend/src/test/java/com/nabd/hms/messaging/Ops045WhatsAppChaosApiTest.java").read_text()
    pom = (root / "backend/pom.xml").read_text()
    if "known-defect-OPS-045" in suite:
        assert re.search(r"<known.defect.tags>[^<]*known-defect-OPS-045", pom), "known-defect-OPS-045 not registered in pom.xml"
    assert suite.count("@Test") >= 10, "scenario matrix incomplete"

    # negative: a rule without a runbook, or with an unknown severity, must be refused
    bad = {"groups": [{"rules": [{"alert": "X", "expr": "up == 0", "labels": {"severity": "page"}, "annotations": {"summary": "s", "runbook": "docs/runbooks/nope.md"}}]}]}
    try:
        check_rules(bad)
    except AssertionError:
        pass
    else:
        raise SystemExit("bad rule file was accepted")
    print("ok: OPS-045 rules, workflow, registration and negative check")


if __name__ == "__main__":
    main()
