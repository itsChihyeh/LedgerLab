"""AC-13.5: the local alert receiver (scripts/ops/alert_sink.py)."""

from __future__ import annotations

import http.client
import io
import json
import tempfile
import threading
import unittest
import unittest.mock
from datetime import UTC, datetime
from pathlib import Path
from typing import Any

import alert_sink

FIXED_NOW = datetime(2026, 11, 7, 8, 30, 15, 123000, tzinfo=UTC)


def alert(status: str, name: str = "LedgerLabInboxStuck", **extra: Any) -> dict[str, Any]:
    return {
        "status": status,
        "labels": {"alertname": name, "severity": "page", "kind": "x"},
        "annotations": {"summary": "inbox stuck — 卡住", "runbook": "docs/ops/runbook.md#inbox-stuck"},
        "startsAt": "2026-11-07T08:00:00.000Z",
        "endsAt": "0001-01-01T00:00:00Z",
        "generatorURL": "http://internal-host:9090/graph?g0.expr=up",
        "fingerprint": "abc123",
        **extra,
    }


def payload(status: str = "firing", **overrides: Any) -> dict[str, Any]:
    body: dict[str, Any] = {
        "version": "4",
        "groupKey": '{}:{alertname="LedgerLabInboxStuck"}',
        "truncatedAlerts": 0,
        "status": status,
        "receiver": "local-sink",
        "groupLabels": {"alertname": "LedgerLabInboxStuck"},
        "commonLabels": {},
        "commonAnnotations": {},
        "externalURL": "http://alertmanager:9093",
        "alerts": [alert(status)],
    }
    body.update(overrides)
    return body


