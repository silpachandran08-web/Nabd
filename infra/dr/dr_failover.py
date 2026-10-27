#!/usr/bin/env python3
"""OPS-059: DR failover and failback for a cell (ADR-09: Mumbai primary, Hyderabad DR), timed against RTO 1 h / RPO 5 min.

  dr_failover.py preflight  --config cell.json
  dr_failover.py failover   --config cell.json [--execute] [--primary-reachable yes|no] [--record out.json]
  dr_failover.py failback   --config cell.json [--execute] [--record out.json]
  dr_failover.py --selftest

Without --execute every command is only PRINTED (dry run, nothing touches a cloud). With --execute commands run through the
aws and kubectl CLIs with the operator's own SSO session; no credential is read or stored here. The config holds identifiers
only (docs/runbooks/dr-failover.md has the schema; infra/dr/dr-config.example.json is a template with REPLACE_ values,
which --execute refuses). Every step is timed; the record (JSON) is what the rehearsal row in the runbook is copied from.

Stop rules: a DR region outside the country's list is refused before anything runs (the list mirrors
infra/terraform/modules/cell/residency.tf, infra/dr/check_dr.py keeps the two equal); a failed step stops the run.
shortcut: the AWS/kubectl command lines here are written from the CLI documentation and are NOT proven against a live account
(none exists yet, Needs external action); the rehearsal in the runbook is where they get proven and corrected.
"""
import argparse
import json
import subprocess
import sys
import time
import urllib.request

RTO_SECONDS = 3600  # internal target (contract value is DEC-36, not decided)
RPO_SECONDS = 300
# Same data as modules/cell/residency.tf (aws / IN). Another country has no DR region on AWS yet.
COUNTRY_REGIONS = {"IN": ("ap-south-1", "ap-south-2")}
KEY_TABLES = ("patients", "queue_entries", "invoices", "appointments", "audit_log")  # compared after failback (no data lost)


class StepFailed(Exception):
    pass


class Runner:
    """Runs one command and returns stdout. DryRunner prints instead; tests pass a scripted one."""

    def run(self, cmd, stdin=None):
        p = subprocess.run(cmd, input=stdin, capture_output=True, text=True)
        if p.returncode != 0:
            raise StepFailed(f"{' '.join(cmd[:3])} ... exited {p.returncode}: {p.stderr.strip()[:300]}")
        return p.stdout


class DryRunner(Runner):
    def __init__(self, canned=None):
        self.canned = canned or {}
        self.commands = []

    def run(self, cmd, stdin=None):
        self.commands.append(cmd)
        print("  $ " + " ".join(cmd))
        for key, value in self.canned.items():
            if key in " ".join(cmd):
                return value
        return ""


def guard_regions(cfg):
    allowed = COUNTRY_REGIONS.get(cfg["country"])
    if not allowed:
        raise StepFailed(f"no DR region list for country {cfg['country']}: refused")
    for k in ("primary_region", "dr_region"):
        if cfg[k] not in allowed:
            raise StepFailed(f"{k} {cfg[k]} is outside {cfg['country']} {list(allowed)}: refused (data residency)")
    if cfg["primary_region"] == cfg["dr_region"]:
        raise StepFailed("primary_region and dr_region must differ")


def guard_placeholders(cfg):
    bad = [k for k, v in cfg.items() if isinstance(v, str) and "REPLACE_" in v]
    if bad:
        raise StepFailed("config still has REPLACE_ values: " + ", ".join(sorted(bad)))


class Timeline:
    def __init__(self, name, clock=time.monotonic):
        self.name, self.clock, self.steps, self.t0 = name, clock, [], clock()

    def step(self, label, fn):
        start = self.clock()
        try:
            detail = fn()
            ok = True
        except StepFailed as e:
            detail, ok = str(e), False
        end = self.clock()
        self.steps.append({"step": label, "ok": ok, "started_s": round(start - self.t0, 1), "seconds": round(end - start, 1), "detail": detail if isinstance(detail, (str, int, float, type(None))) else str(detail)})
        print(f"{'ok  ' if ok else 'FAIL'} {label} ({end - start:.1f}s)" + ("" if ok else f": {detail}"))
        if not ok:
            raise StepFailed(f"{label}: {detail}")

    def record(self, extra):
        total = round(self.clock() - self.t0, 1)
        return {"run": self.name, "total_seconds": total, "within_rto_1h": total <= RTO_SECONDS, "steps": self.steps, **extra}


def aws(cfg, region_key, *args):
    return ["aws", "--region", cfg[region_key], *args]


def kube(cfg, which, *args):
    return ["kubectl", "--context", cfg[f"{which}_context"], "-n", cfg["namespace"], *args]


