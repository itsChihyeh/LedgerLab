"""AC-12.6: the investigation service's Prometheus metrics (docs/design/observability.md section 3)."""

import http.server
import re
import threading
import unittest

from ledgerlab_agent_service.metrics import CONTENT_TYPE, ServiceMetrics, escape_label_value
from ledgerlab_agent_service.runner import Runner

from tests.support import FixedClock, RunningService, dev_issue_id, temp_store
from tests.test_service import BlockingModel

SAMPLE = re.compile(
    r'^(?P<name>[a-zA-Z_:][a-zA-Z0-9_:]*)(?:\{(?P<labels>[a-zA-Z_][a-zA-Z0-9_]*="(?:[^"\\\n]|\\.)*"'
    r'(?:,[a-zA-Z_][a-zA-Z0-9_]*="(?:[^"\\\n]|\\.)*")*)\})? (?P<value>[0-9]+(?:\.[0-9]+)?(?:e[+-]?[0-9]+)?)$'
)


def parse(text: str) -> dict[str, float]:
    """Sample lines as {"name{labels}": value}; fails the test on any line that is not valid exposition text."""
    samples: dict[str, float] = {}
    typed: set[str] = set()
    helped: set[str] = set()
    for line in text.splitlines():
        if line.startswith("# HELP "):
            helped.add(line.split()[2])
        elif line.startswith("# TYPE "):
            name = line.split()[2]
            assert name in helped, f"TYPE before HELP for {name}"
            typed.add(name)
        else:
            match = SAMPLE.match(line)
            assert match, f"not a sample line: {line!r}"
            base = re.sub(r"_(bucket|sum|count)$", "", match["name"])
            assert match["name"] in typed or base in typed, f"sample before TYPE: {line!r}"
            key = match["name"] + (f"{{{match['labels']}}}" if match["labels"] else "")
            assert key not in samples, f"duplicate sample {key}"
            samples[key] = float(match["value"])
    assert text.endswith("\n")
    return samples


def dead_backend_runner() -> Runner:
    probe = http.server.ThreadingHTTPServer(("127.0.0.1", 0), http.server.BaseHTTPRequestHandler)
    port = probe.server_address[1]
    probe.server_close()
    counter = iter(range(5000, 6000))
    return Runner(
        "stub",
        base_url=f"http://127.0.0.1:{port}",
        clock=FixedClock(),
        random_bits=lambda bits: next(counter),
    )


class MetricsEndpointTest(unittest.TestCase):
    def test_ac_12_6_two_ok_runs_and_an_unreachable_backend_are_counted_exactly(self) -> None:
        readings = iter([0.0, 0.3, 10.0, 12.5, 20.0, 80.0])  # durations 0.3, 2.5 and 60 seconds
        issue_id = dev_issue_id("BANK_SPLIT_DEPOSIT")
        with RunningService(temp_store(self), monotonic=lambda: next(readings)) as service:
            first = service.post_issue(issue_id)[2]
            second = service.post_issue(issue_id)[2]
            service.service.runner = dead_backend_runner()
            third = service.post_issue("iss_abc")[2]
            status, headers, text = service.request_text("/agent/metrics")
        self.assertEqual((first["status"], second["status"], third["status"]), ("ok", "ok", "error"))
        self.assertEqual(status, 200)
        self.assertEqual(headers["content-type"], CONTENT_TYPE)
        tool_calls = sum(len(r["toolCalls"]) for r in (first, second, third))
        self.assertGreater(tool_calls, 0)
        expected = {
            'ledgerlab_agent_investigations_total{status="ok"}': 2,
            'ledgerlab_agent_investigations_total{status="budget_exhausted"}': 0,
            'ledgerlab_agent_investigations_total{status="error"}': 1,
            'ledgerlab_agent_investigation_duration_seconds_bucket{le="0.01"}': 0,
            'ledgerlab_agent_investigation_duration_seconds_bucket{le="0.05"}': 0,
            'ledgerlab_agent_investigation_duration_seconds_bucket{le="0.1"}': 0,
            'ledgerlab_agent_investigation_duration_seconds_bucket{le="0.5"}': 1,
            'ledgerlab_agent_investigation_duration_seconds_bucket{le="1"}': 1,
            'ledgerlab_agent_investigation_duration_seconds_bucket{le="2"}': 1,
            'ledgerlab_agent_investigation_duration_seconds_bucket{le="5"}': 2,
            'ledgerlab_agent_investigation_duration_seconds_bucket{le="10"}': 2,
            'ledgerlab_agent_investigation_duration_seconds_bucket{le="30"}': 2,
            'ledgerlab_agent_investigation_duration_seconds_bucket{le="+Inf"}': 3,
            "ledgerlab_agent_investigation_duration_seconds_sum": 0.0 + 0.3 + 2.5 + 60.0,
            "ledgerlab_agent_investigation_duration_seconds_count": 3,
            "ledgerlab_agent_tool_calls_total": tool_calls,
        }
        self.assertEqual(parse(text), expected)

    def test_ac_12_6_the_text_is_valid_exposition_format_with_monotone_buckets(self) -> None:
        metrics = ServiceMetrics()
        for status, seconds, calls in [("ok", 0.004, 3), ("ok", 0.01, 1), ("error", 31.0, 0), ("budget_exhausted", 7, 9)]:
            metrics.observe_investigation(status, seconds, calls)
        text = metrics.render()
        samples = parse(text)
        prefix = 'ledgerlab_agent_investigation_duration_seconds_bucket{le="'
        buckets = [v for k, v in samples.items() if k.startswith(prefix)]
        self.assertEqual(len(buckets), 10)
        self.assertEqual(buckets, sorted(buckets))
        self.assertEqual(buckets[-1], samples["ledgerlab_agent_investigation_duration_seconds_count"])
        self.assertEqual(buckets[0], 2)  # 0.004 and 0.01: the upper bound is inclusive
        self.assertEqual(buckets[1], 2)
        self.assertEqual(samples["ledgerlab_agent_tool_calls_total"], 13)
        self.assertEqual(text.count("# TYPE "), 3)

    def test_ac_12_6_an_unknown_status_is_counted_as_error_so_labels_stay_closed(self) -> None:
        metrics = ServiceMetrics()
        metrics.observe_investigation("iss_123", 1.0, 0)
        self.assertIn('ledgerlab_agent_investigations_total{status="error"} 1', metrics.render())
        self.assertNotIn("iss_123", metrics.render())

    def test_ac_12_6_label_values_are_escaped_per_the_format(self) -> None:
        self.assertEqual(escape_label_value('a\\b"c\nd'), 'a\\\\b\\"c\\nd')

    def test_ac_12_6_a_fresh_service_exposes_zeroes(self) -> None:
        with RunningService(temp_store(self)) as service:
            text = service.request_text("/agent/metrics")[2]
        samples = parse(text)
        self.assertEqual(set(samples.values()), {0.0})


