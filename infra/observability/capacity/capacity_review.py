#!/usr/bin/env python3
"""OPS-060: capacity review against pilot telemetry (doc06 6.5 S8, DP section 6). Runbook: docs/runbooks/capacity-review.md.

  capacity_review.py pull   --from 2026-10-05 --to 2026-11-02 --prom https://prometheus.obs.cell.internal:9090 \
                            --db-instance <rds id> --valkey-cluster <replication group> --region ap-south-1 --out samples.json
  capacity_review.py report samples.json --clinics <pilot clinics live> --cohort-b-date 2026-12-01 --clinics-per-month 4 \
                            [--proven-checkin-rps N] [--out dir] [--ticket]
  capacity_review.py dashboard [--check]      # model.yaml -> infra/observability/dashboards/capacity-review.json
  capacity_review.py selftest

pull: the daily maximum of every resource in model.yaml (Prometheus max_over_time over each UTC day; CloudWatch 5-minute Maximum
reduced per UTC day; CloudWatch keeps 5-minute data 63 days, so pull within 9 weeks of the window). report: per resource the
observed peak, capacity, headroom factor (capacity / peak), the peak scaled to cohort A+B, the clinic count and date at 70%
utilisation, an action (owner) for every resource below the headroom bar, the read-replica and Realtime-extraction trigger
forecasts, and the k6 rush command scaled to A+B. --ticket files each action as a GitHub issue (gh CLI, label `capacity`).
"""
import argparse, datetime as dt, json, math, os, pathlib, subprocess, sys, urllib.parse, urllib.request
import yaml

HERE = pathlib.Path(__file__).resolve().parent
DASHBOARD = HERE.parent / "dashboards" / "capacity-review.json"


def load_model(path=HERE / "model.yaml"):
    return yaml.safe_load(pathlib.Path(path).read_text())


def subst(s, subs):
    for k, v in subs.items():
        s = s.replace("${" + k + "}", v)
    return s


# ---- pull ---------------------------------------------------------------------------------------------------------
def prom_daily(prom, expr, start, end, token=None):
    """[[date, max]] per UTC day: each sample at day+1 00:00 is the max over that day (1-minute subquery)."""
    q = {"query": f"max_over_time(({expr})[1d:1m])", "start": (start + dt.timedelta(days=1)).timestamp(), "end": end.timestamp(), "step": 86400}
    req = urllib.request.Request(f"{prom.rstrip('/')}/api/v1/query_range?{urllib.parse.urlencode(q)}")
    if token:
        req.add_header("Authorization", f"Bearer {token}")
    with urllib.request.urlopen(req, timeout=60) as r:
        body = json.load(r)
    if body.get("status") != "success":
        raise RuntimeError(f"prometheus: {body}")
    out = {}
    for series in body["data"]["result"]:
        for ts, v in series["values"]:
            day = (dt.datetime.fromtimestamp(float(ts), dt.timezone.utc) - dt.timedelta(days=1)).date().isoformat()
            if v not in ("NaN", "+Inf", "-Inf"):
                out[day] = max(out.get(day, float("-inf")), float(v))
    return sorted(out.items())


def prom_instant(prom, expr, at, token=None):
    req = urllib.request.Request(f"{prom.rstrip('/')}/api/v1/query?{urllib.parse.urlencode({'query': expr, 'time': at.timestamp()})}")
    if token:
        req.add_header("Authorization", f"Bearer {token}")
    with urllib.request.urlopen(req, timeout=60) as r:
        res = json.load(r)["data"]["result"]
    return float(res[0]["value"][1]) if res else None


def cw_queries(q, subs):
    """CloudWatch GetMetricData queries for one model query: a SEARCH for wildcard dimensions, metric math for sums."""
    dims = {k: subst(v, subs) for k, v in q["dimensions"].items()}
    metrics = q["metric"] if isinstance(q["metric"], list) else [q["metric"]]
    if "*" in dims.values():
        keys = ",".join(dims)
        return [{"Id": "s0", "ReturnData": True,
                 "Expression": f"SEARCH('{{{q['namespace']},{keys}}} MetricName=\"{metrics[0]}\"', '{q['stat']}', 300)"}]
    qs = [{"Id": f"m{i}", "ReturnData": not q.get("sum"), "MetricStat": {"Period": 300, "Stat": q["stat"], "Metric": {
        "Namespace": q["namespace"], "MetricName": m, "Dimensions": [{"Name": k, "Value": v} for k, v in dims.items()]}}} for i, m in enumerate(metrics)]
    if q.get("sum"):
        qs.append({"Id": "e0", "ReturnData": True, "Expression": "+".join(f"m{i}" for i in range(len(metrics)))})
    return qs


