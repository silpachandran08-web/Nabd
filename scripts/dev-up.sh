#!/usr/bin/env bash
# Spins up local Postgres db/role, backend (Spring Boot, Flyway auto-migrates),
# and frontend (Next.js) for a look at the app. Ctrl+C stops both.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ENV_FILE="$ROOT/.env.dev"

# 1. DB + role (idempotent) — must target -d postgres, the default db
# ("silpapremachandran") doesn't exist on a fresh install
psql -d postgres -tAc "SELECT 1 FROM pg_database WHERE datname='nabd'" | grep -q 1 || createdb nabd
psql -d postgres -tAc "SELECT 1 FROM pg_roles WHERE rolname='nabd_app'" | grep -q 1 || \
  psql -d postgres -c "CREATE USER nabd_app WITH PASSWORD 'nabd_app';"
psql -d nabd -c "GRANT ALL ON DATABASE nabd TO nabd_app;" >/dev/null

# 2. APP_CRYPTO_KEY — generate once, reuse on later runs (regenerating would
# invalidate anything already encrypted with the old key)
if [ ! -f "$ENV_FILE" ]; then
  echo "APP_CRYPTO_KEY=$(openssl rand -base64 32)" > "$ENV_FILE"
fi
set -a; source "$ENV_FILE"; set +a

# 3. Backend, backgrounded; killed on script exit
cd "$ROOT/backend"
mvn -q spring-boot:run &
BACKEND_PID=$!
trap 'kill "$BACKEND_PID" 2>/dev/null' EXIT

echo "Waiting for backend on :8080..."
until (exec 3<>/dev/tcp/127.0.0.1/8080) 2>/dev/null; do sleep 1; done
exec 3<&- 3>&-
echo "Backend up."

# 4. Frontend, foreground
cd "$ROOT/frontend"
[ -f .env.local ] || cp .env.local.example .env.local
npm run dev
