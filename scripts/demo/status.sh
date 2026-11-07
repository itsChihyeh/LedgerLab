#!/bin/sh
# Show the demo stack's containers, counters and last reset.
. "$(dirname -- "$0")/common.sh"
exec "$PYTHON" -u scripts/demo/demo.py status "$@"
