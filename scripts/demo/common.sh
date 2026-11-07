#!/bin/sh
# Shared by the demo scripts: repo root, a Python 3.9+, no ambient Compose selection.
set +x
set -eu
DEMO_ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)
unset COMPOSE_FILE COMPOSE_PROFILES COMPOSE_ENV_FILES COMPOSE_PROJECT_NAME
export COMPOSE_DISABLE_ENV_FILE=1 PYTHONIOENCODING=utf-8
if command -v python >/dev/null && python -c 'import sys; assert sys.version_info >= (3, 9)' 2>/dev/null; then
  PYTHON=python
else
  PYTHON=python3
fi
cd "$DEMO_ROOT"
