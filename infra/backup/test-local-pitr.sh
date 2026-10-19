#!/usr/bin/env bash
# OPS-054 local proof of the drill mechanics on vanilla Postgres 16 (Docker), no cloud:
# real schema (all db/migrations), WAL archiving, base backup, point-in-time recovery to a chosen target time,
# then verify-restore.py must PASS on the restored copy and FAIL after a row is removed and an audit row is tampered.
#   infra/backup/test-local-pitr.sh            (needs docker, psql; ports 55414/55415 on 127.0.0.1)
set -euo pipefail
REPO=$(cd "$(dirname "$0")/../.." && pwd)
WORK=$(mktemp -d)
PW=$(head -c 18 /dev/urandom | base64 | tr -dc 'A-Za-z0-9')   # generated per run, never stored
SRC=ops054-src-$$ RST=ops054-rst-$$
cleanup() { docker rm -f "$SRC" "$RST" >/dev/null 2>&1 || true; rm -rf "$WORK"; }
trap cleanup EXIT
mkdir -p "$WORK/archive"
chmod 777 "$WORK/archive"
export PGPASSWORD=$PW
S="host=127.0.0.1 port=55414 dbname=nabd user=postgres"
R="host=127.0.0.1 port=55415 dbname=nabd user=postgres"
q() { psql "$1" -w -X -q -At -v ON_ERROR_STOP=1 "${@:2}" </dev/null; }
fail() { echo "FAIL: $*"; exit 1; }

docker run -d --name "$SRC" -e POSTGRES_PASSWORD="$PW" -e POSTGRES_DB=nabd -p 127.0.0.1:55414:5432 \
  -v "$WORK/archive:/archive" postgres:16-alpine \
  -c wal_level=replica -c archive_mode=on -c archive_command='cat %p > /archive/%f' -c archive_timeout=30 >/dev/null
for _ in $(seq 60); do q "$S" -c 'select 1' >/dev/null 2>&1 && break; sleep 1; done

echo "== schema: roles + all migrations"
q "$S" -v migrator_pw="$PW" -v app_pw="$PW" -v reports_pw="$PW" -f "$REPO/infra/db/bootstrap-roles.sql" >/dev/null
for f in $(ls "$REPO"/db/migrations/V*__*.sql | sort -V); do q "$S" -f "$f" >/dev/null 2>"$WORK/err" || { cat "$WORK/err"; fail "migration $f"; }; done
echo "applied $(ls "$REPO"/db/migrations/V*__*.sql | wc -l) migrations"

T1=$(q "$S" -c "INSERT INTO tenants(slug,name,region) VALUES ('drill-a','Drill A','IN') RETURNING id")
T2=$(q "$S" -c "INSERT INTO tenants(slug,name,region) VALUES ('drill-b','Drill B','IN') RETURNING id")
seed() { # tenant, n, label
  q "$S" -c "BEGIN; SELECT set_config('app.tenant_id','$1',true);
    INSERT INTO roles(tenant_id,name) SELECT '$1', '$3-'||g FROM generate_series(1,$2) g;
    INSERT INTO audit_log(tenant_id,actor_type,actor_name,actor_role,action,entity_type)
      SELECT '$1','system','drill','System','drill.$3','drill' FROM generate_series(1,$2);
    COMMIT;" >/dev/null
}

echo "== base backup, batch A, target time, batch B"
docker exec -e PGPASSWORD="$PW" "$SRC" pg_basebackup -w -h 127.0.0.1 -U postgres -D /archive/base -X fetch </dev/null
seed "$T1" 40 a; seed "$T2" 25 a
sleep 1; TARGET=$(q "$S" -c "SELECT to_char(clock_timestamp() AT TIME ZONE 'UTC','YYYY-MM-DD\"T\"HH24:MI:SS.US\"Z\"')"); sleep 1
seed "$T1" 7 b; seed "$T2" 3 b
q "$S" -c "SELECT pg_switch_wal()" >/dev/null
WAL=$(q "$S" -c "SELECT pg_walfile_name(pg_current_wal_lsn())")
for _ in $(seq 60); do
  last=$(q "$S" -c "SELECT coalesce(last_archived_wal,'') FROM pg_stat_archiver")
  [ -n "$last" ] && [[ ! "$last" < "$WAL" ]] && break
  q "$S" -c "SELECT pg_switch_wal()" >/dev/null; sleep 1