def daily_max(results):
    """GetMetricDataResults -> [[date, max]] over every returned series (all PgBouncer instances, say)."""
    out = {}
    for r in results:
        for ts, v in zip(r["Timestamps"], r["Values"]):
            day = ts[:10]  # ISO timestamps in UTC
            out[day] = max(out.get(day, float("-inf")), float(v))
    return sorted(out.items())


def cw_daily(q, subs, start, end, region):
    results, token = [], None
    while True:
        cmd = ["aws", "cloudwatch", "get-metric-data", "--region", region, "--output", "json", "--start-time", start.isoformat(),
               "--end-time", end.isoformat(), "--metric-data-queries", json.dumps(cw_queries(q, subs))]
        if token:
            cmd += ["--next-token", token]
        page = json.loads(subprocess.run(cmd, capture_output=True, text=True, check=True, timeout=120).stdout)
        results += [r for r in page["MetricDataResults"]]
        token = page.get("NextToken")
        if not token:
            return daily_max(results)


def pull(a, model):
    start = dt.datetime.fromisoformat(a.start).replace(tzinfo=dt.timezone.utc)
    end = dt.datetime.fromisoformat(a.end).replace(tzinfo=dt.timezone.utc)
    subs = {"DB_INSTANCE": a.db_instance, "VALKEY_CLUSTER": a.valkey_cluster}
    token = os.environ.get("PROM_TOKEN")
    out = {"from": a.start, "to": a.end, "pulledAt": dt.datetime.now(dt.timezone.utc).isoformat(timespec="seconds"), "series": {}, "capacity": {}}
    for r in model["resources"]:
        q = r["query"]
        out["series"][r["id"]] = prom_daily(a.prom, q["expr"], start, end, token) if q["datasource"] == "prometheus" else cw_daily(q, subs, start, end, a.region)
        if r["capacity"].get("query"):
            out["capacity"][r["id"]] = prom_instant(a.prom, r["capacity"]["query"], end, token)
    pathlib.Path(a.out).write_text(json.dumps(out, indent=1) + "\n")
    print(f"wrote {a.out}: {sum(len(v) for v in out['series'].values())} daily maxima over {len(out['series'])} resources")


# ---- report -------------------------------------------------------------------------------------------------------
def slope_per_day(points):
    """Least-squares slope of the daily maxima (units per day); 0 with fewer than 2 points."""
    if len(points) < 2:
        return 0.0
    xs = [(dt.date.fromisoformat(d) - dt.date.fromisoformat(points[0][0])).days for d, _ in points]
    ys = [v for _, v in points]
    mx, my = sum(xs) / len(xs), sum(ys) / len(ys)
    den = sum((x - mx) ** 2 for x in xs)
    return sum((x - mx) * (y - my) for x, y in zip(xs, ys)) / den if den else 0.0


def date_for_clinics(n, plan):
    """First date the plan reaches n clinics (None: never on this plan)."""
    if n <= plan["clinics"]:
        return plan["today"]
    if n <= plan["clinics"] + plan["cohort_b"]:
        return plan["cohort_b_date"]
    if plan["per_month"] <= 0:
        return None
    return plan["cohort_b_date"] + dt.timedelta(days=math.ceil((n - plan["clinics"] - plan["cohort_b"]) / plan["per_month"] * 30))


