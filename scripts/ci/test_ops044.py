#!/usr/bin/env python3
"""OPS-044 checks: python3 scripts/ci/test_ops044.py (run from repo root). Safety report exceptions and journey links, release gate and
checklist, nightly journey workflow shape."""
import contextlib, io, json, pathlib, re, shutil, sys, tempfile, unittest
sys.path.insert(0, "scripts/ci")
import safety_report as sr
import release_checklist as rc
import yaml

FX = pathlib.Path("scripts/ci/fixtures/safety-report")
REL = "v0.0.0-fixture"


def copy():
    d = pathlib.Path(tempfile.mkdtemp()) / "repo"
    shutil.copytree(FX / "repo", d)
    return d


def run(root, *extra, pw=False):
    out = pathlib.Path(tempfile.mkdtemp())
    args = ["report", "--root", str(root), "--sha", "0123abc", "--release", REL, "--junit", str(FX / "surefire"), "--out", str(out)]
    if pw:
        args += ["--playwright", str(FX / "playwright.json")]
    buf = io.StringIO()
    with contextlib.redirect_stdout(buf):
        code = sr.main(args + list(extra))
    return code, buf.getvalue(), json.loads((out / "safety-test-report.json").read_text()), (out / "safety-test-report.html").read_text()


def exception(root, **over):
    rec = {"hazard": "H-05", "release": REL, "reason": "fixture exception", "cso_name": "Test CSO", "approved_on": "2026-10-19", "approval_ref": "https://example.invalid/signed", **over}
    p = root / sr.EXCEPTIONS
    p.parent.mkdir(parents=True, exist_ok=True)
    p.write_text(json.dumps({"exceptions": [rec]}))


def journeys(tmp, hazard="H-05", failing=False, name="J10 IN desktop"):
    f = pathlib.Path(tmp) / "journeys-junit.xml"
    f.write_text(f'<testsuites><testsuite name="J10"><testcase classname="journeys.J10" name="{name}"><properties><property name="hazard" value="{hazard}"/></properties>'
                 + ('<failure message="x">x</failure>' if failing else "") + "</testcase></testsuite></testsuites>")
    return str(f)


class ReleaseGate(unittest.TestCase):
    def test_hazard_missing_its_test_blocks_the_release(self):  # CI: fixture hazard missing its test must block
        code, out, rep, _ = run(FX / "repo")
        self.assertEqual(code, 1)
        self.assertIn("hazard H-05 has no passing verifying test", out)

    def test_report_lists_controls_and_latest_results_per_hazard(self):
        _, _, rep, html = run(FX / "repo", pw=True)
        h = {x["id"]: x for x in rep["hazards"]}
        self.assertEqual(set(h["H-01"]["controls"]), {"existing", "planned"})
        self.assertIn("Existing control:", html)
        self.assertTrue(h["H-01"]["tests"])

    def test_cso_signed_exception_for_this_release_unblocks_and_is_listed(self):
        root = copy(); exception(root)
        code, out, rep, html = run(root)
        self.assertEqual(code, 0, out)
        self.assertEqual(rep["summary"]["excepted"], ["H-05"])
        self.assertEqual(rep["summary"]["unverified"], [])
        self.assertIn("EXCEPTION Test CSO 2026-10-19", html)
        self.assertIn("Released on a CSO-signed exception: H-05", html)

    def test_exception_for_another_release_or_hazard_does_not_apply(self):
        root = copy(); exception(root, release="v9.9.9")
        self.assertEqual(run(root)[0], 1)
        root = copy(); exception(root, hazard="H-01")  # H-01 is verified already: nothing to except, H-05 still blocks
        self.assertEqual(run(root)[0], 1)

    def test_unsigned_or_malformed_exception_blocks_and_says_why(self):
        for over, text in [({"cso_name": ""}, "fields empty: cso_name"), ({"approved_on": "next week"}, "not an ISO date"), ({"approval_ref": " "}, "approval_ref")]:
            root = copy(); exception(root, **over)
            code, out, _, _ = run(root)
            self.assertEqual(code, 1)
            self.assertIn(text, out)

    def test_exception_naming_an_unknown_hazard_is_an_error(self):
        root = copy(); exception(root, hazard="H-99")
        code, out, _, _ = run(root)
        self.assertEqual(code, 1)
        self.assertIn("unknown hazard H-99", out)

    def test_a_passing_journey_with_a_hazard_property_verifies_it(self):
        tmp = tempfile.mkdtemp()
        code, out, rep, _ = run(FX / "repo", "--journeys", journeys(tmp))
        self.assertEqual(code, 0, out)
        t = [x for x in next(h for h in rep["hazards"] if h["id"] == "H-05")["tests"]]
        self.assertEqual([(x["kind"], x["status"]) for x in t], [("journey", "passed")])

    def test_a_failing_journey_does_not_verify(self):
        code, out, _, _ = run(FX / "repo", "--journeys", journeys(tempfile.mkdtemp(), failing=True))
        self.assertEqual(code, 1)
        self.assertIn("H-05", out)

    def test_journey_naming_an_unknown_hazard_is_an_error(self):
        code, out, _, _ = run(FX / "repo", "--journeys", journeys(tempfile.mkdtemp(), hazard="H-77"), pw=True)
        self.assertEqual(code, 1)
        self.assertIn("names a hazard not in hazard-log.csv: H-77", out)


