#!/bin/sh
# Build and start the local demo stack (project ledgerlab-demo), seed it and check all invariants (AC-9.8).
. "$(dirname -- "$0")/common.sh"
exec "$PYTHON" -u scripts/demo/demo.py up "$@"
