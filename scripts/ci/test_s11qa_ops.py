#!/usr/bin/env python3
"""Independent QA (S11) for OPS-033 / OPS-037 / OPS-036 / OPS-048 / OPS-052 / OPS-054 (+ FEF-049 workflow wiring).
Run from the repo root: python3 -W ignore scripts/ci/test_s11qa_ops.py
Tests decorated @unittest.expectedFailure assert the target behaviour of an acceptance criterion that the merged tree
does not meet (known defects, listed in /home/claude/ws/reports/s11-QA-5.md); they keep CI green but stay visible.
OpenTofu / conftest / tflint tests run only when S11QA_TOOLS points at a dir holding tofu, conftest, tflint, tofurc."""
import copy, datetime as dt, glob, importlib.util, json, os, re, shutil, subprocess, sys, tempfile, textwrap, unittest
import yaml

ROOT = os.getcwd()
WF = ".github/workflows"
TOOLS = os.environ.get("S11QA_TOOLS", "")


def load(path, name):
    spec = importlib.util.spec_from_file_location(name, path)
    m = importlib.util.module_from_spec(spec)
    sys.path.insert(0, os.path.dirname(os.path.abspath(path)))
    spec.loader.exec_module(m)
    return m


def wf(name):
    d = yaml.safe_load(open(f"{WF}/{name}"))
    d["on"] = d.pop(True, d.get("on"))  # YAML 1.1 reads the bare key `on` as True
    return d


def run(args, cwd=ROOT, env=None, stdin=None):
    return subprocess.run(args, cwd=cwd, capture_output=True, text=True, env={**os.environ, **(env or {})}, input=stdin)


# =====================================================================================================================
# GitHub Actions restrictions (all workflows of these stories). actionlint is not available offline, so the rules that
# GitHub enforces at workflow load are checked here directly.
# =====================================================================================================================
STORY_WORKFLOWS = ["ci.yml", "dast.yml", "e2e-smoke.yml", "preview.yml", "preview-reaper.yml", "phase0-gate.yml", "infra.yml", "backup.yml"]
CALL_JOB_KEYS = {"name", "uses", "with", "secrets", "needs", "if", "permissions", "strategy", "concurrency"}
RUN_JOB_KEYS = {"name", "needs", "if", "runs-on", "environment", "permissions", "outputs", "env", "defaults", "steps", "timeout-minutes",
                "strategy", "continue-on-error", "container", "services", "concurrency"}
STEP_KEYS = {"id", "if", "name", "uses", "run", "shell", "with", "env", "continue-on-error", "timeout-minutes", "working-directory"}
PERM_ORDER = {"none": 0, "read": 1, "write": 2}
EXPR = re.compile(r"\$\{\{(.*?)\}\}", re.S)
UNTRUSTED = re.compile(r"github\.event\.(pull_request\.(title|body|head\.ref|head\.label)|issue\.(title|body)|comment\.body|head_commit\.message)|github\.head_ref")


def perms(p):
    """Normalised {scope: level}; `read-all`/`write-all` expand; missing scopes are none."""
    if p is None: return None
    if p == "read-all": return {k: "read" for k in ("contents", "packages", "id-token", "pull-requests", "issues", "actions")}
    if p == "write-all": return {k: "write" for k in ("contents", "packages", "id-token", "pull-requests", "issues", "actions")}
    return dict(p)


class GitHubRestrictions(unittest.TestCase):
    def each_job(self):
        for f in STORY_WORKFLOWS:
            d = wf(f)
            for jid, j in d["jobs"].items():
                yield f, d, jid, j

    def test_parse_and_triggers(self):
        for f in STORY_WORKFLOWS:
            d = wf(f)
            self.assertTrue(d.get("on"), f"{f}: no trigger")
            self.assertTrue(d.get("jobs"), f"{f}: no jobs")

    def test_job_shape_keys_and_needs(self):
        for f, d, jid, j in self.each_job():
            self.assertRegex(jid, r"^[A-Za-z_][A-Za-z0-9_-]*$", f"{f}:{jid}")
            if "uses" in j:
                self.assertFalse(set(j) - CALL_JOB_KEYS, f"{f}:{jid} reusable-call job has keys GitHub rejects: {set(j) - CALL_JOB_KEYS}")
            else:
                self.assertFalse(set(j) - RUN_JOB_KEYS, f"{f}:{jid} unknown keys {set(j) - RUN_JOB_KEYS}")
                self.assertIn("runs-on", j, f"{f}:{jid}")
                self.assertIn("timeout-minutes", j, f"{f}:{jid}: every job needs a timeout")
                for i, s in enumerate(j["steps"]):
                    self.assertFalse(set(s) - STEP_KEYS, f"{f}:{jid} step {i} keys {set(s) - STEP_KEYS}")
                    self.assertEqual(("uses" in s) + ("run" in s), 1, f"{f}:{jid} step {i} needs exactly one of uses/run")
            needs = j.get("needs") or []
            for n in [needs] if isinstance(needs, str) else needs:
                self.assertIn(n, d["jobs"], f"{f}:{jid} needs unknown job {n}")

    def test_no_secrets_context_in_if_and_no_env_in_job_if(self):
        for f, d, jid, j in self.each_job():
            ifs = [("job", j.get("if", ""))] + [("step", s.get("if", "")) for s in j.get("steps", [])]
            for kind, cond in ifs:
                self.assertNotIn("secrets.", str(cond), f"{f}:{jid} {kind} if uses secrets (GitHub forbids it)")
            self.assertNotRegex(str(j.get("if", "")), r"\benv\.", f"{f}:{jid}: env context is not available in jobs.<id>.if")

    def test_matrix_only_where_defined(self):
        for f, d, jid, j in self.each_job():
            if "matrix." in json.dumps(j):
                self.assertIn("matrix", (j.get("strategy") or {}), f"{f}:{jid} references matrix without strategy.matrix")

    def test_schedules_are_valid_cron_at_least_5_min(self):
        for f in STORY_WORKFLOWS:
            for s in (wf(f)["on"] or {}).get("schedule", []) if isinstance(wf(f)["on"], dict) else []:
                parts = s["cron"].split()
                self.assertEqual(len(parts), 5, f"{f}: {s['cron']}")
                m = re.fullmatch(r"\*/(\d+)", parts[0])
                if m: self.assertGreaterEqual(int(m.group(1)), 5, f"{f}: GitHub runs schedules at most every 5 min")

    def test_no_untrusted_event_text_interpolated_into_run(self):
        for f, d, jid, j in self.each_job():
            for s in j.get("steps", []):
                for e in EXPR.findall(str(s.get("run", ""))):
                    self.assertNotRegex(e, UNTRUSTED, f"{f}:{jid}: untrusted event text in a run script (injection)")

    def test_reusable_calls_resolve_inputs(self):
        for f, d, jid, j in self.each_job():
            if not str(j.get("uses", "")).startswith("./"): continue
            callee = wf(os.path.basename(j["uses"]))
            wc = callee["on"].get("workflow_call") if isinstance(callee["on"], dict) else None
            self.assertIsNotNone(wc, f"{j['uses']} has no on.workflow_call")
            declared = (wc or {}).get("inputs") or {}
            for k in (j.get("with") or {}):
                self.assertIn(k, declared, f"{f}:{jid} passes undeclared input {k}")
            for k, spec in declared.items():
                if spec.get("required"): self.assertIn(k, j.get("with") or {}, f"{f}:{jid} misses required input {k}")

    def test_called_workflow_permissions_do_not_exceed_the_caller(self):
        """GitHub: a called workflow can only downgrade the GITHUB_TOKEN permissions of the calling job; a nested job that
        asks for more makes the whole caller workflow invalid at load time ('The nested job ... is requesting ...')."""
        problems = []
        for f, d, jid, j in self.each_job():
            if not str(j.get("uses", "")).startswith("./"): continue
            caller = perms(j.get("permissions")) or perms(d.get("permissions")) or {}
            callee = wf(os.path.basename(j["uses"]))
            asks = [perms(callee.get("permissions")) or {}] + [perms(cj.get("permissions")) or {} for cj in callee["jobs"].values()]
            for a in asks:
                for scope, lvl in a.items():
                    if PERM_ORDER[lvl] > PERM_ORDER[caller.get(scope, "none")]:
                        problems.append(f"{f}:{jid} -> {j['uses']} asks {scope}: {lvl}, caller allows {caller.get(scope, 'none')}")
        self.assertEqual(problems, [])


