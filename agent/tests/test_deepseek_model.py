"""Contract tests for the DeepSeek adapter. Nothing here touches the network: a fake transport hands back fixtures."""

from __future__ import annotations

import json
import re
import unittest
from typing import Any

from ledgerlab_agent.agent import investigate
from ledgerlab_agent.deepseek_model import API_KEY_ENV, BASE_URL, ENDPOINT, DeepSeekModel
from ledgerlab_agent.model import (
    ModelError,
    ModelRequest,
    ReasoningBlock,
    TextBlock,
    ToolUseBlock,
    Usage,
    tool_definitions,
)
from ledgerlab_agent.tools import ToolRegistry, load_specs

from tests.test_agent import ISSUE, conclusion, transport

PLACEHOLDER_SECRET = "placeholder-credential-for-unit-tests"
ENV = {API_KEY_ENV: PLACEHOLDER_SECRET}


def completion(
    content: str | None = None,
    tool_calls: list[tuple[str, str, Any]] | None = None,
    finish: str = "stop",
    reasoning: str | None = None,
    usage: dict[str, Any] | None = None,
) -> dict[str, Any]:
    """A chat completion document. tool_calls are (id, name, arguments); a non-string argument is JSON-encoded."""
    message: dict[str, Any] = {"role": "assistant", "content": content}
    if reasoning is not None:
        message["reasoning_content"] = reasoning
    if tool_calls:
        message["tool_calls"] = [
            {"id": i, "type": "function", "function": {"name": n, "arguments": a if isinstance(a, str) else json.dumps(a)}}
            for i, n, a in tool_calls
        ]
    document: dict[str, Any] = {"choices": [{"index": 0, "message": message, "finish_reason": finish}]}
    if usage is not None:
        document["usage"] = usage
    return document


class FakeTransport:
    """Answers with queued (status, body) pairs, or raises a queued exception; records each request."""

    def __init__(self, *replies: tuple[int, Any] | Exception) -> None:
        self._replies = list(replies)
        self.calls: list[tuple[str, dict[str, str], dict[str, Any]]] = []

    def __call__(self, url: str, headers: Any, body: bytes) -> tuple[int, bytes]:
        self.calls.append((url, dict(headers), json.loads(body.decode("utf-8"))))
        reply = self._replies.pop(0)
        if isinstance(reply, Exception):
            raise reply
        status, document = reply
        return status, document if isinstance(document, bytes) else json.dumps(document).encode("utf-8")


def ok(document: dict[str, Any]) -> tuple[int, Any]:
    return 200, document


def request(messages: list[dict[str, Any]] | None = None, tools: list[dict[str, Any]] | None = None) -> ModelRequest:
    first = {"role": "user", "content": [{"type": "text", "text": f"Investigate reconciliation issue {ISSUE}."}]}
    return ModelRequest("system prompt", messages or [first], tools if tools is not None else tool_definitions([]), 2048)


def model(*replies: tuple[int, Any] | Exception, **kwargs: Any) -> tuple[DeepSeekModel, FakeTransport, list[float]]:
    fake, naps = FakeTransport(*replies), []
    kwargs.setdefault("reasoning_effort", "high")
    kwargs.setdefault("max_tokens", 4096)
    return DeepSeekModel("deepseek-flash", transport=fake, environ=ENV, sleep=naps.append, **kwargs), fake, naps


