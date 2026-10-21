#!/usr/bin/env python3
"""OPS-027: slos.yaml -> slo.rules.yml (recording rules + burn-rate alerts) and Grafana dashboards. `--check` fails if the committed output is stale."""
import json, pathlib, sys
import yaml

HERE = pathlib.Path(__file__).resolve().parent
WINDOWS = ["5m", "30m", "1h", "6h", "28d"]
# (alert, long window, short window, burn multiple, severity). 14.4x over 1 h burns 2% of a 28 d budget; 6x over 6 h burns 5%.
BURNS = [("NabdSloFastBurn", "1h", "5m", 14.4, "page"), ("NabdSloSlowBurn", "6h", "30m", 6, "page")]


def load():
    return [d for d in yaml.safe_load_all((HERE / "slos.yaml").read_text()) if d]


def rules(slos):
    groups = []
    for s in slos:
        name, spec, ann = s["metadata"]["name"], s["spec"], s["metadata"].get("annotations", {})
        ratio = spec["indicator"]["spec"]["ratioMetric"]
        target = spec["objectives"][0]["target"]
        budget = round(1 - target, 6)
        base = {"slo": name, "service": ann["nabd.io/service"]}
        rs = []
        for w in WINDOWS[:-1]:
            good = ratio["good"]["metricSource"]["spec"]["query"].replace("{{w}}", w)
            total = ratio["total"]["metricSource"]["spec"]["query"].replace("{{w}}", w)
            # ">= 0" drops NaN (0/0 when a window has no traffic) so it cannot poison the 28 d average.
            rs.append({"record": f"slo:sli_error:ratio_rate{w}", "expr": f"(1 - (({good}) / ({total}))) >= 0", "labels": base})
        # 28 d from the 5 m samples (cheap; a direct 28 d rate every 30 s is not).
        rs.append({"record": "slo:sli_error:ratio_rate28d", "expr": f'avg_over_time(slo:sli_error:ratio_rate5m{{slo="{name}"}}[28d])', "labels": base})
        rs.append({"record": "slo:objective:ratio", "expr": str(target), "labels": base})
        if "nabd.io/contract-target" in ann:
            rs.append({"record": "slo:contract_objective:ratio", "expr": str(float(ann["nabd.io/contract-target"]) / 100), "labels": base})
        rs.append({"record": "slo:error_budget_remaining:ratio",
                   "expr": f"clamp_max(1 - slo:sli_error:ratio_rate28d{{slo=\"{name}\"}} / {budget}, 1)", "labels": base})
        # > 50 % of the 28 d budget burnt = release freeze for the service (error-budget policy).
        rs.append({"record": "slo:release_frozen", "expr": f"slo:error_budget_remaining:ratio{{slo=\"{name}\"}} < bool 0.5", "labels": base})
        for alert, lw, sw, mult, sev in BURNS:
            thr = round(mult * budget, 6)
            rs.append({"alert": alert, "expr": f'slo:sli_error:ratio_rate{lw}{{slo="{name}"}} > {thr} and slo:sli_error:ratio_rate{sw}{{slo="{name}"}} > {thr}',
                       "for": "0m", "labels": {**base, "severity": sev},
                       "annotations": {"summary": f"{name}: burning the 28 d error budget at more than {mult}x ({lw} and {sw} windows)",
                                       "runbook": f"docs/runbooks/{alert}.md"}})
        groups.append({"name": f"nabd-slo-{name}", "rules": rs})
    return {"groups": groups}