def review(model, samples, plan, proven_checkin_rps=None):
    bar, alarm = model["headroom_min"], model["utilisation_alarm"]
    n, ab = plan["clinics"], plan["clinics"] + plan["cohort_b"]
    rows, actions = [], []
    for r in model["resources"]:
        pts = [(d, v) for d, v in samples["series"].get(r["id"], []) if v is not None]
        cap = samples.get("capacity", {}).get(r["id"]) or r["capacity"]["value"]
        if r["id"] == "checkin_rate" and proven_checkin_rps:
            cap = proven_checkin_rps
        row = {"id": r["id"], "title": r["title"], "unit": r["unit"], "owner": r["owner"], "capacity": cap, "capacitySource": r["capacity"]["source"],
               "verify": bool(r["capacity"].get("verify")), "days": len(pts), "peak": None, "headroom": None, "peakAB": None, "headroomAB": None,
               "clinicsAt70": None, "dateAt70": None, "dateAt70Basis": None}
        rows.append(row)
        if not pts:
            actions.append({**row, "why": f"no telemetry for {r['id']} in the window: the metric is not emitted or not scraped"})
            continue
        peak = max(v for _, v in pts)
        row["peak"] = peak
        scales = r["scales"] == "clinics"
        row["peakAB"] = peak * ab / n if scales else peak
        if cap is None:
            actions.append({**row, "why": f"capacity unknown: {r['capacity']['source']}"})
            continue
        row["headroom"] = cap / peak if peak > 0 else math.inf
        row["headroomAB"] = cap / row["peakAB"] if row["peakAB"] > 0 else math.inf
        if scales and peak > 0:
            row["clinicsAt70"] = math.floor(alarm * cap / (peak / n))
        # 70% date: the earlier of the rollout plan (clinic growth) and the trend of the daily peaks (organic growth).
        candidates = []
        if row["clinicsAt70"] is not None:
            d = date_for_clinics(row["clinicsAt70"] + 1, plan)
            if d:
                candidates.append((d, "rollout plan"))
        s = slope_per_day(pts)
        if peak >= alarm * cap:
            candidates.append((plan["today"], "already above"))
        elif s > 0:
            candidates.append((dt.date.fromisoformat(pts[-1][0]) + dt.timedelta(days=math.ceil((alarm * cap - pts[-1][1]) / s)), "trend"))
        if candidates:
            row["dateAt70"], row["dateAt70Basis"] = min(candidates)
        if row["headroom"] < bar:
            actions.append({**row, "why": f"headroom {row['headroom']:.2f}x is below {bar}x of the pilot peak"})
    by = {r["id"]: r for r in rows}

    def forecast(threshold, per_clinic_of):
        pr = by.get(per_clinic_of)
        if not pr or not pr["peak"]:
            return {"threshold": threshold, "clinics": None, "date": None, "note": f"no {per_clinic_of} telemetry"}
        per_clinic = pr["peak"] / n
        c = math.ceil(threshold / per_clinic) if per_clinic > 0 else None
        return {"threshold": threshold, "perClinic": per_clinic, "clinics": c, "date": date_for_clinics(c, plan) if c else None}

    t = model["triggers"]
    replica_n = t["read_replica_clinics"]
    cpu = by.get("postgres_cpu")
    triggers = {
        "readReplica": {"clinics": replica_n, "date": date_for_clinics(replica_n, plan),
                        "postgresCpuAtTrigger": cpu["peak"] / n * replica_n if cpu and cpu["peak"] is not None else None},
        "realtimeExtraction": [forecast(c, "sse_connections_total") for c in t["realtime_extraction_connections"]],
    }
    rush = by.get("checkin_rate")
    k6 = None
    if rush and rush["peak"]:
        k6 = {"tenants": ab, "peakRps": max(1, math.ceil(rush["peak"] * ab / n)),
              "command": f"BASE_URL=<staging-in> PERF_PIN=<synthetic> PERF_TENANTS={ab} PEAK_RPS={max(1, math.ceil(rush['peak'] * ab / n))} perf/run.sh rush --seed"}
    return {"window": {"from": samples["from"], "to": samples["to"]}, "plan": {k: str(v) for k, v in plan.items()}, "headroomMin": bar,
            "rows": rows, "actions": actions, "triggers": triggers, "k6": k6}


def fmt(v, unit=""):
    if v is None:
        return "—"
    if isinstance(v, float) and math.isinf(v):
        return "∞"
    if isinstance(v, (dt.date,)):
        return v.isoformat()
    return f"{v:,.2f}{unit}" if isinstance(v, float) else f"{v}{unit}"