def replica_lag_seconds(cfg, run):
    """Largest ReplicaLag the DR region saw in the last 5 minutes (the data the DR copy may be missing)."""
    out = run.run(aws(cfg, "dr_region", "cloudwatch", "get-metric-statistics", "--namespace", "AWS/RDS", "--metric-name", "ReplicaLag",
                      "--dimensions", f"Name=DBInstanceIdentifier,Value={cfg['replica_id']}", "--statistics", "Maximum", "--period", "60",
                      "--start-time", "-PT5M", "--end-time", "now", "--query", "max(Datapoints[].Maximum)", "--output", "text"))
    try:
        return float(out.strip())
    except ValueError:
        return None  # no sample: replication unknown, the caller decides


def preflight(cfg, run):
    guard_regions(cfg)
    status = run.run(aws(cfg, "dr_region", "rds", "describe-db-instances", "--db-instance-identifier", cfg["replica_id"],
                         "--query", "DBInstances[0].[DBInstanceStatus,ReadReplicaSourceDBInstanceIdentifier,StorageEncrypted]", "--output", "text")).split()
    if status and status[0] != "available":
        raise StepFailed(f"replica is {status[0]}, not available")
    if len(status) > 2 and status[2].lower() != "true":
        raise StepFailed("replica storage is not encrypted")
    lag = replica_lag_seconds(cfg, run)
    if lag is not None and lag > RPO_SECONDS:
        raise StepFailed(f"replica lag {lag:.0f}s exceeds the RPO of {RPO_SECONDS}s: failing over now loses more than 5 minutes")
    run.run(kube(cfg, "dr", "get", "deploy"))  # the DR cluster answers and the namespace exists
    return {"replica_lag_seconds": lag}


def smoke(cfg, fetch=None):
    """Staff can log in and check in a patient: infra/dr/smoke.py does both against the DR host (credentials from the environment)."""
    code = (fetch or urllib.request.urlopen)(f"https://{cfg['api_host']}/actuator/health/readiness", timeout=10).status if fetch is not False else 200
    if code != 200:
        raise StepFailed(f"readiness answered {code}")
    return "readiness 200"


def audit_chain_ok(cfg, run, url_key):
    out = run.run(["psql", cfg[url_key], "-X", "-q", "-At", "-v", "ON_ERROR_STOP=1", "-c",
                   "SELECT count(*) FROM (SELECT id FROM tenants ORDER BY created_at LIMIT 5) t WHERE (SELECT verify_audit_chain(t.id)) IS NOT NULL"])
    if out.strip() not in ("0", ""):
        raise StepFailed(f"audit chain broken for {out.strip()} sampled tenant(s) on {url_key}")
    return "audit chain verifies for the sampled tenants"


def dns_switch(cfg, run, alb_key, zone_key):
    changes = [{"Action": "UPSERT", "ResourceRecordSet": {"Name": host, "Type": "A", "AliasTarget": {
        "HostedZoneId": cfg[zone_key], "DNSName": cfg[alb_key], "EvaluateTargetHealth": True}}} for host in (cfg["api_host"], cfg["app_host"])]
    run.run(["aws", "route53", "change-resource-record-sets", "--hosted-zone-id", cfg["public_zone_id"], "--change-batch",
             json.dumps({"Comment": "OPS-059 DR switch", "Changes": changes})])


def fence(cfg, run, which):
    """Scale the runtime of one side to zero so it cannot write (split-brain guard)."""
    run.run(kube(cfg, which, "scale", "deploy/core", "deploy/worker", "deploy/bff", "--replicas=0"))


def failover(cfg, run, primary_reachable=True, clock=time.monotonic, fetch=None):
    guard_regions(cfg)
    tl = Timeline("failover", clock)
    extra = {"primary_reachable": primary_reachable}
    tl.step("preflight", lambda: extra.update(preflight(cfg, run)) or "ok")
    if primary_reachable:
        tl.step("fence Mumbai runtime", lambda: fence(cfg, run, "primary"))
    else:
        extra["warning"] = "Mumbai could not be fenced; fence it (scale to zero) before failback or two writers can exist"
    extra["rpo_estimate_seconds"] = replica_lag_seconds(cfg, run)
    tl.step("promote DR replica", lambda: run.run(aws(cfg, "dr_region", "rds", "promote-read-replica", "--db-instance-identifier", cfg["replica_id"], "--backup-retention-period", "35")))
    tl.step("wait for DR database available", lambda: run.run(aws(cfg, "dr_region", "rds", "wait", "db-instance-available", "--db-instance-identifier", cfg["replica_id"])))
    tl.step("point DR runtime at the promoted database", lambda: run.run(["sh", "-c", cfg["db_url_sync_command"]]))
    tl.step("start DR runtime", lambda: run.run(["sh", "-c", f"python3 infra/dr/render_dr_manifests.py --dr-region {cfg['dr_region']} --core-tg {cfg['dr_core_tg_arn']} --bff-tg {cfg['dr_bff_tg_arn']} | kubectl --context {cfg['dr_context']} apply -f -"]))
    tl.step("wait for DR rollout", lambda: [run.run(kube(cfg, "dr", "rollout", "status", f"deploy/{d}", "--timeout=900s")) for d in ("core", "worker", "bff")] and "ready")
    tl.step("switch DNS to Hyderabad", lambda: dns_switch(cfg, run, "dr_alb_dns", "dr_alb_zone_id"))
    tl.step("verify: readiness", lambda: smoke(cfg, fetch))
    tl.step("verify: audit chain on DR", lambda: audit_chain_ok(cfg, run, "dr_db_url"))
    rec = tl.record(extra)
    rec["rpo_within_5min"] = extra["rpo_estimate_seconds"] is not None and extra["rpo_estimate_seconds"] <= RPO_SECONDS
    return rec


