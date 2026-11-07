import json
import unittest

from ledgerlab_agent.tools import ToolRegistry, load_specs
from ledgerlab_agent.transport import HttpTransport, TransportResponse

from tests import fixtures
from tests.fakes import FakeBackend, RecordingTransport, json_body


class InvalidArgumentsTest(unittest.TestCase):
    def setUp(self) -> None:
        self.transport = RecordingTransport()
        self.registry = ToolRegistry(self.transport)

    def assert_rejected(self, name: str, args: object) -> None:
        result = self.registry.call(name, args)
        self.assertFalse(result.ok)
        self.assertEqual(result.error, "invalid_arguments")
        self.assertEqual(self.transport.calls, [], f"transport was called for {name} {args!r}")

    def test_ac_10_1_rejects_unknown_tool(self) -> None:
        self.assert_rejected("delete_everything", {})
        self.assert_rejected("resolve_issue", {"issue_id": "iss_1"})

    def test_ac_10_1_rejects_extra_arguments(self) -> None:
        self.assert_rejected("get_issue", {"issue_id": "iss_abc", "path": "/api/demo/reset"})

    def test_ac_10_1_rejects_missing_required_argument(self) -> None:
        self.assert_rejected("get_issue", {})
        self.assert_rejected("get_statement_line", {"file_id": "stm_1"})
        self.assert_rejected("search_bank_lines", {})

    def test_ac_10_1_rejects_non_object_arguments(self) -> None:
        self.assert_rejected("get_issue", "iss_abc")
        self.assert_rejected("get_issue", None)

    def test_ac_10_1_rejects_bad_parameter_values(self) -> None:
        bad_values = [
            "../etc/passwd",
            "iss_abc/../../api/demo/reset",
            "iss_abc?x=1",
            "iss_abc#frag",
            "iss_abc ",
            " iss_abc",
            "iss_abc\n",
            "iss abc",
            "iss_" + "a" * 80,
            "",
            "iss_abc%2e%2e",
            "http://evil.example/",
        ]
        for value in bad_values:
            with self.subTest(value=value):
                self.assert_rejected("get_issue", {"issue_id": value})
                self.assert_rejected("search_bank_lines", {"reference": value})

    def test_ac_10_1_rejects_values_outside_enums_and_ranges(self) -> None:
        self.assert_rejected("list_issues", {"limit": 0})
        self.assert_rejected("list_issues", {"limit": 51})
        self.assert_rejected("list_issues", {"limit": True})
        self.assert_rejected("list_issues", {"limit": "5; DROP TABLE"})
        self.assert_rejected("list_issues", {"status": "DROPPED"})
        self.assert_rejected("list_issues", {"category": "NOT_A_CATEGORY"})
        self.assert_rejected("get_statement_line", {"file_id": "stm_1", "seq": -1})
        self.assert_rejected("get_statement_line", {"file_id": "stm_1", "seq": "1/../2"})
        self.assert_rejected("get_payment_timeline", {"payment_ref": "xx_123"})
        self.assert_rejected("get_batch", {"batch_id": "batch_1"})
        self.assert_rejected("get_issue", {"issue_id": ["iss_abc"]})

    def test_ac_10_1_search_sop_placeholder_is_local_and_validates_query(self) -> None:
        self.assert_rejected("search_sop", {"query": "x" * 201})
        self.assert_rejected("search_sop", {})
        result = self.registry.call("search_sop", {"query": "split deposit"})
        self.assertTrue(result.ok)
        self.assertEqual(result.data, {"results": []})
        self.assertEqual(self.transport.calls, [])


class RoutesTest(unittest.TestCase):
    def test_ac_10_1_every_route_is_get_and_every_design_tool_is_listed(self) -> None:
        specs = load_specs()
        expected = {
            "get_issue", "list_issues", "get_payment_timeline", "get_batch", "get_bank_line",
            "search_bank_lines", "get_statement_line", "get_event", "get_entry", "get_refund", "get_payout",
        }
        self.assertEqual(set(specs), expected)
        raw = json.loads(
            (__import__("pathlib").Path(__file__).parent.parent / "src/ledgerlab_agent/routes.json").read_text("utf-8")
        )
        self.assertTrue(all(r["method"] == "GET" for r in raw))
        self.assertTrue(all(r["path_template"].startswith("/api/") for r in raw))
        self.assertFalse(any("/api/demo" in r["path_template"] for r in raw))

    def test_ac_10_1_registry_lists_search_sop_among_specs(self) -> None:
        names = {s.name for s in ToolRegistry(RecordingTransport()).specs}
        self.assertIn("search_sop", names)