class RealRepo(unittest.TestCase):
    def test_hazard_log_has_h01_to_h08_and_journeys_link_to_known_hazards(self):
        ids = {r["id"] for r in __import__("csv").DictReader(open(sr.LOG, newline=""))}
        self.assertTrue({f"H-0{n}" for n in range(1, 9)} <= ids)
        src = open("frontend/apps/staff-web/tests/e2e/smoke/nightly.mjs").read()
        linked = set(re.findall(r"hazards: \['(H-\d+)'\]", src))
        self.assertTrue(linked and linked <= ids, linked)

    def test_hazard_exceptions_file_is_valid_and_has_no_open_exceptions(self):
        self.assertEqual(json.load(open(sr.EXCEPTIONS))["exceptions"], [])

    def test_sweep_covers_the_journeys_with_gated_controls(self):
        src = open("frontend/apps/staff-web/tests/e2e/smoke/sweep.mjs").read()
        covered = set(re.findall(r"\{ of: '(J\d+)'", src))
        self.assertTrue({"J1", "J2", "J3", "J4", "J5", "J8", "J10", "J11", "J12", "J13"} <= covered, covered)


class Workflows(unittest.TestCase):
    def wf(self, name):
        d = yaml.safe_load(open(f".github/workflows/{name}.yml"))
        d["on"] = d.get("on") or d.get(True)
        return d

    def test_release_is_blocked_by_a_red_nightly_and_attaches_pdf_json_junit_timings(self):
        w = self.wf("release")
        self.assertIn("journey-gate", w["jobs"]["release-gate"]["needs"])
        self.assertEqual(w["jobs"]["safety-report"]["needs"], "journey-gate")
        text = open(".github/workflows/release.yml").read()
        for need in ["--journeys journey-results/journeys-junit.xml", "safety-test-report.pdf", "journeys-timings.json", "playwright pdf", "[ \"$conclusion\" = success ]"]:
            self.assertIn(need, text)

    def test_nightly_runs_on_main_schedule_and_tags_and_exports_junit_and_timings(self):
        w = self.wf("journeys-nightly")
        self.assertIn("schedule", w["on"]); self.assertEqual(w["on"]["push"]["tags"], ["v*"])
        text = open(".github/workflows/journeys-nightly.yml").read()
        for need in ["--suite=nightly", "--suite=sweep", "report.mjs", "name: journey-results", "exit ${{ steps.gate.outputs.rc }}"]:
            self.assertIn(need, text)

    def test_weekly_cross_browser_covers_chrome_edge_safari_and_low_end_android(self):
        w = self.wf("journeys-cross-browser")
        self.assertIn("schedule", w["on"])
        self.assertEqual({m["name"] for m in w["jobs"]["smoke"]["strategy"]["matrix"]["include"]}, {"chrome", "edge", "safari", "android-lowend"})

    def test_release_checklist_workflow_runs_on_release_prs(self):
        w = self.wf("release-checklist")
        self.assertIn("pull_request", w["on"])
        self.assertIn("startsWith(github.event.pull_request.title, 'Release')", w["jobs"]["checklist"]["if"])


