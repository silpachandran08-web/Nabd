"""OPS-057: the residency egress check passes on the repo and fails, naming the endpoint, on a bad change."""
import importlib.util, pathlib, shutil, tempfile, unittest

ROOT = pathlib.Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location("check_egress", ROOT / "infra/residency/check_egress.py")
ce = importlib.util.module_from_spec(spec)
spec.loader.exec_module(ce)


def tree():
    tmp = pathlib.Path(tempfile.mkdtemp())
    for rel in ["infra/residency/sinks.yaml", ce.POLICY, "infra/terraform/modules/cell/residency.tf", "backend/src/main/resources/dpa/sub-processors.txt",
                "infra/observability/stack/otel-collector.yaml", "backend/src/main/resources/application.yml"]:
        (tmp / rel).parent.mkdir(parents=True, exist_ok=True)
        shutil.copy(ROOT / rel, tmp / rel)
    return tmp


def violations(t, extra=()):
    return " | ".join(ce.check(t, "in", extra)["violations"])


class Ops057(unittest.TestCase):
    def test_repo_is_clean(self):
        r = ce.check(ROOT, "in")
        self.assertTrue(r["ok"], r["violations"])
        self.assertTrue({"postgres", "valkey", "object-storage", "backups", "logs", "traces", "metrics", "whatsapp"} <= {c["sink"] for c in r["checked"]})

    def test_new_exporter_outside_the_cell_fails_naming_it(self):
        t = tree()
        f = t / "infra/observability/stack/otel-collector.yaml"
        f.write_text(f.read_text() + '\n  otlp/vendor: { endpoint: "https://ingest.eu-west-1.vendor.example:4317" }\n')
        self.assertIn("ingest.eu-west-1.vendor.example", violations(t))

    def test_sdk_url_in_application_yml_fails(self):
        t = tree()
        f = t / "backend/src/main/resources/application.yml"
        f.write_text(f.read_text() + "\nanalytics:\n  url: https://api.analytics.example.com/v1\n")
        self.assertIn("api.analytics.example.com", violations(t))

    def test_phi_sink_cannot_be_an_approved_processor(self):
        t = tree()
        f = t / "infra/residency/sinks.yaml"
        f.write_text(f.read_text().replace("    phi: false   # utility templates", "    phi: true   # utility templates"))
        self.assertIn("whatsapp", violations(t))

    def test_us_region_is_not_in_country(self):
        self.assertIn("s3.us-east-1.amazonaws.com", violations(tree(), ["s3.us-east-1.amazonaws.com"]))

    def test_policy_must_default_deny_and_match_inventory(self):
        t = tree()
        f = t / ce.POLICY
        f.write_text(f.read_text().replace("policyTypes: [Egress]", "policyTypes: [Ingress]").replace("matchName: graph.facebook.com", "matchName: evil.example.com"))
        v = violations(t)
        for needle in ("default-deny", "evil.example.com", "graph.facebook.com"):
            self.assertIn(needle, v)


if __name__ == "__main__":
    unittest.main(verbosity=2)
