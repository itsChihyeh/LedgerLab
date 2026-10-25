#!/bin/sh
# Shared disposable Compose lifecycle; called by smoke-p2.sh and smoke-p3.sh.
set -eu
suite=$1
case "$suite" in p2|p3) ;; *) echo "Unknown smoke suite: $suite" >&2; exit 2 ;; esac
cd "$(dirname "$0")/.."

command -v docker >/dev/null
if command -v python >/dev/null && python -c 'import sys; assert sys.version_info >= (3, 9)' 2>/dev/null; then
  PYTHON=python
else
  PYTHON=python3
  "$PYTHON" -c 'import sys; assert sys.version_info >= (3, 9)'
fi
docker info >/dev/null

# Explicitly load ONLY public smoke values, overriding inherited credentials. --env-file
# prevents Compose's implicit .env loading; -f/-p prevent ambient project selection.
set -a
. ./compose.smoke.env
set +a
unset COMPOSE_FILE COMPOSE_PROFILES COMPOSE_ENV_FILES
export COMPOSE_DISABLE_ENV_FILE=1
project="ledgerlab-smoke-$("$PYTHON" -c 'import uuid; print(uuid.uuid4().hex[:12])')"
compose() {
  docker compose --env-file compose.smoke.env -f compose.yaml -p "$project" --profile smoke "$@"
}
mkdir -p target/smoke-$suite
cleanup() {
  result=$?
  trap - EXIT HUP INT TERM
  compose logs --no-color >target/smoke-$suite/compose.log 2>&1 || true
  if [ "$result" -ne 0 ]; then
    echo "Smoke failed; last service logs (full log: target/smoke-$suite/compose.log):" >&2
    tail -n 100 target/smoke-$suite/compose.log >&2
  fi
  compose down -v --remove-orphans || result=1
  exit "$result"
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' HUP TERM

echo 'Building the app and simulator jars...'
./mvnw -B -DskipTests package
./mvnw -B -f simulator/pom.xml -DskipTests package
echo "Starting disposable project $project..."
compose up -d --build --wait --wait-timeout 180
app_address=$(compose port app 8080)
sim_address=$(compose port simulator 8081)

"$PYTHON" -u "scripts/smoke/$suite.py" "$app_address" "$sim_address" "$project" "target/smoke-$suite"
echo "Smoke passed. Evidence: target/smoke-$suite/ (services and volumes are removed on exit)."