def markdown(rep):
    L = [f"# Capacity review {rep['window']['from']} to {rep['window']['to']}", "",
         f"Pilot clinics {rep['plan']['clinics']}, cohort B +{rep['plan']['cohort_b']} on {rep['plan']['cohort_b_date']}, then {rep['plan']['per_month']} clinics/month. "
         f"Headroom = capacity / observed 4-week peak; bar {rep['headroomMin']}x. Queries: infra/observability/capacity/model.yaml (Grafana: Nabd capacity review).", "",
         "| Resource | Days | Observed peak | Capacity | Headroom | Peak at A+B | Headroom at A+B | Clinics at 70% | Date to 70% | Owner |",
         "|---|---|---|---|---|---|---|---|---|---|"]
    for r in rep["rows"]:
        cap = fmt(r["capacity"]) + (" (verify)" if r["verify"] else "")
        hr = fmt(r["headroom"], "x") + (" **below bar**" if r["headroom"] is not None and r["headroom"] < rep["headroomMin"] else "")
        d70 = f"{fmt(r['dateAt70'])} ({r['dateAt70Basis']})" if r["dateAt70"] else "not on plan or trend"
        L.append(f"| {r['title']} ({r['unit']}) | {r['days']} | {fmt(r['peak'])} | {cap} | {hr} | {fmt(r['peakAB'])} | {fmt(r['headroomAB'], 'x')} | {fmt(r['clinicsAt70'])} | {d70} | {r['owner']} |")
    L += ["", "## Actions before cohort B entry", ""]
    L += [f"- **{a['title']}** ({a['owner']}): {a['why']}." + (f" Ticket: {a['ticket']}" if a.get("ticket") else " Ticket: not filed (run with --ticket).") for a in rep["actions"]] or ["- none: every resource has at least the headroom bar."]
    rr, rt = rep["triggers"]["readReplica"], rep["triggers"]["realtimeExtraction"]
    L += ["", "## Trigger forecasts", "",
          f"- Read replica at ~{rr['clinics']} clinics: {fmt(rr['date']) if rr['date'] else 'not reached on this plan'}; Postgres CPU projected at that size {fmt(rr['postgresCpuAtTrigger'], '%')}."]
    for f in rt:
        L.append(f"- Realtime extraction at {f['threshold']} SSE connections: " + (f"{fmt(f.get('perClinic'))} connections per clinic, reached at {f['clinics']} clinics, {fmt(f['date']) if f['date'] else 'not on this plan'}." if f["clinics"] else f.get("note", "—")))
    L += ["", "## k6 morning rush at cohort A+B", ""]
    L.append(f"Run in staging-in and record the result here (budgets: check-in p95 < 300 ms, board p95 < 200 ms): `{rep['k6']['command']}`" if rep["k6"] else "No check-in telemetry: cannot scale the rush profile.")
    return "\n".join(L) + "\n"


def ticket_cmd(a, window):
    body = (f"Capacity review {window['from']} to {window['to']} (OPS-060): {a['why']}.\n\nObserved peak {fmt(a['peak'])} {a['unit']}, capacity {fmt(a['capacity'])} "
            f"({a['capacitySource']}).\nOwner: {a['owner']}. Due before cohort B entry. Runbook: docs/runbooks/capacity-review.md")
    return ["gh", "issue", "create", "--label", "capacity", "--title", f"Capacity: {a['title']} before cohort B", "--body", body]


def report(a, model):
    samples = json.loads(pathlib.Path(a.samples).read_text())
    plan = {"today": dt.date.fromisoformat(a.today) if a.today else dt.date.today(), "clinics": a.clinics,
            "cohort_b": model["cohorts"]["cohort_b_new_clinics"], "cohort_b_date": dt.date.fromisoformat(a.cohort_b_date), "per_month": a.clinics_per_month}
    rep = review(model, samples, plan, a.proven_checkin_rps)
    if a.ticket:
        for act in rep["actions"]:
            act["ticket"] = subprocess.run(ticket_cmd(act, rep["window"]), capture_output=True, text=True, check=True).stdout.strip()
    out = pathlib.Path(a.out)
    out.mkdir(parents=True, exist_ok=True)
    (out / "capacity-review.md").write_text(markdown(rep))
    (out / "capacity-review.json").write_text(json.dumps(rep, indent=1, default=str) + "\n")
    print(f"wrote {out}/capacity-review.md: {len(rep['actions'])} action(s)")
    return 1 if rep["actions"] else 0


# ---- dashboard ----------------------------------------------------------------------------------------------------
def targets(q):
    if q["datasource"] == "prometheus":
        return [{"refId": "A", "datasource": {"type": "prometheus", "uid": "prometheus"}, "expr": q["expr"], "legendFormat": "observed"}]
    metrics = q["metric"] if isinstance(q["metric"], list) else [q["metric"]]
    ts = [{"refId": chr(65 + i), "datasource": {"type": "cloudwatch", "uid": "cloudwatch"}, "region": "default", "namespace": q["namespace"], "metricName": m,
           "dimensions": q["dimensions"], "matchExact": "*" not in q["dimensions"].values(), "statistic": q["stat"], "period": "300", "hide": bool(q.get("sum"))}
          for i, m in enumerate(metrics)]
    if q.get("sum"):
        ts.append({"refId": "S", "datasource": {"type": "__expr__", "uid": "__expr__"}, "type": "math", "expression": " + ".join(f"${t['refId']}" for t in ts)})
    return ts


