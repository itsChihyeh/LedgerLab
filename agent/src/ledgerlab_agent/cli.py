"""Command line entry point: ledgerlab-agent investigate <issue_id> ..."""

from __future__ import annotations

import argparse
import json
import sys
from collections.abc import Callable, Sequence
from typing import TextIO

from pathlib import Path

from ledgerlab_agent.agent import Budget, Investigation, investigate
from ledgerlab_agent.baseline import BaselineInvestigator
from ledgerlab_agent.model import ModelClient, ScriptedModel
from ledgerlab_agent.replay import ReplayTransport, load_snapshot
from ledgerlab_agent.sop import SopSearch, load_sop
from ledgerlab_agent.stub_model import StubModel
from ledgerlab_agent.tools import ToolRegistry
from ledgerlab_agent.transport import HttpTransport, Transport

BASELINE = "baseline"


def _scripted(arg: str) -> ModelClient:
    if not arg:
        raise ValueError("scripted model needs a script path: scripted:<path.json>")
    return ScriptedModel.from_json_file(arg)


def _stub(arg: str) -> ModelClient:
    return StubModel()


# name -> factory(argument after the colon). The rule baseline is not a model; main() runs it directly.
MODEL_REGISTRY: dict[str, Callable[[str], ModelClient]] = {"scripted": _scripted, "stub": _stub}


def build_model(spec: str) -> ModelClient:
    name, _, arg = spec.partition(":")
    factory = MODEL_REGISTRY.get(name)
    if factory is None:
        raise ValueError(f"unknown model {name!r}; available: {', '.join(sorted(MODEL_REGISTRY))}")
    return factory(arg)


def _parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(prog="ledgerlab-agent")
    sub = parser.add_subparsers(dest="command", required=True)
    inv = sub.add_parser("investigate", help="investigate one reconciliation issue")
    inv.add_argument("issue_id")
    source = inv.add_mutually_exclusive_group(required=True)
    source.add_argument("--base-url", help="live backend, e.g. http://127.0.0.1:8080")
    source.add_argument("--snapshot", type=Path, help="recorded snapshot (replay), no backend needed")
    inv.add_argument("--model", required=True, help="baseline | stub | scripted:<path.json>")
    inv.add_argument("--max-tool-calls", type=int, default=Budget.max_tool_calls)
    inv.add_argument("--json", action="store_true", help="print the conclusion as JSON")
    return parser


def _summary(result: Investigation) -> str:
    c = result.conclusion
    lines = [
        f"status: {result.status}",
        f"root cause: {c.root_cause} (confidence {c.confidence}{', abstained' if c.abstain else ''})",
        f"recommended action: {c.recommended_action}",
        f"explanation: {c.explanation}",
        "citations: " + (", ".join(f"{r.kind}:{r.id}" for r in c.citations) or "none"),
        f"tool calls: {len(result.tool_calls)}, model turns: {result.turns}",
    ]
    return "\n".join(lines)


def main(argv: Sequence[str] | None = None, stdout: TextIO | None = None, stderr: TextIO | None = None) -> int:
    out = stdout or sys.stdout
    err = stderr or sys.stderr
    args = _parser().parse_args(argv)
    try:
        transport: Transport = (
            ReplayTransport(load_snapshot(args.snapshot)) if args.snapshot else HttpTransport(args.base_url)
        )
        budget = Budget(max_tool_calls=args.max_tool_calls)
        sop = load_sop()
        tools = ToolRegistry(transport, local_tools=[SopSearch(sop)])
        if args.model == BASELINE:
            result = BaselineInvestigator(tools, sop, budget).investigate(args.issue_id)
        else:
            result = investigate(args.issue_id, build_model(args.model), tools, budget)
    except (ValueError, OSError) as exc:
        print(f"error: {exc}", file=err)
        return 2
    if args.json:
        print(json.dumps(result.conclusion.as_dict(), ensure_ascii=False, indent=2), file=out)
    else:
        print(_summary(result), file=out)
    return 0 if result.status == "ok" else 3
