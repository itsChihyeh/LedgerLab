"""Thin launcher: puts agent/src and agent/mcp on sys.path and runs the read-only MCP tool server (stdio)."""

import sys
from pathlib import Path

_HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(_HERE / "src"))
sys.path.insert(0, str(_HERE / "mcp"))

if __name__ == "__main__":
    from ledgerlab_agent_mcp.cli import main

    raise SystemExit(main())
