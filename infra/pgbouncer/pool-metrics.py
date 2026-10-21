#!/usr/bin/env python3
"""OPS-055: publish PgBouncer pool health as CloudWatch metrics (namespace Nabd/PgBouncer, dimension Instance).
Baked into the PgBouncer AMI and run every minute by /etc/cron.d/nabd-pool-metrics (see data_tier_userdata.sh.tftpl).

  pool-metrics.py --host 127.0.0.1 --instance <name>   # PGBOUNCER_STATS_PW and AWS_REGION in the environment, psql + aws CLI on PATH
  pool-metrics.py --selftest

  ClWaiting     SHOW POOLS cl_waiting, summed over pools (clients queued for a server connection)
  SvActive      SHOW POOLS sv_active, summed (server connections running a transaction)
  AvgWaitTimeMs SHOW STATS avg_wait_time (microseconds) / 1000, the worst database
The alarms (monitoring in aws/data_tier_monitoring.tf): AvgWaitTimeMs > 100 for 5 min, ClWaiting > 0 for 5 min, no sample for 5 min.
"""
import csv
import io
import os
import subprocess
import sys


def parse(show_pools, show_stats):
    """Both arguments are `psql --csv` output of SHOW POOLS / SHOW STATS. The `pgbouncer` admin pseudo-database is ignored."""
    pools = [r for r in csv.DictReader(io.StringIO(show_pools)) if r["database"] != "pgbouncer"]
    stats = [r for r in csv.DictReader(io.StringIO(show_stats)) if r["database"] != "pgbouncer"]
    if not pools or not stats:
        raise ValueError("SHOW POOLS/STATS returned no application database: PgBouncer is up but serving nothing")
    return {
        "ClWaiting": (sum(int(r["cl_waiting"]) for r in pools), "Count"),
        "SvActive": (sum(int(r["sv_active"]) for r in pools), "Count"),
        "AvgWaitTimeMs": (max(int(r["avg_wait_time"]) for r in stats) / 1000.0, "Milliseconds"),
    }


def admin(host, sql):
    env = {**os.environ, "PGPASSWORD": os.environ["PGBOUNCER_STATS_PW"]}
    return subprocess.run(["psql", f"host={host} port=6432 dbname=pgbouncer user=pgbouncer_stats sslmode=require", "--csv", "-X", "-c", sql],
                          capture_output=True, text=True, check=True, env=env, timeout=10).stdout


def publish(metrics, instance):
    data = [f"MetricName={k},Value={v},Unit={u},Dimensions=[{{Name=Instance,Value={instance}}}]" for k, (v, u) in metrics.items()]
    subprocess.run(["aws", "cloudwatch", "put-metric-data", "--region", os.environ["AWS_REGION"], "--namespace", "Nabd/PgBouncer",
                    "--metric-data", *data], check=True, timeout=20)


def selftest():
    pools = ("database,user,cl_active,cl_waiting,sv_active,sv_idle,pool_mode\n"
             "nabd,nabd_app,10,3,8,16,transaction\nnabd,nabd_reports,1,0,1,3,transaction\npgbouncer,pgbouncer,1,0,0,0,statement\n")
    stats = "database,total_xact_count,avg_wait_time\nnabd,100,2500\nnabd_x,5,150000\npgbouncer,0,0\n"
    m = parse(pools, stats)
    assert m["ClWaiting"] == (3, "Count") and m["SvActive"] == (9, "Count"), m
    assert m["AvgWaitTimeMs"] == (150.0, "Milliseconds"), m  # worst database, microseconds to ms: this would alarm
    try:
        parse("database,user,cl_waiting,sv_active\npgbouncer,pgbouncer,0,0\n", "database,avg_wait_time\npgbouncer,0\n")
        raise AssertionError("an empty pooler passed as healthy")
    except ValueError:
        pass
    print("pool-metrics selftest: 3/3 passed")
    return 0


if __name__ == "__main__":
    if sys.argv[1:] == ["--selftest"]:
        sys.exit(selftest())
    args = dict(zip(sys.argv[1::2], sys.argv[2::2]))
    host, instance = args.get("--host", "127.0.0.1"), args["--instance"]
    publish(parse(admin(host, "SHOW POOLS"), admin(host, "SHOW STATS")), instance)
