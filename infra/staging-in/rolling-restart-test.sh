#!/usr/bin/env bash
# OPS-053: live-session continuity across a replica restart. Needs kubectl, curl, jq and synthetic credentials in env:
#   STAGING_URL (LB base, no /v1), STAGING_TENANT, STAGING_EMAIL, STAGING_PIN. Nothing is stored; tokens stay in shell variables.
set -euo pipefail
: "${STAGING_URL:?}" "${STAGING_TENANT:?}" "${STAGING_EMAIL:?}" "${STAGING_PIN:?}"
NS=${NS:-staging-in}; API="$STAGING_URL/v1"
code() { curl -s -o /dev/null -w '%{http_code}' -H "Authorization: Bearer $1" "$API/auth/sessions"; }

pair=$(jq -n --arg t "$STAGING_TENANT" --arg e "$STAGING_EMAIL" --arg p "$STAGING_PIN" '{tenantSlug:$t,email:$e,pin:$p}' |
  curl -sf -H 'Content-Type: application/json' -d @- "$API/auth/login")
access=$(jq -r .accessToken <<<"$pair"); refresh=$(jq -r .refreshToken <<<"$pair")
[ "$(kubectl -n "$NS" get pods -l app=core --field-selector=status.phase=Running -o name | wc -l)" -ge 2 ] || { echo "FAIL: fewer than 2 core pods"; exit 1; }
[ "$(code "$access")" = 200 ] || { echo "FAIL: token rejected before restart"; exit 1; }

victim=$(kubectl -n "$NS" get pods -l app=core -o name | head -1)
kubectl -n "$NS" delete "$victim" --wait=false
bad=0
for i in $(seq 40); do c=$(code "$access"); [ "$c" = 200 ] || { bad=$((bad+1)); echo "request $i -> $c"; }; sleep 0.5; done
[ "$bad" = 0 ] || { echo "FAIL: $bad requests not 200 during restart"; exit 1; }

new=$(curl -sf -H 'Content-Type: application/json' -d "$(jq -n --arg r "$refresh" '{refreshToken:$r}')" "$API/auth/refresh")
[ "$(code "$(jq -r .accessToken <<<"$new")")" = 200 ] || { echo "FAIL: refreshed token rejected"; exit 1; }
echo "PASS: session survived restart of $victim, refresh works"