done
[ -n "$last" ] && [[ ! "$last" < "$WAL" ]] || { q "$S" -c "SELECT * FROM pg_stat_archiver"; docker logs "$SRC" 2>&1 | grep -i archiv | tail -5; fail "WAL not archived"; }
echo "target time $TARGET; archived through $last"

echo "== point-in-time restore into an isolated container"
t0=$(date +%s)
PGTARGET="${TARGET/T/ }"; PGTARGET="${PGTARGET%Z}+00"   # recovery_target_time wants 'YYYY-MM-DD HH:MM:SS+00'
cp -a "$WORK/archive/base" "$WORK/restore"
touch "$WORK/restore/recovery.signal"
printf "restore_command = 'cat /archive/%%f > %%p'\nrecovery_target_time = '%s'\nrecovery_target_action = 'promote'\n" "$PGTARGET" >> "$WORK/restore/postgresql.auto.conf"
chmod 700 "$WORK/restore"   # the postgres entrypoint fixes ownership when the container starts
docker run -d --name "$RST" -p 127.0.0.1:55415:5432 -v "$WORK/restore:/var/lib/postgresql/data" -v "$WORK/archive:/archive:ro" \
  postgres:16-alpine >/dev/null
for _ in $(seq 120); do [ "$(q "$R" -c 'select pg_is_in_recovery()' 2>/dev/null)" = "f" ] && break; sleep 1; done
[ "$(q "$R" -c 'select pg_is_in_recovery()' 2>/dev/null)" = "f" ] || { docker logs "$RST" 2>&1 | tail -15; fail "restore did not finish"; }
RESTORE_S=$(( $(date +%s) - t0 ))

count() { q "$1" -c "SELECT count(*) FROM audit_log WHERE tenant_id='$2'"; }
[ "$(count "$S" "$T1")" = 47 ] || fail "source should have 47 audit rows for tenant A"
[ "$(count "$R" "$T1")" = 40 ] || fail "restored should stop at the target time (40 rows), got $(count "$R" "$T1")"
echo "PASS restored stops at the target time (tenant A audit rows: source 47, restored 40)"

V="host=127.0.0.1 port=55414 dbname=nabd user=nabd_reports"; VR="host=127.0.0.1 port=55415 dbname=nabd user=nabd_reports"
t1=$(date +%s)
python3 "$REPO/infra/backup/verify-restore.py" --source "$V" --restored "$VR" --target-time "$TARGET" --tenant "$T1" --tenant "$T2" > "$WORK/ok.json" \
  || { cat "$WORK/ok.json"; fail "verify should pass on a clean restore"; }
VERIFY_S=$(( $(date +%s) - t1 ))
python3 -c "import json,sys;r=json.load(open(sys.argv[1]));assert r['ok'] and all(t['audit_chain_ok'] and t['rows_restored']>0 for t in r['tenants'].values())" "$WORK/ok.json"
echo "PASS verify-restore: row counts match at the target time, audit chain verified for 2 tenants (as nabd_reports, RLS on)"

# Damage the restored copy: lose one role row, tamper one audit row.
q "$R" -c "DELETE FROM roles WHERE id = (SELECT id FROM roles WHERE tenant_id='$T1' LIMIT 1)" >/dev/null
q "$R" -c "ALTER TABLE audit_log DISABLE TRIGGER USER; UPDATE audit_log SET action='tampered' WHERE id=(SELECT min(id) FROM audit_log WHERE tenant_id='$T2'); ALTER TABLE audit_log ENABLE TRIGGER USER" >/dev/null
if python3 "$REPO/infra/backup/verify-restore.py" --source "$V" --restored "$VR" --target-time "$TARGET" --tenant "$T1" --tenant "$T2" > "$WORK/bad.json"; then
  fail "verify passed on a damaged restore"
fi
grep -q "row(s) created before the target time are missing" "$WORK/bad.json" || fail "missing row not reported"
grep -q "audit chain broken" "$WORK/bad.json" || fail "tampered audit chain not reported"
echo "PASS verify-restore fails on a damaged restore (missing row + broken audit chain)"
echo "timings: restore ${RESTORE_S}s, verify ${VERIFY_S}s (local, tiny DB; not a production RTO measurement)"
