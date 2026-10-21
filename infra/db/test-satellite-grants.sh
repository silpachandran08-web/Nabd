#!/usr/bin/env bash
# OPS-055 grant test on vanilla Postgres 16 (Docker), no cloud: Core schema as shipped (bootstrap-roles + every migration),
# then bootstrap-pooler.sql and bootstrap-satellites.sql. Proves, connecting AS the satellite roles:
#   - comms_*/edge_* cannot connect to the Core database, and even with CONNECT forced on, SELECT on every Core table is
#     "permission denied"; they cannot reach each other's database;
#   - each service role works in its own database (migrator creates, app does DML);
#   - pgbouncer_auth resolves only nabd_app/nabd_reports and reads no table; Core's own roles still connect.
#   infra/db/test-satellite-grants.sh        (needs docker, psql; port 55431 on 127.0.0.1)
set -euo pipefail
REPO=$(cd "$(dirname "$0")/../.." && pwd)
PW=$(head -c 18 /dev/urandom | base64 | tr -dc 'A-Za-z0-9')   # generated per run, never stored
C=ops055-grants-$$
trap 'docker rm -f "$C" >/dev/null 2>&1 || true' EXIT
fail() { echo "FAIL: $*"; exit 1; }
pass=0; ok() { pass=$((pass + 1)); echo "ok   $*"; }
# as <role> <db> <sql...>: real login with that role's password; prints stdout, errors to stderr
as() { PGPASSWORD=$PW psql "host=127.0.0.1 port=55431 dbname=$2 user=$1 connect_timeout=5" -X -q -At -v ON_ERROR_STOP=1 -c "${@:3}" </dev/null; }
su() { PGPASSWORD=$PW psql "host=127.0.0.1 port=55431 dbname=${DB:-nabd} user=postgres" -X -q -At -v ON_ERROR_STOP=1 "$@" </dev/null; }
denied() { # denied <what> <role> <db> <sql>: must fail with "permission denied"
  local out; out=$(as "$2" "$3" "$4" 2>&1) && fail "$1: succeeded ($out)"
  grep -qi "permission denied" <<<"$out" || fail "$1: failed, but not with permission denied: $out"
  ok "$1: permission denied"
}

docker run -d --name "$C" -e POSTGRES_PASSWORD="$PW" -e POSTGRES_DB=nabd -p 127.0.0.1:55431:5432 postgres:16-alpine >/dev/null
for _ in $(seq 60); do su -c 'select 1' >/dev/null 2>&1 && break; sleep 1; done

su -c "ALTER SYSTEM SET password_encryption = 'scram-sha-256'" -c "select pg_reload_conf()" >/dev/null  # RDS: pinned by the Terraform parameter group
echo "== Core: roles + all migrations (as Flyway runs them), then the OPS-055 bootstrap scripts"
su -v migrator_pw="$PW" -v app_pw="$PW" -v reports_pw="$PW" -f "$REPO/infra/db/bootstrap-roles.sql" >/dev/null
for f in $(ls "$REPO"/db/migrations/V*__*.sql | sort -V); do su -1 -f "$f" >/dev/null 2>/tmp/err.$$ || { cat /tmp/err.$$; fail "migration $f"; }; done
su -v pgbouncer_auth_pw="$PW" -f "$REPO/infra/db/bootstrap-pooler.sql" >/dev/null
for i in 1 2; do  # twice: the script must be re-runnable
  DB=postgres su -v comms_migrator_pw="$PW" -v comms_app_pw="$PW" -v edge_migrator_pw="$PW" -v edge_app_pw="$PW" \
     -f "$REPO/infra/db/bootstrap-satellites.sql" >/dev/null
done
ok "bootstrap scripts applied (satellites twice)"

TABLES=$(su -c "select count(*) from pg_tables where schemaname='public'")
[ "$TABLES" -gt 50 ] || fail "expected the Core schema, found $TABLES tables"

echo "== satellite roles cannot reach Core"
for r in comms_app comms_migrator edge_app edge_migrator; do
  denied "$r connect to Core" "$r" nabd "select 1"
  # even if CONNECT were granted by mistake, no Core table is readable
  su -c "GRANT CONNECT ON DATABASE nabd TO $r" >/dev/null
  denied "$r SELECT tenants (CONNECT forced on)" "$r" nabd "select count(*) from tenants"
  denied "$r SELECT audit_log (CONNECT forced on)" "$r" nabd "select count(*) from audit_log"
  readable=$(as "$r" nabd "select count(*) from pg_class c join pg_namespace n on n.oid=c.relnamespace where n.nspname in ('public','master') and c.relkind in ('r','p') and has_table_privilege(current_user, c.oid, 'SELECT')")
  [ "$readable" = 0 ] || fail "$r can SELECT $readable Core tables"
  ok "$r: 0 of $TABLES Core tables readable"
  su -c "REVOKE CONNECT ON DATABASE nabd FROM $r" >/dev/null
done

echo "== each service works in its own database only"
as comms_migrator nabd_comms "create table outbox_probe (id int primary key, body text)" >/dev/null
as comms_app nabd_comms "insert into outbox_probe values (1,'x'); update outbox_probe set body='y'; delete from outbox_probe" >/dev/null
ok "comms_app DML on a table comms_migrator created"
denied "comms_app DDL" comms_app nabd_comms "create table nope (id int)"
as edge_migrator nabd_edge "create table slot_probe (id int primary key)" >/dev/null
as edge_app nabd_edge "insert into slot_probe values (1)" >/dev/null
ok "edge_app DML on a table edge_migrator created"
denied "edge_app DDL" edge_app nabd_edge "create table nope (id int)"
denied "comms_app connect to nabd_edge" comms_app nabd_edge "select 1"
denied "edge_app connect to nabd_comms" edge_app nabd_comms "select 1"
denied "comms_migrator connect to nabd_edge" comms_migrator nabd_edge "select 1"

echo "== Core and the pooler are intact"
for r in nabd_app nabd_reports nabd_migrator; do as "$r" nabd "select 1" >/dev/null; done
ok "nabd_app, nabd_reports, nabd_migrator still connect to Core"
[ "$(as pgbouncer_auth nabd "select count(*) from pgbouncer.get_auth('nabd_app') where password like 'SCRAM-SHA-256\$%'")" = 1 ] || fail "get_auth(nabd_app)"
[ "$(as pgbouncer_auth nabd "select count(*) from pgbouncer.get_auth('nabd_migrator')")" = 0 ] || fail "get_auth resolved the migrator"
[ "$(as pgbouncer_auth nabd "select count(*) from pgbouncer.get_auth('postgres')")" = 0 ] || fail "get_auth resolved the admin"
ok "pgbouncer.get_auth resolves nabd_app (SCRAM) only, not the migrator or admin"
denied "pgbouncer_auth SELECT tenants" pgbouncer_auth nabd "select count(*) from tenants"
denied "nabd_app calls get_auth" nabd_app nabd "select * from pgbouncer.get_auth('nabd_app')"
echo "satellite grant test: $pass checks passed"
