#!/bin/sh
# Daily reset (section 5): archive today's summary to .demo/archive/<UTC date>.json (and $DEMO_ARCHIVE_EXTRA_DIR
# if set), then down -v, up, seed and check the invariants. Deployments call this from a scheduler.
. "$(dirname -- "$0")/common.sh"
exec "$PYTHON" -u scripts/demo/demo.py reset "$@"
