"""AC-11.7 (protocol, in-process and over a real pipe) and AC-11.8 (tools are the agent's tools, nothing more)."""

from __future__ import annotations

import io
import json
import subprocess
import sys
import unittest
from pathlib import Path
from typing import Any

from ledgerlab_agent.model import tool_definition
from ledgerlab_agent.replay import ReplayTransport, Snapshot, load_snapshot
from ledgerlab_agent.sop import SopSearch, load_sop
from ledgerlab_agent.tools import ToolRegistry
from ledgerlab_agent.transport import TransportResponse

from ledgerlab_agent_mcp.cli import main
from ledgerlab_agent_mcp.server import LATEST_PROTOCOL, ServerState, handle, result_payload, serve

AGENT = Path(__file__).resolve().parents[2]
DEV_DIR = AGENT / "eval" / "data" / "dev"
LAUNCHER = AGENT / "ledgerlab-agent-mcp.py"
_snapshot: Snapshot | None = None


def dev_snapshot() -> Snapshot:
    global _snapshot
    if _snapshot is None:
        _snapshot = load_snapshot(DEV_DIR / "snapshot.json")
    return _snapshot


def dev_issue_id() -> str:
    return json.loads((DEV_DIR / "labels.json").read_text(encoding="utf-8"))["labels"][0]["issue_id"]


def bank_line_reference() -> str:
    for path, query in dev_snapshot().entries:
        if path == "/api/bank-lines" and query:
            return dict(query)["reference"]
    raise AssertionError("dev snapshot has no search_bank_lines request")


class RecordingTransport:
    def __init__(self) -> None:
        self.requests: list[tuple[str, dict[str, str]]] = []

    def get(self, path: str, query: dict[str, str]) -> TransportResponse:
        self.requests.append((path, query))
        return TransportResponse(200, b"{}")


def make_state(transport: Any = None, max_calls: int = 500) -> ServerState:
    tools = ToolRegistry(transport or ReplayTransport(dev_snapshot()), local_tools=[SopSearch(load_sop())])
    return ServerState(tools, max_calls=max_calls)


def rpc(method: str, params: Any = None, id: Any = 1) -> dict[str, Any]:
    message: dict[str, Any] = {"jsonrpc": "2.0", "id": id, "method": method}
    if params is not None:
        message["params"] = params
    return message


def initialize(state: ServerState, version: str = LATEST_PROTOCOL) -> dict[str, Any]:
    reply = handle(rpc("initialize", {"protocolVersion": version, "capabilities": {}}), state)
    assert reply is not None
    return reply


def ready_state(**kwargs: Any) -> ServerState:
    state = make_state(**kwargs)
    initialize(state)
    handle({"jsonrpc": "2.0", "method": "notifications/initialized"}, state)
    return state


def call(state: ServerState, name: str, arguments: Any) -> dict[str, Any]:
    reply = handle(rpc("tools/call", {"name": name, "arguments": arguments}), state)
    assert reply is not None
    return reply


