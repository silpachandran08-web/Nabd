#!/usr/bin/env bash
# OPS-025 container structure test. Usage: infra/ci/check-image.sh <app-image> [<migrate-image>]
# Asserts: UID 10001, no shell, MaxRAMPercentage in the entrypoint, migrate image entrypoint is MigrateMain.
set -u
fail=0
ok()  { echo "ok   $1"; }
bad() { echo "FAIL $1"; fail=1; }
img="$1"; mig="${2:-}"

[ "$(docker inspect -f '{{.Config.User}}' "$img")" = "10001:10001" ] && ok "app runs as 10001:10001" || bad "app user is not 10001:10001"
docker inspect -f '{{json .Config.Entrypoint}}' "$img" | grep -q 'MaxRAMPercentage=75' && ok "app entrypoint has MaxRAMPercentage=75" || bad "MaxRAMPercentage missing"
docker inspect -f '{{json .Config.Entrypoint}}' "$img" | grep -q 'javaagent' && ok "app entrypoint attaches the OTel agent" || bad "OTel agent missing"
docker inspect -f '{{json .Config.ExposedPorts}}' "$img" | grep -q 8081 && bad "management port 8081 is EXPOSEd" || ok "8081 not exposed"
for sh in /bin/sh /bin/bash /busybox/sh; do
  docker run --rm --entrypoint "$sh" "$img" -c true >/dev/null 2>&1 && bad "image has a shell: $sh" || ok "no $sh"
done
if [ -n "$mig" ]; then
  [ "$(docker inspect -f '{{.Config.User}}' "$mig")" = "10001:10001" ] && ok "migrate runs as 10001:10001" || bad "migrate user is not 10001:10001"
  docker inspect -f '{{json .Config.Entrypoint}}' "$mig" | grep -q 'com.nabd.hms.common.MigrateMain' && ok "migrate entrypoint is MigrateMain" || bad "migrate entrypoint wrong"
fi
exit $fail
