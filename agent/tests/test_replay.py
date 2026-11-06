"""AC-10.5: the replay transport serves recorded bytes, 404s what was not recorded and 503s what is withheld."""

import base64
import json
import unittest

from ledgerlab_agent.replay import (
    NOT_IN_SNAPSHOT_BODY,
    ReplayTransport,
    SnapshotError,
    parse_snapshot,
    request_key,
    snapshot_document,
    SnapshotEntry,
)
from ledgerlab_agent.tools import ToolRegistry

BATCH = "po_0123456789abcdef0123456789abcdef"


def document(*requests: dict) -> dict:
    return {"format": 1, "generated_at": "t", "commit": "c", "requests": list(requests)}


def snapshot_with_batch():
    raw = b'{"batch": {"batchId": "' + BATCH.encode() + b'", "net":  9777},\n "asOf": {"at": "2026-11-06T00:00:00Z"}}'
    return parse_snapshot(
        document(
            {"path": f"/api/batches/{BATCH}", "query": {}, "status": 200, "body": raw.decode()},
            {"path": "/api/bank-lines", "query": {"reference": BATCH, "x": "1"}, "status": 200, "body": "{}"},
            {"path": "/api/binary", "query": {}, "status": 200,
             "body_b64": base64.b64encode(b"\xff\xfe\x00raw").decode()},
        )
    ), raw


class ReplayTransportTest(unittest.TestCase):
    def test_ac_10_5_hit_returns_the_recorded_status_and_bytes(self) -> None:
        snapshot, raw = snapshot_with_batch()
        response = ReplayTransport(snapshot).get(f"/api/batches/{BATCH}", {})
        self.assertEqual((response.status, response.body), (200, raw))

    def test_ac_10_5_binary_bodies_round_trip_through_base64(self) -> None:
        snapshot, _ = snapshot_with_batch()
        self.assertEqual(ReplayTransport(snapshot).get("/api/binary", {}).body, b"\xff\xfe\x00raw")

    def test_ac_10_5_miss_answers_404_not_in_snapshot(self) -> None:
        snapshot, _ = snapshot_with_batch()
        response = ReplayTransport(snapshot).get("/api/batches/po_other", {})
        self.assertEqual(response.status, 404)
        self.assertEqual(response.body, NOT_IN_SNAPSHOT_BODY)
        self.assertEqual(json.loads(response.body), {"error": "not_in_snapshot"})

    def test_ac_10_5_query_parameter_order_does_not_matter(self) -> None:
        snapshot, _ = snapshot_with_batch()
        transport = ReplayTransport(snapshot)
        self.assertEqual(transport.get("/api/bank-lines", {"x": "1", "reference": BATCH}).status, 200)
        self.assertEqual(transport.get("/api/bank-lines", {"reference": BATCH, "x": "1"}).status, 200)

    def test_ac_10_5_a_different_query_value_is_a_miss(self) -> None:
        snapshot, _ = snapshot_with_batch()
        self.assertEqual(ReplayTransport(snapshot).get("/api/bank-lines", {"reference": BATCH}).status, 404)

    def test_ac_10_8_withheld_request_answers_503_even_though_it_is_recorded(self) -> None:
        snapshot, _ = snapshot_with_batch()
        transport = ReplayTransport(snapshot, withheld=[(f"/api/batches/{BATCH}", {})])
        self.assertEqual(transport.get(f"/api/batches/{BATCH}", {}).status, 503)
        self.assertEqual(transport.get("/api/binary", {}).status, 200)

    def test_ac_10_8_withheld_batch_reaches_the_tool_layer_as_a_failed_call(self) -> None:
        snapshot, _ = snapshot_with_batch()
        registry = ToolRegistry(ReplayTransport(snapshot, withheld=[(f"/api/batches/{BATCH}", {})]))
        result = registry.call("get_batch", {"batch_id": BATCH})
        self.assertFalse(result.ok)
        self.assertEqual(result.status, 503)
        self.assertEqual(result.records, ())

    def test_ac_10_5_tool_layer_over_replay_extracts_records_like_a_live_call(self) -> None:
        snapshot, _ = snapshot_with_batch()
        result = ToolRegistry(ReplayTransport(snapshot)).call("get_batch", {"batch_id": BATCH})
        self.assertTrue(result.ok)
        self.assertIn(("batch", BATCH), [(r.kind, r.id) for r in result.records])

    def test_ac_10_5_calls_are_recorded_for_assertions(self) -> None:
        snapshot, _ = snapshot_with_batch()
        transport = ReplayTransport(snapshot)
        transport.get("/api/binary", {})
        self.assertEqual(transport.calls, [request_key("/api/binary", {})])


class SnapshotFormatTest(unittest.TestCase):
    def test_ac_10_5_document_round_trips(self) -> None:
        entries = [
            (request_key("/a", {"q": "1"}), SnapshotEntry(200, b'{"k": "v"}')),
            (request_key("/b", {}), SnapshotEntry(503, b"\xff\xfe")),
        ]
        parsed = parse_snapshot(json.loads(json.dumps(snapshot_document(entries, "now", "abc"))))
        self.assertEqual(dict(parsed.entries), dict(entries))
        self.assertEqual((parsed.generated_at, parsed.commit), ("now", "abc"))

    def test_ac_10_5_rejects_unknown_format_missing_body_and_duplicates(self) -> None:
        with self.assertRaises(SnapshotError):
            parse_snapshot({"format": 2, "requests": []})
        with self.assertRaises(SnapshotError):
            parse_snapshot(document({"path": "/a", "query": {}, "status": 200}))
        same = {"path": "/a", "query": {}, "status": 200, "body": ""}
        with self.assertRaises(SnapshotError):
            parse_snapshot(document(same, dict(same)))


if __name__ == "__main__":
    unittest.main()
