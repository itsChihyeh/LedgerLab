#!/bin/sh
set +x
set -eu
SANDBOX_ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)
. "$SANDBOX_ROOT/scripts/sandbox/env.sh"
if command -v python >/dev/null && python -c 'import sys; assert sys.version_info >= (3, 9)' 2>/dev/null; then
  PYTHON=python
else
  PYTHON=python3
fi
export PYTHONIOENCODING=utf-8
exec "$PYTHON" -u "$SANDBOX_ROOT/scripts/sandbox/probe.py" "$@"