class ProtocolTest(unittest.TestCase):
    def test_ac_11_7_supported_versions_are_echoed_and_unknown_ones_get_the_latest(self) -> None:
        for version in ("2025-11-25", "2025-06-18", "2025-03-26"):
            self.assertEqual(initialize(make_state(), version)["result"]["protocolVersion"], version)
        for version in ("2024-11-05", "2099-01-01", ""):
            self.assertEqual(initialize(make_state(), version)["result"]["protocolVersion"], LATEST_PROTOCOL)

    def test_ac_11_7_initialize_declares_only_tools_without_list_changed(self) -> None:
        result = initialize(make_state())["result"]
        self.assertEqual(result["capabilities"], {"tools": {"listChanged": False}})
        self.assertEqual(result["serverInfo"]["name"], "ledgerlab-readonly-tools")
        self.assertTrue(result["serverInfo"]["version"])
        self.assertIn("read-only", result["instructions"])

    def test_ac_11_7_before_initialize_only_ping_is_served(self) -> None:
        state = make_state()
        self.assertEqual(handle(rpc("ping"), state)["result"], {})
        for method in ("tools/list", "tools/call"):
            reply = handle(rpc(method, {"name": "get_issue", "arguments": {}}), state)
            self.assertEqual(reply["error"]["code"], -32002)
        initialize(state)
        self.assertIn("result", handle(rpc("tools/list"), state))

    def test_ac_11_7_tools_list_matches_the_agents_tools_and_schemas(self) -> None:
        state = ready_state()
        tools = handle(rpc("tools/list"), state)["result"]["tools"]
        specs = {s.name: s for s in state.tools.specs}
        self.assertEqual({t["name"] for t in tools}, set(specs))
        self.assertEqual(len(tools), 12)
        self.assertIn("search_sop", specs)
        self.assertNotIn("submit_conclusion", {t["name"] for t in tools})
        for tool in tools:
            self.assertEqual(tool["inputSchema"], tool_definition(specs[tool["name"]])["input_schema"])
            self.assertIs(tool["inputSchema"]["additionalProperties"], False)
            self.assertEqual(
                tool["annotations"],
                {"readOnlyHint": True, "destructiveHint": False, "idempotentHint": True, "openWorldHint": False},
            )

    def test_ac_11_7_unknown_method_unknown_tool_and_bad_arguments_object(self) -> None:
        state = ready_state()
        self.assertEqual(handle(rpc("resources/list"), state)["error"]["code"], -32601)
        self.assertEqual(handle(rpc("prompts/list"), state)["error"]["code"], -32601)
        self.assertEqual(call(state, "submit_conclusion", {})["error"]["code"], -32602)
        self.assertEqual(call(state, "no_such_tool", {})["error"]["code"], -32602)
        self.assertEqual(call(state, "get_issue", ["iss_1"])["error"]["code"], -32602)
        self.assertEqual(handle(rpc("tools/call", {}), state)["error"]["code"], -32602)

    def test_ac_11_7_batches_and_malformed_requests_are_invalid_requests(self) -> None:
        state = ready_state()
        self.assertEqual(handle([rpc("ping")], state)["error"]["code"], -32600)
        self.assertEqual(handle("ping", state)["error"]["code"], -32600)
        self.assertEqual(handle({"jsonrpc": "1.0", "id": 5, "method": "ping"}, state)["error"]["code"], -32600)
        self.assertEqual(handle({"jsonrpc": "2.0", "id": 5}, state)["error"], {"code": -32600, "message": "not a valid JSON-RPC 2.0 request"})
        self.assertEqual(handle({"jsonrpc": "2.0", "id": True, "method": "ping"}, state)["error"]["code"], -32600)

    def test_ac_11_7_notifications_and_client_responses_get_no_reply(self) -> None:
        state = make_state()
        self.assertIsNone(handle({"jsonrpc": "2.0", "method": "notifications/initialized"}, state))
        self.assertIsNone(handle({"jsonrpc": "2.0", "method": "no/such/thing"}, state))
        self.assertIsNone(handle({"jsonrpc": "2.0", "method": "tools/call", "params": {}}, state))
        self.assertIsNone(handle({"jsonrpc": "2.0", "id": 9, "result": {}}, state))

    def test_ac_11_7_string_ids_are_echoed(self) -> None:
        self.assertEqual(handle(rpc("ping", id="abc"), make_state())["id"], "abc")

    def test_ac_11_7_a_failing_handler_gets_internal_error_and_the_loop_keeps_serving(self) -> None:
        def flaky(message: object, state: ServerState) -> dict[str, Any] | None:
            if isinstance(message, dict) and message.get("method") == "boom":
                raise RuntimeError("secret detail")
            return handle(message, state)

        stdin = io.StringIO(json.dumps(rpc("boom", id=1)) + "\n" + json.dumps(rpc("ping", id=2)) + "\n")
        stdout, stderr = io.StringIO(), io.StringIO()
        self.assertEqual(serve(make_state(), stdin, stdout, stderr, flaky), 0)
        replies = [json.loads(line) for line in stdout.getvalue().splitlines()]
        self.assertEqual(replies[0]["error"]["code"], -32603)
        self.assertNotIn("secret", stdout.getvalue())
        self.assertEqual(replies[1], {"jsonrpc": "2.0", "id": 2, "result": {}})
        self.assertIn("RuntimeError", stderr.getvalue())
        self.assertNotIn("secret", stderr.getvalue())