class ResponseMappingTest(unittest.TestCase):
    def test_ac_10_10_plain_text_reply_maps_to_a_text_block_and_end_turn(self) -> None:
        client, _, _ = model(ok(completion("hello")))
        response = client.complete(request())
        self.assertEqual(response.content, (TextBlock("hello"),))
        self.assertEqual(response.stop_reason, "end_turn")

    def test_ac_10_10_a_single_tool_call_maps_to_a_tool_use_block(self) -> None:
        client, _, _ = model(ok(completion(tool_calls=[("call_1", "get_issue", {"issue_id": ISSUE})], finish="tool_calls")))
        response = client.complete(request())
        self.assertEqual(response.content, (ToolUseBlock("call_1", "get_issue", {"issue_id": ISSUE}),))
        self.assertEqual(response.stop_reason, "tool_use")

    def test_ac_10_10_several_tool_calls_keep_their_order_and_ids(self) -> None:
        calls = [("a", "get_issue", {"issue_id": ISSUE}), ("b", "get_batch", {"batch_id": "po_abc123"})]
        client, _, _ = model(ok(completion(tool_calls=calls, finish="tool_calls")))
        blocks = client.complete(request()).content
        self.assertEqual([(b.id, b.name) for b in blocks if isinstance(b, ToolUseBlock)], [("a", "get_issue"), ("b", "get_batch")])

    def test_ac_10_10_arguments_that_are_not_json_become_an_invalid_tool_use(self) -> None:
        client, _, _ = model(ok(completion(tool_calls=[("a", "get_issue", '{"issue_id": ')], finish="tool_calls")))
        (block,) = client.complete(request()).content
        self.assertIsInstance(block, ToolUseBlock)
        self.assertEqual((block.id, block.name, block.input), ("a", "get_issue", {}))
        self.assertIn("not valid JSON", block.invalid_reason or "")

    def test_ac_10_10_arguments_that_are_not_an_object_are_invalid_too(self) -> None:
        client, _, _ = model(ok(completion(tool_calls=[("a", "get_issue", "[1, 2]")], finish="tool_calls")))
        (block,) = client.complete(request()).content
        self.assertIn("JSON object", block.invalid_reason or "")

    def test_ac_10_10_extra_arguments_pass_through_for_the_tool_layer_to_reject(self) -> None:
        args = {"issue_id": ISSUE, "force": True}
        client, _, _ = model(ok(completion(tool_calls=[("a", "get_issue", args)], finish="tool_calls")))
        (block,) = client.complete(request()).content
        self.assertEqual(block.input, args)
        self.assertIsNone(block.invalid_reason)
        result = ToolRegistry(transport()).call(block.name, block.input)
        self.assertFalse(result.ok)
        self.assertEqual(result.error, "invalid_arguments")

    def test_ac_10_10_finish_reasons_map_to_stop_reasons(self) -> None:
        expected = {
            "stop": "end_turn",
            "tool_calls": "tool_use",
            "length": "max_tokens",
            "content_filter": "content_filter",
            "insufficient_system_resource": "insufficient_system_resource",
            "aborted": "aborted",
        }
        for finish, stop_reason in expected.items():
            with self.subTest(finish=finish):
                client, _, _ = model(ok(completion("x", finish=finish)))
                self.assertEqual(client.complete(request()).stop_reason, stop_reason)

    def test_ac_10_10_usage_maps_cache_and_reasoning_tokens(self) -> None:
        usage = {
            "prompt_tokens": 1000,
            "completion_tokens": 300,
            "prompt_cache_hit_tokens": 640,
            "prompt_cache_miss_tokens": 360,
            "completion_tokens_details": {"reasoning_tokens": 210},
        }
        client, _, _ = model(ok(completion("x", usage=usage)))
        self.assertEqual(client.complete(request()).usage, Usage(1000, 300, 640, 360, 210))

    def test_ac_10_10_usage_without_the_cache_breakdown_counts_the_prompt_as_input(self) -> None:
        client, _, _ = model(ok(completion("x", usage={"prompt_tokens": 50, "completion_tokens": 7})))
        self.assertEqual(client.complete(request()).usage, Usage(50, 7, 0, 0, 0))

    def test_ac_10_10_reasoning_content_becomes_a_reasoning_block_before_the_text(self) -> None:
        client, _, _ = model(ok(completion("answer", reasoning="thinking...")))
        self.assertEqual(client.complete(request()).content, (ReasoningBlock("thinking...", "deepseek"), TextBlock("answer")))

    def test_ac_10_10_a_reply_without_choices_is_a_model_error_not_a_crash(self) -> None:
        for body in (b"not json", json.dumps({"choices": []}).encode(), json.dumps({"error": "x"}).encode()):
            with self.subTest(body=body):
                client, _, _ = model((200, body))
                with self.assertRaises(ModelError) as caught:
                    client.complete(request())
                self.assertFalse(caught.exception.fatal)


