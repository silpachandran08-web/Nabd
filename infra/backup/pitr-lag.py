#!/usr/bin/env python3
"""OPS-054: publish the PITR lag (now - LatestRestorableTime) as CloudWatch metric Nabd/Backup PitrLagSeconds.
The alarms in infra/terraform/modules/cell/aws/monitoring.tf fire above 300 s, or when no sample arrives for 15 min.

  pitr-lag.py <db-instance-identifier> [--region ap-south-1]   # needs AWS credentials (OIDC in CI), aws CLI on PATH
  pitr-lag.py --selftest
Exit 2 when the lag is over 300 s, so a scheduled run also fails visibly.
"""
import datetime as dt
import json
import subprocess
import sys

THRESHOLD = 300


def lag_seconds(describe, now):
    """describe = `aws rds describe-db-instances` JSON. Missing LatestRestorableTime means PITR is not working: infinite lag."""
    inst = describe["DBInstances"][0]
    if inst.get("BackupRetentionPeriod", 0) < 35:
        raise ValueError(f"PITR window is {inst.get('BackupRetentionPeriod')} days, expected 35")
    latest = inst.get("LatestRestorableTime")
    if not latest:
        return float("inf")
    return (now - dt.datetime.fromisoformat(latest.replace("Z", "+00:00"))).total_seconds()


def aws(*args):
    return subprocess.run(["aws", *args, "--output", "json"], capture_output=True, text=True, check=True).stdout


def main(db, region):
    describe = json.loads(aws("rds", "describe-db-instances", "--db-instance-identifier", db, "--region", region))
    lag = lag_seconds(describe, dt.datetime.now(dt.timezone.utc))
    value = min(lag, 86400.0)  # CloudWatch needs a finite number; a day reads as "broken"
    aws("cloudwatch", "put-metric-data", "--region", region, "--namespace", "Nabd/Backup", "--metric-name", "PitrLagSeconds",
        "--unit", "Seconds", "--dimensions", f"DBInstanceIdentifier={db}", "--value", str(value))
    print(json.dumps({"db": db, "pitr_lag_seconds": value, "over_threshold": value > THRESHOLD}))
    return 2 if value > THRESHOLD else 0


def selftest():
    now = dt.datetime(2026, 11, 2, 10, 0, tzinfo=dt.timezone.utc)
    ok = {"DBInstances": [{"BackupRetentionPeriod": 35, "LatestRestorableTime": "2026-11-02T09:57:30+00:00"}]}
    assert lag_seconds(ok, now) == 150
    late = {"DBInstances": [{"BackupRetentionPeriod": 35, "LatestRestorableTime": "2026-11-02T09:54:00Z"}]}
    assert lag_seconds(late, now) == 360 > THRESHOLD
    assert lag_seconds({"DBInstances": [{"BackupRetentionPeriod": 35}]}, now) == float("inf")
    try:
        lag_seconds({"DBInstances": [{"BackupRetentionPeriod": 7, "LatestRestorableTime": "2026-11-02T09:59:00Z"}]}, now)
        raise AssertionError("7-day window accepted")
    except ValueError:
        pass
    print("pitr-lag selftest: 4/4 passed")
    return 0


if __name__ == "__main__":
    if sys.argv[1:] == ["--selftest"]:
        sys.exit(selftest())
    region = sys.argv[sys.argv.index("--region") + 1] if "--region" in sys.argv else "ap-south-1"
    sys.exit(main(sys.argv[1], region))
