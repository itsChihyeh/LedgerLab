"""Command line entry point: ledgerlab-agent investigate <issue_id> ..."""

from __future__ import annotations

import argparse
import json
import sys
import time
from collections.abc import Callable, Mapping, Sequence
from typing import TextIO

from pathlib import Path

from ledgerlab_agent import external
from ledgerlab_agent.agent import Budget, Investigation, investigate
from ledgerlab_agent.baseline import BaselineInvestigator
from ledgerlab_agent.deepseek_model import Transport as ModelTransport
from ledgerlab_agent.model import ModelClient, ModelError, ScriptedModel
from ledgerlab_agent.pricing import PRICING_PATH, Prices, SpendLimitReached, estimate_run_usd
from ledgerlab_agent.replay import ReplayTransport, load_snapshot
from ledgerlab_agent.sop import SopSearch, load_sop
from ledgerlab_agent.stub_model import StubModel
from ledgerlab_agent.tools import ToolRegistry
from ledgerlab_agent.trace import investigation_trace
from ledgerlab_agent.transport import HttpTransport, Transport
from ledgerlab_agent.verify import verify

from ledgerlab_agent.deepseek_model import REASONING_EFFORTS

BASELINE = "baseline"
# (directory, file name, document) -> None; the launcher supplies the one that writes files (this package writes none).
TraceWriter = Callable[[Path, str, dict], object]


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


def add_external_arguments(parser: argparse.ArgumentParser) -> None:
    """Options for an external model (`--model deepseek:<model>`); shared with the eval command."""
    parser.add_argument("--reasoning-effort", choices=REASONING_EFFORTS, default=external.DEFAULT_REASONING_EFFORT)
    parser.add_argument("--max-tokens", type=int, default=external.DEFAULT_MAX_TOKENS, help="output limit per request, thinking included")
    parser.add_argument(
        "--confirm-spend",
        type=float,
        metavar="USD",
        help="required for an external model: the most you allow this run to cost; no request is sent without it",
    )


def _parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(prog="ledgerlab-agent")
    sub = parser.add_subparsers(dest="command", required=True)
    inv = sub.add_parser("investigate", help="investigate one reconciliation issue")
    inv.add_argument("issue_id")
    source = inv.add_mutually_exclusive_group(required=True)
    source.add_argument("--base-url", help="live backend, e.g. http://127.0.0.1:8080")
    source.add_argument("--snapshot", type=Path, help="recorded snapshot (replay), no backend needed")
    inv.add_argument("--model", required=True, help="baseline | stub | scripted:<path.json> | deepseek:<model>")
    add_external_arguments(inv)
    inv.add_argument("--max-tool-calls", type=int, default=Budget.max_tool_calls)
    inv.add_argument("--json", action="store_true", help="print the conclusion as JSON")
    inv.add_argument("--trace-dir", type=Path, help="also write this investigation's trace (JSON) into this directory")
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


def _single_estimate(prices: Prices) -> float:
    return estimate_run_usd(
        1, external.SINGLE_INVESTIGATION_INPUT_TOKENS, prices, 1.0, external.DEFAULT_OUTPUT_TOKENS_PER_QUESTION
    )


def main(
    argv: Sequence[str] | None = None,
    stdout: TextIO | None = None,
    stderr: TextIO | None = None,
    model_transport: ModelTransport | None = None,
    environ: Mapping[str, str] | None = None,
    pricing_path: Path = PRICING_PATH,
    sleep: Callable[[float], None] = time.sleep,
    trace_writer: TraceWriter | None = None,
) -> int:
    """`model_transport`, `environ`, `pricing_path` and `sleep` exist for tests of the external model path;
    `trace_writer` is what `--trace-dir` writes with."""
    out = stdout or sys.stdout
    err = stderr or sys.stderr
    args = _parser().parse_args(argv)
    spent: str | None = None
    try:
        if args.trace_dir is not None and trace_writer is None:
            raise ValueError("--trace-dir needs the launcher (python agent/ledgerlab-agent.py), which writes the files")
        guarded = None
        if external.is_external(args.model):  # every guard runs before anything else is set up
            config = external.parse_spec(args.model, args.reasoning_effort, args.max_tokens)
            guarded, _ = external.prepare(
                config, args.confirm_spend, _single_estimate, err, model_transport, environ, sleep, pricing_path
            )
        transport: Transport = (
            ReplayTransport(load_snapshot(args.snapshot)) if args.snapshot else HttpTransport(args.base_url)
        )
        budget = Budget(max_tool_calls=args.max_tool_calls)
        sop = load_sop()
        tools = ToolRegistry(transport, local_tools=[SopSearch(sop)])
        if guarded is not None:
            result = investigate(args.issue_id, guarded, tools, budget)
            spent = f"spent {guarded.guard.spent_usd:.4f} USD of the {guarded.guard.cap_usd:.4f} USD cap"
        elif args.model == BASELINE:
            result = BaselineInvestigator(tools, sop, budget).investigate(args.issue_id)
        else:
            result = investigate(args.issue_id, build_model(args.model), tools, budget)
        if args.trace_dir is not None and trace_writer is not None:
            document = investigation_trace(args.issue_id, result, verify(result, sop.required_evidence()))
            trace_writer(args.trace_dir, args.issue_id, document)
    except (ValueError, OSError, ModelError, SpendLimitReached) as exc:
        print(f"error: {exc}", file=err)
        return 2
    if spent:
        print(spent, file=err)
    if args.json:
        print(json.dumps(result.conclusion.as_dict(), ensure_ascii=False, indent=2), file=out)
    else:
        print(_summary(result), file=out)
    return 0 if result.status == "ok" else 3
