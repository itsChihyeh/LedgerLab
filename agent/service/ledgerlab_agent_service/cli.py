"""Command line: python agent/ledgerlab-agent-service.py (--base-url URL | --snapshot FILE) --model stub ..."""

from __future__ import annotations

import argparse
import sys
from collections.abc import Sequence
from pathlib import Path
from typing import TextIO

from ledgerlab_agent.replay import load_snapshot

from ledgerlab_agent_service.app import LOOPBACK_HOSTS, InvestigationService, make_server
from ledgerlab_agent_service.runner import Runner
from ledgerlab_agent_service.store import JsonlStore

DEFAULT_PORT = 8097
DEFAULT_STORE = Path("agent/.runs/investigations.jsonl")
MODEL_NAMES = ("stub", "baseline", "scripted")


def _parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(prog="ledgerlab-agent-service")
    source = parser.add_mutually_exclusive_group(required=True)
    source.add_argument("--base-url", help="live backend, e.g. http://127.0.0.1:8080")
    source.add_argument("--snapshot", type=Path, help="recorded snapshot (replay), no backend needed")
    parser.add_argument("--model", required=True, help="stub | baseline | scripted:<path.json>")
    parser.add_argument("--host", default="127.0.0.1", help="loopback address only (default 127.0.0.1)")
    parser.add_argument("--port", type=int, default=DEFAULT_PORT, help="0 picks a free port")
    parser.add_argument("--store", type=Path, default=DEFAULT_STORE, help="append-only JSONL file of investigations")
    return parser


def main(argv: Sequence[str] | None = None, stderr: TextIO | None = None) -> int:
    err = stderr or sys.stderr
    args = _parser().parse_args(argv)  # a usage error exits with code 2 on its own
    try:
        if args.host not in LOOPBACK_HOSTS:
            raise ValueError("--host must be a loopback address: 127.0.0.1, ::1 or localhost")
        if not 0 <= args.port <= 65535:
            raise ValueError("--port must be between 0 and 65535")
        if args.model.partition(":")[0] not in MODEL_NAMES:
            raise ValueError("--model must be one of: stub, baseline, scripted:<path.json>")
        snapshot = load_snapshot(args.snapshot) if args.snapshot else None
        runner = Runner(args.model, base_url=args.base_url, snapshot=snapshot, stderr=err)
        store = JsonlStore(args.store, err)
        service = InvestigationService(runner, store, "snapshot" if snapshot is not None else "base-url")
        server = make_server(args.host, args.port, service, err)
    except (ValueError, OSError) as exc:
        print(f"error: {exc}", file=err)
        return 2
    host, port = server.server_address[:2]
    print(f"listening on http://{'[' + host + ']' if ':' in host else host}:{port}", file=err, flush=True)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        server.server_close()
    return 0
