#!/usr/bin/env python3
"""OPS-057: CI proof that no PHI-bearing endpoint is outside the cell.

  python3 infra/residency/check_egress.py [--cell in] [--root .] [--report out.json] [--endpoints-file live.json]
  python3 infra/residency/check_egress.py --selftest

Checks, per cell (sinks.yaml is the inventory, see its header):
  1. every inventoried host of an in_country sink matches the cell's in-country host patterns (regions from residency.tf);
     an approved_processor needs a processor, a contract file that exists, phi_in_payload false and phi false;
     a sink holding PHI may never be an approved_processor.
  2. every endpoint found in the repo config (OTel exporters, application*.yml, Java sources, prod-in manifests, plus the
     optional --endpoints-file of live values the nightly job exports) is inventoried or listed in not_sinks, else it fails
     naming the endpoint and the file.
  3. infra/prod-in/egress-policy.yaml has a default-deny egress policy and allows exactly the inventoried hosts.
Exit 1 on any violation; the JSON report (always written with --report) lists what was checked.
"""
import argparse, datetime, fnmatch, json, pathlib, re, sys, tempfile

import yaml

ROOT = pathlib.Path(__file__).resolve().parents[2]
URL = re.compile(r"https?://([A-Za-z0-9_.-]+)")
SCAN = ["backend/src/main/java/**/*.java", "backend/src/main/resources/application*.yml", "infra/prod-in/*.yaml",
        "infra/staging-in/*.yaml", "infra/observability/stack/*.yaml"]
POLICY = "infra/prod-in/egress-policy.yaml"


def regions(root, cell):
    tf = (root / cell["residency_tf"]).read_text()
    m = re.search(r"\b%s\s*=\s*\[([^\]]*)\]" % re.escape(cell["country"]), tf)
    return re.findall(r'"([^"]+)"', m.group(1)) if m else []


def patterns(root, cell):
    regs = regions(root, cell)
    out = []
    for p in cell["in_country_hosts"]:
        out += [p.replace("{region}", r) for r in regs] if "{region}" in p else [p]
    return out


def match(host, pats):
    return any(fnmatch.fnmatch(host, p) for p in pats)


def configured(root, extra):
    """(host, where) for every endpoint found in the scanned config; REPLACE_ placeholders are skipped."""
    found = []
    for g in SCAN:
        for f in sorted(root.glob(g)):
            for host in URL.findall(f.read_text(errors="ignore")):
                if "REPLACE_" not in host:
                    found.append((host.lower().rstrip("."), str(f.relative_to(root))))
    found += [(h.lower(), "live endpoints") for h in extra]
    return found


def policy_hosts(root):
    docs = [d for d in yaml.safe_load_all((root / POLICY).read_text()) if d]
    deny = any(d.get("kind") == "NetworkPolicy" and d["spec"].get("podSelector") == {} and "Egress" in d["spec"].get("policyTypes", [])
               and not d["spec"].get("egress") for d in docs)
    hosts = set()
    for d in docs:
        if d.get("kind") == "CiliumNetworkPolicy":
            for rule in d["spec"].get("egress", []):
                for f in rule.get("toFQDNs", []):
                    hosts.add(f.get("matchName") or f.get("matchPattern"))
    return deny, hosts


