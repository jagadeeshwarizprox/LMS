#!/usr/bin/env bash
# PIB LMS load test, end to end: seed, run k6, write results/.
#
# Assumes the stack is up:  docker compose up -d --build   (all three healthy)
#
#   ./loadtest/run.sh               seed (if not seeded) + full test (5m ramp, 10m hold, 2m down)
#   ./loadtest/run.sh seed          seed only
#   ./loadtest/run.sh test          k6 only (data already seeded)
#   ./loadtest/run.sh smoke         2 minute run with 50 VUs, to check the wiring first
#   ./loadtest/run.sh wipe          delete seeded data only
#
# Seeding runs in a python container on the compose network (mongo has no published
# port). To use local python instead: SEED_MODE=local MONGODB_URI=... ./loadtest/run.sh seed
# (publish mongo's port first, see the commented ports block in docker-compose.yml).
#
# Environment (all optional):
#   BASE_URL      default http://localhost:8081 (the web container)
#   SEED_MODE     docker | local                     (default docker)
#   LT_LEARNERS   learners to seed                   (default 20000)
#   SEED_INDEXES  1 = also create the recommended indexes (see README)
#   LEARNER_VUS MENTOR_VUS ADMIN_VUS RAMP HOLD DOWN THINK_MIN THINK_MAX
#   MENTOR_ROSTER_PATH MENTOR_ROSTER_QUERY ADMIN_REGISTER_PATH ADMIN_REGISTER_QUERY
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/.." && pwd)"
cd "$ROOT"

K6_IMAGE="${K6_IMAGE:-grafana/k6:0.54.0}"
PY_IMAGE="${PY_IMAGE:-python:3.12-slim}"
BASE_URL="${BASE_URL:-http://localhost:8081}"
SEED_MODE="${SEED_MODE:-docker}"
RUN_ID="${RUN_ID:-$(date +%Y%m%d-%H%M%S)}"
mkdir -p "$HERE/results" "$HERE/data"

# read MONGO_USER / MONGO_PASSWORD from .env without exporting everything in it
envval() { [ -f "$ROOT/.env" ] && grep -E "^$1=" "$ROOT/.env" | tail -1 | cut -d= -f2- | sed 's/^"\(.*\)"$/\1/' || true; }
MONGO_USER="${MONGO_USER:-$(envval MONGO_USER)}"; MONGO_USER="${MONGO_USER:-pib}"
MONGO_PASSWORD="${MONGO_PASSWORD:-$(envval MONGO_PASSWORD)}"

seed_args=()
[ "${SEED_INDEXES:-0}" = "1" ] && seed_args+=(--indexes)

run_seed() {
  if [ "$SEED_MODE" = "local" ]; then
    : "${MONGODB_URI:?set MONGODB_URI for SEED_MODE=local}"
    python3 -m pip install -q -r "$HERE/seed/requirements.txt"
    python3 "$HERE/seed/seed.py" "$@"
  else
    [ -n "$MONGO_PASSWORD" ] || { echo "MONGO_PASSWORD not set (.env)"; exit 1; }
    # same network as the stack, so the host name "mongo" resolves
    docker compose run --rm --no-deps \
      -e MONGODB_URI="mongodb://${MONGO_USER}:${MONGO_PASSWORD}@mongo:27017/pib_lms?authSource=admin" \
      -e LT_LEARNERS="${LT_LEARNERS:-20000}" \
      seed "$@"
  fi
}

run_k6() {
  local url="$BASE_URL" net=(--network host)
  if [ "$(uname -s)" != "Linux" ]; then
    # Docker Desktop: no host networking; reach the published port via the host gateway
    url="${url/localhost/host.docker.internal}"; url="${url/127.0.0.1/host.docker.internal}"
    net=()
  fi
  [ -s "$HERE/data/learners.csv" ] || { echo "No loadtest/data/learners.csv: run the seed first"; exit 1; }
  curl -fsS "$BASE_URL/api/health" >/dev/null || { echo "API not reachable at $BASE_URL/api/health"; exit 1; }
  echo "k6 against $url, results in loadtest/results/*-$RUN_ID.*"
  set +e
  docker run --rm -i ${net[@]+"${net[@]}"} \
    -u "$(id -u):$(id -g)" \
    -v "$HERE:/lt" -w /lt/k6 \
    -e K6_NO_USAGE_REPORT=true \
    -e BASE_URL="$url" -e RESULTS_DIR=/lt/results -e RUN_ID="$RUN_ID" \
    -e LEARNER_VUS -e MENTOR_VUS -e ADMIN_VUS -e RAMP -e HOLD -e DOWN -e THINK_MIN -e THINK_MAX \
    -e LEARNER_OFFSET -e MENTOR_ROSTER_PATH -e MENTOR_ROSTER_QUERY -e ADMIN_REGISTER_PATH -e ADMIN_REGISTER_QUERY \
    "$K6_IMAGE" run "$@" main.js
  local rc=$?
  set -e
  # what the server looked like at the end of the run
  docker stats --no-stream --format 'table {{.Name}}\t{{.CPUPerc}}\t{{.MemUsage}}' \
    > "$HERE/results/docker-stats-$RUN_ID.txt" 2>/dev/null || true
  echo "Report: loadtest/results/report-$RUN_ID.html  (exit $rc: 0 pass, 99 thresholds failed)"
  return $rc
}

# The progress snapshots are filled by a background job at API start. Seeding into a
# running stack leaves them empty, so restart the API and wait for that job to finish
# before measuring, or the test times first-view recomputes instead of normal reads.
warm_api() {
  echo "Restarting the API so it fills progress snapshots for the seeded learners"
  docker compose restart api
  for i in $(seq 1 180); do
    if docker compose logs --since 10m api 2>/dev/null | grep -q "Progress snapshots (startup backfill): finished"; then
      echo "Snapshots ready"; return 0
    fi
    sleep 5
  done
  echo "Snapshot backfill not finished after 15 minutes; check: docker compose logs api"; exit 1
}

already_seeded() {
  [ -s "$HERE/data/learners.csv" ] && [ "$(wc -l < "$HERE/data/learners.csv")" -gt 1 ]
}

case "${1:-all}" in
  seed)  run_seed ${seed_args[@]+"${seed_args[@]}"} && warm_api ;;
  wipe)  run_seed --wipe && rm -f "$HERE/data/learners.csv" "$HERE/data/staff.csv" ;;
  test)  run_k6 ;;
  smoke) LEARNER_VUS=40 MENTOR_VUS=6 ADMIN_VUS=4 RAMP=30s HOLD=1m DOWN=15s run_k6 ;;
  all)
    if already_seeded; then echo "Seed CSVs present, skipping seed (./loadtest/run.sh wipe to start over)";
    else run_seed ${seed_args[@]+"${seed_args[@]}"} && warm_api; fi
    run_k6 ;;
  *) sed -n '2,25p' "$0"; exit 2 ;;
esac
