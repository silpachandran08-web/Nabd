#!/usr/bin/env bash
# OPS-054: PITR restore drill into the isolated drill environment, with verification and timings. RTO target: 1 h.
#   restore-drill.sh <source-db-identifier> <target-time ISO-8601 UTC, e.g. 2026-11-02T10:15:00Z>
# Runs on a host INSIDE the cell's drill security group (output backup.drill_sg_id), with AWS credentials from OIDC/SSO.
# Env (names only; values from the secret manager, never from git):
#   AWS_REGION            cell primary region (ap-south-1)
#   DRILL_SG_ID           terraform output backup.drill_sg_id
#   DRILL_SUBNET_GROUP    terraform output backup.drill_subnet_group
#   SOURCE_URL            libpq URL of the source DB as nabd_reports, no password (PGPASSFILE)
#   APP_IMAGE             API image digest to boot against the restored DB
#   APP_ENV_FILE          env file for the API (DB_USER=nabd_app, DB_PASSWORD, APP_CRYPTO_KEY, JWT_KEYS_FILE...), from the secret manager
#   DRILL_RECORD          where to write the timings JSON (default ./restore-drill-<id>.json)
set -euo pipefail
SRC=${1:?source db identifier}
TARGET=${2:?target time}
: "${AWS_REGION:?}" "${DRILL_SG_ID:?}" "${DRILL_SUBNET_GROUP:?}" "${SOURCE_URL:?}" "${APP_IMAGE:?}" "${APP_ENV_FILE:?}"
HERE=$(cd "$(dirname "$0")" && pwd)
ID="nabd-drill-$(date -u +%Y%m%d%H%M)"
RECORD=${DRILL_RECORD:-./restore-drill-$ID.json}
APP=""

cleanup() {
  [ -n "$APP" ] && docker rm -f "$APP" >/dev/null 2>&1 || true
  # The drill copy is disposable: no final snapshot, no retained backups.
  aws rds delete-db-instance --region "$AWS_REGION" --db-instance-identifier "$ID" \
    --skip-final-snapshot --delete-automated-backups >/dev/null 2>&1 || true
}
trap cleanup EXIT

t0=$(date +%s)
echo "[$ID] restoring $SRC to $TARGET"
aws rds restore-db-instance-to-point-in-time --region "$AWS_REGION" \
  --source-db-instance-identifier "$SRC" --target-db-instance-identifier "$ID" --restore-time "$TARGET" \
  --db-subnet-group-name "$DRILL_SUBNET_GROUP" --vpc-security-group-ids "$DRILL_SG_ID" \
  --no-publicly-accessible --no-multi-az --no-deletion-protection --copy-tags-to-snapshot \
  --tags Key=purpose,Value=restore-drill Key=data_class,Value=phi >/dev/null
aws rds wait db-instance-available --region "$AWS_REGION" --db-instance-identifier "$ID"
HOST=$(aws rds describe-db-instances --region "$AWS_REGION" --db-instance-identifier "$ID" \
  --query 'DBInstances[0].Endpoint.Address' --output text)
t_restored=$(date +%s)

echo "[$ID] verifying row counts and audit chains"
VERIFY_OK=true
python3 "$HERE/verify-restore.py" --source "$SOURCE_URL" --target-time "$TARGET" \
  --restored "host=$HOST dbname=nabd user=nabd_reports sslmode=require" > "$RECORD.verify.json" || VERIFY_OK=false
t_verified=$(date +%s)

echo "[$ID] booting the API against the restored DB"
APP="$ID-api"
docker run -d --name "$APP" --env-file "$APP_ENV_FILE" -e "DB_URL=jdbc:postgresql://$HOST:5432/nabd?sslmode=require" \
  -p 127.0.0.1:18080:8080 "$APP_IMAGE" >/dev/null
BOOT_OK=false
for _ in $(seq 180); do
  if curl -fsS http://127.0.0.1:18080/actuator/health/readiness >/dev/null 2>&1; then BOOT_OK=true; break; fi
  sleep 5
done
t_booted=$(date +%s)

TOTAL=$((t_booted - t0))
cat > "$RECORD" <<EOF
{"drill": "$ID", "source": "$SRC", "target_time": "$TARGET", "started_at": "$(date -u -d "@$t0" +%FT%TZ)",
 "restore_seconds": $((t_restored - t0)), "verify_seconds": $((t_verified - t_restored)), "boot_seconds": $((t_booted - t_verified)),
 "total_seconds": $TOTAL, "within_rto_1h": $([ "$TOTAL" -le 3600 ] && echo true || echo false),
 "verify_ok": $VERIFY_OK, "app_boot_ok": $BOOT_OK}
EOF
cat "$RECORD"
$VERIFY_OK && $BOOT_OK && [ "$TOTAL" -le 3600 ]
