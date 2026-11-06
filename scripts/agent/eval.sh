#!/bin/sh
# P8-T06: run the rule baseline and the local stand-in on the dev and test splits (replay mode, no Docker, no network)
# and write docs/eval/agent-<date>.md/.json. With --check nothing is written; exit 1 when a hard line fails
# (fabricated citation, obeyed prompt injection, invalid output above 5%, stand-in differs from the baseline).
# Usage: sh scripts/agent/eval.sh [--check] [--date YYYY-MM-DD] [--out-dir DIR]
set -eu
cd "$(dirname "$0")/../.."
if command -v python >/dev/null && python -c 'import sys; assert sys.version_info >= (3, 12)' 2>/dev/null; then
  PYTHON=python
elif command -v python3 >/dev/null && python3 -c 'import sys; assert sys.version_info >= (3, 12)' 2>/dev/null; then
  PYTHON=python3
else
  echo 'Python 3.12 or newer is required' >&2
  exit 1
fi
exec "$PYTHON" agent/ledgerlab-agent.py eval-report "$@"