class ToolsTest(unittest.TestCase):
    def test_ac_11_8_invalid_arguments_are_an_error_result_and_no_request_is_sent(self) -> None:
        transport = RecordingTransport()
        state = ready_state(transport=transport)
        for name, arguments in (
            ("get_issue", {"issue_id": "not-an-issue"}),
            ("get_issue", {}),
            ("get_issue", {"issue_id": "iss_1", "extra": "x"}),
            ("get_issue", {"issue_id": "iss_1/../../admin"}),
        ):
            result = call(state, name, arguments)["result"]
            self.assertTrue(result["isError"], arguments)
            self.assertEqual(result["structuredContent"]["error"], "invalid_arguments")
        self.assertEqual(transport.requests, [])
        ok = call(state, "get_issue", {"issue_id": "iss_abc"})["result"]
        self.assertFalse(ok["isError"])
        self.assertEqual(transport.requests, [("/api/recon/issues/iss_abc", {})])

    def test_ac_11_8_results_equal_the_tool_registrys_own(self) -> None:
        state = ready_state()
        direct = ToolRegistry(ReplayTransport(dev_snapshot()), local_tools=[SopSearch(load_sop())])
        for name, arguments in (
            ("get_issue", {"issue_id": dev_issue_id()}),
            ("search_bank_lines", {"reference": bank_line_reference()}),
            ("get_issue", {"issue_id": "iss_NOTINSNAPSHOT"}),
        ):
            result = call(state, name, arguments)["result"]
            expected = result_payload(direct.call(name, arguments))
            self.assertEqual(result["structuredContent"], expected)
            self.assertEqual(json.loads(result["content"][0]["text"]), expected)
            self.assertEqual(result["isError"], not expected["ok"])
        self.assertTrue(call(state, "get_issue", {"issue_id": dev_issue_id()})["result"]["structuredContent"]["records"])
        self.assertTrue(call(state, "get_issue", {"issue_id": "iss_NOTINSNAPSHOT"})["result"]["isError"])

    def test_ac_11_8_search_sop_works(self) -> None:
        result = call(ready_state(), "search_sop", {"query": "fee mismatch"})["result"]
        self.assertFalse(result["isError"])
        self.assertTrue(result["structuredContent"]["data"]["results"])

    def test_ac_11_8_the_call_limit_stops_the_third_call(self) -> None:
        state = ready_state(max_calls=2)
        for _ in range(2):
            self.assertFalse(call(state, "search_sop", {"query": "fee"})["result"]["isError"])
        limited = call(state, "search_sop", {"query": "fee"})["result"]
        self.assertTrue(limited["isError"])
        self.assertEqual(limited["structuredContent"]["error"], "call_limit_reached")
        self.assertIn("call_limit_reached", limited["content"][0]["text"])


def _spawn(*extra: str) -> subprocess.Popen[str]:
    return subprocess.Popen(
        [sys.executable, str(LAUNCHER), "--snapshot", str(DEV_DIR / "snapshot.json"), *extra],
        stdin=subprocess.PIPE,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        text=True,
        encoding="utf-8",
    )


