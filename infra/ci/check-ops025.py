#!/usr/bin/env python3
"""OPS-025: static assertions (no Docker) that the Dockerfile, CI wiring and application.yml agree.
Run: python3 infra/ci/check-ops025.py   (exit 1 with the failed assertions listed). Self-test: python3 infra/ci/check-ops025.py --selftest"""
import re
import sys
import yaml


def check_dockerfile(d):
    f = []
    def need(c, m):
        if not c: f.append(m)
    need(re.search(r"^FROM \$\{JAVA_RUNTIME\} AS base", d, re.M) and "distroless" in d, "runtime stage must be distroless")
    need(re.search(r"^USER 10001:10001", d, re.M), "must run as USER 10001:10001")
    need(d.count("-XX:MaxRAMPercentage=75") >= 2, "both images need -XX:MaxRAMPercentage=75")
    need("skipTests" not in d and "maven.test.skip" not in d, "Dockerfile must not build or skip tests")
    need(re.search(r"^FROM base AS migrate", d, re.M) and "com.nabd.hms.common.MigrateMain" in d, "needs a migrate stage running MigrateMain")
    need(re.search(r"--checksum=sha256:[0-9a-f]{64}", d), "OTel agent must be pinned by sha256")
    need("-javaagent:" in d, "app must attach the OTel agent")
    need(not re.search(r"EXPOSE.*8081", d), "management port must not be EXPOSEd")
    need(not re.search(r"^(RUN apk|RUN apt|RUN adduser)", d, re.M), "no package or user setup in the runtime image")
    return f


def main():
    if "--selftest" in sys.argv:
        good = open("Dockerfile").read()
        assert check_dockerfile(good) == [], check_dockerfile(good)
        for bad in (good.replace("USER 10001:10001", "USER root"), good.replace("-XX:MaxRAMPercentage=75", ""),
                    good.replace("distroless", "alpine"), good + "\nRUN mvn -DskipTests package\n"):
            assert check_dockerfile(bad), "bad sample passed"
        print("selftest ok")
        return 0
    f = check_dockerfile(open("Dockerfile").read())
    y = yaml.safe_load(open("backend/src/main/resources/application.yml"))
    m = y["management"]
    if m["endpoints"]["web"]["exposure"]["include"] != "health,prometheus": f.append("only health,prometheus may be exposed")
    if "8081" not in str(m["server"]["port"]): f.append("management port must default to 8081")
    if y["server"]["shutdown"] != "graceful": f.append("server.shutdown must be graceful")
    if y["spring"]["lifecycle"]["timeout-per-shutdown-phase"] != "25s": f.append("shutdown phase timeout must be 25s (OPS-053; OPS-025 spec said 20s, decision pending)")
    if y["spring"]["flyway"]["enabled"] is not False: f.append("spring.flyway.enabled must be false")
    inc = m["endpoint"]["health"]["group"]["readiness"]["include"]
    if "db" not in inc or "outbox" not in inc: f.append("readiness must include db and outbox")
    ci = open(".github/workflows/ci.yml").read()
    if "infra/ci/check-image.sh" not in ci: f.append("ci.yml must run infra/ci/check-image.sh")
    if "--target migrate" not in ci: f.append("ci.yml must build the migrate image")
    pc = open(".github/workflows/prod-config.yml").read()
    if "actuator/health/readiness" not in pc: f.append("prod-config.yml must boot the app and poll readiness")
    if f:
        print("FAILED:\n  " + "\n  ".join(f))
        return 1
    print("OPS-025 artefacts consistent")
    return 0


sys.exit(main())
