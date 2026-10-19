#!/usr/bin/env python3
"""Independent QA (S11 CLN-007): hazard gate, safety test report, CSO release approval.
Run from the repo root: python3 scripts/ci/test_s11qa_safety_report.py
Tests marked expectedFailure document real gaps (known-defect-CLN-007) so CI stays green; see reports/s11-QA-1.md."""
import contextlib, io, json, pathlib, sys, tempfile, textwrap, unittest
sys.path.insert(0, "scripts/ci")
import safety_report as sr
import yaml

HEADER = "id,source,hazard,cause,existing_control,planned_control,verifying_test,residual_risk,owner,status,closing_pr\n"


def make_repo(hazards, java):
    """A throwaway repo: a hazard log with `hazards` and one Java test file per {class: source}."""
    root = pathlib.Path(tempfile.mkdtemp()) / "repo"
    log = root / sr.LOG
    log.parent.mkdir(parents=True)
    log.write_text(HEADER + "".join(f"{h},QA,qa hazard {h},c,e,p,v,UNRATED,CSO,open,\n" for h in hazards))
    (root / sr.SIGNOFF).parent.mkdir(parents=True)
    (root / sr.SIGNOFF).write_text(json.dumps({"version": "v1", "cso_name": "", "approved_on": "", "approval_ref": "", "hazard_log_sha256": ""}))
    src = root / "backend/src/test/java/qa"
    src.mkdir(parents=True)
    for cls, body in java.items():
        (src / f"{cls}.java").write_text(textwrap.dedent(body))
    return root


def junit(results):
    """Surefire XML: {class: [(method, 'passed'|'failed')]}."""
    d = pathlib.Path(tempfile.mkdtemp())
    for cls, cases in results.items():
        xml = [f'<testsuite name="qa.{cls}" tests="{len(cases)}">']
        for m, st in cases:
            inner = "<failure message='x'/>" if st == "failed" else ""
            xml.append(f'<testcase classname="qa.{cls}" name="{m}">{inner}</testcase>')
        xml.append("</testsuite>")
        (d / f"TEST-qa.{cls}.xml").write_text("".join(xml))
    return d


def run(root, *extra):
    out = pathlib.Path(tempfile.mkdtemp())
    buf = io.StringIO()
    with contextlib.redirect_stdout(buf):
        rc = sr.main(["report", "--root", str(root), "--sha", "qa5ha123", "--out", str(out)] + list(extra))
    return rc, buf.getvalue(), json.loads((out / "safety-test-report.json").read_text()), (out / "safety-test-report.html").read_text()


JAVA_A = """
    package qa;
    import com.nabd.hms.support.Hazard;
    class GateATest {
        @Test
        @Hazard("H-90")
        void verifiesNinety() {}
    }
"""
JAVA_B = """
    package qa;
    import com.nabd.hms.support.Hazard;
    @Hazard("H-91")
    class GateBTest {
        @Test
        void verifiesNinetyOne() {}
    }
"""


class Ac1Gate(unittest.TestCase):
    def test_hazard_with_zero_tagged_tests_fails_and_is_named(self):
        root = make_repo(["H-90", "H-91"], {"GateATest": JAVA_A})
        rc, out, rep, _ = run(root, "--junit", str(junit({"GateATest": [("verifiesNinety", "passed")]})))
        self.assertEqual(rc, 1)
        self.assertIn("H-91", out)
        self.assertNotIn("hazard H-90 has no passing", out)
        self.assertEqual(rep["summary"]["unverified"], ["H-91"])

    def test_tagged_test_that_failed_does_not_verify(self):
        root = make_repo(["H-90"], {"GateATest": JAVA_A})
        rc, out, rep, _ = run(root, "--junit", str(junit({"GateATest": [("verifiesNinety", "failed")]})))
        self.assertEqual(rc, 1)
        self.assertEqual(rep["summary"]["unverified"], ["H-90"])

    def test_all_hazards_verified_passes(self):
        root = make_repo(["H-90", "H-91"], {"GateATest": JAVA_A, "GateBTest": JAVA_B})
        rc, out, rep, _ = run(root, "--junit", str(junit({"GateATest": [("verifiesNinety", "passed")],
                                                              "GateBTest": [("verifiesNinetyOne", "passed")]})))
        self.assertEqual(rc, 0, out)
        self.assertEqual(rep["summary"]["unverified"], [])


