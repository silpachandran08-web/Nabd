#!/usr/bin/env bash
# OPS-050: produce the exit-report data folder from read-only queries. Usage:
#   CORE_URL=postgres://... EDGE_URL=postgres://... docs/gates/phase1/run.sh <from> <to> <out-dir> <tenant-uuid>...
# Use a read-only role (nabd_reports on Core). RLS is forced, so each clinic is queried with app.tenant_id bound to it.
set -euo pipefail
FROM=$1; TO=$2; OUT=$3; shift 3
DIR=$(cd "$(dirname "$0")" && pwd)
mkdir -p "$OUT"
q() { # db query-name tenant
  [[ $3 =~ ^[0-9a-fA-F-]{36}$ ]] || { echo "not a tenant uuid: $3" >&2; exit 1; }
  psql "$1" -v ON_ERROR_STOP=1 -X -q -A -F, --pset footer=off -v tenant="$3" -v from="$FROM" -v to="$TO" <<SQL
\o /dev/null
SELECT set_config('app.tenant_id', :'tenant', false);
\o
\i $DIR/$2.sql
SQL
}
for name in eta_within10 wait_median delay_broadcast day_close whatsapp_booking; do
  db=${CORE_URL:?}; [ "$name" = whatsapp_booking ] && db=${EDGE_URL:?}
  first=1
  for t in "$@"; do
    out=$(q "$db" "$name" "$t")
    if [ $first = 1 ]; then printf '%s\n' "$out" > "$OUT/$name.csv"; first=0; else printf '%s\n' "$out" | tail -n +2 >> "$OUT/$name.csv"; fi
  done
done
echo "wrote $OUT; add stopwatch.csv (docs/uat), sus.csv and uat-signoff.csv, then: python3 scripts/ci/phase1_gate.py report --data $OUT --sha <sha> --clinics ..."
