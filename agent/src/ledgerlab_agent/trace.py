"""Investigation trace: a JSON-ready record of how one investigation went, for reading failures after a paid run.

Pure data (no file access; the files are written by `agent/eval/trace_files.py`, outside this write-free package).
It holds what the model saw and did, never what it thought: the reasoning content is reported by length only, and no
key or request header is ever part of an `Investigation`, so none can end up here.
"""

from __future__ import annotations

import dataclasses
from typing import Any

from ledgerlab_agent.agent import Investigation, ToolUseRecord, TurnRecord
from ledgerlab_agent.tools import ToolResult
from ledgerlab_agent.verify import Verification

TRACE_FORMAT = 1


def _tool_call(index: int, result: ToolResult) -> dict[str, Any]:
    return {
        "index": index,
        "tool": result.tool,
        "args": result.args,
        "ok": result.ok,
        "status": result.status,
        "truncated": result.truncated,
        "error": result.error,
        "records": [f"{r.kind}:{r.id}" for r in result.records],
    }


def _tool_use(use: ToolUseRecord) -> dict[str, Any]:
    return {"name": use.name, "input": use.input, "call_index": use.call_index, "note": use.note}


def _turn(turn: TurnRecord) -> dict[str, Any]:
    return {
        "turn": turn.turn,
        "final": turn.final,
        "text": turn.text,
        "reasoning_chars": turn.reasoning_chars,
        "stop_reason": turn.stop_reason,
        "usage": dataclasses.asdict(turn.usage),
        "tool_uses": [_tool_use(u) for u in turn.tool_uses],
    }


def _record_list(records: Any) -> list[str]:
    return [f"{r.kind}:{r.id}" for r in records]


def _verification(v: Verification) -> dict[str, Any]:
    return {
        "citations_total": v.citations_total,
        "citations_valid": v.citations_valid,
        "fabricated_citations": _record_list(v.fabricated_citations),
        "fabricated_explanation_ids": list(v.fabricated_explanation_ids),
        "malformed_citations": _record_list(v.malformed_citations),
        "supported": v.supported,
        "missing_evidence": [list(item) for item in v.missing_evidence],
    }


def investigation_trace(
    issue_id: str, result: Investigation, verification: Verification, extra: dict[str, Any] | None = None
) -> dict[str, Any]:
    """`extra` carries what only the caller knows (an evaluation adds the case id and the expected cause)."""
    return {
        "format": TRACE_FORMAT,
        "issue_id": issue_id,
        **(extra or {}),
        "status": result.status,
        "final_turn_submit": result.final_turn_submit,
        "conclusion": result.conclusion.as_dict(),
        "validation_errors": list(result.validation_errors),
        "turns": result.turns,
        "usage": dataclasses.asdict(result.usage),
        "elapsed_ms": result.elapsed_ms,
        "verification": _verification(verification),
        "turn_log": [_turn(t) for t in result.turn_log],
        "tool_calls": [_tool_call(i, c) for i, c in enumerate(result.tool_calls)],
    }
