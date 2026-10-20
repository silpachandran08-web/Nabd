#!/usr/bin/env python3
"""CLN-007 checks: python3 scripts/ci/test_safety_report.py (run from repo root). UPDATE_SNAPSHOT=1 rewrites the snapshot."""
import contextlib, hashlib, io, json, os, pathlib, shutil, sys, tempfile, time, unittest
sys.path.insert(0, "scripts/ci")
import safety_report as sr
import yaml

FX = pathlib.Path("scripts/ci/fixtures/safety-report")


def run(root, *extra, junit=True, pw=True):
    out = pathlib.Path(tempfile.mkdtemp())
    args = ["report", "--root", str(root), "--sha", "0123abc", "--release", "v0.0.0-fixture", "--dictionary", "IN=1", "--out", str(out)]
    if junit:
        args += ["--junit", str(FX / "surefire")]
    if pw:
        args += ["--playwright", str(FX / "playwright.json")]
    buf = io.StringIO()
    with contextlib.redirect_stdout(buf):
        rc = sr.main(args + list(extra))
    return rc, buf.getvalue(), json.loads((out / "safety-test-report.json").read_text()), (out / "safety-test-report.html").read_text()


def repo_copy():
    d = pathlib.Path(tempfile.mkdtemp()) / "repo"
    shutil.copytree(FX / "repo", d)
    return d


def sign(root, **over):
    p = root / sr.SIGNOFF
    rec = json.loads(p.read_text())
    rec.update({"cso_name": "Test CSO", "approved_on": "2026-10-19", "approval_ref": "https://example.invalid/signed-record",
                "hazard_log_sha256": hashlib.sha256((root / sr.LOG).read_bytes()).hexdigest(), **over})
    p.write_text(json.dumps(rec))


class Gate(unittest.TestCase):
    def setUp(self):
        os.environ["SOURCE_DATE_EPOCH"] = "1790000000"

    def test_ac1_all_verified_passes(self):
        rc, out, rep, _ = run(FX / "repo")
        self.assertEqual(rc, 0, out)
        self.assertEqual(rep["summary"]["unverified"], [])

    def test_ac1_hazard_without_test_fails_with_id(self):  # CI self-test: a hazard with no test fails the job
        rc, out, rep, _ = run(FX / "repo", pw=False)
        self.assertEqual(rc, 1)
        self.assertIn("hazard H-05 has no passing verifying test", out)
        self.assertEqual(rep["summary"]["unverified"], ["H-05"])

    def test_ac1_tagged_but_not_run_or_failing_is_unverified(self):
        rc, out, rep, _ = run(FX / "repo", junit=False)
        self.assertEqual(rc, 1)
        self.assertEqual(rep["summary"]["unverified"], ["H-01", "H-04"])
        self.assertEqual({t["status"] for h in rep["hazards"] if h["id"] == "H-01" for t in h["tests"]}, {"not run"})

    def test_unknown_hazard_tag_fails(self):
        root = repo_copy()
        (root / "backend/src/test/java/fx/Typo.java").write_text('package fx;\n@Hazard("H-99")\nclass Typo {}\n')
        rc, out, _, _ = run(root)
        self.assertEqual(rc, 1)
        self.assertIn('@Hazard("H-99") on fx.Typo names a hazard not in hazard-log.csv', out)

    def test_ac2_report_carries_sha_release_and_dictionary_versions(self):
        rc, _, rep, page = run(FX / "repo")
        self.assertEqual((rep["commit_sha"], rep["release"], rep["dictionary_versions"]), ("0123abc", "v0.0.0-fixture", {"IN": "1"}))
        for w in ("0123abc", "v0.0.0-fixture", "IN=1", "VERIFIED"):
            self.assertIn(w, page)
        self.assertIn("<!doctype html>", page)

    def test_ac2_missing_dictionary_is_reported_not_hidden(self):
        out = pathlib.Path(tempfile.mkdtemp())
        with contextlib.redirect_stdout(io.StringIO()):
            sr.main(["report", "--root", str(FX / "repo"), "--sha", "x", "--out", str(out)])
        self.assertIn("unknown", json.loads((out / "safety-test-report.json").read_text())["dictionary_versions"])

    def test_ac4_unsigned_log_fails_release_gate(self):
        rc, out, rep, _ = run(FX / "repo", "--require-signed")
        self.assertEqual(rc, 1)
        self.assertIn("hazard log v1 is not signed by the CSO", out)
        self.assertFalse(rep["hazard_log"]["signoff"]["signed"])

    def test_ac4_signed_log_passes_and_edit_after_signing_voids_it(self):
        root = repo_copy()
        sign(root)
        rc, out, rep, page = run(root, "--require-signed")
        self.assertEqual(rc, 0, out)
        self.assertEqual(rep["hazard_log"]["signoff"]["record"]["approved_on"], "2026-10-19")
        self.assertIn("Test CSO", json.dumps(rep))
        with open(root / sr.LOG, "a") as f:
            f.write("H-06,fixture,late edit,x,x,x,x,x,x,open,\n")
        rc, out, _, _ = run(root, "--require-signed")
        self.assertEqual(rc, 1)
        self.assertIn("changed after sign-off", out)

    def test_ac4_bad_date_is_not_a_signature(self):
        root = repo_copy()
        sign(root, approved_on="soon")
        self.assertIn("not an ISO date", run(root, "--require-signed")[1])

    def test_ac5_h04_lists_otc_gate_tests(self):
        _, _, rep, page = run(FX / "repo")
        h04 = next(h for h in rep["hazards"] if h["id"] == "H-04")
        ids = [(t["id"], t["status"]) for t in h04["tests"]]
        self.assertIn(("fx.OtcGateApiTest#otcCheckoutRefusesRxItem", "passed"), ids)
        self.assertIn(("fx.OtcGateApiTest#rxLineNeedsSignedPrescriptionItem(int)[2]", "failed"), ids)
        self.assertNotIn("untagged", json.dumps(ids))
        self.assertIn("fx.OtcGateApiTest#otcCheckoutRefusesRxItem", page)

    def test_report_snapshot(self):
        _, _, rep, _ = run(FX / "repo")
        snap = FX / "expected-report.json"
        if os.environ.get("UPDATE_SNAPSHOT"):
            snap.write_text(json.dumps(rep, indent=2, sort_keys=True) + "\n")
        self.assertEqual(rep, json.loads(snap.read_text()))

    def test_real_repo_tags_resolve_and_run_fast(self):  # NFR: report generation < 2 min
        t = time.time()
        rc, out, rep, _ = run(pathlib.Path("."), junit=False, pw=False)
        self.assertLess(time.time() - t, 120)
        self.assertEqual(rep["summary"]["tag_errors"], [])
        tagged = {h["id"] for h in rep["hazards"] if h["tests"]}
        self.assertTrue({"H-07", "H-09"} <= tagged, tagged)
        self.assertEqual(rc, 1)  # no results supplied, so nothing is verified


