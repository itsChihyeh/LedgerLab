"""Agent loop: model proposes tool calls, the tool layer runs them, a validated conclusion ends it."""

from __future__ import annotations

import json
import re
import time
from collections.abc import Callable
from dataclasses import dataclass
from pathlib import Path
from typing import Any

from ledgerlab_agent import conclusion as conclusion_mod
from ledgerlab_agent.conclusion import Conclusion, abstention
from ledgerlab_agent.model import (
    SUBMIT_TOOL,
    ContentBlock,
    ModelClient,
    ModelError,
    ModelRequest,
    ModelResponse,
    ReasoningBlock,
    TextBlock,
    ToolUseBlock,
    Usage,
    tool_definitions,
)
from ledgerlab_agent.tools import ToolRegistry, ToolResult

SYSTEM_PROMPT_PATH = Path(__file__).resolve().parent / "prompts" / "system.md"
ISSUE_ID_PATTERN = r"[A-Za-z0-9_\-]{1,64}"
# The reply was cut off or refused; asking again would not help and is never retried automatically.
NOT_CONTINUABLE_STOPS = frozenset({"max_tokens", "content_filter", "insufficient_system_resource", "aborted"})


@dataclass(frozen=True)
class Budget:
    max_tool_calls: int = 12
    max_turns: int = 8
    max_tokens: int = 2048


@dataclass(frozen=True)
class Investigation:
    conclusion: Conclusion
    status: str  # ok | invalid_output | budget_exhausted
    tool_calls: tuple[ToolResult, ...]
    turns: int
    usage: Usage
    elapsed_ms: int
    validation_errors: tuple[str, ...] = ()


def escape_tool_data(text: str) -> str:
    """Keep untrusted data from closing the <tool_data> wrapper."""
    return re.sub(r"</(\s*)tool_data", r"<\\/\1tool_data", text, flags=re.IGNORECASE)


def render_tool_result(result: ToolResult) -> str:
    payload: dict[str, Any] = {
        "ok": result.ok,
        "status": result.status,
        "as_of": result.as_of,
        "truncated": result.truncated,
        "error": result.error,
        "data": result.data,
    }
    body = escape_tool_data(json.dumps(payload, ensure_ascii=False, sort_keys=True))
    return f'<tool_data tool="{result.tool}">{body}</tool_data>'


def load_system_prompt() -> str:
    return SYSTEM_PROMPT_PATH.read_text(encoding="utf-8")


def investigate(
    issue_id: str,
    model: ModelClient,
    tools: ToolRegistry,
    budget: Budget | None = None,
    clock: Callable[[], float] = time.monotonic,
    system_prompt: str | None = None,
) -> Investigation:
    if re.fullmatch(ISSUE_ID_PATTERN, issue_id) is None:
        raise ValueError("issue_id must be 1-64 characters of letters, digits, '_' or '-'")
    budget = budget or Budget()
    system = system_prompt if system_prompt is not None else load_system_prompt()
    definitions = tool_definitions(tools.specs)
    started = clock()

    messages: list[dict[str, Any]] = [
        {
            "role": "user",
            "content": [
                {
                    "type": "text",
                    "text": (
                        f"Investigate reconciliation issue {issue_id}. Use the read-only tools to gather "
                        f"evidence, then call submit_conclusion with issue_id {issue_id}."
                    ),
                }
            ],
        }
    ]
    calls: list[ToolResult] = []
    usage = Usage()
    turns = 0
    invalid_outputs = 0
    last_errors: list[str] = []

    def finish(status: str, concl: Conclusion, errors: list[str] | None = None) -> Investigation:
        return Investigation(
            concl,
            status,
            tuple(calls),
            turns,
            usage,
            int((clock() - started) * 1000),
            tuple(errors or ()),
        )

    def exhausted(reason: str) -> Investigation:
        return finish("budget_exhausted", abstention(issue_id, f"Stopped without a conclusion: {reason}."))

    def invalid() -> Investigation:
        return finish(
            "invalid_output",
            abstention(issue_id, "Stopped without a conclusion: the model did not produce a valid conclusion."),
            last_errors,
        )

    while True:
        if turns >= budget.max_turns:
            return exhausted(f"model turn limit of {budget.max_turns} reached")
        try:
            response: ModelResponse = model.complete(
                ModelRequest(system, list(messages), definitions, budget.max_tokens)
            )
        except ModelError as exc:
            if exc.fatal:
                raise
            turns += 1
            last_errors = [f"model request failed: {exc}"]
            return invalid()
        turns += 1
        usage = usage + response.usage
        messages.append({"role": "assistant", "content": [_block_to_message(b) for b in response.content]})

        if response.stop_reason in NOT_CONTINUABLE_STOPS:
            last_errors = [f"model stopped with {response.stop_reason}; the reply is not usable"]
            return invalid()

        tool_uses = [b for b in response.content if isinstance(b, ToolUseBlock)]
        if not tool_uses:
            invalid_outputs += 1
            last_errors = ["reply contained neither a tool call nor submit_conclusion"]
            if invalid_outputs > 1:
                return invalid()
            messages.append(
                {
                    "role": "user",
                    "content": [{"type": "text", "text": f"Call a tool or {SUBMIT_TOOL}; plain text is not accepted."}],
                }
            )
            continue

        results: list[dict[str, Any]] = []
        for use in tool_uses:
            if use.invalid_reason is not None and use.name != SUBMIT_TOOL:
                results.append(_tool_result(use.id, f"Invalid arguments: {use.invalid_reason}", True))
                continue
            if use.name == SUBMIT_TOOL:
                errors = [f"invalid arguments: {use.invalid_reason}"] if use.invalid_reason else []
                errors = errors or conclusion_mod.validate(use.input, issue_id)
                if not errors:
                    return finish("ok", conclusion_mod.from_dict(use.input))
                invalid_outputs += 1
                last_errors = errors
                if invalid_outputs > 1:
                    return invalid()
                results.append(_tool_result(use.id, "Invalid conclusion: " + "; ".join(errors), True))
                continue
            if len(calls) >= budget.max_tool_calls:
                return exhausted(f"tool call limit of {budget.max_tool_calls} reached")
            result = tools.call(use.name, use.input)
            calls.append(result)
            results.append(_tool_result(use.id, render_tool_result(result), not result.ok))
        messages.append({"role": "user", "content": results})


def _block_to_message(block: ContentBlock) -> dict[str, Any]:
    if isinstance(block, TextBlock):
        return {"type": "text", "text": block.text}
    if isinstance(block, ReasoningBlock):
        return {"type": "reasoning", "text": block.text, "provider": block.provider}
    return {"type": "tool_use", "id": block.id, "name": block.name, "input": block.input}


def _tool_result(tool_use_id: str, content: str, is_error: bool) -> dict[str, Any]:
    return {"type": "tool_result", "tool_use_id": tool_use_id, "content": content, "is_error": is_error}
