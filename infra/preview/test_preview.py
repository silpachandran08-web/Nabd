#!/usr/bin/env python3
"""OPS-036 artefact checks + reaper dry-run: python3 infra/preview/test_preview.py (run from repo root)."""
import json, re, subprocess, sys, unittest
from pathlib import Path
import yaml

sys.path.insert(0, "infra/preview")
import preview

R = lambda p: open(p).read()
DOCS = {(d["kind"], d["metadata"]["name"]): d for d in yaml.safe_load_all(R("infra/preview/preview.yaml")) if d}
JOB = yaml.safe_load(R("infra/preview/migrate.yaml"))
WF = yaml.safe_load(R(".github/workflows/preview.yml"))
REAPER = yaml.safe_load(R(".github/workflows/preview-reaper.yml"))
CI = yaml.safe_load(R(".github/workflows/ci.yml"))
NOW = "2026-10-19T12:00:00Z"


def ns(pr, created):
    return {"metadata": {"name": f"preview-pr-{pr}", "creationTimestamp": created, "labels": {"nabd.io/preview": "true", "nabd.io/pr": str(pr)}}}


class Manifest(unittest.TestCase):
    def test_namespace_carries_cost_tags_and_expiry(self):
        n = DOCS[("Namespace", "${NS}")]["metadata"]
        self.assertEqual(n["labels"]["nabd.io/cost-center"], "previews")
        self.assertEqual(n["labels"]["nabd.io/pr"], "${PR}")
        self.assertIn("nabd.io/expires-at", n["annotations"])

    def test_core_bff_and_stubs_present(self):
        for name in ("core", "web", "postgres", "comms-stub"):
            self.assertIn(("Deployment", name), DOCS); self.assertIn(("Service", name), DOCS)
        paths = {p["path"]: p["backend"]["service"]["name"] for p in DOCS[("Ingress", "edge")]["spec"]["rules"][0]["http"]["paths"]}
        self.assertEqual(paths, {"/v1": "core", "/": "web"})
        self.assertEqual(JOB["kind"], "Job")

    def test_cannot_reach_prod_networks(self):
        np = DOCS[("NetworkPolicy", "isolate")]["spec"]
        self.assertEqual(np["podSelector"], {}); self.assertEqual(sorted(np["policyTypes"]), ["Egress", "Ingress"])
        for rule in np["egress"]:
            for to in rule["to"]:
                self.assertNotIn("ipBlock", to)            # no route to any outside address
                if "namespaceSelector" in to:               # only kube-dns, only port 53
                    self.assertEqual(to["podSelector"]["matchLabels"], {"k8s-app": "kube-dns"})
                    self.assertEqual({p["port"] for p in rule["ports"]}, {53})
        env = {e["name"]: e for e in DOCS[("Deployment", "core")]["spec"]["template"]["spec"]["containers"][0]["env"]}
        self.assertEqual(env["WHATSAPP_API_BASE_URL"]["value"], "http://comms-stub/v21.0")
        self.assertNotIn("WHATSAPP_ACCESS_TOKEN", env)

    def test_no_prod_secrets_no_literal_secrets_no_backups(self):
        self.assertFalse(DOCS[("ServiceAccount", "preview")]["automountServiceAccountToken"])
        for d in [*DOCS.values(), JOB]:
            spec = d.get("spec", {}).get("template", {}).get("spec", {})
            for c in spec.get("containers", []):
                for e in c.get("env", []):
                    if re.search(r"PASSWORD|KEY|TOKEN|PIN", e["name"]):
                        self.assertIn("valueFrom", e, e["name"])
                        self.assertEqual(e["valueFrom"]["secretKeyRef"]["name"], "preview-secrets")
            for v in spec.get("volumes", []):
                self.assertNotIn("persistentVolumeClaim", v)
        self.assertEqual(DOCS[("ResourceQuota", "preview-cap")]["spec"]["hard"]["persistentvolumeclaims"], "0")
        values = json.dumps([*DOCS.values(), JOB]).lower().replace("non-prod", "")
        self.assertNotIn("prod", values)   # no prod host, profile, secret or namespace anywhere in the rendered objects