GOOD = """## Release checklist
- [x] **Change approval** — approver: Jo Lead; link: https://github.com/org/repo/pull/1#pullrequestreview-1
- [x] **Deploy window** — start: 2026-11-03T22:00Z; end: 2026-11-03T23:00Z (UTC)
- [x] **Rollback plan** — redeploy the previous tag v1.2.2; the migration is additive so no data step is needed
- [x] **Safety report** — link: https://github.com/org/repo/releases/tag/v1.2.3
"""


class Checklist(unittest.TestCase):
    def test_template_matches_the_checker(self):
        body = open(".github/PULL_REQUEST_TEMPLATE/release.md").read()
        self.assertEqual(set(rc.parse(body)), set(rc.ITEMS))
        errs, _ = rc.check(body)
        self.assertTrue(errs)  # unticked and placeholders: a PR must fill it in

    def test_complete_checklist_passes(self):
        errs, warns = rc.check(GOOD, {})
        self.assertEqual(errs, [])
        self.assertTrue(any("OPD peaks are not configured" in w for w in warns))

    def test_each_item_is_required_and_must_be_ticked(self):
        for item in rc.ITEMS:
            body = "\n".join(l for l in GOOD.splitlines() if item not in l)
            self.assertIn(f"checklist item '{item}' is missing", rc.check(body, {})[0])
            self.assertIn(f"'{item}' is not ticked", rc.check(GOOD.replace(f"[x] **{item}**", f"[ ] **{item}**"), {})[0])

    def test_window_must_clear_freezes_and_opd_peaks(self):
        peaks = {"opd_peaks": [{"region": "IN", "tz": "Asia/Kolkata", "days": [1, 2, 3, 4, 5, 6], "from": "09:00", "to": "13:00"}]}
        self.assertEqual(rc.check(GOOD, peaks)[0], [])  # 22:00Z = 03:30 IST next day
        busy = GOOD.replace("2026-11-03T22:00Z", "2026-11-03T04:30Z").replace("2026-11-03T23:00Z", "2026-11-03T05:30Z")  # 10:00-11:00 IST, a Tuesday
        self.assertIn("OPD peak", " ".join(rc.check(busy, peaks)[0]))
        freeze = {"freeze": [{"from": "2026-11-03T00:00Z", "to": "2026-11-04T00:00Z", "reason": "year end"}]}
        self.assertIn("freeze", " ".join(rc.check(GOOD, freeze)[0]))

    def test_bad_windows_approvals_and_plans_are_refused(self):
        self.assertIn("between 15 minutes and 4 hours", " ".join(rc.check(GOOD.replace("23:00Z", "22:05Z"), {})[0]))
        self.assertIn("between 15 minutes and 4 hours", " ".join(rc.check(GOOD.replace("23:00Z", "04:00Z").replace("2026-11-03T04", "2026-11-04T04"), {})[0]))
        self.assertIn("must look like", " ".join(rc.check(GOOD.replace("start: 2026-11-03T22:00Z", "start: tonight"), {})[0]))
        self.assertIn("approver", " ".join(rc.check(GOOD.replace("approver: Jo Lead; ", ""), {})[0]))
        self.assertIn("real plan", " ".join(rc.check(GOOD.replace("redeploy the previous tag v1.2.2; the migration is additive so no data step is needed", "revert"), {})[0]))
        self.assertIn("placeholder", " ".join(rc.check(GOOD.replace("https://github.com/org/repo/releases/tag/v1.2.3", "https://example.invalid/x"), {})[0]))


if __name__ == "__main__":
    unittest.main()
