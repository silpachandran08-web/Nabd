#!/usr/bin/env bash
# Independent QA (S12 OPS-035 AC1/AC4 negative): in a throw-away copy of this commit, add an undocumented controller
# method, change one @PreAuthorize and add a phantom operation to api/openapi/core.yaml; the stage 5 gate
# (ApiContractGateTest, exactly what ci.yml runs) and QA's runtime gate must FAIL and name each mutation.
# Usage (heavy: mvn): flock /home/claude/ws/heavy.lock scripts/ci/s12qa_ops035_mutation.sh <scratch-dir>
set -uo pipefail
REPO=$(git rev-parse --show-toplevel)
SCRATCH=${1:?scratch dir}
rm -rf "$SCRATCH"; git -C "$REPO" worktree prune
git -C "$REPO" worktree add --detach "$SCRATCH" HEAD >/dev/null
trap 'git -C "$REPO" worktree remove --force "$SCRATCH"' EXIT
J="$SCRATCH/backend/src/main/java/com/nabd/hms"
python3 - "$J" "$SCRATCH/api/openapi/core.yaml" <<'PY'
import sys
j, core = sys.argv[1], sys.argv[2]
f = j + "/shared/phiread/PhiReadAlertController.java"
s = open(f).read()
s = s.replace('    @GetMapping("/v1/phi-read/alerts")',
              '    @GetMapping("/v1/phi-read/qa-undocumented")\n    public String qaUndocumented() { return "x"; }\n\n    @GetMapping("/v1/phi-read/alerts")', 1)
open(f, "w").write(s)
f = j + "/platform/access/SupportAccessController.java"
s = open(f).read()
assert "hasAuthority('support_access:request')" in s
open(f, "w").write(s.replace("hasAuthority('support_access:request')", "hasAuthority('support_access:view')", 1))
s = open(core).read()
assert "\npaths:\n" in s
open(core, "w").write(s.replace("\npaths:\n", "\npaths:\n  /v1/qa-phantom:\n    get:\n      operationId: qaPhantom\n      x-required-permission: public\n      responses:\n        '200':\n          description: OK\n", 1))
PY
/home/claude/ws/run-backend-tests.sh "$SCRATCH" -Dtest=ApiContractGateTest,S12QaOps035ContractGateTest
python3 - "$SCRATCH/backend/target/surefire-reports" <<'PY'
import glob, sys
txt = "".join(open(x).read() for x in glob.glob(sys.argv[1] + "/TEST-*.xml"))
want = {
    "gate: undocumented operation": "undocumented operation: get /v1/phi-read/qa-undocumented",
    "gate: phantom operation": "phantom operation: get /v1/qa-phantom",
    "gate: permission mismatch": "post /v1/platform/support-access/grants: x-required-permission support_access:request != code [support_access:view]",
    "qa runtime: undocumented": "get /v1/phi-read/qa-undocumented",
    "qa runtime: mismatch": "post /v1/platform/support-access/grants: spec support_access:request != code hasAuthority(&apos;support_access:view&apos;)",
}
bad = [k for k, v in want.items() if v not in txt]
for k, v in want.items():
    print(("SEEN    " if k not in bad else "MISSING ") + k)
print("MUTATION RESULT:", "PASS (every mutation was caught)" if not bad else "FAIL")
sys.exit(1 if bad else 0)
PY
