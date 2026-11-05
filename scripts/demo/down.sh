#!/bin/sh
# Remove the demo stack and its volumes. Demo data is reset daily anyway; secrets and the archive stay in .demo/.
. "$(dirname -- "$0")/common.sh"
exec "$PYTHON" -u scripts/demo/demo.py down "$@"