def check(root, cell_name, extra=()):
    inv = yaml.safe_load((root / "infra/residency/sinks.yaml").read_text())
    cell = inv["cells"][cell_name]
    pats = patterns(root, cell)
    errors, checked, inventoried = [], [], {}
    for s in inv["sinks"]:
        hosts = s["cells"].get(cell_name, {}).get("hosts", [])
        if s["class"] == "not_used":
            if hosts:
                errors.append(f"{s['id']}: class not_used but lists {hosts}")
            continue
        for h in hosts:
            inventoried[h.lower()] = s
            ok = True
            if s["class"] == "in_country":
                ok = match(h, pats)
            elif s["class"] == "approved_processor":
                ok = (s.get("processor") and s.get("phi_in_payload") is False and s.get("phi") is False
                      and s.get("contract") and (root / s["contract"]).exists())
                if not ok:
                    errors.append(f"{s['id']}: {h} is outside the cell and the processor approval is incomplete "
                                  "(needs processor, existing contract file, phi false, phi_in_payload false)")
                    ok = None
            else:
                errors.append(f"{s['id']}: unknown class {s['class']}")
                ok = None
            if ok is False:
                errors.append(f"{s['id']}: {h} is outside the cell {cell_name} (not in-country)")
            checked.append({"sink": s["id"], "host": h, "class": s["class"], "ok": bool(ok)})
    skip = [n["host"].lower() for n in inv.get("not_sinks", [])]
    for host, where in configured(root, extra):
        if match(host, skip) or host in inventoried or match(host, list(inventoried)):
            continue
        errors.append(f"endpoint {host} (in {where}) is not in the sink inventory and not in-country: add it to infra/residency/sinks.yaml "
                      "as in_country, or as an approved_processor with a contract")
    if (root / POLICY).exists():
        deny, allowed = policy_hosts(root)
        if not deny:
            errors.append(f"{POLICY}: no default-deny egress NetworkPolicy (podSelector {{}}, policyTypes Egress, no egress rules)")
        want = set(inventoried)
        for h in sorted(allowed - want):
            errors.append(f"{POLICY} allows {h}, which is not an inventoried sink host")
        for h in sorted(want - allowed):
            errors.append(f"{POLICY} does not allow inventoried host {h}")
    else:
        errors.append(f"{POLICY} is missing (default-deny egress)")
    return {"cell": cell_name, "at": datetime.datetime.now(datetime.timezone.utc).isoformat(timespec="seconds"),
            "checked": checked, "violations": errors, "ok": not errors}


def selftest():
    import shutil
    base = tempfile.mkdtemp()
    for rel in ["infra/residency", "infra/prod-in", "infra/observability/stack", "infra/terraform/modules/cell", "backend/src/main/resources/dpa",
                "backend/src/main/java", "backend/src/main/resources"]:
        (pathlib.Path(base) / rel).mkdir(parents=True, exist_ok=True)
    for rel in ["infra/residency/sinks.yaml", POLICY, "infra/terraform/modules/cell/residency.tf", "backend/src/main/resources/dpa/sub-processors.txt",
                "infra/observability/stack/otel-collector.yaml"]:
        shutil.copy(ROOT / rel, pathlib.Path(base) / rel)
    b = pathlib.Path(base)
    assert check(b, "in")["ok"], check(b, "in")["violations"]
    ex = b / "infra/observability/stack/otel-collector.yaml"
    ex.write_text(ex.read_text() + "\n  otlp/vendor: { endpoint: \"https://collector.eu.vendor.example:4317\" }\n")
    bad = check(b, "in")
    assert not bad["ok"] and any("collector.eu.vendor.example" in v for v in bad["violations"]), bad
    shutil.copy(ROOT / "infra/observability/stack/otel-collector.yaml", ex)
    s = b / "infra/residency/sinks.yaml"
    s.write_text(s.read_text().replace("tempo.obs.cell.internal", "tempo.eu-west-1.example.com"))
    assert any("tempo.eu-west-1.example.com" in v for v in check(b, "in")["violations"])
    shutil.copy(ROOT / "infra/residency/sinks.yaml", s)
    s.write_text(s.read_text().replace("    phi: false   # utility templates", "    phi: true   # utility templates"))
    assert any("whatsapp" in v for v in check(b, "in")["violations"])
    shutil.copy(ROOT / "infra/residency/sinks.yaml", s)
    assert any("eu-west-1" in v for v in check(b, "in", ["s3.eu-west-1.amazonaws.com"])["violations"])
    print("selftest ok")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--cell", default="in")
    ap.add_argument("--root", default=str(ROOT))
    ap.add_argument("--report")
    ap.add_argument("--endpoints-file", help="JSON list of live endpoint hosts exported from the cell (nightly)")
    ap.add_argument("--selftest", action="store_true")
    a = ap.parse_args()
    if a.selftest:
        return selftest()
    extra = json.loads(pathlib.Path(a.endpoints_file).read_text()) if a.endpoints_file else []
    r = check(pathlib.Path(a.root), a.cell, extra)
    if a.report:
        pathlib.Path(a.report).write_text(json.dumps(r, indent=1))
    for v in r["violations"]:
        print("VIOLATION:", v, file=sys.stderr)
    print(f"cell {a.cell}: {len(r['checked'])} sink hosts checked, {len(r['violations'])} violation(s)")
    return 0 if r["ok"] else 1


if __name__ == "__main__":
    sys.exit(main())
