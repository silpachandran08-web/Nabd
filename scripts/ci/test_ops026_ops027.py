"""OPS-026/027 checks on infra/observability and the observability Terraform. Run: python3 -m unittest scripts.ci.test_ops026_ops027
(promtool tests run only when `promtool` is on PATH or PROMTOOL is set; CI installs it)."""
import os, pathlib, re, shutil, subprocess, sys
import unittest
import hcl2, yaml

ROOT = pathlib.Path(__file__).resolve().parents[2]
OBS = ROOT / "infra" / "observability"
SLO = OBS / "slo"
AWS = ROOT / "infra" / "terraform" / "modules" / "cell" / "aws"
PROMTOOL = os.environ.get("PROMTOOL") or shutil.which("promtool")


def collector():
    return yaml.safe_load((OBS / "stack" / "otel-collector.yaml").read_text())


def test_exporters_stay_in_cell():
    # OPS-057 egress test: every exporter target is a *.obs.cell.internal name or a deploy-set env var, never a public host.
    for name, cfg in collector()["exporters"].items():
        targets = re.findall(r"[\w.${}:-]+", str(cfg.get("endpoint", "")) + str(cfg.get("s3uploader", "")))
        ep = cfg.get("endpoint")
        if ep:
            host = re.sub(r"^https?://", "", ep).split(":")[0].split("/")[0]
            assert host.endswith(".obs.cell.internal"), f"{name} exports to {host}"
        else:
            assert "${env:OBS_AUDIT_LOG_BUCKET}" in str(cfg) and "${env:OBS_REGION}" in str(cfg), name


def test_denylist_drops_and_counts():
    c = collector()
    keys = {"phone", "name", "dob", "email", "free_text"}
    attr = {a["key"] for a in c["processors"]["attributes/phi_denylist"]["actions"] if a["action"] == "delete"}
    res = {a["key"] for a in c["processors"]["resource/phi_denylist"]["attributes"] if a["action"] == "delete"}
    assert keys <= attr and keys <= res
    cond = str(c["connectors"]["count/denylisted"])
    assert all(f'attributes["{k}"]' in cond for k in keys)
    pipes = c["service"]["pipelines"]
    for p in ("traces/out", "logs/out", "logs/audit"):  # nothing leaves a pipeline without the denylist
        assert "attributes/phi_denylist" in pipes[p]["processors"], p
    # the counter sits BEFORE the drop, and traces are never sampled (traceparent joins stay whole)
    assert "count/denylisted" in pipes["traces/in"]["exporters"] and "count/denylisted" in pipes["logs/in"]["exporters"]
    assert not [p for pl in pipes.values() for p in pl.get("processors", []) if "sampl" in p]


def test_retention_values():
    assert "retention_period: 4320h" in (OBS / "stack" / "loki.yaml").read_text()  # 180 d
    assert "block_retention: 336h" in (OBS / "stack" / "tempo.yaml").read_text()  # 14 d
    assert "retention.time=395d" in (OBS / "stack" / "prometheus.yml").read_text()  # 13 months
    v = hcl2.loads((AWS / "variables_ops026.tf").read_text())["variable"]
    defaults = {k.strip('"'): b["default"] for d in v for k, b in d.items()}
    assert defaults == {"obs_metrics_retention_days": 395, "obs_log_retention_days": 180, "obs_trace_retention_days": 14}


def test_buckets_encrypted_locked_private():
    doc = hcl2.loads((AWS / "monitoring_slo_buckets.tf").read_text())
    res = {(t.strip('"'), n.strip('"')): b for blk in doc["resource"] for t, named in blk.items() for n, b in named.items()}
    text = (AWS / "monitoring_slo_buckets.tf").read_text()
    assert ("aws_s3_bucket_public_access_block", "obs") in res and ("aws_s3_bucket_versioning", "obs") in res
    sse = res[("aws_s3_bucket_server_side_encryption_configuration", "obs")]
    assert "aws:kms" in str(sse) and "aws_kms_key.root" in str(sse)  # cell KMS key, not an AWS-managed one
    lock = res[("aws_s3_bucket_object_lock_configuration", "obs_audit_logs")]
    assert "COMPLIANCE" in str(lock) and "var.obs_log_retention_days" in str(lock)
    assert 'audit-logs = { class = "audit", days = var.obs_log_retention_days, lock = true }' in text
    assert "cidr_ipv4         = var.vpc_cidr" in text and "0.0.0.0/0" not in text  # endpoints private to the cell


