#!/usr/bin/env python3
"""Merge the per-domain spec files into the Workbench data bundle.

Usage:
  python3 workbench/tools/merge_specs.py <specs_dir> <tracker_tasks.json> [reconcile.json]

Reads every <DOMAIN>.json in specs_dir (files starting with "_" are ignored),
validates them, applies the optional reconciliation file (cross-domain
ownership decisions: stories to drop and their replacements, small edits to
surviving stories, migration number bands per domain), resolves dependencies
written as tracker ids (T-xxx) to the stories that cover them, and writes
workbench/data/specs.json. Prints a validation
report: duplicate ids, unknown personas/services, dangling dependencies and
tracker tasks that no story covers.
"""
import json
import os
import re
import sys
from collections import Counter, defaultdict

HERE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(HERE, "..", "data", "specs.json")

PERSONAS = [
    ("reception", "Receptionist", "Front desk: queue, bookings, check-in, checkout"),
    ("doctor", "Doctor", "Consults, prescribes, reviews results, approves record requests"),
    ("nurse", "Nurse / triage", "Triage, vitals, nursing orders, vaccines"),
    ("solo", "Solo doctor", "Runs a clinic alone: queue, consult, payment from one screen"),
    ("ward", "Ward nurse (IPD)", "Beds, admissions, medication rounds, discharge"),
    ("er", "Emergency team", "ER triage, pathways, ambulance pre-arrival"),
    ("radiology", "Radiology", "Imaging worklist, reporting, critical findings"),
    ("hospops", "Hospital services", "Blood bank, CSSD, dietary, stores, records, roster, fleet"),
    ("pharmacist", "Pharmacist", "Dispensing, counter sale, stock, registers (in-clinic or standalone)"),
    ("lab", "Lab technician", "Samples, results, validation, QC (in-clinic or standalone)"),
    ("billing", "Billing & insurance", "Checkout, invoices, packages, claims, day close"),
    ("owner", "Owner / admin", "Settings, workflows, privacy, staff, reports, compliance"),
    ("patient", "Patient", "Books, waits from home, keeps records, controls sharing"),
    ("caregiver", "Caregiver / family", "Acts for a child, parent or relative within granted scope"),
    ("platform", "Nabd platform team", "Onboards clinics, runs regions, support with consent"),
    ("engineering", "Engineering", "Internal technical work that unblocks the personas above"),
]

SERVICES = [
    ("core", "Nabd Core", "Spring Boot modular monolith, system of record (ADR-01/02)"),
    ("comms-gateway", "Comms Gateway", "WhatsApp, SMS, email sending and webhooks"),
    ("patient-edge", "Patient Access Edge", "Public booking API, WhatsApp bot flows, aggregator, branded sites"),
    ("clinical-intelligence", "Clinical Intelligence", "Python CDS/AI service: rules first, guarded suggestions"),
    ("control-plane", "Control Plane", "Operator plane: tenants, provisioning, plans, regions, support"),
    ("staff-web", "Staff web", "Next.js staff app with BFF"),
    ("patient-web", "Patient web", "Secure links, consent approvals, aggregator site"),
    ("patient-app", "Patient app", "Expo mobile app (Phase 3, gated)"),
    ("infra", "Infrastructure", "Regional stacks, CI/CD, observability, data platform"),
]

STATUS_DEFAULT = "backlog"


def load_specs(spec_dir):
    out = []
    for name in sorted(os.listdir(spec_dir)):
        if not name.endswith(".json") or name.startswith("_") or name in ("tracker_tasks.json", "reconcile.json"):
            continue
        with open(os.path.join(spec_dir, name)) as f:
            out.append((name, json.load(f)))
    return out


def as_list(v):
    if v is None:
        return []
    return v if isinstance(v, list) else [v]


