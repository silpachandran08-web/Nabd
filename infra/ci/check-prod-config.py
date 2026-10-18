#!/usr/bin/env python3
"""OPS-003: asserts the prod-config artefacts agree (workflow parses, uses the split roles, dev-up.sh builds the same topology).
Run: python3 infra/ci/check-prod-config.py   (exit 1 with the failed assertions listed)"""
import re
import sys
import yaml

fails = []
def check(cond, msg):
    if not cond:
        fails.append(msg)

wf = yaml.safe_load(open(".github/workflows/prod-config.yml"))
job = wf["jobs"]["prod-config"]
steps = "\n".join(str(s.get("run", "")) for s in job["steps"])
trig = wf.get(True) or wf.get("on")  # PyYAML reads the bare key `on` as True
check("pull_request" in trig, "workflow must run on pull_request")
check("bootstrap-roles.sql" in steps, "workflow must run infra/db/bootstrap-roles.sql")
check("MigrateMain" in steps and "nabd_migrator" in steps, "workflow must migrate as nabd_migrator via MigrateMain")
check("mvn -B test" in steps, "workflow must run the API suite")

boot = open("infra/db/bootstrap-roles.sql").read()
for needle in ["NOBYPASSRLS", "statement_timeout = '5s'", "lock_timeout = '3s'", "idle_in_transaction_session_timeout = '15s'",
               "statement_timeout = '60s'", ":'migrator_pw'", ":'app_pw'", ":'reports_pw'"]:
    check(needle in boot, f"bootstrap-roles.sql missing {needle}")
check(not re.search(r"PASSWORD\s+'", boot), "bootstrap-roles.sql must not contain a literal password")

dev = open("scripts/dev-up.sh").read()
check("bootstrap-roles.sql" in dev and "MigrateMain" in dev and "DB_USER=nabd_app" in dev, "dev-up.sh must use the same split roles")
check(re.search(r"flyway:\s*\n\s*#.*\n\s*enabled: false", open("backend/src/main/resources/application.yml").read()), "application.yml must set spring.flyway.enabled=false")

if fails:
    print("FAILED:\n  " + "\n  ".join(fails))
    sys.exit(1)
print("prod-config artefacts consistent")
