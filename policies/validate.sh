#!/usr/bin/env bash
# IDC-026: `cedar validate` for every policy file in policies/ against the schema; a schema error or a warning fails.
# Also the gate's own test: a policy with a known schema error must be rejected, so a broken gate cannot pass silently.
# Needs the cedar CLI on PATH (CI job cedar-validate installs the pinned release). Usage: policies/validate.sh
set -euo pipefail
cd "$(dirname "$0")"
cedar --version
for f in *.cedar; do
  echo "validate $f"
  cedar validate --schema nabd.cedarschema --policies "$f" --deny-warnings
done
if cedar validate --schema nabd.cedarschema --policies test-fixtures/schema-error.cedar >/dev/null 2>&1; then
  echo "FAIL: test-fixtures/schema-error.cedar passed validation, the gate does not catch schema errors"; exit 1
fi
echo "ok: test-fixtures/schema-error.cedar is rejected"
