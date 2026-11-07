import contextlib
import http.server
import io
import json
import threading
import unittest

from ledgerlab_agent.cli import main as agent_main
from ledgerlab_agent.model import ModelRequest, ModelResponse
from ledgerlab_agent.stub_model import StubModel

from tests.support import DEV_DIR, RunningService, dev_issue_id, temp_store


def cli_conclusion(issue_id: str) -> dict:
    out = io.StringIO()
    code = agent_main(
        ["investigate", issue_id, "--snapshot", str(DEV_DIR / "snapshot.json"), "--model", "stub", "--json"],
        out,
        io.StringIO(),
    )
    assert code == 0
    return json.loads(out.getvalue())


class InvestigationEndpointsTest(unittest.TestCase):
    def test_ac_11_1_post_returns_the_stub_conclusion_and_it_can_be_read_back_after_a_restart(self) -> None:
        issue_id = dev_issue_id("BANK_SPLIT_DEPOSIT")
        path = temp_store(self)
        with RunningService(path) as service:
            status, headers, record = service.post_issue(issue_id)
            self.assertEqual(status, 201)
            self.assertTrue(headers["content-type"].startswith("application/json"))
            self.assertEqual(record["conclusion"], cli_conclusion(issue_id))
            self.assertEqual(record["conclusion"]["root_cause"], "BANK_SPLIT_DEPOSIT")
            self.assertEqual(record["status"], "ok")
            self.assertEqual(record["issueId"], issue_id)
            self.assertEqual(record["model"], "stub")
            self.assertTrue(record["modelIsStandIn"])
            self.assertEqual(record["verification"]["fabricatedCitations"], [])
            self.assertEqual(record["verification"]["fabricatedExplanationIds"], [])
            self.assertIs(record["verification"]["supported"], True)
            self.assertTrue(record["toolCalls"])
            self.assertEqual(set(record["toolCalls"][0]), {"tool", "args", "status", "ok", "asOf"})
            self.assertTrue(record["usage"]["isEstimate"])
            run_id = record["runId"]
            self.assertRegex(run_id, r"^inv_[0-9A-HJKMNP-TV-Z]{26}$")

            status, _, listed = service.request("GET", f"/agent/investigations?issueId={issue_id}")
            self.assertEqual((status, listed), (200, {"investigations": [record]}))
            status, _, single = service.request("GET", f"/agent/investigations/{run_id}")
            self.assertEqual((status, single), (200, record))
        with RunningService(path) as restarted:
            status, _, single = restarted.request("GET", f"/agent/investigations/{run_id}")
            self.assertEqual((status, single), (200, record))
            self.assertEqual(restarted.request("GET", f"/agent/investigations?issueId={issue_id}")[2]["investigations"], [record])

    def test_ac_11_1_health_reports_model_and_source(self) -> None:
        with RunningService(temp_store(self)) as service:
            status, _, body = service.request("GET", "/agent/health")
        self.assertEqual(status, 200)
        self.assertEqual(body, {"status": "ok", "model": "stub", "modelIsStandIn": True, "source": "snapshot"})

    def test_ac_11_1_listing_is_newest_first_and_capped_at_twenty(self) -> None:
        issue_id = dev_issue_id("BANK_SPLIT_DEPOSIT")
        with RunningService(temp_store(self), model_spec="baseline") as service:
            ids = [service.post_issue(issue_id)[2]["runId"] for _ in range(22)]
            other = dev_issue_id("BANK_DUPLICATE_CREDIT")
            service.post_issue(other)
            listed = service.request("GET", f"/agent/investigations?issueId={issue_id}")[2]["investigations"]
        self.assertEqual([r["runId"] for r in listed], list(reversed(ids))[:20])
        self.assertEqual(ids, sorted(ids))  # the fixed clock makes run ids sort in creation order

    def test_ac_11_1_unknown_run_and_malformed_queries(self) -> None:
        with RunningService(temp_store(self)) as service:
            self.assertEqual(service.request("GET", "/agent/investigations/inv_" + "0" * 26)[0], 404)
            self.assertEqual(service.request("GET", "/agent/investigations/nope")[0], 404)
            for query in ("", "?issueId=", "?issueId=bad", "?issueId=iss_a&x=1", "?x=1", "?issueId=iss_a&issueId=iss_b"):
                self.assertEqual(service.request("GET", "/agent/investigations" + query)[0], 400, query)
            self.assertEqual(service.request("GET", "/agent/investigations?issueId=iss_unknown")[2], {"investigations": []})


