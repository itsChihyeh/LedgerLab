"""Command line for the MCP tool server: ledgerlab-agent-mcp (--base-url URL | --snapshot FILE) [--max-calls N]."""

from __future__ import annotations

import argparse
import sys
from collections.abc import Sequence
from pathlib import Path
from typing import TextIO

from ledgerlab_agent.replay import ReplayTransport, load_snapshot
from ledgerlab_agent.sop import SopSearch, load_sop
from ledgerlab_agent.tools import ToolRegistry
from ledgerlab_agent.transport import HttpTransport, Transport

from ledgerlab_agent_mcp.server import DEFAULT_MAX_CALLS, ServerState, serve


def _parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(prog="ledgerlab-agent-mcp", description="Read-only MCP tool server (stdio).")
    source = parser.add_mutually_exclusive_group(required=True)
    source.add_argument("--base-url", help="live backend, e.g. http://127.0.0.1:8080")
    source.add_argument("--snapshot", type=Path, help="recorded snapshot (replay), no backend needed")
    parser.add_argument("--max-calls", type=int, default=DEFAULT_MAX_CALLS, help="tools/call limit per process")
    return parser


def build_state(args: argparse.Namespace) -> ServerState:
    if args.max_calls < 1:
        raise ValueError("--max-calls must be at least 1")
    transport: Transport = (
        ReplayTransport(load_snapshot(args.snapshot)) if args.snapshot else HttpTransport(args.base_url)
    )
    tools = ToolRegistry(transport, local_tools=[SopSearch(load_sop())])
    return ServerState(tools, max_calls=args.max_calls)


def main(
    argv: Sequence[str] | None = None,
    stdin: TextIO | None = None,
    stdout: TextIO | None = None,
    stderr: TextIO | None = None,
) -> int:
    err = stderr or sys.stderr
    try:
        args = _parser().parse_args(argv)
    except SystemExit as exc:  # argparse already printed the problem to stderr
        return int(exc.code) if isinstance(exc.code, int) else 2
    try:
        state = build_state(args)
    except (ValueError, OSError) as exc:
        print(f"error: {exc}", file=err)
        return 2
    in_stream, out_stream = stdin or sys.stdin, stdout or sys.stdout
    if stdin is None:
        in_stream.reconfigure(encoding="utf-8", newline="\n")  # type: ignore[union-attr]
    if stdout is None:
        out_stream.reconfigure(encoding="utf-8", newline="\n")  # type: ignore[union-attr]
    return serve(state, in_stream, out_stream, err)