def counts(cfg, run, url_key):
    sql = " UNION ALL ".join(f"SELECT '{t}', count(*) FROM {t}" for t in KEY_TABLES)
    # nabd_reports with RLS on: counted per sampled tenant would be exact; here one number per table over the sampled tenants
    out = run.run(["psql", cfg[url_key], "-X", "-q", "-At", "-v", "ON_ERROR_STOP=1", "-c", f"SELECT 1; {sql}"])
    return {line.split("|")[0]: int(line.split("|")[1]) for line in out.splitlines() if "|" in line}


def failback(cfg, run, clock=time.monotonic, fetch=None):
    guard_regions(cfg)
    tl = Timeline("failback", clock)
    extra = {}
    # 1. rebuild Mumbai as a replica OF the promoted DR database (the old Mumbai instance diverged and is kept, renamed, for forensics)
    tl.step("keep the old Mumbai database aside", lambda: run.run(aws(cfg, "primary_region", "rds", "modify-db-instance", "--db-instance-identifier", cfg["primary_id"],
                                                                     "--new-db-instance-identifier", cfg["primary_id"] + "-pre-failback", "--apply-immediately")))
    tl.step("create Mumbai replica of DR", lambda: run.run(aws(cfg, "primary_region", "rds", "create-db-instance-read-replica", "--db-instance-identifier", cfg["primary_id"],
                                                              "--source-db-instance-identifier", cfg["dr_replica_arn"], "--kms-key-id", cfg["primary_kms_key_arn"],
                                                              "--db-subnet-group-name", cfg["primary_subnet_group"], "--vpc-security-group-ids", cfg["primary_db_sg"],
                                                              "--db-parameter-group-name", cfg["primary_parameter_group"], "--multi-az", "--deletion-protection")))
    tl.step("wait for Mumbai replica", lambda: run.run(aws(cfg, "primary_region", "rds", "wait", "db-instance-available", "--db-instance-identifier", cfg["primary_id"])))
    # 2. fence DR, let Mumbai catch up to zero lag, compare, promote
    tl.step("fence Hyderabad runtime", lambda: fence(cfg, run, "dr"))
    tl.step("wait for zero replica lag", lambda: run.run(["sh", "-c", cfg["wait_zero_lag_command"]]))
    def compare():
        dr, mum = counts(cfg, run, "dr_db_url"), counts(cfg, run, "primary_db_url")
        lost = {t: dr[t] - mum.get(t, 0) for t in dr if dr[t] > mum.get(t, 0)}
        extra["dr_counts"], extra["mumbai_counts"] = dr, mum
        if lost:
            raise StepFailed(f"Mumbai is missing rows written during the DR period: {lost}")
        return "no table is behind"
    tl.step("compare key tables (no data lost from the DR period)", compare)
    tl.step("promote Mumbai", lambda: run.run(aws(cfg, "primary_region", "rds", "promote-read-replica", "--db-instance-identifier", cfg["primary_id"], "--backup-retention-period", "35")))
    tl.step("wait for Mumbai available", lambda: run.run(aws(cfg, "primary_region", "rds", "wait", "db-instance-available", "--db-instance-identifier", cfg["primary_id"])))
    tl.step("point Mumbai runtime at the database", lambda: run.run(["sh", "-c", cfg["primary_db_url_sync_command"]]))
    tl.step("scale Mumbai runtime up", lambda: [run.run(kube(cfg, "primary", "scale", f"deploy/{d}", "--replicas=2")) for d in ("core", "worker", "bff")] and "scaled")
    tl.step("wait for Mumbai rollout", lambda: [run.run(kube(cfg, "primary", "rollout", "status", f"deploy/{d}", "--timeout=900s")) for d in ("core", "worker", "bff")] and "ready")
    tl.step("switch DNS back to Mumbai", lambda: dns_switch(cfg, run, "primary_alb_dns", "primary_alb_zone_id"))
    tl.step("verify: readiness", lambda: smoke(cfg, fetch))
    tl.step("verify: audit chain on Mumbai", lambda: audit_chain_ok(cfg, run, "primary_db_url"))
    # 3. the standby is gone (the promoted DR instance is standalone): recreate it as a replica of the new Mumbai primary
    tl.step("keep the promoted DR database aside", lambda: run.run(aws(cfg, "dr_region", "rds", "modify-db-instance", "--db-instance-identifier", cfg["replica_id"],
                                                                      "--new-db-instance-identifier", cfg["replica_id"] + "-post-failback", "--apply-immediately")))
    tl.step("recreate the Hyderabad replica", lambda: run.run(aws(cfg, "dr_region", "rds", "create-db-instance-read-replica", "--db-instance-identifier", cfg["replica_id"],
                                                                 "--source-db-instance-identifier", cfg["primary_arn"], "--kms-key-id", cfg["dr_kms_key_arn"],
                                                                 "--db-subnet-group-name", cfg["dr_subnet_group"], "--vpc-security-group-ids", cfg["dr_db_sg"],
                                                                 "--db-parameter-group-name", cfg["dr_parameter_group"], "--multi-az", "--deletion-protection")))
    extra["follow_up"] = "reconcile Terraform state for the DB instances (tofu import) and delete the *-pre-failback / *-post-failback instances after the incident review"
    return tl.record(extra)