def dashboard(model):
    panels = []
    for i, r in enumerate(model["resources"]):
        cap = r["capacity"]["value"]
        steps = [{"color": "green", "value": None}] + ([{"color": "orange", "value": cap * model["utilisation_alarm"]}, {"color": "red", "value": cap}] if cap else [])
        panels.append({"id": i + 1, "type": "timeseries", "title": f"{r['title']} (capacity {cap if cap is not None else 'from k6'})",
                       "description": f"{r['capacity']['source']}. Owner {r['owner']}.",
                       "gridPos": {"h": 8, "w": 12, "x": (i % 2) * 12, "y": (i // 2) * 8}, "targets": targets(r["query"]),
                       "fieldConfig": {"defaults": {"thresholds": {"mode": "absolute", "steps": steps}, "custom": {"thresholdsStyle": {"mode": "line"}}}},
                       "options": {"legend": {"displayMode": "table", "calcs": ["max"]}}})
    return {"uid": "nabd-capacity", "title": "Nabd capacity review (OPS-060, per cell)", "schemaVersion": 39, "version": 1, "editable": False,
            "tags": ["nabd", "ops-060"], "time": {"from": "now-28d", "to": "now"}, "refresh": "", "panels": panels,
            "templating": {"list": [{"name": "DB_INSTANCE", "type": "textbox", "label": "RDS instance id"},
                                    {"name": "VALKEY_CLUSTER", "type": "textbox", "label": "Valkey replication group"}]}}


def dashboard_text(model):
    return json.dumps(dashboard(model), indent=1, sort_keys=True) + "\n"


# ---- selftest -----------------------------------------------------------------------------------------------------
def selftest():
    model = load_model()
    ids = [r["id"] for r in model["resources"]]
    need = {"postgres_cpu", "postgres_iops", "hikari_active", "pgbouncer_server_connections", "valkey_memory", "valkey_engine_cpu",
            "sse_connections_total", "outbox_lag_seconds", "checkin_rate"}
    assert need <= set(ids) and len(ids) == len(set(ids)), ids
    assert all(r["owner"].startswith("@nabd/") and r["scales"] in ("clinics", "none") for r in model["resources"])
    days = [(dt.date(2026, 10, 5) + dt.timedelta(days=i)).isoformat() for i in range(28)]
    flat = lambda v: [[d, v] for d in days]
    series = {i: flat(1.0) for i in ids}
    series.update({"postgres_cpu": [[d, 20 + i * 0.25] for i, d in enumerate(days)],  # 26.75% peak, rising 0.25/day
                   "pgbouncer_server_connections": flat(18.0),                         # 28 / 18 = 1.56x: below the bar
                   "sse_connections_total": flat(100.0), "checkin_rate": flat(3.2)})
    series["valkey_memory"] = []                                                       # not emitted: an action, never a pass
    samples = {"from": days[0], "to": days[-1], "series": series, "capacity": {"hikari_active": 24}}
    plan = {"today": dt.date(2026, 11, 3), "clinics": 5, "cohort_b": 15, "cohort_b_date": dt.date(2026, 12, 1), "per_month": 5}
    rep = review(model, samples, plan)
    by = {r["id"]: r for r in rep["rows"]}
    cpu = by["postgres_cpu"]
    assert cpu["peak"] == 26.75 and abs(cpu["headroom"] - 100 / 26.75) < 1e-9, cpu
    assert cpu["peakAB"] == 26.75 * 20 / 5 and cpu["clinicsAt70"] == math.floor(70 / (26.75 / 5)), cpu  # 13 clinics
    assert cpu["dateAt70"] == dt.date(2026, 12, 1) and cpu["dateAt70Basis"] == "rollout plan", cpu  # cohort B (20 clinics) crosses 70%
    assert by["hikari_active"]["capacity"] == 24  # the live hikaricp_connections_max wins over the model default
    acts = {a["id"]: a for a in rep["actions"]}
    assert set(acts) == {"pgbouncer_server_connections", "valkey_memory", "checkin_rate"}, set(acts)
    assert "below 2.0x" in acts["pgbouncer_server_connections"]["why"] and "no telemetry" in acts["valkey_memory"]["why"]
    assert "capacity unknown" in acts["checkin_rate"]["why"]
    assert "checkin_rate" not in {a["id"] for a in review(model, samples, plan, proven_checkin_rps=40)["actions"]}  # a green k6 run settles it
    tr = rep["triggers"]
    assert tr["readReplica"]["date"] == dt.date(2026, 12, 1) + dt.timedelta(days=math.ceil(80 / 5 * 30)), tr  # 20 -> 100 clinics at 5/month
    assert tr["realtimeExtraction"][0]["clinics"] == 100 and tr["realtimeExtraction"][1]["clinics"] == 150, tr  # 20 connections per clinic
    assert rep["k6"]["tenants"] == 20 and rep["k6"]["peakRps"] == 13 and "PERF_TENANTS=20 PEAK_RPS=13 perf/run.sh rush" in rep["k6"]["command"]
    md = markdown(rep)
    assert "**below bar**" in md and "Ticket: not filed" in md and "Read replica at ~100 clinics" in md, md
    cmd = ticket_cmd(acts["pgbouncer_server_connections"], rep["window"])
    assert cmd[:5] == ["gh", "issue", "create", "--label", "capacity"] and "@nabd/platform-owners" in cmd[-1]
    # flat trend, no rollout growth: no 70% date rather than an invented one
    flat_plan = {**plan, "per_month": 0, "cohort_b": 0}
    assert review(model, {**samples, "series": {**series, "postgres_cpu": flat(10.0)}}, flat_plan)["rows"][0]["dateAt70"] is None
    # CloudWatch: wildcard dimensions become a SEARCH, sums become metric math, daily max spans every series
    q = next(r for r in model["resources"] if r["id"] == "pgbouncer_server_connections")["query"]
    assert cw_queries(q, {})[0]["Expression"].startswith("SEARCH('{Nabd/PgBouncer,Instance} MetricName=\"SvActive\"'")
    iops = cw_queries(next(r for r in model["resources"] if r["id"] == "postgres_iops")["query"], {"DB_INSTANCE": "db1"})
    assert [x["Id"] for x in iops] == ["m0", "m1", "e0"] and iops[2]["Expression"] == "m0+m1" and iops[0]["MetricStat"]["Metric"]["Dimensions"][0]["Value"] == "db1"
    assert daily_max([{"Timestamps": ["2026-10-05T04:00:00Z", "2026-10-05T05:00:00Z"], "Values": [3, 9]}, {"Timestamps": ["2026-10-05T04:00:00Z"], "Values": [12]}]) == [("2026-10-05", 12.0)]
    assert slope_per_day([("2026-10-05", 1), ("2026-10-06", 3)]) == 2.0
    d = dashboard(model)
    assert len(d["panels"]) == len(ids) and all(p["targets"] for p in d["panels"])
    assert DASHBOARD.exists() and DASHBOARD.read_text() == dashboard_text(model), "capacity-review.json is stale: run capacity_review.py dashboard"
    print("capacity review selftest: ok")
    return 0


def main(argv=None):
    p = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    sub = p.add_subparsers(dest="cmd", required=True)
    pp = sub.add_parser("pull")
    pp.add_argument("--from", dest="start", required=True); pp.add_argument("--to", dest="end", required=True)
    pp.add_argument("--prom", required=True); pp.add_argument("--db-instance", required=True); pp.add_argument("--valkey-cluster", required=True)
    pp.add_argument("--region", required=True); pp.add_argument("--out", default="samples.json")
    pr = sub.add_parser("report")
    pr.add_argument("samples"); pr.add_argument("--clinics", type=int, required=True); pr.add_argument("--cohort-b-date", required=True)
    pr.add_argument("--clinics-per-month", type=float, default=0); pr.add_argument("--proven-checkin-rps", type=float)
    pr.add_argument("--today"); pr.add_argument("--out", default="capacity-review"); pr.add_argument("--ticket", action="store_true")
    pd = sub.add_parser("dashboard"); pd.add_argument("--check", action="store_true")
    sub.add_parser("selftest")
    a = p.parse_args(argv)
    model = load_model()
    if a.cmd == "pull":
        return pull(a, model) or 0
    if a.cmd == "report":
        if a.clinics < 1:
            p.error("--clinics must be at least 1")
        return report(a, model)
    if a.cmd == "dashboard":
        text = dashboard_text(model)
        if a.check:
            if not DASHBOARD.exists() or DASHBOARD.read_text() != text:
                print("stale: run infra/observability/capacity/capacity_review.py dashboard")
                return 1
            return 0
        DASHBOARD.write_text(text)
        return 0
    return selftest()


if __name__ == "__main__":
    sys.exit(main())
