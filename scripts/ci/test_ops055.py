"""OPS-055 data tier: static checks that run without a cloud account (python3 -m unittest scripts/ci/test_ops055.py)."""
import json
import pathlib
import re
import shutil
import subprocess
import sys
import unittest

ROOT = pathlib.Path(__file__).resolve().parents[2]
TF = ROOT / "infra" / "terraform"
AWS = TF / "modules" / "cell" / "aws"


def run(*cmd, **kw):
    return subprocess.run([str(c) for c in cmd], capture_output=True, text=True, cwd=kw.get("cwd", ROOT))


class DataTier(unittest.TestCase):
    def test_terraform_static_rules_and_selftest(self):
        self.assertEqual(run(sys.executable, TF / "check-terraform.py").returncode, 0)
        r = run(sys.executable, TF / "check-terraform.py", "--selftest")
        self.assertEqual(r.returncode, 0, r.stdout)
        for case in ("single_pooler", "session_pool", "no_prepared", "open_sg", "pg_no_pool_params"):
            self.assertIn(f"selftest {case}: caught", r.stdout)

    def test_pgbouncer_config_is_transaction_mode_with_prepared_statements(self):
        ini = (AWS / "data_tier_pgbouncer.ini.tftpl").read_text()
        self.assertRegex(ini, r"(?m)^pool_mode = transaction$")
        self.assertRegex(ini, r"(?m)^max_prepared_statements = [1-9]\d*$")
        self.assertRegex(ini, r"(?m)^auth_user = pgbouncer_auth$")
        self.assertNotRegex(ini, r"(?i)password\s*=")  # secrets come from Secrets Manager at boot
        # the migrator and the admin user are never pooled
        self.assertNotIn("nabd_migrator", ini)

    def test_boot_script_refuses_pgbouncer_below_1_21(self):
        sh = (AWS / "data_tier_userdata.sh.tftpl").read_text()
        self.assertIn("1.21", sh)
        self.assertIn("shutdown -h now", sh)

    def test_pool_wait_alarm_is_100ms_for_5_minutes(self):
        t = (AWS / "data_tier_monitoring.tf").read_text()
        block = t[t.index('"pool_wait"'):t.index('"pool_waiting_clients"')]
        self.assertIn('metric_name         = "AvgWaitTimeMs"', block)
        self.assertIn("threshold           = var.alarm_pool_wait_ms", block)
        self.assertRegex(block, r"period\s*=\s*60")
        self.assertRegex(block, r"evaluation_periods\s*=\s*5")
        v = (AWS / "variables_ops055.tf").read_text()
        self.assertRegex(v, r'variable "alarm_pool_wait_ms"[^}]*default\s*=\s*100')
        # the exporter publishes exactly the three metrics the story names
        self.assertEqual(run(sys.executable, ROOT / "infra/pgbouncer/pool-metrics.py", "--selftest").returncode, 0)
        src = (ROOT / "infra/pgbouncer/pool-metrics.py").read_text()
        for m in ("ClWaiting", "SvActive", "AvgWaitTimeMs"):
            self.assertIn(f'"{m}"', src)

    def test_relay_and_business_traffic_use_different_endpoints(self):
        out = (AWS / "data_tier_outputs.tf").read_text()
        self.assertRegex(out, r"pooler_host\s*=\s*aws_route53_record\.pool\.fqdn")
        self.assertRegex(out, r"direct_host\s*=\s*aws_route53_record\.db\.fqdn")
        self.assertIn("RELAY_DB_URL", (ROOT / "docs/runbooks/data-tier.md").read_text())

    def test_satellite_bootstrap_grants_nothing_on_core(self):
        sql = (ROOT / "infra/db/bootstrap-satellites.sql").read_text()
        self.assertIn("REVOKE ALL ON DATABASE nabd_comms FROM PUBLIC", sql)
        self.assertIn("REVOKE ALL ON DATABASE %I FROM PUBLIC, comms_migrator, comms_app, edge_migrator, edge_app", sql)
        self.assertIsNotNone(re.search(r"test-satellite-grants", (ROOT / ".github/workflows/infra.yml").read_text()))

    @unittest.skipUnless(shutil.which("conftest"), "conftest not installed")
    def test_data_tier_policy_good_passes_bad_fails(self):
        pol = TF / "policy"
        self.assertEqual(run("conftest", "test", "--policy", pol, pol / "fixtures/plan-data-tier-good.json").returncode, 0)
        bad = run("conftest", "test", "--no-color", "--policy", pol, pol / "fixtures/plan-data-tier-bad.json")
        self.assertEqual(bad.returncode, 1)
        for msg in ("at least 2 instances", "Multi-AZ", "storage autoscaling", "max_connections", "IMDSv2", "internal", "AvgWaitTimeMs"):
            self.assertIn(msg, bad.stdout)


if __name__ == "__main__":
    unittest.main()