class ToolFixturesTest(unittest.TestCase):
    def test_ac_10_1_each_tool_builds_fixed_template_request_and_extracts_records(self) -> None:
        for tool, (args, path, query, body, expected) in fixtures.TOOL_CASES.items():
            with self.subTest(tool=tool):
                transport = RecordingTransport({path: body})
                result = ToolRegistry(transport).call(tool, args)
                self.assertTrue(result.ok, result)
                self.assertEqual(transport.calls, [(path, query)])
                self.assertEqual(result.status, 200)
                self.assertEqual(result.as_of, fixtures.AS_OF["at"])
                self.assertEqual({(r.kind, r.id) for r in result.records}, set(expected))
                self.assertEqual(len(result.records), len(set(result.records)), "records must be de-duplicated")
                self.assertFalse(result.truncated)

    def test_ac_10_1_records_keep_first_seen_order(self) -> None:
        transport = RecordingTransport({"/api/recon/issues": fixtures.ISSUE_LIST})
        result = ToolRegistry(transport).call("list_issues", {})
        self.assertEqual([r.id for r in result.records][:2],
                         ["iss_01HX0000000000000000000001", "iss_01HX0000000000000000000002"])

    def test_ac_10_1_path_parameters_are_url_encoded_not_spliced(self) -> None:
        transport = RecordingTransport()
        ToolRegistry(transport).call("get_statement_line", {"file_id": "stm_1", "seq": 12})
        self.assertEqual(transport.calls, [("/api/statement-lines/stm_1/12", {})])

    def test_ac_10_1_large_body_is_truncated_and_flagged(self) -> None:
        body = b'{"asOf":{"at":"2026-01-01T00:00:00Z"},"pad":"' + b"x" * 40000 + b'"}'
        transport = RecordingTransport({"/api/events/evt_big": body})
        result = ToolRegistry(transport).call("get_event", {"event_id": "evt_big"})
        self.assertTrue(result.ok)
        self.assertTrue(result.truncated)
        self.assertIsInstance(result.data, str)
        self.assertEqual(len(result.data), 16 * 1024)
        self.assertEqual(result.records, ())

    def test_ac_10_1_non_2xx_is_not_ok_and_carries_status(self) -> None:
        transport = RecordingTransport()
        result = ToolRegistry(transport).call("get_event", {"event_id": "evt_missing"})
        self.assertFalse(result.ok)
        self.assertEqual(result.status, 404)
        self.assertEqual(result.error, "http_404")
        self.assertEqual(result.records, ())

        unavailable = RecordingTransport(default=TransportResponse(503, b'{"error":"unavailable"}'))
        self.assertEqual(ToolRegistry(unavailable).call("get_event", {"event_id": "evt_x"}).status, 503)

    def test_ac_10_1_transport_failure_is_reported_without_status(self) -> None:
        transport = RecordingTransport(default=TransportResponse(0, None, "transport_error: TimeoutError"))
        result = ToolRegistry(transport).call("get_event", {"event_id": "evt_x"})
        self.assertFalse(result.ok)
        self.assertEqual(result.status, 0)
        self.assertEqual(result.error, "transport_error: TimeoutError")

    def test_ac_10_1_end_to_end_against_local_http_backend(self) -> None:
        routes = {
            "/api/recon/issues/iss_01HX0000000000000000000001": (200, json_body(fixtures.ISSUE_DETAIL), {}),
            "/api/bank-lines": (200, json_body(fixtures.BANK_SEARCH), {}),
            "/api/events/evt_big": (200, b'{"pad":"' + b"y" * 50000 + b'"}', {}),
        }
        with FakeBackend(routes) as backend:
            registry = ToolRegistry(HttpTransport(backend.base_url))
            issue = registry.call("get_issue", {"issue_id": "iss_01HX0000000000000000000001"})
            search = registry.call("search_bank_lines", {"reference": "po_abc123"})
            big = registry.call("get_event", {"event_id": "evt_big"})
            missing = registry.call("get_event", {"event_id": "evt_none"})
            self.assertTrue(issue.ok and search.ok)
            self.assertEqual(len(search.records), 2)
            self.assertTrue(big.truncated)
            self.assertEqual(missing.status, 404)
            self.assertEqual({m for m, _, _ in backend.requests}, {"GET"})
            self.assertIn(("GET", "/api/bank-lines", {"reference": "po_abc123"}), backend.requests)


if __name__ == "__main__":
    unittest.main()
