#!/bin/sh
# k6 performance baseline (AC-9.10, docs/design/demo-and-delivery.md section 8). Runs on its own Compose project
# `ledgerlab-perf` (ports 38080/38081/35432); never touches ledgerlab-sandbox or ledgerlab-demo.
# Usage: sh perf/run.sh            full baseline (3 rounds), writes docs/perf/baseline-<UTC date>.{md,json}
#        sh perf/run.sh --quick    1 round, 10 s stages, for checking the harness (writes under target/perf/)
set +x
set -eu
unset COMPOSE_FILE COMPOSE_PROFILES COMPOSE_ENV_FILES COMPOSE_PROJECT_NAME
export COMPOSE_DISABLE_ENV_FILE=1 PYTHONIOENCODING=utf-8
cd "$(dirname "$0")/.."
if command -v python >/dev/null && python -c 'import sys; assert sys.version_info >= (3, 9)' 2>/dev/null; then
  PYTHON=python
else
  PYTHON=python3
fi
exec "$PYTHON" -u perf/run.py "$@"
