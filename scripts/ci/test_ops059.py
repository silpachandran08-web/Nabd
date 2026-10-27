"""OPS-059 tests: DR failover/failback script (ordering, guards, timing, RTO/RPO, audit chain) and the Terraform standby shape."""
import copy
import pathlib
import sys
import unittest

ROOT = pathlib.Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "infra" / "dr"))
import dr_failover as d  # noqa: E402

CFG = {k: ("x" if "REPLACE" in str(v) else v) for k, v in d.EXAMPLE.items()}


class Rec(d.Runner):
    def __init__(self, canned=None, fail_on=None):
        self.cmds, self.canned, self.fail_on = [], canned or {}, fail_on

    def run(self, cmd, stdin=None):
        line = " ".join(cmd)
        self.cmds.append(line)
        if self.fail_on and self.fail_on in line:
            raise d.StepFailed("boom")
        for k, v in self.canned.items():
            if k in line:
                return v
        return ""


OK = {"describe-db-instances": "available nabd-in-prod true", "ReplicaLag": "20", "verify_audit_chain": "0"}
TICK = lambda: iter(range(0, 10000, 10))


def clock():
    it = iter(range(0, 100000, 10))
    return lambda: float(next(it))


class DrFailover(unittest.TestCase):
    def test_order_promote_before_dns_and_verify_last(self):
        r = Rec(OK)
        rec = d.failover(CFG, r, clock=clock(), fetch=False)
        order = [s["step"] for s in rec["steps"]]
        self.assertLess(order.index("fence Mumbai runtime"), order.index("promote DR replica"))
        self.assertLess(order.index("promote DR replica"), order.index("switch DNS to Hyderabad"))
        self.assertEqual(order[-1], "verify: audit chain on DR")
        self.assertTrue(rec["within_rto_1h"] and rec["rpo_within_5min"])

    def test_region_outside_country_refused_before_any_command(self):
        for k, v in (("dr_region", "us-east-1"), ("primary_region", "eu-west-1")):
            r, c = Rec(OK), copy.deepcopy(CFG)
            c[k] = v
            with self.assertRaises(d.StepFailed):
                d.failover(c, r, fetch=False)
            self.assertEqual(r.cmds, [])

    def test_same_region_and_unknown_country_refused(self):
        c = dict(CFG, dr_region="ap-south-1")
        with self.assertRaises(d.StepFailed):
            d.guard_regions(c)
        with self.assertRaises(d.StepFailed):
            d.guard_regions(dict(CFG, country="SA"))

    def test_lag_over_rpo_stops_before_promote(self):
        r = Rec(dict(OK, ReplicaLag="900"))
        with self.assertRaises(d.StepFailed):
            d.failover(CFG, r, clock=clock(), fetch=False)
        self.assertFalse(any("promote-read-replica" in c for c in r.cmds))

    def test_failed_step_stops_run(self):
        r = Rec(OK, fail_on="route53")
        with self.assertRaises(d.StepFailed):
            d.failover(CFG, r, clock=clock(), fetch=False)
        self.assertFalse(any("verify_audit_chain" in c for c in r.cmds))

    def test_rto_breach_is_reported(self):
        t = iter(range(0, 100000, 400))
        rec = d.failover(CFG, Rec(OK), clock=lambda: float(next(t)), fetch=False)
        self.assertFalse(rec["within_rto_1h"])

    def test_audit_chain_failure_fails_run(self):
        with self.assertRaises(d.StepFailed):
            d.failover(CFG, Rec(dict(OK, verify_audit_chain="2")), clock=clock(), fetch=False)

    def test_unreachable_primary_skips_fence_and_warns(self):
        rec = d.failover(CFG, Rec(OK), primary_reachable=False, clock=clock(), fetch=False)
        self.assertNotIn("fence Mumbai runtime", [s["step"] for s in rec["steps"]])
        self.assertIn("warning", rec)

    def test_placeholders_refused_for_execute(self):
        with self.assertRaises(d.StepFailed):
            d.guard_placeholders(d.EXAMPLE)

    def test_failback_data_loss_blocks_promotion(self):
        class R(Rec):
            def run(self, cmd, stdin=None):
                if cmd[0] == "psql" and "dr" in cmd[1]:
                    return "patients|10\nqueue_entries|1\ninvoices|1\nappointments|1\naudit_log|5"
                if cmd[0] == "psql":
                    return "patients|9\nqueue_entries|1\ninvoices|1\nappointments|1\naudit_log|5"
                return super().run(cmd, stdin)
        c = dict(CFG, dr_db_url="dr", primary_db_url="mum")
        r = R(OK)
        with self.assertRaises(d.StepFailed):
            d.failback(c, r, clock=clock(), fetch=False)
        self.assertFalse(any("promote-read-replica" in x and "ap-south-1" in x for x in r.cmds))

    def test_failback_order(self):
        class R(Rec):
            def run(self, cmd, stdin=None):
                if cmd[0] == "psql" and "SELECT 1" in " ".join(cmd):
                    return "patients|10\nqueue_entries|1\ninvoices|1\nappointments|1\naudit_log|5"
                return super().run(cmd, stdin)
        rec = d.failback(CFG, R(OK), clock=clock(), fetch=False)
        o = [s["step"] for s in rec["steps"]]
        self.assertLess(o.index("fence Hyderabad runtime"), o.index("promote Mumbai"))
        self.assertLess(o.index("compare key tables (no data lost from the DR period)"), o.index("promote Mumbai"))
        self.assertLess(o.index("switch DNS back to Mumbai"), o.index("recreate the Hyderabad replica"))


class DrTerraform(unittest.TestCase):
    def test_standby_shape(self):
        t = (ROOT / "infra/terraform/modules/cell/aws/dr_standby.tf").read_text()
        for needle in ("replicate_source_db", "multi_az", "deletion_protection", "backup_retention_period", "ReplicaLag", "local.dr_standby"):
            self.assertIn(needle, t)
        v = (ROOT / "infra/terraform/modules/cell/aws/variables_ops059.tf").read_text()
        self.assertRegex(v.replace(" ", ""), r'dr_standby_enabled"\{[^}]*default=false')

    def test_standby_only_on_cross_region_cells(self):
        t = (ROOT / "infra/terraform/modules/cell/aws/dr_standby.tf").read_text()
        self.assertIn("var.dr_standby_enabled && local.cross_region", t)

    def test_script_region_list_equals_residency_tf(self):
        t = (ROOT / "infra/terraform/modules/cell/residency.tf").read_text()
        for r in d.COUNTRY_REGIONS["IN"]:
            self.assertIn(r, t)


if __name__ == "__main__":
    unittest.main()
