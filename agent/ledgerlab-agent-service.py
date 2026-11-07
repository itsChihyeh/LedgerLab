"""Thin launcher: puts agent/src and agent/service on sys.path and runs the local investigation service."""

import sys
from pathlib import Path

_HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(_HERE / "src"))
sys.path.insert(0, str(_HERE / "service"))

if __name__ == "__main__":
    from ledgerlab_agent_service.cli import main

    raise SystemExit(main())
