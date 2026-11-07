"""DeepSeek adapter: the OpenAI-compatible chat completions API over plain HTTP (standard library only).

This is the one place in `agent/src` that sends a non-GET request, and only to BASE_URL (a constant; no environment
variable or argument changes it). tests/test_static.py allows the HTTP verb here and nowhere else.

Conversation shape: the internal messages use content blocks (text, tool_use, tool_result, reasoning). This module maps
them to chat completions messages and maps the reply back. Points that are easy to get wrong:
  * thinking mode needs every earlier assistant turn's `reasoning_content` sent back unchanged when tools are present
    (otherwise HTTP 400), so replies keep it in a `ReasoningBlock` and requests restore it;
  * with thinking on, `tool_choice` may only be "auto";
  * `function.arguments` is a string the model wrote: it can be invalid JSON, not an object, or carry extra keys. A bad
    string becomes a `ToolUseBlock` with `invalid_reason` so the Agent loop reports the error to the model;
  * the API key comes from DEEPSEEK_API_KEY only and is scrubbed from everything this module raises.
"""

from __future__ import annotations

import json
import os
import time
import urllib.error
import urllib.request
from collections.abc import Callable, Mapping
from typing import Any

from ledgerlab_agent.model import (
    ModelError,
    ModelRequest,
    ModelResponse,
    ReasoningBlock,
    TextBlock,
    ToolUseBlock,
    Usage,
)

BASE_URL = "https://api.deepseek.com"
ENDPOINT = BASE_URL + "/chat/completions"
API_KEY_ENV = "DEEPSEEK_API_KEY"
PROVIDER = "deepseek"
REASONING_EFFORTS = ("none", "low", "high", "max")
MAX_RETRIES = 2
RETRY_BASE_SECONDS = 1.0
REQUEST_TIMEOUT_SECONDS = 180.0
MAX_TOKENS_RANGE = (1, 393216)
FATAL_STATUSES = frozenset({401, 402, 403, 404})  # bad key, no balance, forbidden, wrong path or model
_ERROR_TEXT_CHARS = 300
_STOP_REASONS = {"stop": "end_turn", "tool_calls": "tool_use", "length": "max_tokens"}

# transport(url, headers, body) -> (HTTP status, response body). Network failures raise OSError.
Transport = Callable[[str, Mapping[str, str], bytes], tuple[int, bytes]]


class _NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, *args: Any, **kwargs: Any) -> None:
        return None


def urllib_transport(url: str, headers: Mapping[str, str], body: bytes) -> tuple[int, bytes]:
    if not url.startswith(BASE_URL + "/"):
        raise ValueError(f"refusing to send a request outside {BASE_URL}")
    request = urllib.request.Request(url, data=body, headers=dict(headers), method="POST")
    opener = urllib.request.build_opener(_NoRedirect)
    try:
        with opener.open(request, timeout=REQUEST_TIMEOUT_SECONDS) as response:
            return response.status, response.read()
    except urllib.error.HTTPError as exc:
        with exc:
            return exc.code, exc.read()


class DeepSeekModel:
    def __init__(
        self,
        model: str,
        reasoning_effort: str = "high",
        max_tokens: int = 8192,
        transport: Transport | None = None,
        environ: Mapping[str, str] | None = None,
        sleep: Callable[[float], None] = time.sleep,
    ) -> None:
        if not model or not model.strip():
            raise ValueError("a DeepSeek model name is required, e.g. deepseek:deepseek-flash")
        if reasoning_effort not in REASONING_EFFORTS:
            raise ValueError(f"reasoning effort must be one of {', '.join(REASONING_EFFORTS)}")
        if not MAX_TOKENS_RANGE[0] <= max_tokens <= MAX_TOKENS_RANGE[1]:
            raise ValueError(f"max_tokens must be between {MAX_TOKENS_RANGE[0]} and {MAX_TOKENS_RANGE[1]}")
        key = (environ if environ is not None else os.environ).get(API_KEY_ENV, "").strip()
        if not key:
            raise ModelError(f"set the {API_KEY_ENV} environment variable to your DeepSeek API key", fatal=True)
        self.model = model
        self.reasoning_effort = reasoning_effort
        self.max_tokens = max_tokens
        self._key = key
        self._transport = transport or urllib_transport
        self._sleep = sleep

    def __repr__(self) -> str:
        return f"DeepSeekModel(model={self.model!r}, reasoning_effort={self.reasoning_effort!r})"

    # ---- request ---------------------------------------------------------------------------------------------------
    def build_body(self, request: ModelRequest) -> dict[str, Any]:
        body: dict[str, Any] = {
            "model": self.model,
            "messages": to_chat_messages(request.system, request.messages),
            "tools": [_chat_tool(t) for t in request.tools],
            "tool_choice": "auto",
            # The adapter's own limit, not the loop's: thinking tokens count against it, so it has to be roomy.
            "max_tokens": self.max_tokens,
            "stream": False,
        }
        if self.reasoning_effort == "none":
            body["thinking"] = {"type": "disabled"}
        else:
            body["thinking"] = {"type": "enabled"}
            body["reasoning_effort"] = self.reasoning_effort
        return body

    def complete(self, request: ModelRequest) -> ModelResponse:
        payload = json.dumps(self.build_body(request), ensure_ascii=False).encode("utf-8")
        headers = {"Authorization": f"Bearer {self._key}", "Content-Type": "application/json"}
        status, raw = self._send(headers, payload)
        try:
            document = json.loads(raw.decode("utf-8"))
            return parse_response(document)
        except (ValueError, KeyError, TypeError, IndexError, AttributeError) as exc:
            raise ModelError(self._scrub(f"malformed DeepSeek reply (HTTP {status}): {type(exc).__name__}")) from None

    def _send(self, headers: Mapping[str, str], payload: bytes) -> tuple[int, bytes]:
        last = "no attempt was made"
        for attempt in range(MAX_RETRIES + 1):
            try:
                status, raw = self._transport(ENDPOINT, headers, payload)
            except OSError as exc:
                last = f"network error: {type(exc).__name__}"
            else:
                if 200 <= status < 300:
                    return status, raw
                last = f"HTTP {status}: {self._error_text(raw)}"
                if status != 429 and status < 500:
                    raise ModelError(self._scrub(f"DeepSeek rejected the request ({last})"), fatal=status in FATAL_STATUSES)
            if attempt < MAX_RETRIES:
                self._sleep(RETRY_BASE_SECONDS * 2**attempt)
        raise ModelError(self._scrub(f"DeepSeek did not answer after {MAX_RETRIES + 1} attempts ({last})"))

    def _error_text(self, raw: bytes) -> str:
        text = raw.decode("utf-8", errors="replace")
        try:
            message = json.loads(text)["error"]["message"]
            text = message if isinstance(message, str) else text
        except (ValueError, KeyError, TypeError):
            pass
        return " ".join(text.split())[:_ERROR_TEXT_CHARS]

    def _scrub(self, text: str) -> str:
        return text.replace(self._key, "[redacted]")