class MetricsNotCountedTest(unittest.TestCase):
    def test_ac_12_6_a_409_and_rejected_requests_do_not_change_the_counts(self) -> None:
        model = BlockingModel()
        readings = iter([0.0, 1.0])
        issue_id = dev_issue_id("BANK_SPLIT_DEPOSIT")
        with RunningService(
            temp_store(self), model_factory=lambda: model, monotonic=lambda: next(readings)
        ) as service:
            zero = service.request_text("/agent/metrics")[2]
            first: list = []
            worker = threading.Thread(target=lambda: first.append(service.post_issue(issue_id)))
            worker.start()
            self.assertTrue(model.entered.wait(timeout=10))
            self.assertEqual(service.post_issue(issue_id)[0], 409)
            self.assertEqual(service.post_issue("not-an-issue")[0], 400)
            self.assertEqual(service.request("POST", "/agent/investigations", b"{}", {"Content-Type": "text/plain"})[0], 415)
            self.assertEqual(service.request_text("/agent/metrics")[2], zero)  # nothing finished yet
            model.release.set()
            worker.join(timeout=30)
            self.assertEqual(first[0][0], 201)
            after = parse(service.request_text("/agent/metrics")[2])
        self.assertEqual(after['ledgerlab_agent_investigations_total{status="ok"}'], 1)
        self.assertEqual(after["ledgerlab_agent_investigation_duration_seconds_count"], 1)
        self.assertEqual(after['ledgerlab_agent_investigation_duration_seconds_bucket{le="1"}'], 1)

    def test_ac_12_6_metrics_endpoint_rejects_a_query_has_no_cors_headers_and_checks_host(self) -> None:
        with RunningService(temp_store(self)) as service:
            status, headers, body = service.request("GET", "/agent/metrics?x=1", headers={"Origin": "https://evil.example"})
            self.assertEqual((status, body), (400, {"error": "invalid_request"}))
            self.assertEqual([h for h in headers if h.startswith("access-control-")], [])
            status, headers, _ = service.request_text("/agent/metrics", headers={"Origin": "https://evil.example"})
            self.assertEqual(status, 200)
            self.assertEqual([h for h in headers if h.startswith("access-control-")], [])
            status, _, body = service.request("GET", "/agent/metrics", headers={"Host": "evil.example:8097"})
            self.assertEqual((status, body), (403, {"error": "forbidden_host"}))
            self.assertEqual(service.request_text("/agent/metrics", headers={"Host": "localhost:5173"})[0], 200)
            self.assertEqual(service.request("POST", "/agent/metrics", b"{}", {"Content-Type": "application/json"})[0], 404)


if __name__ == "__main__":
    unittest.main()