def panel(i, title, expr, kind="stat", unit="percentunit", w=6, extra=None):
    p = {"id": i, "type": kind, "title": title, "datasource": {"type": "prometheus", "uid": "prometheus"},
         "gridPos": {"h": 6 if kind == "stat" else 8, "w": w, "x": ((i - 1) * w) % 24, "y": ((i - 1) * w // 24) * 8},
         "targets": [{"expr": expr, "refId": "A", "legendFormat": "{{slo}}"}], "fieldConfig": {"defaults": {"unit": unit}}}
    p.update(extra or {})
    return p


def dash(uid, title, panels):
    return {"uid": uid, "title": title, "schemaVersion": 39, "version": 1, "editable": False, "tags": ["nabd", "ops-027"],
            "time": {"from": "now-28d", "to": "now"}, "refresh": "1m", "panels": panels}


def dashboards():
    table = panel(1, "SLOs (28 d): target, actual, budget remaining, release frozen", "", "table", w=24, extra={
        "targets": [{"refId": r, "format": "table", "instant": True, "expr": e} for r, e in [
            ("A", "slo:objective:ratio"), ("B", "1 - slo:sli_error:ratio_rate28d"), ("C", "slo:error_budget_remaining:ratio"), ("D", "slo:release_frozen")]],
        "transformations": [{"id": "merge"}]})
    overview = dash("nabd-slo", "Nabd SLOs and error budgets (per cell)", [
        table,
        panel(2, "Budget remaining", "slo:error_budget_remaining:ratio", "bargauge", w=12),
        panel(3, "Actual vs target vs contract (availability)", '1 - slo:sli_error:ratio_rate1h{slo="staff-api-availability"} or slo:objective:ratio{slo="staff-api-availability"} or slo:contract_objective:ratio',
              "timeseries", w=12),
        panel(4, "Release frozen (budget burnt > 50 %)", "slo:release_frozen", "stat", unit="none", w=24)])
    red = dash("nabd-service", "Nabd service health: RED, outbox, SSE, WhatsApp, read-model lag", [
        panel(1, "Request rate", 'sum(rate(http_server_requests_seconds_count{uri!~"/actuator.*"}[5m]))', "timeseries", "reqps", 12),
        panel(2, "Error ratio (5xx)", 'sum(rate(http_server_requests_seconds_count{status=~"5..",uri!~"/actuator.*"}[5m])) / sum(rate(http_server_requests_seconds_count{uri!~"/actuator.*"}[5m]))', "timeseries", "percentunit", 12),
        panel(3, "Latency p99", 'histogram_quantile(0.99, sum by (le) (rate(http_server_requests_seconds_bucket{uri!~"/actuator.*"}[5m])))', "timeseries", "s", 12),
        panel(4, "Outbox oldest pending (s)", "max(outbox_oldest_pending_age_seconds)", "timeseries", "s", 12),
        panel(5, "SSE connections / event-to-screen p95 (ACC-021, pending)", "histogram_quantile(0.95, sum by (le) (rate(realtime_event_to_screen_seconds_bucket[5m])))", "timeseries", "s", 12),
        panel(6, "WhatsApp sender quality (1 red, 2 yellow, 3 green; Comms, pending)", "waba_quality_rating", "timeseries", "none", 12),
        panel(7, "Consumer / read-model lag (s)", "max by (group, stream) (consumer_lag_seconds)", "timeseries", "s", 12),
        panel(8, "Telemetry dropped by PHI denylist", "sum(increase(nabd_telemetry_denylisted_spans_total[1h])) + sum(increase(nabd_telemetry_denylisted_logs_total[1h]))", "stat", "none", 12)])
    return {"dashboards/slo-overview.json": overview, "dashboards/service-health.json": red}


def outputs():
    out = {"slo.rules.yml": "# GENERATED by generate.py from slos.yaml. Do not edit.\n" + yaml.safe_dump(rules(load()), sort_keys=False, width=200)}
    out.update({k: json.dumps(v, indent=1, sort_keys=True) + "\n" for k, v in dashboards().items()})
    return out


if __name__ == "__main__":
    stale = []
    for rel, text in outputs().items():
        f = HERE / rel
        if "--check" in sys.argv:
            if not f.exists() or f.read_text() != text:
                stale.append(rel)
        else:
            f.write_text(text)
    if stale:
        print("stale generated files, run generate.py:", *stale)
        sys.exit(1)