class SafetyPaths(unittest.TestCase):
    def test_codeowners_safety_paths(self):
        pats = sr.safety_patterns(pathlib.Path("."))
        hit = sr.safety_paths(["backend/src/main/java/com/nabd/hms/clinical/RxSafetyChecks.java", "docs/clinical-safety/hazard-log.csv",
                               "backend/src/main/java/com/nabd/hms/clinical/cds/Rule.java", "frontend/app/page.tsx",
                               "backend/src/main/java/com/nabd/hms/clinical/RxSafetyChecksX.java"], pats)
        self.assertEqual(hit, ["backend/src/main/java/com/nabd/hms/clinical/RxSafetyChecks.java", "docs/clinical-safety/hazard-log.csv",
                               "backend/src/main/java/com/nabd/hms/clinical/cds/Rule.java"])


class ReleaseWorkflow(unittest.TestCase):
    wf = yaml.safe_load(open(".github/workflows/release.yml"))
    j = wf["jobs"]

    def test_ac1_ac2_report_job_gates_and_attaches(self):
        steps = json.dumps(self.j["safety-report"]["steps"])
        for w in ("safety_report.py report", "--require-signed", "--sha", "gh release upload", "safety-test-report.html", "safety-test-report.json"):
            self.assertIn(w, steps)
        self.assertLessEqual(self.j["safety-report"]["timeout-minutes"], 60)

    def test_ac3_cso_approval_for_safety_paths_before_deploy(self):
        cso = self.j["cso-approval"]
        self.assertEqual(cso["environment"], "clinical-safety-release")
        self.assertIn("needs.safety-paths.outputs.changed == 'true'", cso["if"])
        gate = self.j["release-gate"]
        self.assertTrue({"safety-report", "cso-approval"} <= set(gate["needs"]))
        self.assertIn("needs.cso-approval.result == 'success'", gate["if"])
        self.assertIn("needs.safety-paths.outputs.changed == 'false'", gate["if"])
        self.assertIn("safety_report.py paths", json.dumps(self.j["safety-paths"]["steps"]))


if __name__ == "__main__":
    unittest.main(verbosity=2)
