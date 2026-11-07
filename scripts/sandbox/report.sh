#!/bin/sh
. "$(dirname -- "$0")/common.sh"
exec "$PYTHON" -u scripts/sandbox/report.py "$@"
