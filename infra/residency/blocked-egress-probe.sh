#!/usr/bin/env bash
# OPS-057 staging probe (Needs external action: run against staging-in/prod-in with kubectl access and Cilium Hubble).
# A pod that tries a host outside infra/prod-in/egress-policy.yaml must fail to connect AND the drop must appear in Hubble.
set -euo pipefail
NS="${1:-prod-in}"
HOST="${2:-example.org}"
if kubectl -n "$NS" run egress-probe --rm -i --restart=Never --image=curlimages/curl --command -- curl -sS --max-time 8 "https://$HOST" >/dev/null 2>&1; then
  echo "FAIL: egress to $HOST was NOT blocked"; exit 1
fi
hubble observe --namespace "$NS" --verdict DROPPED --to-fqdn "$HOST" --last 50 | grep -q "$HOST" \
  || { echo "FAIL: $HOST was blocked but no DROPPED verdict was logged"; exit 1; }
echo "ok: egress to $HOST blocked and logged"