class Reaper(unittest.TestCase):
    L = {"items": [ns(10, "2026-10-16T11:00:00Z"), ns(11, "2026-10-16T13:00:00Z"), ns(12, "2026-10-19T08:00:00Z"),
                   {"metadata": {"name": "kube-system", "creationTimestamp": "2020-01-01T00:00:00Z", "labels": {}}}]}

    def test_reaps_after_72h_and_on_merged_or_closed_pr(self):
        got = dict(preview.to_reap(self.L, {10, 11}, NOW))
        self.assertEqual(set(got), {"preview-pr-10", "preview-pr-12"})   # 73 h old; PR 12 closed. PR 11 is 71 h old: kept
        self.assertIn("older than 72 h", got["preview-pr-10"]); self.assertIn("merged or closed", got["preview-pr-12"])

    def test_dry_run_cli_deletes_nothing(self):
        r = subprocess.run([sys.executable, "infra/preview/preview.py", "reap", "--open-prs", "10,11", "--now", NOW],
                           input=json.dumps(self.L), capture_output=True, text=True)
        self.assertEqual(r.returncode, 0, r.stderr)
        self.assertEqual(r.stdout.count("would delete"), 2); self.assertNotIn("DELETE", r.stdout)

    def test_concurrency_cap(self):
        self.assertTrue(preview.over_cap(self.L, 3, 99))
        self.assertFalse(preview.over_cap(self.L, 3, 10))   # a redeploy of a running preview is always allowed
        self.assertFalse(preview.over_cap(self.L, 4, 99))


class Comment(unittest.TestCase):
    def test_url_and_one_login_per_role_and_tenant(self):
        logins = {"tenants": {k: {"slug": f"preview-{k.lower()}", "staff": {r: {"email": f"{r}@x.example.test", "pin": "00000000"} for r in ("owner", "doctor")}} for k in ("IN", "SA", "KSA")}}
        md = preview.comment("https://pr-7.preview.example", logins)
        self.assertTrue(md.startswith("<!-- preview-env -->"))
        self.assertIn("https://pr-7.preview.example", md)
        self.assertEqual(md.count("| IN |"), 2); self.assertEqual(md.count("| SA |"), 2); self.assertNotIn("| KSA |", md)


class Workflows(unittest.TestCase):
    def test_deploy_within_8_minutes_with_oidc_and_no_kubeconfig_secret(self):
        self.assertEqual(WF["jobs"]["deploy"]["timeout-minutes"], 8)
        self.assertEqual(WF["permissions"]["id-token"], "write")
        self.assertNotIn("KUBECONFIG", R(".github/workflows/preview.yml").upper().replace("NO KUBECONFIG", ""))
        self.assertIn("infra/preview/deploy.sh", str(WF["jobs"]["deploy"]["steps"]))
        self.assertIn("gh pr comment", str(WF["jobs"]["deploy"]["steps"]))

    def test_smoke_runs_on_the_preview_and_blocks_the_merge(self):
        self.assertEqual(WF["jobs"]["smoke"]["uses"], "./.github/workflows/e2e-smoke.yml")
        self.assertEqual(CI["jobs"]["preview"]["uses"], "./.github/workflows/preview.yml")
        gate = CI["jobs"]["stage7-preview-e2e"]
        self.assertEqual(gate["needs"], "preview")
        self.assertIn("needs.preview.result", str(gate["steps"]))
        self.assertIn("stage7-preview-e2e", json.load(open(".github/branch-protection.json"))["required_status_checks"]["contexts"])

    def test_reaper_runs_hourly_and_on_close(self):
        on = REAPER.get("on") or REAPER.get(True)
        self.assertEqual(on["pull_request"]["types"], ["closed"])
        self.assertTrue(on["schedule"])
        self.assertIn("preview.py reap", str(REAPER["jobs"]["reap"]["steps"]))

    def test_deploy_script_parses_and_seeds_through_the_scanner(self):
        subprocess.run(["bash", "-n", "infra/preview/deploy.sh"], check=True)
        d = R("infra/preview/deploy.sh")
        self.assertIn("preview.py\" cap", d)
        self.assertLess(d.index("synth.py\" generate"), d.index("load.py"))
        self.assertIn("synth.scan(data)", R("tools/synthetic-data/load.py"))   # the loader refuses flagged data itself


if __name__ == "__main__":
    unittest.main()
