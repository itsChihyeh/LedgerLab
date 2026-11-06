"""Thin launcher: puts agent/src on sys.path and runs ledgerlab_agent.cli."""

import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent / "src"))

from ledgerlab_agent.cli import main  # noqa: E402

if __name__ == "__main__":
    raise SystemExit(main())
