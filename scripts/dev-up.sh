#!/usr/bin/env bash
# Spins up local Postgres with the SAME split roles as production (OPS-003 / ADR-07), migrates as nabd_migrator,
# then runs the backend as nabd_app (DML only, RLS-bound) and the frontend (Next.js). Ctrl+C stops both.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ENV_FILE="$ROOT/.env.dev"

# 1. Local-only secrets, generated once and reused (regenerating APP_CRYPTO_KEY would orphan anything encrypted with it)
if [ ! -f "$ENV_FILE" ]; then
  {
    echo "APP_CRYPTO_KEY=$(openssl rand -base64 32)"
    echo "MIGRATOR_PW=$(openssl rand -hex 16)"
    echo "APP_PW=$(openssl rand -hex 16)"
    echo "REPORTS_PW=$(openssl rand -hex 16)"
  } > "$ENV_FILE"
fi
set -a; source "$ENV_FILE"; set +a
: "${MIGRATOR_PW:?delete .env.dev so it is regenerated with the split-role passwords}"

# 2. DB + roles (idempotent; -d postgres because the default db "<user>" may not exist on a fresh install)
psql -d postgres -tAc "SELECT 1 FROM pg_database WHERE datname='nabd'" | grep -q 1 || createdb nabd
if [ "$(psql -d nabd -tAc "SELECT count(*) FROM pg_class WHERE relowner = (SELECT oid FROM pg_roles WHERE rolname='nabd_app') AND relkind = 'r'" 2>/dev/null || echo 0)" != "0" ]; then
  echo "This database was created before the split-role topology (nabd_app owns tables). Run: dropdb nabd && rm .env.dev" >&2
  exit 1
fi
psql -v ON_ERROR_STOP=1 -q -d nabd -v migrator_pw="$MIGRATOR_PW" -v app_pw="$APP_PW" -v reports_pw="$REPORTS_PW" \
  -f "$ROOT/infra/db/bootstrap-roles.sql"

# 3. Migrate as nabd_migrator (the only place those credentials are used), then boot the API as nabd_app
cd "$ROOT/backend"
mvn -q -DskipTests package
MIGRATE_DB_URL="jdbc:postgresql://localhost:5432/nabd" MIGRATE_DB_USER=nabd_migrator MIGRATE_DB_PASSWORD="$MIGRATOR_PW" \
  MIGRATE_LOCATIONS="filesystem:$ROOT/db/migrations" \
  java -cp target/hms-backend-0.1.0.jar -Dloader.main=com.nabd.hms.common.MigrateMain \
  org.springframework.boot.loader.launch.PropertiesLauncher

export DB_USER=nabd_app DB_PASSWORD="$APP_PW"
mvn -q spring-boot:run &
BACKEND_PID=$!
trap 'kill "$BACKEND_PID" 2>/dev/null' EXIT

echo "Waiting for backend on :8080..."
until (exec 3<>/dev/tcp/127.0.0.1/8080) 2>/dev/null; do sleep 1; done
exec 3<&- 3>&-
echo "Backend up."

# 4. Frontend, foreground
cd "$ROOT/frontend/apps/staff-web"
[ -f .env.local ] || cp .env.local.example .env.local
pnpm run dev
