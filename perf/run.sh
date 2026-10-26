#!/usr/bin/env bash
# OPS-046: one command per profile against any environment, synthetic credentials only.
#   BASE_URL=https://<env> PERF_PIN=<synthetic pin> PEAK_RPS=<pilot peak req/s> perf/run.sh registry|checkin|rush|contention|webhook_storm|dayend|soak [--seed] [--save-baseline]
# The run drives 2 x PEAK_RPS for DURATION (default 30m). Optional:
#   PERF_DB_URL       psql URL (any DML role): --seed loads perf/data/seed.sql, waits for the registry read model to drain,
#                     and samples pg_stat_activity (API connection usage, the Hikari pool's view of the DB) every 5 s
#   PERF_PG_CONTAINER docker container of a local Postgres: also samples DB CPU (managed DBs: read CPU from the provider)
#   PERF_TAG (s2) PERF_TENANTS (50) PERF_PATIENTS per tenant (10000) DURATION RAMP K6 (k6 binary)
# Output: perf/out/<profile>-<utc>/ with the k6 summary, <profile>-report.json/.html; exit 1 on a failed gate or a >10% p95
# regression against perf/baseline/<profile>.json (--save-baseline stores this run as the new baseline when the gate passes).
set -euo pipefail
PROFILE=${1:?registry|checkin}; shift
: "${BASE_URL:?}" "${PERF_PIN:?}" "${PEAK_RPS:?pilot peak requests/s (the run uses 2x)}"
HERE=$(cd "$(dirname "$0")" && pwd)
export PERF_TAG=${PERF_TAG:-s2} PERF_TENANTS=${PERF_TENANTS:-50} RATE=$((PEAK_RPS * 2))
OUT="$HERE/out/$PROFILE-$(date -u +%Y%m%dT%H%M%SZ)"; mkdir -p "$OUT"; export OUT
SEED=0; SAVE=""
for a in "$@"; do case $a in --seed) SEED=1 ;; --save-baseline) SAVE=--save-baseline ;; *) echo "unknown $a"; exit 2 ;; esac; done

if [ "$SEED" = 1 ]; then
  : "${PERF_DB_URL:?--seed needs PERF_DB_URL}"
  M2=${M2:-$HOME/.m2/repository}
  CP="$(ls "$M2"/org/springframework/security/spring-security-crypto/*/*.jar | tail -1):$(ls "$M2"/org/bouncycastle/bcprov-jdk18on/*/*.jar | tail -1):$(ls "$M2"/org/springframework/spring-jcl/*/*.jar | tail -1)"
  HASH=$(java -cp "$CP" "$HERE/data/PinHash.java" "$PERF_PIN" 2>/dev/null)
  psql "$PERF_DB_URL" -q -v tag="$PERF_TAG" -v tenants="$PERF_TENANTS" -v patients="${PERF_PATIENTS:-10000}" -v pin_hash="$HASH" -f "$HERE/data/seed.sql"
  echo "waiting for the registry read model to drain..."
  until [ "$(psql "$PERF_DB_URL" -tAc 'SELECT count(*) FROM registry_dirty_tenants(1000)')" = 0 ]; do sleep 5; done
fi

SAMPLER=""
if [ -n "${PERF_DB_URL:-}" ]; then
  ( while :; do
      cpu=""; [ -n "${PERF_PG_CONTAINER:-}" ] && cpu=$(docker stats --no-stream --format '{{.CPUPerc}}' "$PERF_PG_CONTAINER" | tr -d '%')
      conns=$(psql "$PERF_DB_URL" -tAc "SELECT count(*) FILTER (WHERE state = 'active') || ',' || count(*) FROM pg_stat_activity WHERE usename = 'nabd_app' AND pid <> pg_backend_pid()" 2>/dev/null || echo ",")
      echo "$(date +%s),$cpu,$conns" >> "$OUT/db-samples.csv"; sleep 5
    done ) & SAMPLER=$!
fi
trap '[ -n "$SAMPLER" ] && kill "$SAMPLER" 2>/dev/null || true' EXIT

"${K6:-k6}" run --quiet "$HERE/k6/$PROFILE.js" || true # thresholds are judged by report.mjs, together with the DB gate
node "$HERE/report.mjs" "$OUT/$PROFILE-summary.json" --samples "$OUT/db-samples.csv" --baseline "$HERE/baseline/$PROFILE.json" $SAVE