class AlertSinkTest(unittest.TestCase):
    def setUp(self) -> None:
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.path = Path(self.tmp.name) / "alerts.jsonl"
        self.out = open(self.path, "a", encoding="utf-8", newline="\n")  # noqa: SIM115 - closed by the cleanup
        self.addCleanup(self.out.close)
        self.stdout = io.StringIO()
        self.server = alert_sink.make_server(
            alert_sink.DEFAULT_HOST, 0, self.out, self.stdout, lambda: FIXED_NOW, io.StringIO()
        )
        thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        thread.start()
        self.addCleanup(thread.join, 5)
        self.addCleanup(self.server.server_close)
        self.addCleanup(self.server.shutdown)

    def request(
        self,
        method: str = "POST",
        path: str = "/alerts",
        body: bytes | None = None,
        headers: dict[str, str] | None = None,
    ) -> tuple[int, dict[str, Any], http.client.HTTPResponse]:
        conn = http.client.HTTPConnection(*self.server.server_address[:2], timeout=5)
        try:
            conn.request(method, path, body=body, headers=headers or {})
            response = conn.getresponse()
            return response.status, json.loads(response.read()), response
        finally:
            conn.close()

    def post_json(self, body: Any) -> tuple[int, dict[str, Any]]:
        raw = body if isinstance(body, bytes) else json.dumps(body).encode("utf-8")
        status, parsed, _ = self.request(body=raw, headers={"Content-Type": "application/json"})
        return status, parsed

    def lines(self) -> list[str]:
        return self.path.read_text(encoding="utf-8").splitlines()

    def test_ac_13_5_defaultHostIsLoopback(self) -> None:
        self.assertEqual("127.0.0.1", alert_sink.DEFAULT_HOST)
        self.assertEqual("127.0.0.1", self.server.server_address[0])
        args = alert_sink.parse_args(["--out", "x.jsonl"])
        self.assertEqual(("127.0.0.1", 9095), (args.host, args.port))

    def test_ac_13_5_outIsRequired(self) -> None:
        with self.assertRaises(SystemExit), unittest.mock.patch("sys.stderr", io.StringIO()):
            alert_sink.parse_args([])

    def test_ac_13_5_firingAndResolvedEachAppendOneLine(self) -> None:
        for status in ("firing", "resolved"):
            code, body = self.post_json(payload(status))
            self.assertEqual((200, {"ok": True}), (code, body))
        rows = [json.loads(line) for line in self.lines()]
        self.assertEqual(2, len(rows))
        firing, resolved = rows
        self.assertEqual("firing", firing["status"])
        self.assertEqual("resolved", resolved["status"])
        self.assertEqual("2026-11-07T08:30:15.123Z", firing["receivedAt"])
        self.assertEqual('{}:{alertname="LedgerLabInboxStuck"}', firing["groupKey"])
        self.assertEqual("local-sink", firing["receiver"])
        self.assertEqual(
            {
                "status": "firing",
                "labels": {"alertname": "LedgerLabInboxStuck", "severity": "page", "kind": "x"},
                "annotations": {"summary": "inbox stuck — 卡住", "runbook": "docs/ops/runbook.md#inbox-stuck"},
                "startsAt": "2026-11-07T08:00:00.000Z",
                "endsAt": "0001-01-01T00:00:00Z",
                "fingerprint": "abc123",
            },
            firing["alerts"][0],
        )
        self.assertEqual({"alerts", "groupKey", "receiver", "receivedAt", "status"}, set(firing))

    def test_ac_13_5_lineIsSortedUtf8WithoutEscapesAndWithoutGeneratorUrl(self) -> None:
        self.post_json(payload())
        raw = self.path.read_bytes()
        self.assertTrue(raw.endswith(b"\n"))
        self.assertNotIn(b"\r", raw)
        text = raw.decode("utf-8")
        self.assertIn("卡住", text)
        self.assertNotIn("generatorURL", text)
        self.assertNotIn("internal-host", text)
        row = json.loads(text)
        self.assertEqual(sorted(row), list(row))

    def test_ac_13_5_standardOutputGetsOneLinePerAlert(self) -> None:
        body = payload()
        body["alerts"].append(alert("firing", "LedgerLabMetricsDown"))
        body["alerts"][1]["labels"]["severity"] = "ticket"
        self.post_json(body)
        self.assertEqual(
            "alert firing LedgerLabInboxStuck page\nalert firing LedgerLabMetricsDown ticket\n",
            self.stdout.getvalue(),
        )

    def test_ac_13_5_otherPathIs404(self) -> None:
        for path in ("/", "/alert", "/alerts/x"):
            code, body, _ = self.request(path=path, body=b"{}", headers={"Content-Type": "application/json"})
            self.assertEqual((404, {"error": "not_found"}), (code, body), path)
        self.assertEqual([], self.lines())

    def test_ac_13_5_otherMethodIs405(self) -> None:
        for method in ("GET", "PUT", "DELETE"):
            code, body, response = self.request(method=method)
            self.assertEqual((405, {"error": "method_not_allowed"}), (code, body), method)
            self.assertEqual("POST", response.getheader("Allow"))
        self.assertEqual([], self.lines())

    def test_ac_13_5_declaredHugeBodyIs413WithoutReadingIt(self) -> None:
        headers = {"Content-Type": "application/json", "Content-Length": str(alert_sink.MAX_BODY_BYTES + 1)}
        code, body, _ = self.request(headers=headers)
        self.assertEqual((413, {"error": "payload_too_large"}), (code, body))
        self.assertEqual([], self.lines())

    def test_ac_13_5_bodyAtTheLimitIsStillReadAndJudgedOnContent(self) -> None:
        code, _ = self.post_json(b" " * alert_sink.MAX_BODY_BYTES)
        self.assertEqual(400, code)

    def test_ac_13_5_badRequestsAre400AndLeaveTheFileAlone(self) -> None:
        no_alerts = payload(alerts=[])
        alerts_not_list = payload(alerts={"a": 1})
        no_alertname = payload()
        del no_alertname["alerts"][0]["labels"]["alertname"]
        no_alert_status = payload()
        del no_alert_status["alerts"][0]["status"]
        no_group_key = payload()
        del no_group_key["groupKey"]
        cases: dict[str, Any] = {
            "bad json": b"{not json",
            "not utf-8": b"\xff\xfe",
            "not an object": b"[1]",
            "wrong version": payload(version="3"),
            "version number": payload(version=4),
            "bad status": payload(status="pending"),
            "no alerts": no_alerts,
            "alerts not a list": alerts_not_list,
            "alert without alertname": no_alertname,
            "alert without status": no_alert_status,
            "no groupKey": no_group_key,
        }
        for name, case in cases.items():
            code, body = self.post_json(case)
            self.assertEqual((400, {"error": "invalid_request"}), (code, body), name)
        self.assertEqual([], self.lines())

    def test_ac_13_5_wrongContentTypeIs400AndCharsetIsAllowed(self) -> None:
        raw = json.dumps(payload()).encode("utf-8")
        for content_type in ("text/plain", "application/x-www-form-urlencoded", ""):
            headers = {"Content-Type": content_type} if content_type else {}
            code, body, _ = self.request(body=raw, headers=headers)
            self.assertEqual((400, {"error": "invalid_request"}), (code, body), content_type)
        self.assertEqual([], self.lines())
        code, body, _ = self.request(body=raw, headers={"Content-Type": "application/json; charset=utf-8"})
        self.assertEqual((200, {"ok": True}), (code, body))
        self.assertEqual(1, len(self.lines()))

    def test_ac_13_5_missingContentLengthIs400(self) -> None:
        conn = http.client.HTTPConnection(*self.server.server_address[:2], timeout=5)
        try:
            conn.putrequest("POST", "/alerts")
            conn.putheader("Content-Type", "application/json")
            conn.endheaders()
            response = conn.getresponse()
            self.assertEqual(400, response.status)
        finally:
            conn.close()
        self.assertEqual([], self.lines())


if __name__ == "__main__":
    unittest.main()
