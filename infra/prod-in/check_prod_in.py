#!/usr/bin/env python3
"""OPS-056: static checks on infra/prod-in manifests. python3 infra/prod-in/check_prod_in.py [--selftest]"""
import pathlib
import re
import sys
import yaml

HERE = pathlib.Path(__file__).resolve().parent


def docs(text):
    return [d for d in yaml.safe_load_all(text) if d]


def problems(files):
    out = []
    deps = {}
    for f, text in files.items():
        if re.search(r"onrender\.com|vercel\.app|vercel\.com", text):
            out.append(f"{f}: references Render/Vercel (PHI must not reach them)")
        for d in docs(text):
            if d["kind"] == "Deployment":
                deps[d["metadata"]["name"]] = d
    for name in ("core", "worker", "bff"):
        d = deps.get(name)
        if not d:
            out.append(f"{name}: Deployment missing")
            continue
        s, pod = d["spec"], d["spec"]["template"]["spec"]
        c = pod["containers"][0]
        if s.get("replicas", 0) < 2:
            out.append(f"{name}: replicas must be >= 2")
        if s["strategy"]["rollingUpdate"].get("maxUnavailable") != 0:
            out.append(f"{name}: maxUnavailable must be 0")
        if "@sha256:" not in c["image"]:
            out.append(f"{name}: image must be pinned by digest")
        if pod.get("nodeSelector", {}).get("topology.kubernetes.io/region") != "ap-south-1":
            out.append(f"{name}: must be pinned to the Mumbai region")
        if not any(t.get("topologyKey") == "topology.kubernetes.io/zone" and t.get("whenUnsatisfiable") == "DoNotSchedule" for t in pod.get("topologySpreadConstraints", [])):
            out.append(f"{name}: replicas must be spread over zones")
        if "readinessProbe" not in c or "livenessProbe" not in c:
            out.append(f"{name}: readiness and liveness probes required")
        env = {e["name"]: e for e in c.get("env", [])}
        if any("value" in e and re.search(r"PASSWORD|SECRET|KEY$|PIN$|TOKEN", e["name"]) and e["name"] != "APP_CRYPTO_ACTIVE_KID" for e in c.get("env", [])):
            out.append(f"{name}: literal secret in env")
        if name in ("core", "worker") and "DB_URL" not in env:
            out.append(f"{name}: DB_URL (PgBouncer) missing")
    w = deps.get("worker")
    if w:
        e = {x["name"]: x for x in w["spec"]["template"]["spec"]["containers"][0]["env"]}
        if "worker" not in e.get("SPRING_PROFILES_ACTIVE", {}).get("value", ""):
            out.append("worker: SPRING_PROFILES_ACTIVE must include worker")
        if "valueFrom" not in e.get("RELAY_DB_URL", {}):
            out.append("worker: RELAY_DB_URL (direct, non-PgBouncer endpoint) missing")
    b = deps.get("bff")
    if b:
        api = next((x.get("value", "") for x in b["spec"]["template"]["spec"]["containers"][0]["env"] if x["name"] == "NEXT_PUBLIC_API_BASE_URL"), "")
        if not api.startswith("https://"):
            out.append("bff: NEXT_PUBLIC_API_BASE_URL must be https (the Mumbai ingress)")
    pol = [d for t in files.values() for d in docs(t) if d["kind"] == "ClusterPolicy"]
    if not pol or pol[0]["spec"].get("validationFailureAction") != "Enforce" or not pol[0]["spec"]["rules"][0]["verifyImages"][0].get("required"):
        out.append("verify-images: signature policy must be Enforce and required")
    return out


def load():
    return {p.name: p.read_text() for p in HERE.glob("*.yaml")}


def selftest():
    good = load()
    assert not problems(good), problems(good)
    muts = [("core.yaml", "replicas: 2", "replicas: 1"), ("worker.yaml", "replicas: 2", "replicas: 1"), ("bff.yaml", "replicas: 2", "replicas: 1"),
            ("core.yaml", "@sha256:REPLACE_DIGEST", ":latest"), ("core.yaml", "ap-south-1", "us-east-1"),
            ("worker.yaml", "RELAY_DB_URL", "X_URL"), ("worker.yaml", '"prod,worker"', "prod"),
            ("bff.yaml", "https://REPLACE_API_HOST/v1", "https://nabd-f0zb.onrender.com/v1"),
            ("core.yaml", "name: DB_USER, value: nabd_app", "name: DB_PASSWORD, value: hunter2"),
            ("verify-images.yaml", "Enforce", "Audit"), ("core.yaml", "whenUnsatisfiable: DoNotSchedule", "whenUnsatisfiable: ScheduleAnyway")]
    for f, a, b in muts:
        assert a in good[f], (f, a)
        bad = dict(good)
        bad[f] = good[f].replace(a, b)
        assert problems(bad), f"not caught: {f} {a!r}"
    print(f"selftest ok ({len(muts)} mutations caught)")


if __name__ == "__main__":
    if "--selftest" in sys.argv:
        selftest()
        sys.exit(0)
    p = problems(load())
    print("\n".join(p) if p else "prod-in manifests ok")
    sys.exit(1 if p else 0)