# ---- message mapping -----------------------------------------------------------------------------------------------
def _chat_tool(definition: Mapping[str, Any]) -> dict[str, Any]:
    return {
        "type": "function",
        "function": {
            "name": definition["name"],
            "description": definition["description"],
            "parameters": definition["input_schema"],
        },
    }


def to_chat_messages(system: str, messages: list[dict[str, Any]]) -> list[dict[str, Any]]:
    out: list[dict[str, Any]] = [{"role": "system", "content": system}]
    for message in messages:
        blocks = [b for b in message.get("content") or [] if isinstance(b, dict)]
        if message.get("role") == "assistant":
            out.append(_assistant_message(blocks))
            continue
        # Tool results must directly follow the assistant turn that made the calls, so they go first.
        for block in blocks:
            if block.get("type") == "tool_result":
                out.append({"role": "tool", "tool_call_id": block["tool_use_id"], "content": str(block.get("content", ""))})
        text = "\n\n".join(str(b["text"]) for b in blocks if b.get("type") == "text")
        if text:
            out.append({"role": "user", "content": text})
    return out


def _assistant_message(blocks: list[dict[str, Any]]) -> dict[str, Any]:
    message: dict[str, Any] = {
        "role": "assistant",
        "content": "\n\n".join(str(b["text"]) for b in blocks if b.get("type") == "text"),
    }
    reasoning = [str(b["text"]) for b in blocks if b.get("type") == "reasoning" and b.get("provider") == PROVIDER]
    if reasoning:
        message["reasoning_content"] = "".join(reasoning)
    calls = [
        {
            "id": b["id"],
            "type": "function",
            "function": {"name": b["name"], "arguments": json.dumps(b.get("input") or {}, ensure_ascii=False)},
        }
        for b in blocks
        if b.get("type") == "tool_use"
    ]
    if calls:
        message["tool_calls"] = calls
    return message


def parse_response(document: Any) -> ModelResponse:
    choice = document["choices"][0]
    message = choice["message"]
    content: list[Any] = []
    reasoning = message.get("reasoning_content")
    if isinstance(reasoning, str):
        content.append(ReasoningBlock(reasoning, PROVIDER))
    text = message.get("content")
    if isinstance(text, str) and text:
        content.append(TextBlock(text))
    for index, call in enumerate(message.get("tool_calls") or []):
        content.append(_tool_use(call, index))
    finish = choice.get("finish_reason")
    return ModelResponse(
        content=tuple(content),
        stop_reason=_STOP_REASONS.get(finish, str(finish)),
        usage=parse_usage(document.get("usage")),
    )


def _tool_use(call: Mapping[str, Any], index: int) -> ToolUseBlock:
    function = call["function"]
    call_id = str(call.get("id") or f"call_{index}")
    name = str(function["name"])
    raw = function.get("arguments")
    try:
        arguments = json.loads(raw) if isinstance(raw, str) and raw.strip() else {}
    except ValueError:
        return ToolUseBlock(call_id, name, {}, "arguments are not valid JSON")
    if not isinstance(arguments, dict):
        return ToolUseBlock(call_id, name, {}, "arguments must be a JSON object")
    return ToolUseBlock(call_id, name, arguments)


def _count(value: Any) -> int:
    return value if isinstance(value, int) and not isinstance(value, bool) and value >= 0 else 0


def parse_usage(usage: Any) -> Usage:
    if not isinstance(usage, dict):
        return Usage()
    hit, miss = _count(usage.get("prompt_cache_hit_tokens")), _count(usage.get("prompt_cache_miss_tokens"))
    prompt = hit + miss if hit + miss > 0 else _count(usage.get("prompt_tokens"))
    details = usage.get("completion_tokens_details")
    reasoning = _count(details.get("reasoning_tokens")) if isinstance(details, dict) else 0
    return Usage(prompt, _count(usage.get("completion_tokens")), hit, miss, reasoning)