# =====================================================================================================================
# OPS-033: architecture checks (scanner fixtures in a throwaway repo root; the real tree is never touched)
# =====================================================================================================================
ARCH = os.path.join(ROOT, "scripts/ci/arch.py")


class ArchSandbox:
    def __init__(self, java, gate=None, baseline=None, reads=None):
        self.d = tempfile.mkdtemp()
        self.w("db/migrations/V1__t.sql", "CREATE TABLE queue_entries (id uuid, tenant_id uuid NOT NULL, status text);\n"
                                          "CREATE TABLE invoices (id uuid, tenant_id uuid NOT NULL, total int);\n")
        self.w("docs/architecture/table-ownership.yaml", yaml.safe_dump({"modules": {
            "queue": {"owns": ["queue_entries"], "reads": []}, "billing": {"owns": ["invoices"], "reads": reads or []}}}))
        self.w("ci/architecture-gate.json", json.dumps(gate or {"new_code_blocking_from": None, "all_blocking_from": None}))
        if baseline is not None: self.w("ci/architecture-baseline.json", json.dumps({"violations": baseline}))
        for path, src in java.items(): self.w(f"backend/src/main/java/com/nabd/hms/{path}", src)
        self.w("java.json", "[]")

    def w(self, rel, text):
        p = os.path.join(self.d, rel); os.makedirs(os.path.dirname(p), exist_ok=True); open(p, "w").write(text)

    def arch(self, *a, env=None):
        return run(["python3", ARCH, *a], cwd=self.d, env=env)


QUEUE_REPO = textwrap.dedent('''\
    package com.nabd.hms.queue;
    class QueueRepository {
        void a() {
            jdbc.update("UPDATE queue_entries SET status = ? WHERE id = ?", s, id);
        }
        void b() {
            jdbc.query("SELECT id FROM queue_entries WHERE tenant_id = ? AND id = ?", m, t, id);
        }
        void c() {
            jdbc.query("""
                SELECT q.id
                FROM queue_entries q
                WHERE q.id = ?""", m, id);
        }
    }
    ''')


class Ops033Scans(unittest.TestCase):
    def scan(self, java, **kw):
        out = ArchSandbox(java, **kw).arch("scan")
        self.assertEqual(out.returncode, 0, out.stderr)
        return [ln.split("\t") for ln in out.stdout.splitlines() if ln.strip()]

    def test_ac4_queue_entries_without_tenant_id_reported_with_file_and_line(self):
        rows = [r for r in self.scan({"queue/QueueRepository.java": QUEUE_REPO}) if r[0] == "tenant-predicate"]
        where = sorted(r[1] for r in rows)
        self.assertEqual(where, ["backend/src/main/java/com/nabd/hms/queue/QueueRepository.java:11",
                                 "backend/src/main/java/com/nabd/hms/queue/QueueRepository.java:4"])
        self.assertTrue(all("queue_entries" in r[2] for r in rows))

    def test_ac4_predicate_via_alias_and_constant_is_clean(self):
        src = 'package com.nabd.hms.queue;\nclass R {\n String W = " WHERE q.tenant_id = ?";\n void a(){ jdbc.query("SELECT q.id FROM queue_entries q" + W, m, t); }\n}\n'
        self.assertEqual([r for r in self.scan({"queue/R.java": src}) if r[0] == "tenant-predicate"], [])

    def test_ac4_tenant_id_only_in_select_list_is_still_reported(self):
        src = 'package com.nabd.hms.queue;\nclass R {\n void a(){ jdbc.query("SELECT tenant_id, id FROM queue_entries WHERE id = ?", m, id); }\n}\n'
        self.assertEqual(len([r for r in self.scan({"queue/R.java": src}) if r[0] == "tenant-predicate"]), 1)

    @unittest.expectedFailure  # QA-5 finding F1 (OPS-033, minor): `SET tenant_id = ?` counts as a tenant predicate
    def test_ac4_update_setting_tenant_id_without_where_predicate_is_reported(self):
        src = 'package com.nabd.hms.queue;\nclass R {\n void a(){ jdbc.update("UPDATE queue_entries SET tenant_id = ? WHERE id = ?", t, id); }\n}\n'
        self.assertEqual(len([r for r in self.scan({"queue/R.java": src}) if r[0] == "tenant-predicate"]), 1)

    def test_ac5_foreign_write_reported_own_write_clean(self):
        billing = 'package com.nabd.hms.billing;\nclass B {\n void a(){ jdbc.update("DELETE FROM queue_entries WHERE tenant_id = ? AND id = ?", t, id); }\n' \
                  ' void b(){ jdbc.update("INSERT INTO invoices (id, tenant_id, total) VALUES (?, ?, ?)", i, t, x); }\n}\n'
        own = [r for r in self.scan({"billing/B.java": billing}) if r[0] == "sql-ownership"]
        self.assertEqual(len(own), 1, own)
        self.assertIn("billing/B.java:3", own[0][1])
        self.assertIn("writes queue_entries owned by queue", own[0][2])

    def test_ac5_foreign_read_needs_allow_list(self):
        billing = 'package com.nabd.hms.billing;\nclass B {\n void a(){ jdbc.query("SELECT id FROM queue_entries WHERE tenant_id = ?", m, t); }\n}\n'
        self.assertEqual(len([r for r in self.scan({"billing/B.java": billing}) if r[0] == "sql-ownership"]), 1)
        self.assertEqual([r for r in self.scan({"billing/B.java": billing}, reads=["queue_entries"]) if r[0] == "sql-ownership"], [])


