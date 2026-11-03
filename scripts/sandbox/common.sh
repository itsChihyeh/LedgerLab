#!/bin/sh
set +x
set -eu
SANDBOX_ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)
. "$SANDBOX_ROOT/scripts/sandbox/env.sh"
# Database credentials are generated and loaded from .sandbox/secrets.env by Python.
unset COMPOSE_FILE COMPOSE_PROFILES COMPOSE_ENV_FILES
export COMPOSE_DISABLE_ENV_FILE=1 PYTHONIOENCODING=utf-8
if command -v python >/dev/null && python -c 'import sys; assert sys.version_info >= (3, 9)' 2>/dev/null; then
  PYTHON=python
else
  PYTHON=python3
fi
cd "$SANDBOX_ROOT"
