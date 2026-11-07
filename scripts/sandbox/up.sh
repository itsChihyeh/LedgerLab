#!/bin/sh
. "$(dirname -- "$0")/common.sh"
exec "$PYTHON" -u scripts/sandbox/stack.py up "$@"