def test_grafana_sso_only_operators():
    ini = (OBS / "stack" / "grafana" / "grafana.ini").read_text()
    for needle in ("disable_login_form = true", "allow_sign_up = false", "role_attribute_strict = true",
                   "platform-operators", "router_logging = true"):
        assert needle in ini, needle
    assert re.search(r"\[auth.anonymous\]\s+enabled = false", ini)


def test_generated_slo_files_current():
    r = subprocess.run([sys.executable, str(SLO / "generate.py"), "--check"], capture_output=True, text=True)
    assert r.returncode == 0, r.stdout + r.stderr


def alerts():
    for f in (SLO / "slo.rules.yml", SLO / "alerts.rules.yml", OBS / "event-consumers.rules.yml"):
        for g in yaml.safe_load(f.read_text())["groups"]:
            for r in g["rules"]:
                if "alert" in r:
                    yield r


def test_every_slo_alert_has_runbook_and_page_alerts_are_burn_rates():
    seen = set()
    for r in alerts():
        rb = r["annotations"]["runbook"]
        if r["alert"] in ("NabdSloFastBurn", "NabdSloSlowBurn", "NabdConsumerLagging", "NabdWabaQualityDropped"):
            assert rb == f"docs/runbooks/{r['alert']}.md" and (ROOT / rb).is_file(), r["alert"]
            seen.add(r["alert"])
    assert len(seen) == 4


def test_burn_rate_thresholds():
    rules = yaml.safe_load((SLO / "slo.rules.yml").read_text())["groups"]
    fast = next(r for g in rules for r in g["rules"] if r.get("alert") == "NabdSloFastBurn" and "staff-api" in g["name"])
    # 14.4 x 0.1% budget, 1 h and 5 m
    assert "ratio_rate1h" in fast["expr"] and "ratio_rate5m" in fast["expr"] and "0.0144" in fast["expr"]
    slow = next(r for g in rules for r in g["rules"] if r.get("alert") == "NabdSloSlowBurn" and "staff-api" in g["name"])
    assert "ratio_rate6h" in slow["expr"] and "ratio_rate30m" in slow["expr"] and "0.006" in slow["expr"]


def test_alertmanager_clinic_hours_paging():
    am = yaml.safe_load((OBS / "alertmanager" / "alertmanager.in.yml").read_text())
    ti = am["time_intervals"][0]["time_intervals"][0]
    assert ti["times"] == [{"start_time": "07:00", "end_time": "23:00"}] and ti["location"] == "Asia/Kolkata"
    page = am["route"]["routes"][0]
    assert page["receiver"] == "oncall-primary" and page["active_time_intervals"] == ["clinic-hours"]
    assert am["route"]["routes"][1]["receiver"] == "ticket"  # out of hours: never silently dropped
    assert int(am["route"]["group_wait"].rstrip("s")) <= 60  # page within 5 min of the rule firing
    assert not re.search(r"api_key:|password", (OBS / "alertmanager" / "alertmanager.in.yml").read_text())


def test_dashboards_are_json_with_targets():
    import json
    for f in (SLO / "dashboards").glob("*.json"):
        d = json.loads(f.read_text())
        assert d["panels"] and all(p["targets"] for p in d["panels"]), f.name


def test_promtool():
    if not PROMTOOL:
        raise unittest.SkipTest("promtool not installed")
    for f in (SLO / "slo.rules.yml", SLO / "alerts.rules.yml", OBS / "event-consumers.rules.yml"):
        assert subprocess.run([PROMTOOL, "check", "rules", str(f)], capture_output=True).returncode == 0, f
    r = subprocess.run([PROMTOOL, "test", "rules", "slo.rules.test.yml"], cwd=SLO, capture_output=True, text=True)
    assert r.returncode == 0, r.stdout + r.stderr


class Ops026Ops027(unittest.TestCase):
    pass


for _n, _f in list(globals().items()):
    if _n.startswith("test_") and callable(_f):
        setattr(Ops026Ops027, _n, lambda self, f=_f: f())
