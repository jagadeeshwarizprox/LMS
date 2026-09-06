#!/usr/bin/env bash
# Local development without Docker. Needs Java 17, Maven, Node 20 and a running Mongo.
set -euo pipefail

command -v mvn >/dev/null || { echo "Maven is not installed."; exit 1; }
command -v node >/dev/null || { echo "Node is not installed."; exit 1; }

if ! (exec 3<>/dev/tcp/127.0.0.1/27017) 2>/dev/null; then
  echo "MongoDB is not answering on 27017. Start it first."
  exit 1
fi

export JWT_SECRET="${JWT_SECRET:-development-secret-change-me-at-least-32-characters}"

# a fresh database has no users at all, so without these nobody can sign in. Only ever
# used when the database is empty; it can never reset an existing account.
export BOOTSTRAP_EMAIL="${BOOTSTRAP_EMAIL:-admin@proitbridge.com}"
export BOOTSTRAP_PASSWORD="${BOOTSTRAP_PASSWORD:-change-me-on-first-sign-in}"
export BOOTSTRAP_NAME="${BOOTSTRAP_NAME:-Super admin}"

echo "Building the backend."
(cd backend && mvn -q clean package -DskipTests)

echo "Starting the API on 8080."
(cd backend && java -jar target/pib-lms-1.0.0.jar) &
API=$!
trap 'kill $API 2>/dev/null || true' EXIT

until curl -sf http://localhost:8080/api/health >/dev/null 2>&1; do
  sleep 1
done
echo "API is up."

echo "Starting the frontend on 5173."
(cd frontend && npm install --no-audit --no-fund && npm run dev)
