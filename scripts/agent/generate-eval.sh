#!/bin/sh
# P8-T04: generate the frozen evaluation sets (agent/eval/data/dev and test) against a disposable Compose stack.
# Replacing frozen data needs --force. Needs Docker, Python >= 3.12 and the app and simulator jars:
#   ./mvnw -B -DskipTests package && ./mvnw -B -f simulator/pom.xml -DskipTests package
# Usage: sh scripts/agent/generate-eval.sh [--split dev|test|all] [--force]
set -eu
cd "$(dirname "$0")/../.."
command -v docker >/dev/null || { echo 'docker is required' >&2; exit 1; }
if command -v python >/dev/null && python -c 'import sys; assert sys.version_info >= (3, 12)' 2>/dev/null; then
  PYTHON=python
elif command -v python3 >/dev/null && python3 -c 'import sys; assert sys.version_info >= (3, 12)' 2>/dev/null; then
  PYTHON=python3
else
  echo 'Python 3.12 or newer is required' >&2
  exit 1
fi
docker info >/dev/null 2>&1 || { echo 'Docker is not running' >&2; exit 1; }
exec "$PYTHON" -u agent/eval/generate.py "$@"
