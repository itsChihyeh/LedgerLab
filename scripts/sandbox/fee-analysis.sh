#!/bin/sh
. "$(dirname -- "$0")/common.sh"
exec "$PYTHON" -u scripts/sandbox/fee_analysis.py "$@"