def main():
    spec_dir, tracker_path = sys.argv[1], sys.argv[2]
    tracker = json.load(open(tracker_path))
    persona_ids = {p[0] for p in PERSONAS}
    service_ids = {s[0] for s in SERVICES}

    domains, stories, problems = [], [], []
    seen = Counter()
    for fname, spec in load_specs(spec_dir):
        dom = spec.get("domain") or fname[:-5]
        d = {"id": dom, "name": spec.get("domain_name", dom), "squad": spec.get("owner_squad", ""),
             "summary": spec.get("summary", ""), "open_questions": as_list(spec.get("open_questions")), "epics": []}
        for ep in as_list(spec.get("epics")):
            e = {"id": ep.get("id"), "title": ep.get("title", ""), "goal": ep.get("goal", ""), "phase": ep.get("phase"), "stories": []}
            for st in as_list(ep.get("stories")):
                sid = st.get("id")
                seen[sid] += 1
                st = dict(st)
                st["domain"], st["epic"] = dom, e["id"]
                for k in ("acceptance", "api", "db", "events", "ui", "nfr", "tests", "subtasks", "depends_on",
                          "tracker_refs", "source_refs", "prototype", "other_personas", "also_touches"):
                    st[k] = as_list(st.get(k))
                st["estimate_days"] = float(st.get("estimate_days") or sum(float(x.get("estimate_days") or 0) for x in st["subtasks"]) or 0)
                svc = (st.get("service") or "").split("/")[0]
                if st.get("persona") not in persona_ids:
                    problems.append(f"{sid}: unknown persona {st.get('persona')!r}")
                if svc not in service_ids:
                    problems.append(f"{sid}: unknown service {st.get('service')!r}")
                stories.append(st)
                e["stories"].append(sid)
            d["epics"].append(e)
        domains.append(d)

    rec = json.load(open(sys.argv[3])) if len(sys.argv) > 3 else {}
    dropped = {d["id"]: d for d in rec.get("drop", [])}
    replaced = {k: as_list(v.get("replaced_by")) for k, v in dropped.items()}
    stories = [s for s in stories if s["id"] not in dropped]
    for d in domains:
        for e in d["epics"]:
            e["stories"] = [x for x in e["stories"] if x not in dropped]
        d["epics"] = [e for e in d["epics"] if e["stories"]]
    by_id = {s["id"]: s for s in stories}
    for ed in rec.get("edit", []):
        s = by_id.get(ed["id"])
        if not s:
            problems.append(f"reconcile edit for unknown story {ed['id']}")
            continue
        for k in ("depends_on", "tracker_refs", "source_refs", "acceptance", "also_touches", "other_personas"):
            for v in as_list(ed.get("add_" + k)):
                if v not in s[k]:
                    s[k].append(v)
        if ed.get("append_context"):
            s["context"] = (s.get("context", "") + "\n\n" + ed["append_context"]).strip()
        for k in ("priority", "phase", "sprint", "service", "module", "persona", "estimate_days"):
            if k in ed:
                s[k] = ed[k]
    bands = rec.get("migration_bands", {})
    for d in domains:
        d["migration_band"] = bands.get(d["id"], "")
        d["ownership_notes"] = [n for n in rec.get("ownership_notes", []) if d["id"] in str(n.get("domains", ""))]
    # Resolve dependencies: dropped stories -> replacements, tracker ids -> covering stories.
    cover_pre = defaultdict(list)
    for s in stories:
        for t in s["tracker_refs"]:
            cover_pre[str(t)].append(s["id"])
    for s in stories:
        out = []
        for dep in s["depends_on"]:
            dep = str(dep)
            targets = replaced.get(dep) if dep in dropped else ([x for x in cover_pre.get(dep, []) if x != s["id"]] if re.match(r"^T-\d{3}$", dep) else [dep])
            for t in targets or [dep]:
                if t not in out and t != s["id"]:
                    out.append(t)
        s["depends_on"] = out
    ids = {s["id"] for s in stories}
    for sid, n in seen.items():
        if n > 1:
            problems.append(f"duplicate story id {sid} ({n}x)")
    dangling = []
    for s in stories:
        for dep in s["depends_on"]:
            if re.match(r"^[A-Z]{3}-\d{3}$", str(dep)) and dep not in ids:
                dangling.append(f"{s['id']} -> {dep}")

    cover = defaultdict(list)
    for s in stories:
        for t in s["tracker_refs"]:
            cover[str(t)].append(s["id"])
    trk = []
    for t in tracker:
        tid = t.get("Task ID")
        trk.append({"id": tid, "title": t.get("Title"), "priority": t.get("Priority"), "phase": t.get("Phase"),
                    "area": t.get("Area/Module"), "stories": cover.get(tid, [])})
    uncovered = [t["id"] for t in trk if not t["stories"]]

    bundle = {
        "generated_from": sorted(n for n, _ in load_specs(spec_dir)),
        "personas": [{"id": a, "name": b, "desc": c} for a, b, c in PERSONAS],
        "services": [{"id": a, "name": b, "desc": c} for a, b, c in SERVICES],
        "domains": domains,
        "stories": stories,
        "tracker": trk,
        "status_default": STATUS_DEFAULT,
        "reconciliation": {"dropped": [{"id": k, "replaced_by": replaced[k], "reason": v.get("reason", "")} for k, v in dropped.items()],
                           "notes": rec.get("ownership_notes", []), "migration_bands": bands},
    }
    os.makedirs(os.path.dirname(OUT), exist_ok=True)
    with open(OUT, "w") as f:
        json.dump(bundle, f, separators=(",", ":"))

    est = sum(s["estimate_days"] for s in stories)
    subs = sum(len(s["subtasks"]) for s in stories)
    print(f"domains={len(domains)} stories={len(stories)} subtasks={subs} estimate_days={est:.0f}")
    print(f"tracker covered {len(trk) - len(uncovered)}/{len(trk)}; uncovered ({len(uncovered)}): {', '.join(uncovered[:40]) or 'none'}{' ...' if len(uncovered) > 40 else ''}")
    print(f"dropped by reconciliation: {len(dropped)}")
    print(f"problems: {len(problems)}")
    for p in problems[:60]:
        print("  ", p)
    print(f"dangling deps: {len(dangling)}")
    for p in dangling[:40]:
        print("  ", p)
    print("wrote", os.path.normpath(OUT), f"{os.path.getsize(OUT) / 1e6:.2f} MB")


if __name__ == "__main__":
    main()
