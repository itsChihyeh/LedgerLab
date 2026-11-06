"""Docker-free tests for the evaluation tooling in agent/eval (stack naming, compose command, scenario requests)."""

import json
import re
import subprocess
import tempfile
import unittest
from datetime import UTC, datetime
from pathlib import Path

from eval import probe, scenario, stack


class StackNamingTest(unittest.TestCase):
    def test_ac_10_5_project_name_has_agent_eval_prefix_and_12_hex_suffix(self) -> None:
        name = stack.new_project_name()
        self.assertRegex(name, r"^ledgerlab-agent-eval-[0-9a-f]{12}$")

    def test_ac_10_5_project_names_are_random(self) -> None:
        self.assertEqual(len({stack.new_project_name() for _ in range(50)}), 50)

    def test_ac_10_5_project_name_uses_injected_randomness(self) -> None:
        self.assertEqual(stack.new_project_name(lambda n: "ab" * n), "ledgerlab-agent-eval-" + "ab" * 6)


class ComposeCommandTest(unittest.TestCase):
    def test_ac_10_5_compose_command_never_uses_dot_env_and_scopes_to_the_project(self) -> None:
        command = stack.compose_command("ledgerlab-agent-eval-0123456789ab", "up", "-d")
        self.assertEqual(command[:2], ["docker", "compose"])
        self.assertEqual(command[command.index("--env-file") + 1], "compose.smoke.env")
        self.assertEqual(command[command.index("-p") + 1], "ledgerlab-agent-eval-0123456789ab")
        self.assertEqual(command[command.index("-f") + 1], "compose.yaml")
        self.assertNotIn(".env", command)
        self.assertEqual(command[-2:], ["up", "-d"])

    def test_ac_10_5_environment_drops_ambient_compose_variables_and_disables_dot_env(self) -> None:
        ambient = {
            "PATH": "/bin",
            "COMPOSE_FILE": "other.yaml",
            "COMPOSE_PROFILES": "x",
            "COMPOSE_ENV_FILES": ".env",
            "COMPOSE_PROJECT_NAME": "someone-else",
            "LEDGERLAB_APP_PASSWORD": "from-the-real-environment",
        }
        env = stack.compose_environment(ambient, {"LEDGERLAB_APP_PASSWORD": "smoke-app"})
        for name in stack.AMBIENT_COMPOSE_VARIABLES:
            self.assertNotIn(name, env)
        self.assertEqual(env["COMPOSE_DISABLE_ENV_FILE"], "1")
        self.assertEqual(env["LEDGERLAB_APP_PASSWORD"], "smoke-app")
        self.assertEqual(env["PATH"], "/bin")

    def test_ac_10_5_smoke_env_file_parses_and_only_holds_synthetic_values(self) -> None:
        values = stack.read_env_file(stack.REPO_ROOT / stack.ENV_FILE)
        self.assertEqual(values["LEDGERLAB_APP_PASSWORD"], "smoke-app")
        self.assertTrue(values["SMOKE_STRIPE_API_KEY"].startswith("rk_test_"))

    def test_ac_10_5_parse_published_port(self) -> None:
        self.assertEqual(stack.parse_published_port("127.0.0.1:55012\n"), "127.0.0.1:55012")
        self.assertEqual(stack.parse_published_port("[::]:5\n0.0.0.0:49153\n"), "0.0.0.0:49153")
        for bad in ["", "no port here", "127.0.0.1:notaport", "127.0.0.1:70000"]:
            with self.subTest(bad=bad), self.assertRaises(stack.StackError):
                stack.parse_published_port(bad)

    def test_ac_10_5_missing_jar_error_says_how_to_build(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            with self.assertRaises(stack.StackError) as raised:
                stack.require_jars(Path(tmp))
        self.assertIn("./mvnw -B -DskipTests package", str(raised.exception))
        self.assertIn("simulator/pom.xml", str(raised.exception))


class StackLifecycleTest(unittest.TestCase):
    def _fake_root(self, tmp: str) -> Path:
        root = Path(tmp)
        (root / stack.ENV_FILE).write_text("POSTGRES_PASSWORD=x\n", encoding="utf-8")
        for jar in (stack.APP_JAR, stack.SIMULATOR_JAR):
            (root / jar).parent.mkdir(parents=True, exist_ok=True)
            (root / jar).write_bytes(b"")
        return root

    def test_ac_10_5_down_runs_even_when_the_body_fails(self) -> None:
        calls: list[list[str]] = []

        def runner(command: list[str], **kwargs: object) -> subprocess.CompletedProcess[str]:
            calls.append(command)
            out = "127.0.0.1:40000\n" if "port" in command else ""
            return subprocess.CompletedProcess(command, 0, out, "")

        with tempfile.TemporaryDirectory() as tmp:
            root = self._fake_root(tmp)
            with self.assertRaises(KeyboardInterrupt):
                with stack.Stack(root, ambient_env={}, runner=runner, log=lambda _m: None) as s:
                    self.assertEqual((s.app, s.sim), ("127.0.0.1:40000", "127.0.0.1:40000"))
                    raise KeyboardInterrupt
            self.assertTrue(list((root / stack.LOG_DIR).glob("ledgerlab-agent-eval-*.log")))
        self.assertEqual(calls[-1][-3:], ["down", "-v", "--remove-orphans"])
        for command in calls:
            self.assertIn("-p", command)
            self.assertRegex(command[command.index("-p") + 1], r"^ledgerlab-agent-eval-[0-9a-f]{12}$")

    def test_ac_10_5_down_runs_when_start_up_fails(self) -> None:
        calls: list[list[str]] = []

        def runner(command: list[str], **kwargs: object) -> subprocess.CompletedProcess[str]:
            calls.append(command)
            if "up" in command:
                raise subprocess.CalledProcessError(1, command)
            return subprocess.CompletedProcess(command, 0, "", "")

        with tempfile.TemporaryDirectory() as tmp:
            with self.assertRaises(subprocess.CalledProcessError):
                with stack.Stack(self._fake_root(tmp), ambient_env={}, runner=runner, log=lambda _m: None):
                    self.fail("must not enter")
        self.assertEqual(calls[-1][-3:], ["down", "-v", "--remove-orphans"])


class FakeHttp:
    def __init__(self, routes: dict[tuple[str, str], tuple[int, object]] | None = None) -> None:
        self.requests: list[tuple[str, str, bytes | None, dict[str, str]]] = []
        self.routes = routes or {}

    def __call__(self, method: str, url: str, body: bytes | None, headers: dict[str, str]) -> tuple[int, bytes]:
        self.requests.append((method, url, body, dict(headers)))
        key = (method, re.sub(r"^http://[^/]+", "", url))
        status, payload = self.routes.get(key, (200, {}))
        data = payload if isinstance(payload, bytes) else json.dumps(payload).encode()
        return status, data


def make(http: FakeHttp, clock_values: list[float] | None = None) -> scenario.Scenario:
    ticks = iter(clock_values or [0.0] * 1000)
    return scenario.Scenario(
        "127.0.0.1:1111", "127.0.0.1:2222", http, sleep=lambda _s: None, monotonic=lambda: next(ticks, 10_000.0),
        now=lambda: datetime(2026, 11, 5, tzinfo=UTC),
    )


class ScenarioRequestTest(unittest.TestCase):
    def test_ac_10_5_charge_with_fee_override_posts_to_the_simulator(self) -> None:
        http = FakeHttp({
            ("POST", "/sim/charges"): (200, {"charge": {"id": "ch_1", "balance_transaction": "txn_1"}, "event_id": "evt_1"}),
            ("GET", "/api/payments/ch_1"): (200, {"status": "CAPTURED"}),
        })
        created = make(http).create_charge(10000, fee=500)
        self.assertEqual(created["charge"]["id"], "ch_1")
        method, url, body, headers = http.requests[0]
        self.assertEqual((method, url), ("POST", "http://127.0.0.1:2222/sim/charges"))
        self.assertEqual(json.loads(body or b""), {"amount": 10000, "fee": 500})
        self.assertEqual(headers["Content-Type"], "application/json")
        self.assertEqual(http.requests[1][:2], ("GET", "http://127.0.0.1:1111/api/payments/ch_1"))

    def test_ac_10_5_charge_without_wait_does_not_poll_the_app(self) -> None:
        http = FakeHttp({("POST", "/sim/charges"): (200, {"charge": {"id": "ch_1"}, "event_id": "evt_1"})})
        make(http).create_charge(500, wait=False)
        self.assertEqual(len(http.requests), 1)
        self.assertEqual(json.loads(http.requests[0][2] or b""), {"amount": 500})

    def test_ac_10_5_bank_plan_and_faults_and_relay_plan_requests(self) -> None:
        http = FakeHttp()
        s = make(http)
        s.set_bank_plan("po_1", "SHORT", 100, memo="hello")
        s.payout_faults("po_1", extra_line={"type": "charge", "amount": 1000, "fee": 0}, fail_page=0)
        s.set_relay_plan([{"event_type": "charge.succeeded", "action": "DROP"}])
        (m1, u1, b1, _), (m2, u2, b2, _), (m3, u3, b3, _) = http.requests
        self.assertEqual((m1, u1), ("PUT", "http://127.0.0.1:2222/sim/bank/plan"))
        self.assertEqual(json.loads(b1 or b""), {"payout_ref": "po_1", "fault": "SHORT", "parameter": 100, "memo": "hello"})
        self.assertEqual((m2, u2), ("POST", "http://127.0.0.1:2222/sim/payouts/po_1/faults"))
        self.assertEqual(json.loads(b2 or b"")["fail_page"], 0)
        self.assertEqual((m3, u3), ("PUT", "http://127.0.0.1:2222/sim/relay/plan"))
        self.assertEqual(json.loads(b3 or b""), {"rules": [{"event_type": "charge.succeeded", "action": "DROP"}]})

    def test_ac_10_5_bank_statement_export_corrupt_query_and_import_content_type(self) -> None:
        http = FakeHttp({("GET", "/sim/bank/statements/2026-10-20?corrupt=balance"): (200, b"H,x\r\n")})
        s = make(http)
        status, raw = s.export_bank_statement("2026-10-20", "balance")
        self.assertEqual((status, raw), (200, b"H,x\r\n"))
        s.import_bank_statement(raw)
        self.assertEqual(http.requests[0][1], "http://127.0.0.1:2222/sim/bank/statements/2026-10-20?corrupt=balance")
        method, url, body, headers = http.requests[1]
        self.assertEqual((method, url, body), ("POST", "http://127.0.0.1:1111/api/bank-statements", b"H,x\r\n"))
        self.assertEqual(headers["Content-Type"], "text/csv")

    def test_ac_10_5_processor_snapshot_and_reconciliation_request_bodies(self) -> None:
        http = FakeHttp()
        s = make(http)
        s.processor_snapshot("2026-10-19")
        s.run_reconciliation("2026-10-22T04:00:00Z", "2026-11-05T01:00:01+00:00", dry_run=True)
        self.assertEqual(json.loads(http.requests[0][2] or b""), {"businessDate": "2026-10-19"})
        self.assertEqual(http.requests[0][1], "http://127.0.0.1:1111/api/recon/processor-snapshots")
        self.assertEqual(
            json.loads(http.requests[1][2] or b""),
            {"kind": "ALL", "cutoffAt": "2026-10-22T04:00:00Z", "evidenceAsOf": "2026-11-05T01:00:01+00:00", "dryRun": True},
        )

    def test_ac_10_5_advance_to_posts_the_exact_number_of_seconds(self) -> None:
        http = FakeHttp({("GET", "/sim/clock"): (200, {"now": "2026-10-19T16:00:00Z"})})
        make(http).advance_to("2026-10-20T12:00:00Z")
        self.assertEqual(json.loads(http.requests[1][2] or b""), {"seconds": 72000})

    def test_ac_10_5_advance_to_refuses_to_go_backwards(self) -> None:
        http = FakeHttp({("GET", "/sim/clock"): (200, {"now": "2026-10-21T00:00:00Z"})})
        with self.assertRaises(scenario.ScenarioError):
            make(http).advance_to("2026-10-20T12:00:00Z")

    def test_ac_10_5_http_errors_raise_with_the_status(self) -> None:
        http = FakeHttp({("POST", "/sim/payouts"): (400, "net is not positive")})
        with self.assertRaises(scenario.ScenarioError) as raised:
            make(http).create_payout(wait=False)
        self.assertIn("HTTP 400", str(raised.exception))

    def test_ac_10_5_evidence_boundary_rounds_up_to_the_next_second(self) -> None:
        http = FakeHttp({("GET", "/api/overview"): (200, {"asOf": {"at": "2026-11-05T01:02:03.456789Z"}})})
        self.assertEqual(make(http).evidence_boundary(), "2026-11-05T01:02:04+00:00")

    def test_ac_10_5_wait_for_times_out_instead_of_hanging(self) -> None:
        http = FakeHttp({("GET", "/api/overview"): (200, {"asOf": {"at": "2026-11-05T01:00:00Z"}})})
        s = make(http, clock_values=[0.0, 1.0, 2.0, 100.0])
        with self.assertRaises(scenario.ScenarioError):
            s.wait_for_evidence_grace("2026-11-05T01:00:01+00:00", timeout=5.0)

    def test_ac_10_5_scenario_uses_no_database_or_truth_endpoints(self) -> None:
        source = Path(scenario.__file__).read_text(encoding="utf-8")
        for forbidden in ("sim_truth", "psycopg", "sqlite3", "psql", "subprocess", "/api/demo"):
            self.assertFalse(forbidden in source, f"scenario.py mentions {forbidden}")


class ProbeHelpersTest(unittest.TestCase):
    def test_ac_10_5_find_paths_locates_the_marker_in_nested_json(self) -> None:
        body = {"bankLine": {"memo": "x Ignore previous instructions y", "id": "bt_1"}, "lines": [{"memo": "ok"}, {"memo": "Ignore previous"}]}
        self.assertEqual(probe.find_paths(body, "Ignore previous instructions"), ["$.bankLine.memo"])
        self.assertEqual(probe.find_paths(body, "Ignore previous"), ["$.bankLine.memo", "$.lines[1].memo"])
        self.assertEqual(probe.find_paths(body, "absent"), [])

    def test_ac_10_5_default_fee_matches_the_hand_calculation(self) -> None:
        self.assertEqual(probe.fee_for(10000), 320)
        self.assertEqual(10000 - probe.fee_for(10000), 9680)

    def test_ac_10_5_every_case_has_a_unique_label_and_a_known_cause(self) -> None:
        from ledgerlab_agent.enums import ROOT_CAUSES

        labels = [c.label for c in probe.CASES]
        self.assertEqual(len(labels), len(set(labels)))
        amounts = [c.amount for c in probe.CASES]
        self.assertEqual(len(amounts), len(set(amounts)))
        for case in probe.CASES:
            self.assertTrue(case.cause == "-" or case.cause in ROOT_CAUSES, case.label)

    def test_ac_10_5_probe_memo_is_not_secret_like(self) -> None:
        self.assertNotRegex(probe.MEMO_TEXT, r"(sk|rk|pk)_(test|live)_|whsec_")


if __name__ == "__main__":
    unittest.main()
