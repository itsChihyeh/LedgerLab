#!/bin/sh
# P8-T04 (AC-10.5): regenerate a split from its manifest seed and compare it with the committed data, ignoring ids.
# Usage: sh scripts/agent/verify-eval.sh --split dev|test|hard
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
exec "$PYTHON" -u agent/eval/verify_dataset.py "$@"
