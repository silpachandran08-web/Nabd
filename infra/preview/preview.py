#!/usr/bin/env python3
"""OPS-036 preview helpers (pure logic is unit-tested in infra/preview/test_preview.py).

  preview.py cap <max> <pr> < namespaces.json         exit 1 when <max> previews already run and <pr> is not one of them
  preview.py comment --url URL --logins logins.json   PR comment (URL + per-role test logins), marker <!-- preview-env -->
  preview.py reap --open-prs 12,15 [--now ISO] [--apply] < namespaces.json
        lists previews older than 72 h or whose PR is closed/merged; --apply deletes them with kubectl and then
        checks that nothing labelled with that PR is left (the cost tags then report zero).
namespaces.json = `kubectl get ns -l nabd.io/preview=true -o json`.
"""
import argparse, datetime as dt, json, subprocess, sys

TTL_HOURS = 72


def previews(ns_list):
    return [{"name": n["metadata"]["name"], "pr": int(n["metadata"]["labels"]["nabd.io/pr"]),
             "created": n["metadata"]["creationTimestamp"]} for n in ns_list.get("items", [])
            if n["metadata"].get("labels", {}).get("nabd.io/preview") == "true"]


def over_cap(ns_list, max_previews, pr):
    running = previews(ns_list)
    return not any(p["pr"] == pr for p in running) and len(running) >= max_previews


def to_reap(ns_list, open_prs, now):
    """(name, reason) for every preview past its TTL or whose PR is no longer open."""
    t = dt.datetime.fromisoformat(now.replace("Z", "+00:00"))
    out = []
    for p in previews(ns_list):
        age = (t - dt.datetime.fromisoformat(p["created"].replace("Z", "+00:00"))).total_seconds() / 3600
        if p["pr"] not in open_prs:
            out.append((p["name"], f"PR #{p['pr']} is merged or closed"))
        elif age > TTL_HOURS:
            out.append((p["name"], f"older than {TTL_HOURS} h ({age:.0f} h)"))
    return out


def comment(url, logins):
    rows = []
    for key, t in sorted(logins["tenants"].items()):
        if key == "KSA":  # alias of SA used by the smoke harness
            continue
        for role, c in t["staff"].items():
            rows.append(f"| {key} | `{t['slug']}` | {role} | `{c['email']}` | `{c['pin']}` |")
    return "\n".join(["<!-- preview-env -->", "### Preview environment", "",
                      f"{url} (synthetic data only; destroyed after {TTL_HOURS} h or when this PR closes; PINs die with it)", "",
                      "| Tenant | Clinic code | Role | Email | PIN |", "|---|---|---|---|---|", *rows]) + "\n"


def kubectl(*args):
    return subprocess.run(["kubectl", *args], check=True, capture_output=True, text=True).stdout


def main():
    ap = argparse.ArgumentParser()
    sub = ap.add_subparsers(dest="cmd", required=True)
    c = sub.add_parser("cap"); c.add_argument("max", type=int); c.add_argument("pr", type=int)
    m = sub.add_parser("comment"); m.add_argument("--url", required=True); m.add_argument("--logins", required=True)
    r = sub.add_parser("reap"); r.add_argument("--open-prs", default=""); r.add_argument("--now"); r.add_argument("--apply", action="store_true")
    a = ap.parse_args()
    if a.cmd == "comment":
        print(comment(a.url, json.load(open(a.logins))), end=""); return
    ns = json.load(sys.stdin)
    if a.cmd == "cap":
        if over_cap(ns, a.max, a.pr):
            print(f"preview cap reached: {a.max} previews already running; close a PR or wait for the reaper"); sys.exit(1)
        print("under cap"); return
    now = a.now or dt.datetime.now(dt.timezone.utc).isoformat()
    open_prs = {int(x) for x in a.open_prs.split(",") if x.strip()}
    doomed = to_reap(ns, open_prs, now)
    for name, why in doomed:
        print(f"{'DELETE' if a.apply else 'would delete'} {name}: {why}")
        if a.apply:
            kubectl("delete", "namespace", name, "--wait=true", "--timeout=5m")
            pr = name.rsplit("-", 1)[-1]
            # every preview resource is namespaced (no PVC, no LoadBalancer: ResourceQuota), so no tagged namespace = nothing billable left
            left = kubectl("get", "namespace", "-l", f"nabd.io/pr={pr}", "-o", "name").strip()
            if left:
                print(f"FAIL: resources still tagged nabd.io/pr={pr}:\n{left}"); sys.exit(1)
            print(f"  cost tag nabd.io/pr={pr}: 0 resources left")
    if not doomed:
        print("nothing to reap")


if __name__ == "__main__":
    main()
