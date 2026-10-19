#!/usr/bin/env bash
# OPS-036: deploy (or redeploy) the preview of one PR and seed it. Called by .github/workflows/preview.yml.
# Needs kubectl already logged in to the NON-PROD preview cluster with a short-lived OIDC token (Needs external action),
# psql, python3 + argon2-cffi. Env: PR SHA IMAGE_CORE IMAGE_MIGRATE IMAGE_WEB PREVIEW_DOMAIN [PREVIEW_MAX=10] [SEED_OUT=seed]
# Prints app_url= and api_url= lines for $GITHUB_OUTPUT. Generated passwords live only in the namespace Secret.
set -euo pipefail
: "${PR:?}" "${SHA:?}" "${IMAGE_CORE:?}" "${IMAGE_MIGRATE:?}" "${IMAGE_WEB:?}" "${PREVIEW_DOMAIN:?}"
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"; ROOT="$HERE/../.."
export NS="preview-pr-$PR" HOST="pr-$PR.$PREVIEW_DOMAIN" PR SHA IMAGE_CORE IMAGE_MIGRATE IMAGE_WEB
export CREATED_AT="$(date -u +%FT%TZ)" EXPIRES_AT="$(date -u -d '+72 hours' +%FT%TZ)"
SEED_OUT="${SEED_OUT:-seed}"

kubectl get ns -l nabd.io/preview=true -o json | python3 "$HERE/preview.py" cap "${PREVIEW_MAX:-10}" "$PR"

render() { envsubst '${NS} ${PR} ${SHA} ${HOST} ${CREATED_AT} ${EXPIRES_AT} ${IMAGE_CORE} ${IMAGE_MIGRATE} ${IMAGE_WEB}' < "$HERE/$1"; }
render preview.yaml | kubectl apply -f -
fresh=0
if ! kubectl -n "$NS" get secret preview-secrets >/dev/null 2>&1; then
  fresh=1
  kubectl -n "$NS" create secret generic preview-secrets \
    --from-literal=db-admin-password="$(openssl rand -hex 16)" --from-literal=db-migrator-password="$(openssl rand -hex 16)" \
    --from-literal=db-app-password="$(openssl rand -hex 16)" --from-literal=db-reports-password="$(openssl rand -hex 16)" \
    --from-literal=app-crypto-key="$(openssl rand -base64 32)"
fi
sec() { kubectl -n "$NS" get secret preview-secrets -o jsonpath="{.data.$1}" | base64 -d; }

# Every deploy (new commit) starts from an empty database: emptyDir is wiped on restart, and stale seed data hides bugs.
kubectl -n "$NS" delete job migrate --ignore-not-found
kubectl -n "$NS" rollout restart deploy/postgres
kubectl -n "$NS" rollout status deploy/postgres --timeout=2m

kubectl -n "$NS" port-forward svc/postgres 15432:5432 >/dev/null 2>&1 &
PF=$!; trap 'kill $PF 2>/dev/null' EXIT
for i in $(seq 30); do pg_isready -h 127.0.0.1 -p 15432 -q && break; sleep 1; done
ADMIN="postgresql://postgres:$(sec db-admin-password)@127.0.0.1:15432/nabd"
psql -v ON_ERROR_STOP=1 -q "$ADMIN" -v migrator_pw="$(sec db-migrator-password)" -v app_pw="$(sec db-app-password)" \
  -v reports_pw="$(sec db-reports-password)" -f "$ROOT/infra/db/bootstrap-roles.sql"
render migrate.yaml | kubectl apply -f -
kubectl -n "$NS" wait --for=condition=complete job/migrate --timeout=4m
kubectl -n "$NS" rollout restart deploy/core   # boot against the migrated schema
kubectl -n "$NS" rollout status deploy/core --timeout=4m
kubectl -n "$NS" rollout status deploy/web --timeout=2m
kubectl -n "$NS" rollout status deploy/comms-stub --timeout=1m

python3 "$ROOT/tools/synthetic-data/synth.py" generate --out "$RUNNER_TEMP/synthetic.json"
python3 "$ROOT/tools/synthetic-data/load.py" "$RUNNER_TEMP/synthetic.json" --db "$ADMIN" --api "https://$HOST/v1" --out "$SEED_OUT"
echo "fresh=$fresh"
echo "app_url=https://$HOST"
echo "api_url=https://$HOST/v1"