class Ac2Report(unittest.TestCase):
    def test_report_json_and_html_carry_commit_sha_release_and_dictionary_versions(self):
        root = make_repo(["H-90"], {"GateATest": JAVA_A})
        rc, _, rep, page = run(root, "--release", "v9.9.9-qa", "--dictionary", "IN=0", "--dictionary", "SA=3",
                               "--junit", str(junit({"GateATest": [("verifiesNinety", "passed")]})))
        self.assertEqual(rc, 0)
        self.assertEqual(rep["commit_sha"], "qa5ha123")
        self.assertEqual(rep["release"], "v9.9.9-qa")
        self.assertEqual(rep["dictionary_versions"], {"IN": "0", "SA": "3"})
        for w in ("qa5ha123", "v9.9.9-qa", "IN=0", "SA=3"):
            self.assertIn(w, page)

    def test_release_workflow_attaches_both_report_files(self):
        wf = pathlib.Path(".github/workflows/release.yml").read_text()
        self.assertIn("safety-test-report.html", wf)
        self.assertIn("safety-test-report.json", wf)
        self.assertIn("gh release upload", wf)
        self.assertIn("--require-signed", wf)


class Ac3CsoApproval(unittest.TestCase):
    def setUp(self):
        self.wf = yaml.safe_load(pathlib.Path(".github/workflows/release.yml").read_text())
        self.jobs = self.wf["jobs"]

    def test_safety_path_change_needs_a_protected_environment_before_the_release_gate(self):
        cso = self.jobs["cso-approval"]
        self.assertEqual(cso["environment"], "clinical-safety-release")
        self.assertIn("changed == 'true'", cso["if"])
        gate = self.jobs["release-gate"]
        self.assertIn("cso-approval", gate["needs"])
        # with safety paths changed, only a successful CSO approval clears the gate
        self.assertIn("needs.cso-approval.result == 'success'", gate["if"])
        self.assertIn("needs.safety-paths.outputs.changed == 'false'", gate["if"])

    def test_clinical_safety_paths_are_owned_by_the_cso_team(self):
        owners = pathlib.Path(".github/CODEOWNERS").read_text()
        self.assertIn("@nabd/clinical-safety", owners)
        self.assertRegex(owners, r"(?m)^\S*clinical\S*\s+.*@nabd/clinical-safety")

    @unittest.expectedFailure  # known-defect-CLN-007: no production deploy job exists that `needs: release-gate`
    def test_a_deploy_job_waits_for_the_release_gate(self):
        found = False
        for f in pathlib.Path(".github/workflows").glob("*.yml"):
            for name, job in (yaml.safe_load(f.read_text()).get("jobs") or {}).items():
                needs = job.get("needs") or []
                needs = [needs] if isinstance(needs, str) else needs
                if "deploy" in name and ("release-gate" in needs):
                    found = True
        self.assertTrue(found)


class Ac4Signature(unittest.TestCase):
    def test_unsigned_log_fails_the_release_gate(self):
        root = make_repo(["H-90"], {"GateATest": JAVA_A})
        rc, out, _, _ = run(root, "--require-signed", "--junit", str(junit({"GateATest": [("verifiesNinety", "passed")]})))
        self.assertEqual(rc, 1)
        self.assertIn("not signed", out)

    @unittest.expectedFailure  # known-defect-CLN-007: hazard log v1 has no CSO signature (needs clinical sign-off)
    def test_repo_hazard_log_v1_carries_cso_approval_and_date(self):
        rec = json.loads(pathlib.Path(sr.SIGNOFF).read_text())
        self.assertTrue(rec["cso_name"])
        self.assertRegex(rec["approved_on"], r"^\d{4}-\d{2}-\d{2}$")
        self.assertTrue(rec["approval_ref"])


class Ac5H04(unittest.TestCase):
    def h04_tests(self):
        out = pathlib.Path(tempfile.mkdtemp())
        with contextlib.redirect_stdout(io.StringIO()):
            sr.main(["report", "--root", ".", "--sha", "qa", "--out", str(out)])
        rep = json.loads((out / "safety-test-report.json").read_text())
        return [t["id"] for h in rep["hazards"] if h["id"] == "H-04" for t in h["tests"]]

    def test_h04_is_in_the_hazard_log(self):
        self.assertIn("H-04", pathlib.Path(sr.LOG).read_text())

    def test_report_lists_the_otc_gate_tests_as_h04_verifying_tests(self):
        ids = self.h04_tests()
        self.assertTrue(any("OtcRxGateApiTest" in i or "S11QaRxGateTest" in i for i in ids), ids)


if __name__ == "__main__":
    unittest.main(verbosity=2)