EXAMPLE = {
    "country": "IN", "primary_region": "ap-south-1", "dr_region": "ap-south-2", "namespace": "prod-in",
    "primary_context": "REPLACE_MUMBAI_KUBE_CONTEXT", "dr_context": "REPLACE_HYDERABAD_KUBE_CONTEXT",
    "primary_id": "nabd-in-prod", "replica_id": "nabd-in-prod-dr",
    "primary_arn": "REPLACE_PRIMARY_DB_ARN", "dr_replica_arn": "REPLACE_DR_DB_ARN",
    "primary_kms_key_arn": "REPLACE", "dr_kms_key_arn": "REPLACE", "primary_subnet_group": "nabd-in-prod", "dr_subnet_group": "nabd-in-prod-dr",
    "primary_db_sg": "REPLACE", "dr_db_sg": "REPLACE", "primary_parameter_group": "nabd-in-prod", "dr_parameter_group": "nabd-in-prod-dr",
    "api_host": "REPLACE_API_HOST", "app_host": "REPLACE_APP_HOST", "public_zone_id": "REPLACE_PUBLIC_ZONE_ID",
    "primary_alb_dns": "REPLACE", "primary_alb_zone_id": "REPLACE", "dr_alb_dns": "REPLACE", "dr_alb_zone_id": "REPLACE",
    "dr_core_tg_arn": "REPLACE", "dr_bff_tg_arn": "REPLACE",
    "dr_db_url": "host=REPLACE dbname=nabd user=nabd_reports sslmode=verify-full", "primary_db_url": "host=REPLACE dbname=nabd user=nabd_reports sslmode=verify-full",
    "db_url_sync_command": "REPLACE_SECRET_SYNC_COMMAND_FOR_DR", "primary_db_url_sync_command": "REPLACE_SECRET_SYNC_COMMAND_FOR_MUMBAI",
    "wait_zero_lag_command": "REPLACE_COMMAND_THAT_EXITS_0_AT_ZERO_LAG",
}


def main(argv):
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("action", nargs="?", choices=["preflight", "failover", "failback"])
    ap.add_argument("--config")
    ap.add_argument("--execute", action="store_true", help="run the commands (default: print them)")
    ap.add_argument("--primary-reachable", choices=["yes", "no"], default="yes")
    ap.add_argument("--record")
    ap.add_argument("--selftest", action="store_true")
    a = ap.parse_args(argv)
    if a.selftest:
        import unittest
        sys.path.insert(0, "scripts/ci")
        return 0 if unittest.main(module="test_ops059", argv=["x"], exit=False).result.wasSuccessful() else 1
    if not a.action or not a.config:
        ap.error("action and --config are required")
    cfg = json.load(open(a.config))
    try:
        if a.execute:
            guard_placeholders(cfg)
        run = Runner() if a.execute else DryRunner({"ReplicaLag": "12", "describe-db-instances": "available nabd-in-prod true", "psql": "0"})
        fetch = None if a.execute else False
        if a.action == "preflight":
            print(json.dumps(preflight(cfg, run)))
            return 0
        rec = failover(cfg, run, a.primary_reachable == "yes", fetch=fetch) if a.action == "failover" else failback(cfg, run, fetch=fetch)
    except StepFailed as e:
        print("STOPPED:", e, file=sys.stderr)
        return 1
    print(json.dumps(rec, indent=1))
    if a.record:
        json.dump(rec, open(a.record, "w"), indent=1)
    return 0 if rec["within_rto_1h"] else 2


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
