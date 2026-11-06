"""Replaceable model interface (no vendor SDK types) and the scripted test model."""

from __future__ import annotations

import json
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any, Protocol

from ledgerlab_agent.enums import (
    CONFIDENCES,
    MAX_CITATIONS,
    MAX_EXPLANATION_CHARS,
    RECOMMENDED_ACTIONS,
    RECORD_KINDS,
    ROOT_CAUSES,
    SOP_CLAUSE_PATTERN,
)
from ledgerlab_agent.tools import ParamSpec, ToolSpec

SUBMIT_TOOL = "submit_conclusion"


@dataclass(frozen=True)
class TextBlock:
    text: str


@dataclass(frozen=True)
class ToolUseBlock:
    id: str
    name: str
    input: dict[str, Any]


ContentBlock = TextBlock | ToolUseBlock


@dataclass(frozen=True)
class Usage:
    input_tokens: int = 0
    output_tokens: int = 0

    def __add__(self, other: Usage) -> Usage:
        return Usage(self.input_tokens + other.input_tokens, self.output_tokens + other.output_tokens)


@dataclass(frozen=True)
class ModelRequest:
    system: str
    messages: list[dict[str, Any]]
    tools: list[dict[str, Any]]
    max_tokens: int


@dataclass(frozen=True)
class ModelResponse:
    content: tuple[ContentBlock, ...]
    stop_reason: str = "end_turn"
    usage: Usage = field(default_factory=Usage)


class ModelClient(Protocol):
    def complete(self, request: ModelRequest) -> ModelResponse: ...


class ScriptExhausted(RuntimeError):
    """The scripted model was asked for more replies than the script holds."""


class ScriptedModel:
    """Replays fixed replies in order; used by unit tests and offline demos."""

    def __init__(self, steps: list[ModelResponse]) -> None:
        self._steps = list(steps)
        self._next = 0
        self.requests: list[ModelRequest] = []

    @classmethod
    def from_json_file(cls, path: str | Path) -> ScriptedModel:
        raw = json.loads(Path(path).read_text(encoding="utf-8"))
        if not isinstance(raw, list):
            raise ValueError("a model script must be a JSON list of replies")
        return cls([_response_from_json(step) for step in raw])

    def complete(self, request: ModelRequest) -> ModelResponse:
        self.requests.append(request)
        if self._next >= len(self._steps):
            raise ScriptExhausted(f"script has only {len(self._steps)} replies")
        step = self._steps[self._next]
        self._next += 1
        return step


def _response_from_json(step: object) -> ModelResponse:
    if not isinstance(step, dict) or not isinstance(step.get("content"), list):
        raise ValueError("each scripted reply needs a content list")
    blocks: list[ContentBlock] = []
    for block in step["content"]:
        if block.get("type") == "text":
            blocks.append(TextBlock(str(block["text"])))
        elif block.get("type") == "tool_use":
            blocks.append(ToolUseBlock(str(block["id"]), str(block["name"]), dict(block["input"])))
        else:
            raise ValueError(f"unknown content block type {block.get('type')!r}")
    usage = step.get("usage") or {}
    return ModelResponse(
        content=tuple(blocks),
        stop_reason=str(step.get("stop_reason", "end_turn")),
        usage=Usage(int(usage.get("input_tokens", 0)), int(usage.get("output_tokens", 0))),
    )


def _param_schema(param: ParamSpec) -> dict[str, Any]:
    if param.type == "integer":
        return {"type": "integer"}
    return {"type": "string", "pattern": param.pattern}


def tool_definition(spec: ToolSpec) -> dict[str, Any]:
    properties = {p.name: _param_schema(p) for p in spec.params}
    return {
        "name": spec.name,
        "description": spec.description,
        "input_schema": {
            "type": "object",
            "properties": properties,
            "required": [p.name for p in spec.params if p.required],
            "additionalProperties": False,
        },
    }


def submit_conclusion_definition() -> dict[str, Any]:
    return {
        "name": SUBMIT_TOOL,
        "description": (
            "Finish the investigation with a structured conclusion. Call it exactly once. "
            "Every fact in the explanation needs a citation to a record you actually read."
        ),
        "input_schema": {
            "type": "object",
            "properties": {
                "issue_id": {"type": "string"},
                "root_cause": {"type": "string", "enum": list(ROOT_CAUSES)},
                "confidence": {"type": "string", "enum": list(CONFIDENCES)},
                "abstain": {"type": "boolean"},
                "explanation": {"type": "string", "maxLength": MAX_EXPLANATION_CHARS},
                "citations": {
                    "type": "array",
                    "maxItems": MAX_CITATIONS,
                    "items": {
                        "type": "object",
                        "properties": {
                            "kind": {"type": "string", "enum": list(RECORD_KINDS)},
                            "id": {"type": "string", "maxLength": 128},
                        },
                        "required": ["kind", "id"],
                        "additionalProperties": False,
                    },
                },
                "sop_clauses": {
                    "type": "array",
                    "items": {"type": "string", "pattern": SOP_CLAUSE_PATTERN},
                },
                "recommended_action": {"type": "string", "enum": list(RECOMMENDED_ACTIONS)},
            },
            "required": [
                "issue_id",
                "root_cause",
                "confidence",
                "abstain",
                "explanation",
                "citations",
                "sop_clauses",
                "recommended_action",
            ],
            "additionalProperties": False,
        },
    }


def tool_definitions(specs: list[ToolSpec]) -> list[dict[str, Any]]:
    """JSON-Schema tool list for a request: every investigation tool plus submit_conclusion."""
    return [*(tool_definition(s) for s in specs), submit_conclusion_definition()]
