"""Local stand-in for a language model: no network, no shortcuts, same Agent loop and validator.

Every turn the stub reads only the conversation it is handed. It parses the `<tool_data>` blocks of the tool results
already in it back into `ToolResult` objects, asks the baseline's pure `next_step` function what to do, and answers
with `tool_use` blocks: the next tool calls, or `submit_conclusion` once the evidence is complete. It keeps no state
between turns, so a conversation with different tool results leads to a different next step.

Token usage is an estimate (characters / 4, rounded up), not a measurement: `usage_is_estimate` says so.
Reports must label results from this model as "stand-in, not a measure of a language model".
"""

from __future__ import annotations

import json
import re
from typing import Any

from ledgerlab_agent.baseline import NeedMore, next_step
from ledgerlab_agent.model import SUBMIT_TOOL, ModelRequest, ModelResponse, ToolUseBlock, Usage
from ledgerlab_agent.records import extract_records
from ledgerlab_agent.sop import SopIndex, load_sop
from ledgerlab_agent.tools import ToolResult

_ISSUE_ID = re.compile(r"Investigate reconciliation issue ([A-Za-z0-9_\-]{1,64})")
_TOOL_DATA = re.compile(r'<tool_data tool="([^"]*)">(.*)</tool_data>', re.DOTALL)
CHARS_PER_TOKEN = 4


def estimate_tokens(text: str) -> int:
    return -(-len(text) // CHARS_PER_TOKEN)


class StubModel:
    usage_is_estimate = True

    def __init__(self, sop: SopIndex | None = None) -> None:
        self._sop = sop or load_sop()

    def complete(self, request: ModelRequest) -> ModelResponse:
        issue_id = _issue_id(request.messages)
        results = _tool_results(request.messages)
        step = next_step(issue_id, results, self._sop)
        used = sum(1 for message in request.messages for block in _blocks(message) if block.get("type") == "tool_use")
        if isinstance(step, NeedMore):
            content = tuple(ToolUseBlock(f"toolu_stub_{used + i}", c.tool, c.args) for i, c in enumerate(step.calls))
        else:
            content = (ToolUseBlock(f"toolu_stub_{used}", SUBMIT_TOOL, step.conclusion.as_dict()),)
        prompt = request.system + json.dumps(request.messages, ensure_ascii=False)
        reply = json.dumps([{"name": b.name, "input": b.input} for b in content], ensure_ascii=False)
        usage = Usage(estimate_tokens(prompt), estimate_tokens(reply))
        return ModelResponse(content=content, stop_reason="tool_use", usage=usage)


def _blocks(message: dict[str, Any]) -> list[dict[str, Any]]:
    content = message.get("content")
    return [b for b in content if isinstance(b, dict)] if isinstance(content, list) else []


def _issue_id(messages: list[dict[str, Any]]) -> str:
    for message in messages:
        if message.get("role") != "user":
            continue
        for block in _blocks(message):
            match = _ISSUE_ID.search(str(block.get("text", ""))) if block.get("type") == "text" else None
            if match:
                return match.group(1)
    raise ValueError("the conversation does not say which issue to investigate")


def _tool_results(messages: list[dict[str, Any]]) -> list[ToolResult]:
    """Tool results found in the conversation, in order, rebuilt from the <tool_data> text the model was shown."""
    calls: dict[str, tuple[str, dict[str, Any]]] = {}
    results: list[ToolResult] = []
    for message in messages:
        for block in _blocks(message):
            if block.get("type") == "tool_use" and isinstance(block.get("input"), dict):
                calls[str(block.get("id"))] = (str(block.get("name")), block["input"])
            elif block.get("type") == "tool_result":
                parsed = _parse_result(calls.get(str(block.get("tool_use_id"))), str(block.get("content", "")))
                if parsed is not None:
                    results.append(parsed)
    return results


def _parse_result(call: tuple[str, dict[str, Any]] | None, content: str) -> ToolResult | None:
    match = _TOOL_DATA.fullmatch(content.strip())
    if call is None or match is None:
        return None
    try:
        payload = json.loads(match.group(2))
    except ValueError:
        return None
    if not isinstance(payload, dict):
        return None
    name, args = call
    ok, truncated = bool(payload.get("ok")), bool(payload.get("truncated"))
    data = payload.get("data")
    status = payload.get("status")
    return ToolResult(
        tool=name,
        args=args,
        ok=ok,
        status=status if isinstance(status, int) else None,
        data=data,
        as_of=payload.get("as_of") if isinstance(payload.get("as_of"), str) else None,
        records=tuple(extract_records(name, data)) if ok and not truncated else (),
        truncated=truncated,
        error=payload.get("error") if isinstance(payload.get("error"), str) else None,
    )


__all__ = ["StubModel", "estimate_tokens"]
