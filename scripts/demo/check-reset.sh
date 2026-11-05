#!/bin/sh
# One-shot evidence for AC-9.5 and AC-9.8: up, a few actions, reset, assert archive/zero counts/invariants, down.
# Writes target/demo/check-reset-*.json. Leaves the ledgerlab-sandbox project alone and checks that.
. "$(dirname -- "$0")/common.sh"
exec "$PYTHON" -u scripts/demo/demo.py check-reset "$@"
