#!/usr/bin/env bash
# IDC-055: validates the FHIR golden bundles (backend/src/test/resources/exchange/fhir/golden) against the NRCeS / ABDM
# profiles with the HL7 FHIR validator (validator_cli.jar, released at github.com/hapifhir/org.hl7.fhir.core).
# The jar is not vendored and not downloaded here: point FHIR_VALIDATOR_JAR at it. Without it this prints SKIPPED and
# exits 0: the build stays green but the profile check has NOT run (docs/architecture/fhir-mapping.md).
#   FHIR_IG              the guide to validate against: package id#version (fetched from the FHIR package registry) or a
#                        local package.tgz. Default ndhm.in#6.5.0.
#   FHIR_VALIDATOR_ARGS  extra validator arguments, e.g. "-tx n/a" on a runner that cannot reach the terminology server.
# Exit code: the validator's (non-zero when any bundle has an error).
set -euo pipefail
cd "$(dirname "$0")/../.."
JAR="${FHIR_VALIDATOR_JAR:-}"
if [ -z "$JAR" ] || [ ! -f "$JAR" ]; then
  echo "SKIPPED: FHIR profile validation did not run (FHIR_VALIDATOR_JAR is not set or is not a file)."
  [ -z "${GITHUB_ACTIONS:-}" ] || echo "::warning title=FHIR profile validation::SKIPPED, no validator jar on this runner (IDC-055)"
  exit 0
fi
# shellcheck disable=SC2086  # FHIR_VALIDATOR_ARGS is a list of arguments
exec java -jar "$JAR" backend/src/test/resources/exchange/fhir/golden -version 4.0.1 -ig "${FHIR_IG:-ndhm.in#6.5.0}" ${FHIR_VALIDATOR_ARGS:-}