class ProcessTest(unittest.TestCase):
    def _ask(self, process: subprocess.Popen[str], line: str) -> dict[str, Any]:
        assert process.stdin is not None and process.stdout is not None
        process.stdin.write(line + "\n")
        process.stdin.flush()
        reply = json.loads(process.stdout.readline())
        self.assertEqual(reply["jsonrpc"], "2.0")
        return reply

    def test_ac_11_7_full_session_over_a_real_pipe(self) -> None:
        process = _spawn()
        try:
            assert process.stdin is not None and process.stdout is not None
            init = self._ask(process, json.dumps(rpc("initialize", {"protocolVersion": "2025-06-18"}, id=1)))
            self.assertEqual(init["result"]["protocolVersion"], "2025-06-18")
            process.stdin.write(json.dumps({"jsonrpc": "2.0", "method": "notifications/initialized"}) + "\n")
            listing = self._ask(process, json.dumps(rpc("tools/list", id=2)))
            self.assertEqual(listing["id"], 2)
            self.assertEqual(len(listing["result"]["tools"]), 12)
            issue = self._ask(
                process, json.dumps(rpc("tools/call", {"name": "get_issue", "arguments": {"issue_id": dev_issue_id()}}, id=3))
            )
            self.assertEqual(issue["id"], 3)
            self.assertFalse(issue["result"]["isError"])
            self.assertTrue(issue["result"]["structuredContent"]["ok"])
            bad = self._ask(process, "{this is not json")
            self.assertEqual(bad, {"jsonrpc": "2.0", "id": None, "error": {"code": -32700, "message": "parse error"}})
            self.assertEqual(self._ask(process, json.dumps(rpc("ping", id=4)))["result"], {})
            self.assertIsNone(process.poll())
            process.stdin.close()
            remaining = process.stdout.read()
            self.assertEqual(remaining, "")
            self.assertEqual(process.wait(timeout=10), 0)
        finally:
            if process.poll() is None:
                process.kill()
                process.wait()
            for stream in (process.stdin, process.stdout, process.stderr):
                if stream is not None:
                    stream.close()

    def test_ac_11_7_argument_errors_exit_2_with_the_reason_on_stderr(self) -> None:
        for extra in (
            [],
            ["--snapshot", str(DEV_DIR / "snapshot.json"), "--base-url", "http://127.0.0.1:1"],
        ):
            done = subprocess.run(
                [sys.executable, str(LAUNCHER), *extra], capture_output=True, text=True, input="", timeout=30
            )
            self.assertEqual(done.returncode, 2, extra)
            self.assertEqual(done.stdout, "")
            self.assertTrue(done.stderr)

    def test_ac_11_7_bad_values_exit_2_in_process(self) -> None:
        for argv in (
            ["--base-url", "ftp://example.test"],
            ["--snapshot", str(DEV_DIR / "no-such-file.json")],
            ["--snapshot", str(DEV_DIR / "snapshot.json"), "--max-calls", "0"],
        ):
            err = io.StringIO()
            self.assertEqual(main(argv, io.StringIO(""), io.StringIO(), err), 2, argv)
            self.assertTrue(err.getvalue().startswith("error:"))

    def test_ac_11_8_max_calls_option_reaches_the_server(self) -> None:
        stdin = io.StringIO(
            "\n".join(
                [json.dumps(rpc("initialize", {"protocolVersion": LATEST_PROTOCOL}))]
                + [json.dumps(rpc("tools/call", {"name": "search_sop", "arguments": {"query": "fee"}}, id=i)) for i in (2, 3, 4)]
            )
            + "\n"
        )
        stdout = io.StringIO()
        code = main(["--snapshot", str(DEV_DIR / "snapshot.json"), "--max-calls", "2"], stdin, stdout, io.StringIO())
        self.assertEqual(code, 0)
        lines = [json.loads(line) for line in stdout.getvalue().splitlines()]
        self.assertEqual([r["result"].get("isError") for r in lines[1:]], [False, False, True])
        self.assertEqual(lines[3]["result"]["structuredContent"]["error"], "call_limit_reached")


if __name__ == "__main__":
    unittest.main()