class RequestBuildingTest(unittest.TestCase):
    def test_ac_10_10_request_goes_to_the_fixed_endpoint_with_the_bearer_header(self) -> None:
        client, fake, _ = model(ok(completion("x")))
        client.complete(request())
        url, headers, _ = fake.calls[0]
        self.assertEqual(url, ENDPOINT)
        self.assertEqual(ENDPOINT, "https://api.deepseek.com/chat/completions")
        self.assertEqual(headers["Authorization"], f"Bearer {PLACEHOLDER_SECRET}")
        self.assertEqual(headers["Content-Type"], "application/json")

    def test_ac_10_10_thinking_mode_requests_use_tool_choice_auto(self) -> None:
        client, fake, _ = model(ok(completion("x")))
        client.complete(request())
        body = fake.calls[0][2]
        self.assertEqual(body["tool_choice"], "auto")
        self.assertEqual(body["thinking"], {"type": "enabled"})
        self.assertEqual(body["reasoning_effort"], "high")
        self.assertEqual(body["model"], "deepseek-flash")
        self.assertEqual(body["max_tokens"], 4096)
        self.assertNotIn("temperature", body)

    def test_ac_10_10_effort_none_turns_thinking_off_and_sends_no_effort(self) -> None:
        client, fake, _ = model(ok(completion("x")), reasoning_effort="none")
        client.complete(request())
        body = fake.calls[0][2]
        self.assertEqual(body["thinking"], {"type": "disabled"})
        self.assertNotIn("reasoning_effort", body)
        self.assertEqual(body["tool_choice"], "auto")

    def test_ac_10_10_tool_schemas_use_the_function_format_with_legal_names(self) -> None:
        client, fake, _ = model(ok(completion("x")))
        client.complete(request(tools=tool_definitions(list(load_specs().values()))))
        tools = fake.calls[0][2]["tools"]
        self.assertTrue(tools)
        for tool in tools:
            self.assertEqual(tool["type"], "function")
            function = tool["function"]
            self.assertRegex(function["name"], r"^[A-Za-z0-9_-]{1,128}$")
            self.assertEqual(function["parameters"]["type"], "object")
            self.assertNotIn("strict", function)
        self.assertIn("submit_conclusion", [t["function"]["name"] for t in tools])

    def test_ac_10_10_reasoning_content_of_earlier_turns_is_sent_back_unchanged(self) -> None:
        first = completion(
            tool_calls=[("call_1", "get_issue", {"issue_id": ISSUE})], finish="tool_calls", reasoning="step one: look at the issue\n"
        )
        client, fake, _ = model(ok(first), ok(completion("done", reasoning="step two")))
        messages = request().messages
        reply = client.complete(request(messages))
        messages += [
            {"role": "assistant", "content": [_as_message(b) for b in reply.content]},
            {"role": "user", "content": [{"type": "tool_result", "tool_use_id": "call_1", "content": "<tool_data>x</tool_data>", "is_error": False}]},
        ]
        client.complete(request(messages))
        sent = fake.calls[1][2]["messages"]
        assistant = [m for m in sent if m["role"] == "assistant"]
        self.assertEqual([m["reasoning_content"] for m in assistant], ["step one: look at the issue\n"])
        self.assertEqual(assistant[0]["tool_calls"][0]["function"], {"name": "get_issue", "arguments": json.dumps({"issue_id": ISSUE})})

    def test_ac_10_10_tool_results_become_tool_role_messages_after_the_assistant_turn(self) -> None:
        messages = request().messages + [
            {"role": "assistant", "content": [{"type": "tool_use", "id": "c1", "name": "get_issue", "input": {"issue_id": ISSUE}}]},
            {
                "role": "user",
                "content": [
                    {"type": "tool_result", "tool_use_id": "c1", "content": "R1", "is_error": False},
                    {"type": "text", "text": "Call a tool."},
                ],
            },
        ]
        client, fake, _ = model(ok(completion("x")))
        client.complete(request(messages))
        sent = fake.calls[0][2]["messages"]
        self.assertEqual([m["role"] for m in sent], ["system", "user", "assistant", "tool", "user"])
        self.assertEqual(sent[0]["content"], "system prompt")
        self.assertEqual(sent[3], {"role": "tool", "tool_call_id": "c1", "content": "R1"})

    def test_ac_10_10_reasoning_from_another_provider_is_not_sent(self) -> None:
        messages = request().messages + [
            {"role": "assistant", "content": [{"type": "reasoning", "text": "other", "provider": "someone-else"}, {"type": "text", "text": "hi"}]}
        ]
        client, fake, _ = model(ok(completion("x")))
        client.complete(request(messages))
        self.assertNotIn("reasoning_content", fake.calls[0][2]["messages"][-1])

    def test_ac_10_10_constructor_rejects_bad_settings(self) -> None:
        with self.assertRaises(ValueError):
            DeepSeekModel("", environ=ENV)
        with self.assertRaises(ValueError):
            DeepSeekModel("m", reasoning_effort="extreme", environ=ENV)
        with self.assertRaises(ValueError):
            DeepSeekModel("m", max_tokens=0, environ=ENV)
        with self.assertRaises(ValueError):
            DeepSeekModel("m", max_tokens=393217, environ=ENV)