class Ops033Gate(unittest.TestCase):
    JAVA = {"queue/QueueRepository.java": QUEUE_REPO}

    def test_ac1_report_mode_publishes_counts_by_module_and_never_fails(self):
        sb = ArchSandbox(self.JAVA)
        s = sb.arch("summary", "java.json", "report.json")
        self.assertEqual(s.returncode, 0)
        self.assertIn("| module | modulith | archunit | sql-ownership | tenant-predicate | total |", s.stdout)
        self.assertRegex(s.stdout, r"\| queue \| 0 \| 0 \| 0 \| 2 \| 2 \|")
        self.assertIn("report only", s.stdout)
        self.assertEqual(json.load(open(f"{sb.d}/report.json"))["mode"], "report")
        self.assertEqual(sb.arch("gate", "java.json").returncode, 0)

    def test_ac2_new_mode_fails_on_a_new_violation_only(self):
        sb = ArchSandbox(self.JAVA, gate={"new_code_blocking_from": "2026-01-01", "all_blocking_from": None})
        self.assertEqual(sb.arch("freeze", "java.json").returncode, 0)  # frozen baseline = today's violations
        self.assertEqual(sb.arch("gate", "java.json").returncode, 0, "existing violations in the baseline must not fail")
        sb.w("backend/src/main/java/com/nabd/hms/queue/QueueRepository.java", "\n\n" + QUEUE_REPO)  # same code, lines shifted
        self.assertEqual(sb.arch("gate", "java.json").returncode, 0, "a line shift is not a new violation")
        sb.w("backend/src/main/java/com/nabd/hms/queue/New.java",
             'package com.nabd.hms.queue;\nclass New { void a(){ jdbc.update("DELETE FROM queue_entries WHERE id = ?", id); } }\n')
        g = sb.arch("gate", "java.json")
        self.assertEqual(g.returncode, 1)
        self.assertIn("1 new violation", g.stdout)

    def test_ac2_new_mode_before_its_date_is_still_report_only(self):
        sb = ArchSandbox(self.JAVA, gate={"new_code_blocking_from": "2026-12-01", "all_blocking_from": None}, baseline={})
        self.assertEqual(sb.arch("gate", "java.json", env={"GATE_TODAY": "2026-11-30"}).returncode, 0)
        self.assertEqual(sb.arch("gate", "java.json", env={"GATE_TODAY": "2026-12-01"}).returncode, 1)

    def test_ac3_all_mode_needs_an_empty_baseline_and_zero_violations(self):
        clean = {"queue/Ok.java": 'package com.nabd.hms.queue;\nclass Ok { void a(){ jdbc.query("SELECT id FROM queue_entries WHERE tenant_id = ?", m, t); } }\n'}
        on = {"new_code_blocking_from": "2026-01-01", "all_blocking_from": "2026-01-02"}
        self.assertEqual(ArchSandbox(clean, gate=on, baseline={}).arch("gate", "java.json").returncode, 0)
        self.assertEqual(ArchSandbox(clean, gate=on, baseline={"x|y|z||d": 1}).arch("gate", "java.json").returncode, 1)
        self.assertEqual(ArchSandbox(self.JAVA, gate=on, baseline={}).arch("gate", "java.json").returncode, 1)

    def test_gate_fails_closed_when_the_archunit_report_is_missing_in_blocking_mode(self):
        sb = ArchSandbox({}, gate={"new_code_blocking_from": "2026-01-01", "all_blocking_from": None}, baseline={})
        self.assertEqual(sb.arch("gate", "missing.json").returncode, 1)

    def test_committed_gate_is_report_only_and_wired_into_stage2(self):
        g = json.load(open("ci/architecture-gate.json"))
        self.assertIsNone(g["new_code_blocking_from"]); self.assertIsNone(g["all_blocking_from"])
        steps = json.dumps(wf("ci.yml")["jobs"]["stage2-unit"]["steps"])
        self.assertIn("arch.py summary", steps); self.assertIn("arch.py gate", steps); self.assertIn("architecture-report", steps)

    @unittest.expectedFailure  # QA-5 finding F2 (OPS-033): baseline frozen on E13's branch, stale after the Sprint 11 merge
    def test_ac2_committed_baseline_covers_every_sql_violation_of_the_integrated_tree(self):
        arch = load("scripts/ci/arch.py", "arch_qa")
        base = arch.load_baseline()
        sql = arch.collections.Counter({k: v for k, v in base.items() if k.split("|")[0] in ("sql-ownership", "tenant-predicate")})
        new, _, _ = arch.evaluate(arch.scan(), sql, "new")
        self.assertEqual([f"{x['file']}:{x['line']} {x['detail']}" for x in new][:5], [], f"{len(new)} SQL violations not in the baseline")


# =====================================================================================================================
# OPS-037: ZAP / Schemathesis gates and the live header checks (fake HTTP)
# =====================================================================================================================
DAST = os.path.join(ROOT, "scripts/ci/dast.py")


def zap(*alerts):
    return {"site": [{"alerts": [{"alert": n, "pluginid": p, "riskcode": r, "instances": [{"uri": u} for u in us]} for n, p, r, us in alerts]}]}


class Ops037(unittest.TestCase):
    def gate(self, report):
        d = tempfile.mkdtemp(); p = f"{d}/z.json"; json.dump(report, open(p, "w"))
        return run(["python3", DAST, "zap-gate", p])

    def test_ac1_any_high_fails(self):
        r = self.gate(zap(("SQL Injection", "40018", "3", ["https://pr-1.x/v1/patients"])))
        self.assertEqual(r.returncode, 1); self.assertIn("High: 1", r.stdout); self.assertIn("Gate: FAIL", r.stdout)

    def test_ac1_mediums_listed_with_owner_and_do_not_fail(self):
        r = self.gate(zap(("CSP: Wildcard Directive", "10055", "2", ["https://pr-1.x/"]), ("Cookie No HttpOnly", "10098", "2", ["https://pr-1.x/"])))
        self.assertEqual(r.returncode, 0, r.stdout)
        self.assertIn("| CSP: Wildcard Directive | 10055 | @nabd/platform-owners | 1 |", r.stdout)  # default owner
        self.assertIn("| Cookie No HttpOnly | 10098 | @nabd/security-owners | 1 |", r.stdout)

    def test_low_and_info_only_pass(self):
        self.assertEqual(self.gate(zap(("x", "1", "1", []), ("y", "2", "0", []))).returncode, 0)

    def test_token_in_url_fails_and_is_redacted(self):
        r = self.gate(zap(("Info", "10027", "0", ["https://pr-1.x/cb?access_token=eyJabc.def"])))
        self.assertEqual(r.returncode, 1); self.assertNotIn("eyJabc", r.stdout)

    def test_ac2_nightly_issues_only_for_highs(self):
        d = tempfile.mkdtemp(); p = f"{d}/z.json"
        json.dump(zap(("XSS", "40012", "3", ["https://s/v1/a?q=1", "https://s/v1/a?q=2"]), ("Med", "1", "2", ["https://s/"])), open(p, "w"))
        lines = [json.loads(x) for x in run(["python3", DAST, "zap-issues", p]).stdout.splitlines()]
        self.assertEqual(len(lines), 1); self.assertIn("[ZAP High] XSS", lines[0]["title"]); self.assertNotIn("q=1", lines[0]["body"])
        j = wf("dast.yml")["jobs"]["zap-full-nightly"]
        self.assertEqual(j["if"], "github.event_name == 'schedule'")
        self.assertEqual(j["strategy"]["matrix"]["role"], ["owner", "receptionist", "doctor", "nurse"])
        self.assertIn("gh issue create", json.dumps(j)); self.assertEqual(j["permissions"]["issues"], "write")

    def test_ac3_schemathesis_checks_and_s4_switch(self):
        steps = json.dumps(wf("dast.yml")["jobs"]["schemathesis"])
        for c in ("not_a_server_error", "status_code_conformance", "response_schema_conformance", "--stateful=links", "Authorization: Bearer"):
            self.assertIn(c, steps)
        d = tempfile.mkdtemp(); os.makedirs(f"{d}/ci")
        for date, today, rc, want in ((None, "2027-01-01", 1, 0), ("2026-12-01", "2026-11-30", 1, 0), ("2026-12-01", "2026-12-01", 1, 1), ("2026-12-01", "2027-01-01", 0, 0)):
            json.dump({"schemathesis_blocking_from": date}, open(f"{d}/ci/dast-gate.json", "w"))
            self.assertEqual(run(["python3", DAST, "st-gate", str(rc)], cwd=d, env={"GATE_TODAY": today}).returncode, want, (date, today, rc))

    def fake(self, csp=True, hsts=True, cors_verbs=("GET", "POST", "PUT", "PATCH", "DELETE"), evil=False, limit0=(400, "application/problem+json")):
        def f(url, method="GET", headers=None):
            headers = headers or {}
            h = {}
            if csp: h["content-security-policy"] = "default-src 'none'"
            if hsts: h["strict-transport-security"] = "max-age=31536000"
            if method == "OPTIONS":
                o = headers.get("Origin")
                if o == "https://evil.example.com":
                    if evil: h["access-control-allow-origin"] = o
                    return 403 if not evil else 200, h, b""
                h["access-control-allow-origin"] = o; h["access-control-allow-methods"] = ",".join(cors_verbs)
                return (200 if headers["Access-Control-Request-Method"] in cors_verbs else 403), h, b""
            if url.endswith("limit=0"):
                h["content-type"] = limit0[1]; return limit0[0], h, b"{}"
            return 200, h, b""
        return f

    def test_ac4_ac5_header_checks(self):
        dast = load("scripts/ci/dast.py", "dast_qa")
        hc = lambda **k: dast.header_checks("https://web", "https://api", "https://web", "tok", f=self.fake(**k))
        self.assertEqual(hc(), [])
        self.assertTrue(any("CORS preflight PUT" in x for x in hc(cors_verbs=("GET", "POST", "PATCH", "DELETE"))), "QA-05 regression must be caught")
        self.assertTrue(any("limit=0" in x for x in hc(limit0=(500, "application/json"))), "QA-19 regression must be caught")
        self.assertTrue(any("limit=0" in x for x in hc(limit0=(400, "application/json"))), "400 without Problem+JSON is not enough")
        self.assertTrue(any("missing strict-transport-security" in x for x in hc(hsts=False)))
        self.assertTrue(any("content-security-policy" in x for x in hc(csp=False)))
        self.assertTrue(any("foreign origin" in x for x in hc(evil=True)))

    def test_ac1_ac3_pr_jobs_have_budgets(self):
        j = wf("dast.yml")["jobs"]
        self.assertEqual(j["zap-baseline"]["timeout-minutes"], 5)
        self.assertIn("6", str(j["schemathesis"]["timeout-minutes"]))
        self.assertIn("ghcr.io/zaproxy/zaproxy:2.16.0", json.dumps(j["zap-baseline"]))

    def test_ac1_pr_previews_actually_trigger_the_dast_workflow(self):
        """A PR preview must start dast.yml. Deployments created with GITHUB_TOKEN (a job `environment:` or a /deployments
        call) never trigger workflows, so a job environment alone is not enough (QA-5 D2). Fix M: dast.yml runs on
        workflow_run of the workflow that calls preview.yml, gated on vars.PREVIEW_ENABLED, and scans deploy.sh's host."""
        d = wf("dast.yml")
        callers = [wf(f)["name"] for f in STORY_WORKFLOWS
                   if any(str(j.get("uses", "")).endswith("/preview.yml") for j in wf(f)["jobs"].values())]
        self.assertTrue(callers, "nothing calls preview.yml")
        wr = (d["on"] or {}).get("workflow_run") or {}
        self.assertTrue(set(callers) <= set(wr.get("workflows") or []), f"dast.yml does not run after {callers}")
        self.assertIn("completed", wr.get("types") or [])
        cond = d["jobs"]["target"]["if"]
        for w in ("vars.PREVIEW_ENABLED == 'true'", "workflow_run.event == 'pull_request'", "workflow_run.conclusion == 'success'"):
            self.assertIn(w, cond)
        self.assertIn('HOST="pr-$PR.$PREVIEW_DOMAIN"', open("infra/preview/deploy.sh").read())
        self.assertIn('web="https://pr-$RUN_PR.$PREVIEW_DOMAIN"', json.dumps(d["jobs"]["target"]).replace('\\"', '"'))


