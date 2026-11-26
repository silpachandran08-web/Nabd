#!/usr/bin/env bash
# IDC-026: builds the spike on the host, builds the production image (repo Dockerfile, default target = the API image)
# and runs the spike inside it as the image's own runtime user. Exit 0 = in runs 1 and 2 the native library loaded,
# the self-checks passed and the 1-resource row met p95 < 5 ms and p99 < 10 ms (see CedarSpike).
# Uses Docker, so it takes the heavy lock exactly like kanban/sprint-kit/run-service-tests.sh.
# Usage: spikes/cedar/run-in-prod-image.sh
set -uo pipefail
REPO=$(cd "$(dirname "$0")/../.." && pwd)
LOCK=/tmp/nabd-heavy.lock
until mkdir "$LOCK" 2>/dev/null; do echo "waiting for heavy-job lock ($(cat $LOCK/owner 2>/dev/null))"; sleep 20; done
echo "$REPO/spikes/cedar $$" > "$LOCK/owner"; trap 'rm -rf "$LOCK"' EXIT
if ! docker info >/dev/null 2>&1; then
  open -a Docker; for i in $(seq 1 48); do docker info >/dev/null 2>&1 && break; sleep 5; done
  docker info >/dev/null 2>&1 || { echo "Docker did not start: NOT RUN (environment)"; exit 1; }
fi
mvn -B -q -f "$REPO/spikes/cedar/pom.xml" package dependency:copy-dependencies -DoutputDirectory="$REPO/spikes/cedar/target/lib" \
  || { echo "spike build or cedar-java download failed: NOT RUN (environment)"; exit 1; }
IMG=nabd-cedar-spike-prod:local
docker build -q -t "$IMG" "$REPO" || { echo "production image build failed: NOT RUN (environment)"; exit 1; }
echo "image: user=$(docker inspect -f '{{.Config.User}}' "$IMG") platform=$(docker inspect -f '{{.Os}}/{{.Architecture}}' "$IMG")"
run() {  # $1 label, $2 resources-per-call sweep, rest = docker flags. Same JVM flag as the image entrypoint; the OTel
         # agent is left out (it instruments nothing in cedar-java).
  echo "== $1 | host load: $(uptime | sed 's/.*load averages*: //')"; sweep=$2; shift 2
  docker run --rm --network none "$@" -v "$REPO/spikes/cedar/target:/spike:ro" -v "$REPO/policies:/policies:ro" \
    --entrypoint java "$IMG" -XX:MaxRAMPercentage=75 -cp '/spike/classes:/spike/lib/*' com.nabd.spike.cedar.CedarSpike /policies $sweep
}
rc=0
run "1. image defaults: the user set in the Dockerfile, no extra flags" "1 10 25 50" || rc=1
run "2. hardened: every capability dropped, no-new-privileges, read-only root, /tmp on tmpfs with exec" 1 \
  --cap-drop ALL --security-opt no-new-privileges --read-only --tmpfs /tmp:exec || rc=1
run "3. reported only: read-only root and no writable /tmp" 1 --cap-drop ALL --read-only
echo "(run 3 exit=$?)"
run "4. reported only: read-only root, /tmp on tmpfs with Docker's default noexec" 1 --cap-drop ALL --read-only --tmpfs /tmp
echo "(run 4 exit=$?)"
docker rmi "$IMG" >/dev/null
exit $rc