class RequestRulesTest(unittest.TestCase):
    def setUp(self) -> None:
        self.service = RunningService(temp_store(self))
        self.service.__enter__()
        self.addCleanup(self.service.__exit__)
        self.good = json.dumps({"issueId": "iss_abc"}).encode()

    def post(self, body: bytes, content_type: str | None = "application/json") -> tuple[int, dict, object]:
        headers = {"Content-Type": content_type} if content_type else {}
        return self.service.request("POST", "/agent/investigations", body, headers)

    def test_ac_11_1_rejects_non_json_post(self) -> None:
        for content_type in ("text/plain", "application/x-www-form-urlencoded", "multipart/form-data", None):
            status, _, body = self.post(self.good, content_type)
            self.assertEqual((status, body), (415, {"error": "unsupported_media_type"}), content_type)

    def test_ac_11_1_accepts_json_content_type_with_a_charset(self) -> None:
        status, _, _ = self.post(self.good, "application/json; charset=utf-8")
        self.assertEqual(status, 201)

    def test_ac_11_1_rejects_bad_json_extra_fields_and_bad_issue_ids(self) -> None:
        bodies = [
            b"not json",
            b"[]",
            b"{}",
            b'{"issueId": 5}',
            b'{"issueId": "iss_abc", "model": "x"}',
            b'{"issueId": "abc"}',
            b'{"issueId": "iss_a/../b"}',
            b'{"issueId": "iss_' + b"a" * 61 + b'"}',
            b"\xff\xfe",
        ]
        for body in bodies:
            self.assertEqual(self.post(body)[:1], (400,), body)

    def test_ac_11_1_rejects_a_body_over_four_kilobytes(self) -> None:
        body = b'{"issueId": "iss_' + b"a" * 5000 + b'"}'
        status, _, payload = self.post(body)
        self.assertEqual((status, payload), (413, {"error": "payload_too_large"}))

    def test_ac_11_1_unknown_paths_are_404_and_other_methods_405(self) -> None:
        self.assertEqual(self.service.request("GET", "/")[0], 404)
        self.assertEqual(self.service.request("GET", "/agent")[0], 404)
        self.assertEqual(self.service.request("GET", "/agent/health/x")[0], 404)
        self.assertEqual(self.service.request("POST", "/agent/other", self.good, {"Content-Type": "application/json"})[0], 404)
        for method in ("PUT", "DELETE", "OPTIONS", "PATCH", "HEAD"):
            status, headers, _ = self.service.request(method, "/agent/investigations")
            self.assertEqual(status, 405, method)
            self.assertEqual(headers["allow"], "GET, POST")

    def test_ac_11_1_no_response_carries_cross_origin_headers(self) -> None:
        origin = {"Origin": "https://evil.example"}
        responses = [
            self.service.request("GET", "/agent/health", headers=origin),
            self.service.request("OPTIONS", "/agent/investigations", headers={**origin, "Access-Control-Request-Method": "POST"}),
            self.service.request("POST", "/agent/investigations", self.good, {**origin, "Content-Type": "text/plain"}),
            self.service.request("POST", "/agent/investigations", self.good, {**origin, "Content-Type": "application/json"}),
            self.service.request("GET", "/agent/nothing"),
        ]
        for _, headers, _ in responses:
            self.assertEqual([h for h in headers if h.startswith("access-control-")], [])

    def test_ac_11_1_a_foreign_host_header_is_refused(self) -> None:
        status, _, body = self.service.request("GET", "/agent/health", headers={"Host": "evil.example:8097"})
        self.assertEqual((status, body), (403, {"error": "forbidden_host"}))
        self.assertEqual(self.service.request("GET", "/agent/health", headers={"Host": "localhost:5173"})[0], 200)
        self.assertEqual(self.service.request("GET", "/agent/health", headers={"Host": "[::1]:8097"})[0], 200)