# =====================================================================================================================
# OPS-036: synthetic seed, pre-load PHI scanner, reaper, isolation
# =====================================================================================================================
SYNTH = os.path.join(ROOT, "tools/synthetic-data/synth.py")
synth = load(SYNTH, "synth_qa")
preview = load("infra/preview/preview.py", "preview_qa")


def verhoeff_valid(body11):
    for d in "0123456789":
        if synth.verhoeff_ok(body11 + d): return body11 + d


def luhn_valid(body):
    for d in "0123456789":
        if synth.luhn_ok(body + d): return body + d


class Ops036Seed(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.data = synth.generate(seed=7, day="2026-11-02")
        cls.roles = [k for k in json.load(open("frontend/tests/e2e/smoke/roles.json")) if not k.startswith("_")]

    def test_ac3_four_tenants_one_staff_per_role_2000_patients(self):
        ts = self.data["tenants"]
        self.assertEqual([t["key"] for t in ts], ["IN", "AE", "SA", "QA"])
        for t in ts: self.assertEqual(sorted(s["role"] for s in t["staff"]), sorted(self.roles))
        self.assertEqual(sum(len(t["patients"]) for t in ts), 2000)
        self.assertEqual(len({p["ref"] for t in ts for p in t["patients"]}), 2000)

    def test_ac3_a_day_of_appointments_on_the_grid_without_overlap(self):
        for t in self.data["tenants"]:
            starts = [a["localStart"] for a in t["appointments"]]
            self.assertGreaterEqual(len(starts), 1)
            self.assertEqual(len(set(starts)), len(starts))
            self.assertTrue(all(s.startswith("2026-11-02T") and int(s[14:16]) % 15 == 0 for s in starts))
            refs = {p["ref"] for p in t["patients"]}
            self.assertTrue(all(a["patientRef"] in refs for a in t["appointments"]))

    def test_ac3_every_phone_in_reserved_test_range_and_names_by_region(self):
        phones = [v for path, v in synth._walk(self.data) if re.search(r"phone", path.rsplit(".", 1)[-1], re.I)]
        self.assertGreater(len(phones), 2000)
        self.assertTrue(all(re.fullmatch(r"\+(91|971|966|974)0000\d{6}", p) for p in phones))
        self.assertTrue(any(n in self.data["tenants"][0]["patients"][0]["name"] for n in synth.IN_FIRST + synth.IN_LAST))
        self.assertTrue(any(n in self.data["tenants"][1]["patients"][0]["name"] for n in synth.AR_FIRST + synth.AR_LAST))

    def test_ac4_generated_seed_passes_the_scanner(self):
        self.assertEqual(synth.scan(self.data), [])
        d = tempfile.mkdtemp(); p = f"{d}/s.json"; json.dump(self.data, open(p, "w"))
        self.assertEqual(run(["python3", SYNTH, "scan", p]).returncode, 0)


class Ops036Scanner(unittest.TestCase):
    def dataset(self, **patient):
        return {"tenants": [{"key": "IN", "patients": [{"ref": "IN-0", "name": "Asha Rao", "phone": "+910000000001", **patient}]}]}

    def rejected(self, data):
        d = tempfile.mkdtemp(); p = f"{d}/x.json"; json.dump(data, open(p, "w"))
        r = run(["python3", SYNTH, "scan", p])
        return r.returncode == 1, r.stdout + r.stderr

    def test_ac4_valid_verhoeff_aadhaar_rejected_plain_spaced_and_in_free_text(self):
        a = verhoeff_valid("23456789012")
        for v in ({"nationalId": a}, {"nationalId": f"{a[:4]} {a[4:8]} {a[8:]}"}, {"notes": f"old card {a} seen"}):
            bad, out = self.rejected(self.dataset(**v))
            self.assertTrue(bad, v); self.assertNotIn(a, out, "the id must be masked in the output")

    def test_ac4_invalid_checksum_aadhaar_accepted(self):
        a = verhoeff_valid("23456789012"); wrong = a[:-1] + str((int(a[-1]) + 1) % 10)
        self.assertFalse(self.rejected(self.dataset(nationalId=wrong))[0])

    def test_ac4_non_test_phone_rejected(self):
        for ph in ("+919876543210", "+971501234567", "+9100001234"):
            self.assertTrue(self.rejected(self.dataset(phone=ph))[0], ph)

    def test_ac4_gcc_ids_rejected(self):
        self.assertTrue(self.rejected(self.dataset(nationalId=luhn_valid("78419901234567")))[0])  # Emirates ID
        self.assertTrue(self.rejected(self.dataset(nationalId=luhn_valid("108765432")))[0])       # Saudi id
        self.assertTrue(self.rejected(self.dataset(nationalId="28735612345"))[0])                 # QID shape

    @unittest.expectedFailure  # QA-5 finding F3 (OPS-036): the scanner walks strings only
    def test_ac4_aadhaar_stored_as_a_json_number_is_rejected(self):
        self.assertTrue(self.rejected(self.dataset(nationalId=int(verhoeff_valid("23456789012"))))[0])

    @unittest.expectedFailure  # QA-5 finding F3 (OPS-036): phones are recognised only under phone/mobile/msisdn/whatsapp keys
    def test_ac4_real_phone_under_another_key_is_rejected(self):
        self.assertTrue(self.rejected(self.dataset(emergencyContact="+919876543210"))[0])

    def test_ac4_loader_refuses_before_touching_the_db(self):
        d = tempfile.mkdtemp(); p = f"{d}/x.json"; json.dump(self.dataset(nationalId=verhoeff_valid("23456789012")), open(p, "w"))
        r = run(["python3", "tools/synthetic-data/load.py", p, "--db", "postgresql://nobody@127.0.0.1:1/none", "--api", "http://127.0.0.1:1/v1", "--out", f"{d}/seed"])
        self.assertEqual(r.returncode, 1); self.assertIn("REJECTED", r.stdout + r.stderr)
        self.assertFalse(os.path.exists(f"{d}/seed"))


def ns(pr, created):
    return {"metadata": {"name": f"preview-pr-{pr}", "labels": {"nabd.io/preview": "true", "nabd.io/pr": str(pr)}, "creationTimestamp": created}}


class Ops036Reaper(unittest.TestCase):
    NOW = "2026-11-05T12:00:00Z"

    def test_ac5_72h_boundary_and_closed_prs(self):
        items = {"items": [ns(1, "2026-11-02T12:00:00Z"), ns(2, "2026-11-02T11:59:00Z"), ns(3, "2026-11-05T11:00:00Z"),
                           {"metadata": {"name": "kube-system", "labels": {}, "creationTimestamp": "2020-01-01T00:00:00Z"}}]}
        doomed = dict(preview.to_reap(items, {1, 2}, self.NOW))
        self.assertNotIn("preview-pr-1", doomed, "exactly 72 h is kept")
        self.assertIn("older than 72 h", doomed["preview-pr-2"])
        self.assertIn("merged or closed", doomed["preview-pr-3"])
        self.assertNotIn("kube-system", doomed)

    def test_ac5_dry_run_deletes_nothing(self):
        d = tempfile.mkdtemp()  # PATH without kubectl: any delete attempt would crash
        r = run(["python3", "infra/preview/preview.py", "reap", "--open-prs", "", "--now", self.NOW],
                env={"PATH": "/usr/bin:/bin"}, stdin=json.dumps({"items": [ns(9, "2026-11-05T11:00:00Z")]}))
        self.assertEqual(r.returncode, 0, r.stderr); self.assertIn("would delete preview-pr-9", r.stdout)

    def test_ac5_apply_checks_cost_tag_left_zero(self):
        d = tempfile.mkdtemp(); log = f"{d}/k.log"
        open(f"{d}/kubectl", "w").write(f"#!/bin/sh\necho \"$@\" >> {log}\n"); os.chmod(f"{d}/kubectl", 0o755)
        r = run(["python3", "infra/preview/preview.py", "reap", "--open-prs", "1", "--now", self.NOW, "--apply"],
                env={"PATH": f"{d}:/usr/bin:/bin"}, stdin=json.dumps({"items": [ns(9, "2026-11-05T11:00:00Z"), ns(1, "2026-11-05T11:00:00Z")]}))
        self.assertEqual(r.returncode, 0, r.stdout + r.stderr)
        calls = open(log).read()
        self.assertIn("delete namespace preview-pr-9", calls); self.assertNotIn("preview-pr-1", calls)
        self.assertIn("get namespace -l nabd.io/pr=9", calls); self.assertIn("0 resources left", r.stdout)

    def test_cap_on_concurrent_previews(self):
        items = {"items": [ns(i, self.NOW) for i in range(10)]}
        self.assertTrue(preview.over_cap(items, 10, 99)); self.assertFalse(preview.over_cap(items, 10, 3)); self.assertFalse(preview.over_cap(items, 11, 99))

    def test_reaper_workflow_hourly_and_on_close(self):
        on = wf("preview-reaper.yml")["on"]
        self.assertEqual(on["pull_request"]["types"], ["closed"]); self.assertEqual(on["schedule"][0]["cron"].split()[1:], ["*"] * 4)


class Ops036Isolation(unittest.TestCase):
    def docs(self):
        text = open("infra/preview/preview.yaml").read()
        for k, v in {"NS": "preview-pr-7", "PR": "7", "SHA": "abc", "HOST": "pr-7.preview.test", "CREATED_AT": "x", "EXPIRES_AT": "y",
                     "IMAGE_CORE": "c@sha256:1", "IMAGE_MIGRATE": "m@sha256:1", "IMAGE_WEB": "w@sha256:1"}.items():
            text = text.replace("${" + k + "}", v)
        return [d for d in yaml.safe_load_all(text) if d]

    def test_ac6_no_egress_outside_namespace_except_dns(self):
        np = [d for d in self.docs() if d["kind"] == "NetworkPolicy"]
        self.assertEqual(len(np), 1)
        self.assertEqual(sorted(np[0]["spec"]["policyTypes"]), ["Egress", "Ingress"])
        for rule in np[0]["spec"]["egress"]:
            for to in rule["to"]:
                self.assertNotIn("ipBlock", to)
                if "namespaceSelector" in to: self.assertEqual({p["port"] for p in rule["ports"]}, {53})

    def test_ac6_secrets_only_by_reference_no_prod_no_sa_token(self):
        docs = self.docs()
        self.assertNotRegex(json.dumps(docs).lower(), r"(?<!non-)\bprod\b")
        for d in docs:
            for c in (d.get("spec", {}).get("template", {}).get("spec", {}).get("containers") or []):
                for e in c.get("env", []):
                    if re.search(r"PASSWORD|SECRET|KEY|TOKEN", e["name"]): self.assertIn("valueFrom", e, e["name"])
        self.assertTrue(any(d["kind"] == "ServiceAccount" and d.get("automountServiceAccountToken") is False for d in docs))

    def test_ac6_ci_uses_oidc_not_stored_cluster_credentials(self):
        for f in ("preview.yml", "preview-reaper.yml"):
            d = wf(f); text = json.dumps(d)
            self.assertEqual(d["permissions"]["id-token"], "write")
            self.assertNotRegex(text, r"secrets\.(KUBE|PREVIEW_CLUSTER|K8S)")
        self.assertEqual(wf("preview.yml")["jobs"]["deploy"]["timeout-minutes"], 8)

    def test_ac2_stage7_is_required_and_fails_when_the_preview_fails(self):
        self.assertIn("stage7-preview-e2e", json.load(open(".github/branch-protection.json"))["required_status_checks"]["contexts"])
        s7 = json.dumps(wf("ci.yml")["jobs"]["stage7-preview-e2e"])
        self.assertIn("needs.preview.result", s7); self.assertIn("exit 1", s7)
        self.assertEqual(wf("preview.yml")["jobs"]["smoke"]["uses"], "./.github/workflows/e2e-smoke.yml")


# =====================================================================================================================
# FEF-049 workflow wiring (logic is in frontend/tests/unit/s11qa-fef049.test.mjs; browser runs in the report)
# =====================================================================================================================
class Fef049Workflow(unittest.TestCase):
    def test_four_shards_8_min_chromium_selfcheck(self):
        j = wf("e2e-smoke.yml")["jobs"]
        self.assertEqual(j["smoke"]["strategy"]["matrix"]["shard"], [1, 2, 3, 4]); self.assertEqual(j["smoke"]["timeout-minutes"], 8)
        self.assertIn("install --with-deps chromium", json.dumps(j["smoke"]))
        self.assertIn("--break", json.dumps(j["selfcheck"]))
        self.assertIn("exit ${{ steps.gate.outputs.rc }}", json.dumps(j["report"]))

    @unittest.expectedFailure  # QA-5 finding F4 (FEF-049): flaky history lives in a PR-scoped Actions cache
    def test_flaky_history_is_shared_across_prs(self):
        """GitHub caches saved by a pull_request run are visible only to that PR (and the base branch's caches to all).
        The smoke runs only on PR previews, so "3 flaky runs in 5 days" is counted per PR, never across PRs."""
        j = wf("e2e-smoke.yml")["jobs"]["report"]
        self.assertNotIn("actions/cache/save", json.dumps(j), "history must live somewhere repo-wide (artifact on main, issue, branch)")


# =====================================================================================================================
# OPS-048: controls mapping, gate pack, record
# =====================================================================================================================
GATE = os.path.join(ROOT, "scripts/ci/phase0_gate.py")


class GateSandbox:
    def __init__(self, n_tests=67, n_verified=67, tests_exist=True):
        self.d = tempfile.mkdtemp()
        rows = []
        for i in range(1, 68):
            rows.append({"id": f"SC-{i:02d}", "control": f"control {i}", "owner": f"@team{i % 3}", "tests": [f"Ctl{i}Test"] if i <= n_tests else [],
                         "evidence": [] if i <= n_tests else [{"link": f"docs/ev{i}.md", "accepted_by": "", "accepted_on": ""}]})
        self.w("docs/security/controls.yaml", yaml.safe_dump({"controls": rows}))
        names = [f"Ctl{i}Test" for i in range(1, 68)] + ["ClinicalReadScopeTest", "PhiReadAuditTest", "ClinicalReadScopeApiTest"]
        if tests_exist:
            for n in names: self.w(f"backend/src/test/java/x/{n}.java", "class X {}")
        self.junit({f"Ctl{i}Test": i <= n_verified for i in range(1, 68)}, "base")
        self.w("docs/gates/phase0.md", open(f"{ROOT}/docs/gates/phase0.md").read())
        shutil.copytree(f"{ROOT}/docs/clinical-safety", f"{self.d}/docs/clinical-safety")

    def w(self, rel, text):
        p = os.path.join(self.d, rel); os.makedirs(os.path.dirname(p), exist_ok=True); open(p, "w").write(text)

    def junit(self, results, name):
        cases = "".join(f'<testcase classname="com.nabd.{c}" name="t()">{"" if ok else "<failure/>"}</testcase>' for c, ok in results.items())
        self.w(f"junit/{name}/TEST-x.xml", f'<testsuite tests="{len(results)}">{cases}</testsuite>')

    def gate(self, *a, env=None):
        return run(["python3", GATE, *a, "--root", self.d], cwd=self.d, env=env)


GOOD_EVIDENCE = {"defects": {"open_s1": 0, "open_security_s2": 0, "link": "https://tracker/q"},
                 "prod_config": {"conclusion": "success", "link": "https://gh/run/1"}, "two_replicas": {"conclusion": "success", "link": "https://gh/run/2"},
                 "k6": {"registry_p95_ms": 210, "checkin_p95_ms": 120, "link": "https://gh/run/3/k6.html"}}


class Ops048(unittest.TestCase):
    def pack(self, sb, evidence=GOOD_EVIDENCE, junits=("base",), sha="abc1234", epoch="1760000000"):
        p = f"{sb.d}/ev.json"; json.dump(evidence, open(p, "w"))
        args = ["pack", "--sha", sha, "--evidence", p, "--out", f"{sb.d}/out"]
        for j in junits: args += ["--junit", f"{sb.d}/junit/{j}"]
        r = sb.gate(*args, env={"SOURCE_DATE_EPOCH": epoch})
        return r, json.load(open(f"{sb.d}/out/phase0-gate-pack.json")), open(f"{sb.d}/out/phase0-gate-pack.md").read()

    def test_ac1_real_controls_file_has_67_controls_with_owners(self):
        c = yaml.safe_load(open("docs/security/controls.yaml"))["controls"]
        self.assertEqual(len(c), 67); self.assertEqual(len({x["id"] for x in c}), 67)
        self.assertTrue(all(str(x.get("owner") or "").strip() for x in c))

    def test_ac1_completeness_check_and_percent_from_latest_results(self):
        sb = GateSandbox()
        r = sb.gate("controls", "--junit", f"{sb.d}/junit/base")
        self.assertEqual(r.returncode, 0, r.stdout); self.assertIn("67/67 = 100.0%", r.stdout)
        sb.junit({"Ctl1Test": False}, "later")  # a later failing result makes that control unverified
        self.assertIn("66/67", sb.gate("controls", "--junit", f"{sb.d}/junit/base", "--junit", f"{sb.d}/junit/later").stdout)

    def test_ac1_unmapped_control_or_unknown_test_fails_the_check(self):
        sb = GateSandbox(n_tests=66)
        sb.w("docs/security/controls.yaml", open(f"{sb.d}/docs/security/controls.yaml").read().replace("link: docs/ev67.md", "link: ''"))
        r = sb.gate("controls"); self.assertEqual(r.returncode, 1); self.assertIn("SC-67: no test id or evidence link", r.stdout)
        sb2 = GateSandbox(tests_exist=False)
        r2 = sb2.gate("controls"); self.assertEqual(r2.returncode, 1); self.assertIn("not found in backend/src/test/java", r2.stdout)

    def test_ac1_evidence_counts_only_once_accepted(self):
        sb = GateSandbox(n_tests=66)  # SC-67 has evidence, not yet accepted
        self.assertIn("66/67", sb.gate("controls", "--junit", f"{sb.d}/junit/base").stdout)
        sb.w("docs/security/controls.yaml", open(f"{sb.d}/docs/security/controls.yaml").read()
             .replace("accepted_by: ''\n    accepted_on: ''", "accepted_by: '@sec'\n    accepted_on: '2026-11-01'"))
        self.assertIn("67/67", sb.gate("controls", "--junit", f"{sb.d}/junit/base").stdout)

    def test_ac2_below_60_percent_is_red_with_unverified_list_and_owners(self):
        r, p, md = self.pack(GateSandbox(n_verified=40))  # 40/67 = 59.7 %
        c5 = p["criteria"][4]
        self.assertEqual(c5["status"], "FAIL"); self.assertIn("59.7%", c5["detail"])
        self.assertIn("Security Controls >= 60% verified | FAIL (red)", md)
        self.assertIn("| SC-41 | @team2 | control 41 |", md); self.assertIn("| SC-67 | @team1 | control 67 |", md)
        self.assertEqual(len(p["security_controls"]["unverified"]), 27)
        _, p2, _ = self.pack(GateSandbox(n_verified=41))  # 61.2 %
        self.assertEqual(p2["criteria"][4]["status"], "PASS")

    def test_ac3_pack_shows_every_criterion_with_status_and_evidence_links(self):
        sb = GateSandbox()
        sb.junit({"ClinicalReadScopeTest": True, "PhiReadAuditTest": True}, "c")
        r, p, md = self.pack(sb, junits=("base", "c"))
        st = {c["id"]: c["status"] for c in p["criteria"]}
        self.assertEqual(sorted(st), list(range(1, 9)))
        self.assertEqual({k: v for k, v in st.items() if k != 6}, {k: "PASS" for k in (1, 2, 3, 4, 5, 7, 8)})
        self.assertEqual(st[6], "FAIL", "hazard log v1 is not CSO-signed in the repo, so criterion 6 must stay red")
        self.assertEqual(r.returncode, 1)
        for c in p["criteria"]:
            if c["id"] != 6: self.assertTrue(c["evidence"], c)
        for link in ("https://tracker/q", "https://gh/run/1", "https://gh/run/3/k6.html", "hazard-log-v1.json", "PhiReadAuditTest"):
            self.assertIn(link, md)
        self.assertEqual(p["commit_sha"], "abc1234")

    def test_ac3_k6_p95_boundary_300_ms_fails(self):
        ev = copy.deepcopy(GOOD_EVIDENCE); ev["k6"]["registry_p95_ms"] = 300
        _, p, _ = self.pack(GateSandbox(), evidence=ev)
        self.assertEqual(p["criteria"][2]["status"], "FAIL")
        ev["k6"]["registry_p95_ms"] = 299.9
        self.assertEqual(self.pack(GateSandbox(), evidence=ev)[1]["criteria"][2]["status"], "PASS")

    def test_ac4_failing_pack_says_onboarding_stays_blocked_and_exits_1(self):
        r, p, md = self.pack(GateSandbox(), evidence={})
        self.assertEqual(r.returncode, 1); self.assertEqual(p["result"], "FAIL"); self.assertIn("stays blocked", md)

    @unittest.expectedFailure  # QA-5 defect D3 (OPS-048 x IDC-001): criterion 7 looks for a test class that does not exist
    def test_ac3_consent_404_criterion_reads_the_merged_idc001_test(self):
        """IDC-001 merged as ClinicalReadScopeApiTest; the pack only accepts results of ClinicalReadScopeTest, so criterion 7
        stays red even when every consent-404 test passes."""
        self.assertTrue(os.path.exists("backend/src/test/java/com/nabd/hms/clinical/ClinicalReadScopeApiTest.java"))
        sb = GateSandbox(); sb.junit({"ClinicalReadScopeApiTest": True, "PhiReadAuditTest": True}, "c")
        self.assertEqual(self.pack(sb, junits=("base", "c"))[1]["criteria"][6]["status"], "PASS")

    def test_nfr_pack_reproducible_from_the_same_sha(self):
        sb = GateSandbox()
        a = self.pack(sb)[2]; b = self.pack(sb)[2]
        self.assertEqual(a, b)
        self.assertNotEqual(a, self.pack(sb, sha="def5678")[2])

    def test_ac5_record_needs_date_four_attendees_and_pack_sha(self):
        sb = GateSandbox()
        self.assertEqual(sb.gate("record").returncode, 0)
        text = open(f"{sb.d}/docs/gates/phase0.md").read()
        filled = text.replace("- Decision: not yet held", "- Decision: passed").replace("- Date:", "- Date: 2026-11-20") \
            .replace("- Attendee Founder:", "- Attendee Founder: A").replace("- Attendee CTO:", "- Attendee CTO: B") \
            .replace("- Attendee CSO:", "- Attendee CSO: C").replace("- Attendee DPO:", "- Attendee DPO: D").replace("- Pack commit:", "- Pack commit: abc1234")
        sb.w("docs/gates/phase0.md", filled); self.assertEqual(sb.gate("record").returncode, 0)
        sb.w("docs/gates/phase0.md", filled.replace("- Attendee DPO: D", "- Attendee DPO:"))
        r = sb.gate("record"); self.assertEqual(r.returncode, 1); self.assertIn("Attendee DPO missing", r.stdout)
        sb.w("docs/gates/phase0.md", filled.replace("2026-11-20", "next week")); self.assertEqual(sb.gate("record").returncode, 1)

    def test_ac4_runbook_names_the_flag_and_guard_is_in_provisioning(self):
        self.assertIn("REAL_DATA_ONBOARDING=open", open("docs/gates/phase0.md").read())
        svc = open("backend/src/main/java/com/nabd/hms/platform/provisioning/ProvisioningService.java").read()
        self.assertEqual(svc.count("realDataGuard.check()"), 2)


# =====================================================================================================================
# OPS-052: hosting ADR, IaC (static + OpenTofu/conftest/tflint when available)
# =====================================================================================================================
TF = "infra/terraform"


class Ops052Static(unittest.TestCase):
    def test_ac1_adr_compares_two_providers_per_country_and_is_not_yet_signed(self):
        adr = open("docs/adr/0016-hosting.md").read()
        for c in ("India", "UAE", "Saudi", "Qatar"): self.assertIn(c, adr)
        for p in ("AWS", "Azure", "GCP", "Oracle"): self.assertIn(p, adr)
        self.assertRegex(adr, r"(?i)status[^\n]*proposed")

    def test_ac2_no_static_cloud_credentials_anywhere(self):
        pat = re.compile(r"AKIA[0-9A-Z]{16}|aws_secret_access_key|AWS_SECRET_ACCESS_KEY|secrets\.AWS_|access_key\s*=|secret_key\s*=")
        hits = []
        for f in glob.glob(f"{TF}/**/*", recursive=True) + glob.glob(f"{WF}/*.yml"):
            if os.path.isfile(f) and not f.endswith(".py") and pat.search(open(f, errors="ignore").read()): hits.append(f)
        self.assertEqual(hits, [])
        j = wf("infra.yml")["jobs"]["plan-in"]
        self.assertEqual(j["permissions"]["id-token"], "write")
        self.assertIn("role-to-assume", json.dumps(j))

    def test_nfr_state_encrypted_and_locked(self):
        for env in ("staging", "prod"):
            b = open(f"{TF}/cells/in/backend-{env}.hcl").read()
            self.assertRegex(b, r"encrypt\s*=\s*true"); self.assertIn("dynamodb_table", b); self.assertIn("ap-south-1", b)
        boot = open(f"{TF}/bootstrap/aws/main.tf").read()
        for r in ("aws_s3_bucket_versioning", "aws_s3_bucket_server_side_encryption_configuration", "aws_dynamodb_table", "aws_s3_bucket_public_access_block"):
            self.assertIn(r, boot)

    def test_ac3_mumbai_primary_hyderabad_dr(self):
        m = open(f"{TF}/cells/in/main.tf").read()
        self.assertIn('region = "ap-south-1"', m.replace("  ", " ")); self.assertIn('dr_region         = "ap-south-2"', m)
        aws = "".join(open(f).read() for f in glob.glob(f"{TF}/modules/cell/aws/*.tf"))
        for r in ("aws_vpc", "aws_db_instance", "aws_s3_bucket", "aws_kms_key", "aws_kms_replica_key", "aws_db_instance_automated_backups_replication"):
            self.assertIn(f'resource "{r}"', aws)


def tool(name):
    return os.path.join(TOOLS, name)


@unittest.skipUnless(TOOLS and os.path.exists(os.path.join(TOOLS, "tofu")), "S11QA_TOOLS not set (OpenTofu/conftest/tflint)")
class Ops052Tofu(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.d = tempfile.mkdtemp(prefix="s11qa-tf-")
        shutil.copytree(TF, f"{cls.d}/tf", ignore=shutil.ignore_patterns(".terraform", ".terraform.lock.hcl"))
        cls.env = {"TF_CLI_CONFIG_FILE": tool("tofurc"), "TF_IN_AUTOMATION": "1", "CHECKPOINT_DISABLE": "1"}
        for sub in ("cells/in", "bootstrap/aws", "modules/cell"):
            r = run([tool("tofu"), "init", "-backend=false", "-input=false"], cwd=f"{cls.d}/tf/{sub}", env=cls.env)
            assert r.returncode == 0, r.stdout + r.stderr

    def tofu(self, sub, *a):
        return run([tool("tofu"), *a], cwd=f"{self.d}/tf/{sub}", env=self.env)

    def test_fmt_and_validate(self):
        r = run([tool("tofu"), "fmt", "-recursive", "-check"], cwd=f"{self.d}/tf", env=self.env)
        self.assertEqual(r.returncode, 0, r.stdout)
        for sub in ("cells/in", "bootstrap/aws"):
            v = self.tofu(sub, "validate"); self.assertEqual(v.returncode, 0, v.stdout + v.stderr)

    def test_ac4_plan_only_tests_incl_qa_cases(self):
        r = self.tofu("modules/cell", "test", "-no-color")
        self.assertEqual(r.returncode, 0, r.stdout + r.stderr)
        self.assertRegex(r.stdout, r"Success! \d+ passed, 0 failed")
        self.assertIn("s11qa.tftest.hcl", r.stdout)

    def test_ac5_every_taggable_resource_carries_cell_service_data_class(self):
        import hcl2
        sr = self.tofu("modules/cell", "providers", "schema", "-json")
        self.assertEqual(sr.returncode, 0, sr.stderr)
        schema = json.loads(sr.stdout)
        res = next(iter(schema["provider_schemas"].values()))["resource_schemas"]
        taggable = {t for t, s in res.items() if "tags" in s["block"].get("attributes", {})}
        problems, seen = [], 0
        for f in glob.glob(f"{TF}/modules/cell/aws/*.tf") + [f"{TF}/bootstrap/aws/main.tf"]:
            for block in hcl2.load(open(f)).get("resource", []):
                for rtype, inst in block.items():
                    rtype = rtype.strip('"')  # python-hcl2 v5+ keeps the label quotes
                    for name, body in inst.items():
                        if rtype not in taggable: continue
                        seen += 1
                        tags = str(body.get("tags", ""))
                        if not (("var.tags" in tags or "local.tags" in tags) and "data_class" in tags):
                            problems.append(f"{f}: {rtype}.{name} tags={tags[:60]!r}")
        self.assertGreater(seen, 20); self.assertEqual(problems, [])
        main = open(f"{TF}/modules/cell/main.tf").read(); boot = open(f"{TF}/bootstrap/aws/main.tf").read()
        for k in ("cell", "service"):
            self.assertRegex(main, rf"\b{k}\s*=\s*var\.{k}"); self.assertRegex(boot, rf"\b{k}\s*=")

    def conftest(self, plan):
        p = f"{self.d}/plan.json"; json.dump(plan, open(p, "w"))
        return run([tool("conftest"), "test", "--no-color", "--policy", f"{TF}/policy", p])

    def test_nfr_policy_good_passes_and_each_single_mutation_is_denied(self):
        good = json.load(open(f"{TF}/policy/fixtures/plan-good.json"))
        self.assertEqual(self.conftest(good).returncode, 0)
        rc = good["resource_changes"]
        def mutate(rtype, fn):
            g = copy.deepcopy(good)
            r = next(x for x in g["resource_changes"] if x["type"] == rtype); fn(r["change"]["after"])
            return self.conftest(g)
        cases = {
            "public DB": ("aws_db_instance", lambda a: a.update(publicly_accessible=True)),
            "unencrypted DB": ("aws_db_instance", lambda a: a.update(storage_encrypted=False)),
            "PITR 34 days": ("aws_db_instance", lambda a: a.update(backup_retention_period=34)),
            "no deletion protection": ("aws_db_instance", lambda a: a.update(deletion_protection=False)),
            "bucket policy not blocked": ("aws_s3_bucket_public_access_block", lambda a: a.update(block_public_policy=False)),
            "missing data_class": ("aws_db_instance", lambda a: a["tags"].pop("data_class")),
        }
        for label, (rtype, fn) in cases.items():
            if not any(x["type"] == rtype for x in rc): self.fail(f"fixture lacks {rtype}")
            r = mutate(rtype, fn)
            self.assertEqual(r.returncode, 1, f"{label} was not denied: {r.stdout}")

    def test_tflint_clean(self):
        r = run([tool("tflint"), "--recursive", "--config", os.path.abspath(f"{TF}/.tflint.hcl")], cwd=f"{self.d}/tf",
                env={**self.env, "TFLINT_PLUGIN_DIR": tool("tflint.d/plugins")})
        self.assertEqual(r.returncode, 0, r.stdout + r.stderr)


# =====================================================================================================================
# OPS-054: PITR, lag alarm, Hyderabad copy, drill schedule, runbook
# =====================================================================================================================
lag = load("infra/backup/pitr-lag.py", "pitr_lag_qa")


class Ops054(unittest.TestCase):
    NOW = dt.datetime(2026, 11, 2, 10, 0, tzinfo=dt.timezone.utc)

    def d(self, latest, days=35):
        return {"DBInstances": [{"BackupRetentionPeriod": days, "LatestRestorableTime": latest}]}

    def test_ac1_lag_boundary_alert_above_5_min(self):
        self.assertEqual(lag.lag_seconds(self.d("2026-11-02T09:55:00Z"), self.NOW), 300)
        self.assertFalse(lag.lag_seconds(self.d("2026-11-02T09:55:00Z"), self.NOW) > lag.THRESHOLD)
        self.assertTrue(lag.lag_seconds(self.d("2026-11-02T09:54:59Z"), self.NOW) > lag.THRESHOLD)
        self.assertEqual(lag.lag_seconds({"DBInstances": [{"BackupRetentionPeriod": 35}]}, self.NOW), float("inf"))
        with self.assertRaises(ValueError): lag.lag_seconds(self.d("2026-11-02T09:59:00Z", days=34), self.NOW)

    def test_ac1_terraform_pitr_35_days_kms_and_alarm_300s(self):
        pg = open(f"{TF}/modules/cell/aws/postgres.tf").read()
        self.assertRegex(pg, r"backup_retention_period\s*=\s*35"); self.assertRegex(pg, r'kms_key_id\s*=\s*aws_kms_key\.root\["phi"\]\.arn')
        self.assertRegex(pg, r"storage_encrypted\s*=\s*true"); self.assertRegex(pg, r"deletion_protection\s*=\s*true")
        self.assertRegex(pg, r"delete_automated_backups\s*=\s*false")
        mon = open(f"{TF}/modules/cell/aws/monitoring.tf").read()
        self.assertIn("GreaterThanThreshold", mon); self.assertRegex(open(f"{TF}/modules/cell/variables.tf").read(), r"pitr_lag_alarm_seconds[\s\S]*?default\s*=\s*300")

    def test_ac4_hyderabad_copy_and_residency_guard(self):
        pg = open(f"{TF}/modules/cell/aws/postgres.tf").read()
        self.assertIn("provider               = aws.dr", pg); self.assertRegex(pg, r"retention_period\s*=\s*35")
        res = open(f"{TF}/modules/cell/residency.tf").read()
        self.assertIn('IN = ["ap-south-1", "ap-south-2"]', res); self.assertIn("contains(local.allowed_regions, var.dr_region)", res)

    def test_ac5_quarterly_drill_scheduled_and_runbook_records_timings(self):
        crons = [s["cron"] for s in wf("backup.yml")["on"]["schedule"]]
        self.assertIn("17 4 15 1,4,7,10 *", crons)
        rb = open("docs/runbooks/restore.md").read()
        self.assertIn("## Drill record", rb); self.assertRegex(rb, r"(?i)restore\s*\|"); self.assertIn("Needs external action", rb)
        drill = wf("backup.yml")["jobs"]["drill"]
        run_txt = drill["steps"][2]["run"]
        self.assertNotIn("inputs.", run_txt, "dispatch inputs must reach the script via env, never interpolated")
        self.assertEqual(drill["environment"], "restore-drill")

    def test_ac2_drill_script_restores_isolated_and_times_rto(self):
        s = open("infra/backup/restore-drill.sh").read()
        self.assertEqual(run(["bash", "-n", "infra/backup/restore-drill.sh"]).returncode, 0)
        for k in ("restore-db-instance-to-point-in-time", "within_rto_1h", "readiness", "verify-restore.py", "--no-publicly-accessible"):
            self.assertIn(k, s)

    def test_ac3_verify_restore_selftest(self):
        r = run(["python3", "infra/backup/verify-restore.py", "--selftest"])
        self.assertEqual(r.returncode, 0, r.stdout + r.stderr)


if __name__ == "__main__":
    unittest.main(verbosity=1)