def _as_message(block: Any) -> dict[str, Any]:
    from ledgerlab_agent.agent import _block_to_message

    return _block_to_message(block)


class RetryTest(unittest.TestCase):
    def test_ac_10_10_429_is_retried_with_backoff_and_then_succeeds(self) -> None:
        client, fake, naps = model((429, {"error": {"message": "slow down"}}), ok(completion("fine")))
        self.assertEqual(client.complete(request()).content, (TextBlock("fine"),))
        self.assertEqual(len(fake.calls), 2)
        self.assertEqual(naps, [1.0])

    def test_ac_10_10_server_errors_and_network_failures_are_retried_at_most_twice(self) -> None:
        client, fake, naps = model((503, b"busy"), OSError("reset"), (500, b"oops"))
        with self.assertRaises(ModelError) as caught:
            client.complete(request())
        self.assertFalse(caught.exception.fatal)
        self.assertEqual(len(fake.calls), 3)
        self.assertEqual(naps, [1.0, 2.0])

    def test_ac_10_10_a_client_error_is_not_retried(self) -> None:
        client, fake, naps = model((400, {"error": {"message": "bad request"}}))
        with self.assertRaises(ModelError) as caught:
            client.complete(request())
        self.assertEqual((len(fake.calls), naps), (1, []))
        self.assertFalse(caught.exception.fatal)
        self.assertIn("bad request", str(caught.exception))

    def test_ac_10_10_auth_and_balance_errors_are_fatal(self) -> None:
        for status in (401, 402, 403, 404):
            with self.subTest(status=status):
                client, fake, _ = model((status, {"error": {"message": "no"}}))
                with self.assertRaises(ModelError) as caught:
                    client.complete(request())
                self.assertTrue(caught.exception.fatal)
                self.assertEqual(len(fake.calls), 1)


class SecretHandlingTest(unittest.TestCase):
    def test_ac_10_10_a_missing_key_is_a_clear_fatal_error_before_any_request(self) -> None:
        fake = FakeTransport()
        for environ in ({}, {API_KEY_ENV: "  "}):
            with self.assertRaises(ModelError) as caught:
                DeepSeekModel("deepseek-flash", transport=fake, environ=environ)
            self.assertIn(API_KEY_ENV, str(caught.exception))
            self.assertTrue(caught.exception.fatal)
        self.assertEqual(fake.calls, [])

    def test_ac_10_10_the_key_never_appears_in_errors_repr_or_request_body(self) -> None:
        echo = {"error": {"message": f"invalid key {PLACEHOLDER_SECRET}"}}
        failures: list[str] = []
        for replies in ([(401, echo)], [(500, echo)] * 3, [(200, b"junk " + PLACEHOLDER_SECRET.encode())]):
            client, fake, _ = model(*replies)
            self.assertNotIn(PLACEHOLDER_SECRET, repr(client))
            with self.assertRaises(ModelError) as caught:
                client.complete(request())
            failures.append(str(caught.exception))
            self.assertNotIn(PLACEHOLDER_SECRET, json.dumps(fake.calls[0][2]))
        self.assertTrue(failures)
        for text in failures:
            self.assertNotIn(PLACEHOLDER_SECRET, text)


