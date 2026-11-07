"""Agent loop: model proposes tool calls, the tool layer runs them, a validated conclusion ends it."""

from __future__ import annotations

import json
import re
import time
from collections.abc import Callable
from dataclasses import dataclass, replace
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
LOW_BUDGET_TOOL_CALLS = 3  # from this many remaining tool calls on, the hint tells the model to wrap up
NOT_CONTINUABLE_STOPS =frozenset({"max_tokens", "content_filter", "insufficient_system_resource", "aborted"})


@dataclass(frozen=True)
class Budget:
    max_tool_calls: int = 12
    max_turns: int = 8
    max_tokens: int = 2048


@dataclass(frozen=True)
class ToolUseRecord:
    """One tool call the model asked for in a turn. `call_index` points into `Investigation.tool_calls` when the call
    was run; `note` says why it was not (limit reached, invalid arguments) or that it was the conclusion."""

    name: str
    input: dict[str, Any]
    call_index: int | None = None
    note: str | None = None


@dataclass(frozen=True)
class TurnRecord:
    """What one model turn showed: visible text, the size (never the content) of the reasoning, how it stopped."""

    turn: int
    final: bool
    text: str
    reasoning_chars: int
    stop_reason: str
    usage: Usage
    tool_uses: tuple[ToolUseRecord, ...] = ()


@dataclass(frozen=True)
class Investigation:
    conclusion: Conclusion
    status: str  # ok | invalid_output | budget_exhausted
    tool_calls: tuple[ToolResult, ...]
    turns: int
    usage: Usage
    elapsed_ms: int
    validation_errors: tuple[str, ...] = ()
    final_turn_submit: bool = False  # the conclusion came from the extra turn given after the budget ran out
    turn_log: tuple[TurnRecord, ...] = ()  # empty for the rule baseline, which has no model turns


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


def budget_spent(budget: Budget, tool_calls: int, turns: int) -> bool:
    return tool_calls >= budget.max_tool_calls or turns >= budget.max_turns