class BlockingModel:
    """Holds the first model call until released, then answers like the stub."""

    def __init__(self) -> None:
        self.entered = threading.Event()
        self.release = threading.Event()
        self._stub = StubModel()

    def complete(self, request: ModelRequest) -> ModelResponse:
        self.entered.set()
        if not self.release.wait(timeout=30):
            raise TimeoutError("test never released the model")
        return self._stub.complete(request)


class ConcurrencyTest(unittest.TestCase):
    def test_ac_11_1_a_second_post_while_one_runs_gets_409_and_the_first_still_finishes(self) -> None:
        model = BlockingModel()
        issue_id = dev_issue_id("BANK_SPLIT_DEPOSIT")
        with RunningService(temp_store(self), model_factory=lambda: model) as service:
            first: list = []
            worker = threading.Thread(target=lambda: first.append(service.post_issue(issue_id)))
            worker.start()
            self.assertTrue(model.entered.wait(timeout=10))
            status, _, body = service.post_issue(issue_id)
            self.assertEqual((status, body), (409, {"error": "busy"}))
            model.release.set()
            worker.join(timeout=30)
            self.assertEqual(first[0][0], 201)
            self.assertEqual(first[0][2]["status"], "ok")
            self.assertEqual(service.post_issue(issue_id)[0], 201)  # the lock was released


class FailureTest(unittest.TestCase):
    def test_ac_11_1_an_unreachable_backend_becomes_an_error_record_without_the_url(self) -> None:
        probe = http.server.ThreadingHTTPServer(("127.0.0.1", 0), http.server.BaseHTTPRequestHandler)
        dead_port = probe.server_address[1]
        probe.server_close()
        url = f"http://127.0.0.1:{dead_port}"
        path = temp_store(self)
        with RunningService(path, base_url=url, use_snapshot=False) as service:
            status, _, record = service.post_issue("iss_abc")
            self.assertEqual(status, 201)
            self.assertEqual(record["status"], "error")
            self.assertEqual(record["error"], "backend_unreachable")
            self.assertTrue(record["conclusion"]["abstain"])
            self.assertEqual(record["conclusion"]["root_cause"], "INSUFFICIENT_EVIDENCE")
            self.assertEqual(record["conclusion"]["recommended_action"], "ESCALATE")
            self.assertEqual(record["conclusion"]["citations"], [])
            self.assertIsNone(record["verification"]["supported"])
            stderr = service.stderr.getvalue()
        for text in (json.dumps(record), path.read_text(encoding="utf-8"), stderr):
            self.assertNotIn(url, text)
            self.assertNotIn("127.0.0.1", text)

    def test_ac_11_1_an_unexpected_exception_becomes_internal_error_without_its_text(self) -> None:
        class Exploding:
            def complete(self, request: ModelRequest) -> ModelResponse:
                raise RuntimeError("secret detail http://10.0.0.1/x")

        path = temp_store(self)
        with RunningService(path, model_factory=Exploding) as service:
            status, _, record = service.post_issue(dev_issue_id("BANK_SPLIT_DEPOSIT"))
            stderr = service.stderr.getvalue()
        self.assertEqual((status, record["status"], record["error"]), (201, "error", "internal_error"))
        self.assertTrue(record["conclusion"]["abstain"])
        for text in (json.dumps(record), path.read_text(encoding="utf-8")):
            self.assertNotIn("secret detail", text)
        self.assertNotIn("secret detail", stderr)
        self.assertIn("RuntimeError", stderr)

    def test_ac_11_1_a_baseline_model_runs_through_the_same_service(self) -> None:
        issue_id = dev_issue_id("BANK_SPLIT_DEPOSIT")
        with RunningService(temp_store(self), model_spec="baseline") as service:
            record = service.post_issue(issue_id)[2]
        self.assertEqual(record["model"], "baseline")
        self.assertEqual(record["conclusion"], cli_conclusion(issue_id))


if __name__ == "__main__":
    unittest.main()
