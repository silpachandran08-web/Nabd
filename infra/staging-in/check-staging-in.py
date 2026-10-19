#!/usr/bin/env python3
"""OPS-053: asserts the staging-in artefacts keep the zero-logout properties. Run: python3 infra/staging-in/check-staging-in.py [manifest]
Exit 1 with failed assertions listed. Self-test: python3 infra/staging-in/check-staging-in.py --selftest"""
import sys
import yaml

def problems(path):
    docs = {d["kind"]: d for d in yaml.safe_load_all(open(path)) if d}
    f = []
    dep, svc, pdb = docs.get("Deployment"), docs.get("Service"), docs.get("PodDisruptionBudget")
    if not dep: return ["no Deployment"]
    spec = dep["spec"]; c = spec["template"]["spec"]["containers"][0]
    if spec.get("replicas") != 2: f.append("replicas must be 2")
    ru = spec.get("strategy", {}).get("rollingUpdate", {})
    if ru.get("maxUnavailable") != 0: f.append("rollingUpdate.maxUnavailable must be 0")
    if "readinessProbe" not in c: f.append("readinessProbe missing (LB must route only to ready pods)")
    if "livenessProbe" not in c: f.append("livenessProbe missing")
    if "preStop" not in c.get("lifecycle", {}): f.append("preStop drain delay missing")
    if spec["template"]["spec"].get("terminationGracePeriodSeconds", 30) <= 35: f.append("terminationGracePeriodSeconds must exceed preStop 10s + 25s shutdown phase")
    envs = {e["name"] for e in c.get("env", [])}
    if "JWT_KEYS_FILE" not in envs: f.append("JWT_KEYS_FILE missing (replicas must share signing keys)")
    if "@sha256:" not in c["image"]: f.append("image must be pinned by digest")
    if any("value" in e and e["name"].endswith(("PASSWORD", "PIN")) for e in c.get("env", [])): f.append("literal secret in env")
    if not svc or svc["spec"].get("sessionAffinity", "None") != "None": f.append("Service must not use sticky sessions")
    if not pdb: f.append("PodDisruptionBudget missing")
    return f

def main():
    if "--selftest" in sys.argv:
        import tempfile, os
        good = open(os.path.join(os.path.dirname(__file__), "core.yaml")).read()
        assert not problems(os.path.join(os.path.dirname(__file__), "core.yaml")), "good manifest rejected"
        for bad in [good.replace("replicas: 2", "replicas: 1"), good.replace("maxUnavailable: 0", "maxUnavailable: 1"),
                    good.replace("readinessProbe:", "xProbe:"), good.replace("type: LoadBalancer", "type: LoadBalancer\n  sessionAffinity: ClientIP")]:
            with tempfile.NamedTemporaryFile("w", suffix=".yaml", delete=False) as t: t.write(bad)
            assert problems(t.name), "bad manifest accepted"
            os.unlink(t.name)
        print("selftest ok"); return
    f = problems(sys.argv[1] if len(sys.argv) > 1 else "infra/staging-in/core.yaml")
    if f: print("FAILED:\n  " + "\n  ".join(f)); sys.exit(1)
    print("staging-in manifest ok")

main()
