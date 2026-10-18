#!/usr/bin/env bash
# flyway validate + migrate on an empty DB, then (if SEED_SQL is set) on a seeded snapshot. Fails if > 180 s.
# Needs: PGURL host/port/db via env, a running Postgres, docker (flyway image). Not run in the sandbox without Docker.
set -euo pipefail
: "${DB_HOST:=localhost}" "${DB_PORT:=5432}" "${DB_NAME:=nabd}" "${DB_USER:=postgres}" "${PGPASSWORD:?set PGPASSWORD}"
fw() { docker run --rm --network host -v "$PWD/db/migrations:/flyway/sql:ro" flyway/flyway:10 \
  -url="jdbc:postgresql://$DB_HOST:$DB_PORT/$1" -user="$DB_USER" -password="$PGPASSWORD" "${@:2}"; }
start=$(date +%s)
psql -h "$DB_HOST" -p "$DB_PORT" -U "$DB_USER" -d postgres -c "DROP DATABASE IF EXISTS ${DB_NAME}_empty" -c "CREATE DATABASE ${DB_NAME}_empty"
fw "${DB_NAME}_empty" migrate validate
if [ -n "${SEED_SQL:-}" ]; then  # snapshot produced by the perf seed (OPS-031), restored at the previous release schema
  psql -h "$DB_HOST" -p "$DB_PORT" -U "$DB_USER" -d postgres -c "DROP DATABASE IF EXISTS ${DB_NAME}_seeded" -c "CREATE DATABASE ${DB_NAME}_seeded"
  psql -h "$DB_HOST" -p "$DB_PORT" -U "$DB_USER" -d "${DB_NAME}_seeded" -v ON_ERROR_STOP=1 -f "$SEED_SQL"
  fw "${DB_NAME}_seeded" migrate validate
fi
elapsed=$(( $(date +%s) - start )); echo "migrate-check: ${elapsed}s"
[ "$elapsed" -le 180 ] || { echo "migrate-check: over the 3 min budget"; exit 1; }