def budget_hint(budget: Budget, tool_calls: int, turns: int) -> str:
    """The Agent's own line after a batch of tool results (outside <tool_data>): how much budget is used and the nudge
    to finish. Once the budget is spent, the next request is the final turn, which only offers submit_conclusion."""
    used = f"{tool_calls}/{budget.max_tool_calls} tool calls and {turns}/{budget.max_turns} model turns used"
    if budget_spent(budget, tool_calls, turns):
        return (
            f"Budget: {used}. The budget is used up. Submit your conclusion now with {SUBMIT_TOOL} from the evidence "
            "you have; if the evidence is not enough, abstain."
        )
    left = budget.max_tool_calls - tool_calls
    if left <= LOW_BUDGET_TOOL_CALLS:
        plural = "" if left == 1 else "s"
        return f"Budget: {used}. Only {left} tool call{plural} left; on your next turn call {SUBMIT_TOOL}."
    return f"Budget: {used}."


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
    turn_log: list[TurnRecord] = []
    usage = Usage()
    turns = 0
    invalid_outputs = 0
    last_errors: list[str] = []
    submit_only = [d for d in definitions if d["name"] == SUBMIT_TOOL]

    def finish(
        status: str, concl: Conclusion, errors: list[str] | None = None, final_turn_submit: bool = False
    ) -> Investigation:
        return Investigation(
            concl,
            status,
            tuple(calls),
            turns,
            usage,
            int((clock() - started) * 1000),
            tuple(errors or ()),
            final_turn_submit,
            tuple(turn_log),
        )

    def exhausted(reason: str) -> Investigation:
        return finish("budget_exhausted", abstention(issue_id, f"Stopped without a conclusion: {reason}."))

    def invalid() -> Investigation:
        return finish(
            "invalid_output",
            abstention(issue_id, "Stopped without a conclusion: the model did not produce a valid conclusion."),
            last_errors,
        )

    def push_user(blocks: list[dict[str, Any]]) -> None:
        hint = budget_hint(budget, len(calls), turns)
        messages.append({"role": "user", "content": [*blocks, {"type": "text", "text": hint}]})

    def limit_reason() -> str:
        if len(calls) >= budget.max_tool_calls:
            return f"tool call limit of {budget.max_tool_calls} reached"
        return f"model turn limit of {budget.max_turns} reached"

    def check_conclusion(use: ToolUseBlock) -> list[str]:
        if use.invalid_reason:
            return [f"invalid arguments: {use.invalid_reason}"]
        return conclusion_mod.validate(use.input, issue_id)

    while True:
        # Budget spent: the model gets one more turn, offered submit_conclusion only. Nothing after it is allowed.
        final = budget_spent(budget, len(calls), turns)
        try:
            response: ModelResponse = model.complete(
                ModelRequest(system, list(messages), submit_only if final else definitions, budget.max_tokens)
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
        turn_log.append(
            TurnRecord(
                turns,
                final,
                "\n".join(b.text for b in response.content if isinstance(b, TextBlock)),
                sum(len(b.text) for b in response.content if isinstance(b, ReasoningBlock)),
                response.stop_reason,
                response.usage,
            )
        )
        uses: list[ToolUseRecord] = []

        def seal_turn() -> None:
            turn_log[-1] = replace(turn_log[-1], tool_uses=tuple(uses))

        if response.stop_reason in NOT_CONTINUABLE_STOPS:
            last_errors = [f"model stopped with {response.stop_reason}; the reply is not usable"]
            return invalid()

        tool_uses = [b for b in response.content if isinstance(b, ToolUseBlock)]
        if final:
            for use in tool_uses:
                if use.name != SUBMIT_TOOL:
                    uses.append(ToolUseRecord(use.name, use.input, None, "not offered on the final turn"))
                    continue
                errors = check_conclusion(use)
                uses.append(ToolUseRecord(use.name, use.input, None, "; ".join(errors) or "conclusion"))
                if not errors:
                    seal_turn()
                    return finish("ok", conclusion_mod.from_dict(use.input), final_turn_submit=True)
            seal_turn()
            return exhausted(f"{limit_reason()} and the final turn gave no valid conclusion")

        if not tool_uses:
            invalid_outputs += 1
            last_errors = ["reply contained neither a tool call nor submit_conclusion"]
            if invalid_outputs > 1:
                return invalid()
            push_user([{"type": "text", "text": f"Call a tool or {SUBMIT_TOOL}; plain text is not accepted."}])
            continue

        results: list[dict[str, Any]] = []
        for use in tool_uses:
            if use.invalid_reason is not None and use.name != SUBMIT_TOOL:
                uses.append(ToolUseRecord(use.name, use.input, None, f"invalid arguments: {use.invalid_reason}"))
                results.append(_tool_result(use.id, f"Invalid arguments: {use.invalid_reason}", True))
                continue
            if use.name == SUBMIT_TOOL:
                errors = check_conclusion(use)
                uses.append(ToolUseRecord(use.name, use.input, None, "; ".join(errors) or "conclusion"))
                if not errors:
                    seal_turn()
                    return finish("ok", conclusion_mod.from_dict(use.input))
                invalid_outputs += 1
                last_errors = errors
                if invalid_outputs > 1:
                    seal_turn()
                    return invalid()
                results.append(_tool_result(use.id, "Invalid conclusion: " + "; ".join(errors), True))
                continue
            if len(calls) >= budget.max_tool_calls:
                uses.append(ToolUseRecord(use.name, use.input, None, "tool call limit reached, not run"))
                results.append(_tool_result(use.id, "Tool call limit reached; this call was not run.", True))
                continue
            result = tools.call(use.name, use.input)
            uses.append(ToolUseRecord(use.name, use.input, len(calls)))
            calls.append(result)
            results.append(_tool_result(use.id, render_tool_result(result), not result.ok))
        seal_turn()
        push_user(results)


def _block_to_message(block: ContentBlock) -> dict[str, Any]:
    if isinstance(block, TextBlock):
        return {"type": "text", "text": block.text}
    if isinstance(block, ReasoningBlock):
        return {"type": "reasoning", "text": block.text, "provider": block.provider}
    return {"type": "tool_use", "id": block.id, "name": block.name, "input": block.input}


def _tool_result(tool_use_id: str, content: str, is_error: bool) -> dict[str, Any]:
    return {"type": "tool_result", "tool_use_id": tool_use_id, "content": content, "is_error": is_error}