class AgentLoopWithDeepSeekTest(unittest.TestCase):
    def run_loop(self, *replies: tuple[int, Any] | Exception):
        client, fake, _ = model(*replies)
        result = investigate(ISSUE, client, ToolRegistry(transport()), clock=iter([1.0, 2.0]).__next__)
        return result, fake

    def test_ac_10_10_a_full_investigation_runs_through_the_adapter_and_returns_reasoning(self) -> None:
        submit = ("c2", "submit_conclusion", conclusion())
        result, fake = self.run_loop(
            ok(completion(tool_calls=[("c1", "get_issue", {"issue_id": ISSUE})], finish="tool_calls", reasoning="r1", usage={"prompt_tokens": 10, "completion_tokens": 5})),
            ok(completion(tool_calls=[submit], finish="tool_calls", reasoning="r2", usage={"prompt_tokens": 20, "completion_tokens": 6})),
        )
        self.assertEqual(result.status, "ok")
        self.assertEqual(result.usage.input_tokens, 30)
        second = fake.calls[1][2]["messages"]
        self.assertEqual([m.get("reasoning_content") for m in second if m["role"] == "assistant"], ["r1"])
        self.assertTrue(any(m["role"] == "tool" and "<tool_data" in m["content"] for m in second))

    def test_ac_10_10_invalid_arguments_are_reported_back_and_the_model_can_recover(self) -> None:
        submit = ("c3", "submit_conclusion", conclusion())
        result, fake = self.run_loop(
            ok(completion(tool_calls=[("c1", "get_issue", '{"issue_id": ')], finish="tool_calls")),
            ok(completion(tool_calls=[submit], finish="tool_calls")),
        )
        self.assertEqual(result.status, "ok")
        self.assertEqual(result.tool_calls, ())  # the broken call never ran
        tool_messages = [m for m in fake.calls[1][2]["messages"] if m["role"] == "tool"]
        self.assertEqual(len(tool_messages), 1)
        self.assertIn("Invalid arguments", tool_messages[0]["content"])

    def test_ac_10_10_broken_submit_arguments_count_as_an_invalid_conclusion(self) -> None:
        broken = ok(completion(tool_calls=[("c1", "submit_conclusion", "{oops")], finish="tool_calls"))
        result, _ = self.run_loop(broken, broken)
        self.assertEqual(result.status, "invalid_output")
        self.assertTrue(result.conclusion.abstain)

    def test_ac_10_10_unusable_finish_reasons_end_the_run_as_invalid_output_without_retrying(self) -> None:
        for finish in ("length", "content_filter", "insufficient_system_resource", "aborted"):
            with self.subTest(finish=finish):
                submit = ("c1", "submit_conclusion", conclusion())
                result, fake = self.run_loop(ok(completion(tool_calls=[submit], finish=finish)))
                self.assertEqual(result.status, "invalid_output")
                self.assertTrue(result.conclusion.abstain)
                self.assertEqual(len(fake.calls), 1)
                self.assertTrue(any(finish.replace("length", "max_tokens") in e for e in result.validation_errors))

    def test_ac_10_10_a_provider_failure_after_retries_ends_the_question_not_the_process(self) -> None:
        result, fake = self.run_loop((500, b"a"), (500, b"b"), (500, b"c"))
        self.assertEqual(result.status, "invalid_output")
        self.assertEqual(len(fake.calls), 3)
        self.assertTrue(re.search("did not answer", " ".join(result.validation_errors)))

    def test_ac_10_10_a_fatal_provider_error_propagates(self) -> None:
        client, _, _ = model((401, {"error": {"message": "bad key"}}))
        with self.assertRaises(ModelError):
            investigate(ISSUE, client, ToolRegistry(transport()))


class ModuleShapeTest(unittest.TestCase):
    def test_ac_10_10_base_url_is_the_deepseek_api(self) -> None:
        self.assertEqual(BASE_URL, "https://api.deepseek.com")


if __name__ == "__main__":
    unittest.main()
