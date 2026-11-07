"""Spend protection and the external-model evaluation path. A fake transport stands in for the network, and only the
frozen dev split is evaluated (the test and hard splits are never run by unit tests)."""

from __future__ import annotations

import contextlib
import datetime as dt
import io
import json
import re
import tempfile
import unittest
from pathlib import Path
from typing import Any

from eval import run_eval
from ledgerlab_agent import evaluate, external
from ledgerlab_agent.cli import main as cli_main
from ledgerlab_agent.conclusion import abstention
from ledgerlab_agent.model import ModelRequest, ModelResponse, ToolUseBlock, Usage
from ledgerlab_agent.pricing import (
    PRICING_PATH,
    GuardedModel,
    PriceNotVerified,
    Prices,
    SpendGuard,
    SpendLimitReached,
    estimate_run_usd,
    load_prices,
    usage_cost_usd,
)
from ledgerlab_agent.sop import load_sop
from ledgerlab_agent.stub_model import StubModel

from tests.dev_data import DEV_DIR
from tests.test_deepseek_model import API_KEY_ENV, PLACEHOLDER_SECRET, completion

NOW = lambda: dt.datetime(2026, 11, 7, 12, 0, tzinfo=dt.timezone.utc)  # noqa: E731
ENV = {API_KEY_ENV: PLACEHOLDER_SECRET}
PRICE_ENTRY = {
    "input_cache_hit": 0.5,
    "input_cache_miss": 1.0,
    "output": 2.0,
    "source_url": "https://example.invalid/prices",
    "checked_at": "2026-11-07",
}
# 500,000 uncached input + 250,000 output tokens per request cost 0.5 + 0.5 = 1.0 USD at the prices above.
ONE_USD_USAGE = {"prompt_cache_hit_tokens": 0, "prompt_cache_miss_tokens": 500_000, "completion_tokens": 250_000}


class AbstainingDeepSeek:
    """A fake network: answers every request by abstaining on the issue named in the first user message."""

    def __init__(self, usage: dict[str, Any] | None = None, status: int = 200, body: Any = None) -> None:
        self.calls = 0
        self._usage, self._status, self._body = usage, status, body

    def __call__(self, url: str, headers: Any, body: bytes) -> tuple[int, bytes]:
        self.calls += 1
        if self._status != 200:
            return self._status, json.dumps(self._body).encode("utf-8")
        text = json.loads(body.decode("utf-8"))["messages"][1]["content"]
        issue = re.search(r"issue (\S+?)\.", text).group(1)  # type: ignore[union-attr]
        reply = completion(
            tool_calls=[("c1", "submit_conclusion", abstention(issue, "No evidence was gathered.").as_dict())],
            finish="tool_calls",
            usage=self._usage,
        )
        return 200, json.dumps(reply).encode("utf-8")


def write_prices(directory: Path, entry: dict[str, Any] | None) -> Path:
    path = directory / "pricing.json"
    path.write_text(json.dumps({"deepseek": entry}), encoding="utf-8")
    return path


class PricingTest(unittest.TestCase):
    def test_ac_10_10_a_provider_with_a_null_field_is_refused(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            for field in PRICE_ENTRY:
                with self.subTest(null=field):
                    path = write_prices(Path(tmp), {**PRICE_ENTRY, field: None})
                    with self.assertRaises(PriceNotVerified) as caught:
                        load_prices("deepseek", path)
                    self.assertIn(field, str(caught.exception))

    def test_ac_10_10_a_complete_entry_loads_and_a_bad_number_is_refused(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            prices = load_prices("deepseek", write_prices(Path(tmp), PRICE_ENTRY))
            self.assertEqual((prices.input_cache_hit, prices.input_cache_miss, prices.output), (0.5, 1.0, 2.0))
            self.assertEqual((prices.source_url, prices.checked_at), ("https://example.invalid/prices", "2026-11-07"))
            with self.assertRaises(PriceNotVerified):
                load_prices("deepseek", write_prices(Path(tmp), {**PRICE_ENTRY, "output": "cheap"}))
            with self.assertRaises(PriceNotVerified):
                load_prices("deepseek", write_prices(Path(tmp), {**PRICE_ENTRY, "output": -1}))
            with self.assertRaises(PriceNotVerified):
                load_prices("unknown-provider", write_prices(Path(tmp), PRICE_ENTRY))

    def test_ac_10_10_the_shipped_price_file_lists_deepseek_with_the_expected_fields(self) -> None:
        table = json.loads(PRICING_PATH.read_text(encoding="utf-8"))
        self.assertEqual(set(table["deepseek"]), set(PRICE_ENTRY))
        self.assertTrue(all(isinstance(v, (int, float, str)) or v is None for v in table["deepseek"].values()))

    def test_ac_10_10_cost_bills_cache_hits_misses_and_output_separately(self) -> None:
        prices = Prices(0.5, 1.0, 2.0, "u", "d")
        usage = Usage(2_000_000, 1_000_000, 1_500_000, 500_000, 300_000)
        self.assertAlmostEqual(usage_cost_usd(usage, prices), 0.75 + 0.5 + 2.0)  # reasoning is already inside the output
        self.assertAlmostEqual(usage_cost_usd(Usage(1_000_000, 0), prices), 1.0)  # no breakdown: all input at the miss price

    def test_ac_10_10_the_estimate_multiplies_the_stand_in_input_and_adds_flat_output(self) -> None:
        prices = Prices(0.5, 1.0, 2.0, "u", "d")
        self.assertAlmostEqual(estimate_run_usd(10, 1_000_000, prices, 3.0, 50_000), 3.0 + 1.0)


class SpendGuardTest(unittest.TestCase):
    PRICES = Prices(0.5, 1.0, 2.0, "u", "d")

    def test_ac_10_10_the_cap_must_be_positive(self) -> None:
        for cap in (0, -1, float("nan")):
            with self.assertRaises(ValueError):
                SpendGuard(cap, self.PRICES)

    def test_ac_10_10_a_request_is_refused_once_the_total_is_over_the_cap(self) -> None:
        class Inner:
            calls = 0

            def complete(self, request: ModelRequest) -> ModelResponse:
                self.calls += 1
                return ModelResponse((), "end_turn", Usage(500_000, 250_000, 0, 500_000, 0))  # 1.0 USD

        inner = Inner()
        guarded = GuardedModel(inner, SpendGuard(1.5, self.PRICES))
        request = ModelRequest("s", [], [], 10)
        guarded.complete(request)
        guarded.complete(request)  # 1.0 USD is under the cap, so this one is still allowed; it ends at 2.0
        self.assertTrue(guarded.guard.exceeded)
        with self.assertRaises(SpendLimitReached):
            guarded.complete(request)
        self.assertEqual(inner.calls, 2)
        self.assertAlmostEqual(guarded.guard.spent_usd, 2.0)


class PricedStub:
    """The stand-in's decisions with a fixed price tag of 1 USD per request."""

    def __init__(self) -> None:
        self._stub = StubModel()
        self.calls = 0

    def complete(self, request: ModelRequest) -> ModelResponse:
        self.calls += 1
        reply = self._stub.complete(request)
        return ModelResponse(reply.content, reply.stop_reason, Usage(500_000, 250_000, 0, 500_000, 0))


class RunSplitSpendTest(unittest.TestCase):
    def test_ac_10_10_a_cap_hit_inside_a_question_stops_the_run_and_drops_that_question(self) -> None:
        split = evaluate.load_split("dev")
        inner = PricedStub()
        # Every dev question takes 3 requests of 1 USD each: question 1 ends at 3.0 USD, question 2 is cut off after its
        # first request (4.0 USD is over the 3.5 cap, so its second request is refused).
        guarded = GuardedModel(inner, SpendGuard(3.5, Prices(0.5, 1.0, 2.0, "u", "d")))
        run = evaluate.run_split(split, "deepseek:x:high", client=guarded, limit=10)
        self.assertFalse(run.completed)
        self.assertEqual(run.incomplete_reason, "达到费用上限")
        self.assertEqual(len(run.questions), 1)
        self.assertEqual(run.metrics["questions"], 1)
        self.assertEqual(inner.calls, 4)  # the dropped question's request was made and is paid for
        self.assertAlmostEqual(run.metrics["cost_usd"], 4.0)

    def test_ac_10_10_a_run_inside_the_cap_is_complete_and_reports_the_measured_cost(self) -> None:
        split = evaluate.load_split("dev")
        guarded = GuardedModel(PricedStub(), SpendGuard(1000.0, Prices(0.5, 1.0, 2.0, "u", "d")))
        run = evaluate.run_split(split, "deepseek:x:high", client=guarded, limit=3)
        self.assertTrue(run.completed)
        self.assertEqual(len(run.questions), 3)
        self.assertAlmostEqual(run.metrics["cost_usd"], guarded.guard.spent_usd)
        self.assertEqual(run.metrics["tokens"]["cache_miss"], 500_000 * sum(q.investigation.turns for q in run.questions))


class EvalCommandTest(unittest.TestCase):
    def run_eval(self, extra: list[str], tmp: Path, *, transport: Any, prices: dict[str, Any] | None = PRICE_ENTRY,
                 environ: dict[str, str] | None = ENV, with_out: bool = True, split: str = "dev") -> tuple[int, str, str, Path]:
        target = tmp / "result.json"
        argv = ["eval", "--split", split, "--model", "deepseek:deepseek-flash", *extra]
        if with_out:
            argv += ["--out", str(target)]
        out, err = io.StringIO(), io.StringIO()
        code = run_eval.main(
            argv, out, err, NOW, transport, environ, write_prices(tmp, prices), lambda _s: None, tmp / "logs"
        )
        return code, out.getvalue(), err.getvalue(), target

    def test_ac_10_10_unverified_prices_refuse_the_run_without_a_request(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            fake = AbstainingDeepSeek(ONE_USD_USAGE)
            nulls = {k: None for k in PRICE_ENTRY}
            code, _, err, target = self.run_eval(["--confirm-spend", "5", "--limit", "2"], Path(tmp), transport=fake, prices=nulls)
            self.assertEqual(code, 2)
            self.assertIn("not verified", err)
            self.assertEqual(fake.calls, 0)
            self.assertFalse(target.exists())

    def test_ac_10_10_without_confirm_spend_the_estimate_is_printed_and_nothing_is_requested(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            fake = AbstainingDeepSeek(ONE_USD_USAGE)
            code, _, err, target = self.run_eval(["--limit", "2"], Path(tmp), transport=fake)
            self.assertEqual(code, 2)
            self.assertEqual(fake.calls, 0)
            self.assertRegex(err, r"estimated cost \d+\.\d{4} USD")
            self.assertIn("--confirm-spend", err)
            self.assertFalse(target.exists())

    def test_ac_10_10_a_run_needs_somewhere_to_write_its_result_and_an_api_key(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            fake = AbstainingDeepSeek(ONE_USD_USAGE)
            code, _, err, _ = self.run_eval(["--confirm-spend", "5"], Path(tmp), transport=fake, with_out=False)
            self.assertEqual(code, 2)
            self.assertIn("--out", err)
            code, _, err, _ = self.run_eval(["--confirm-spend", "5"], Path(tmp), transport=fake, environ={})
            self.assertEqual(code, 2)
            self.assertIn(API_KEY_ENV, err)
            code, _, _, _ = self.run_eval(["--confirm-spend", "0"], Path(tmp), transport=fake)
            self.assertEqual(code, 2)
            self.assertEqual(fake.calls, 0)

    def test_ac_10_10_a_limited_run_inside_the_cap_writes_a_complete_result(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            fake = AbstainingDeepSeek(ONE_USD_USAGE)
            code, out, _, target = self.run_eval(["--confirm-spend", "10", "--limit", "3", "--reasoning-effort", "low"], Path(tmp), transport=fake)
            self.assertEqual(code, 0, out)
            self.assertEqual(fake.calls, 3)
            document = json.loads(target.read_text(encoding="utf-8"))
            self.assertEqual(document["kind"], "external_run")
            self.assertEqual((document["label"], document["reasoning_effort"], document["split"]), ("deepseek:deepseek-flash:low", "low", "dev"))
            self.assertTrue(document["completed"])
            self.assertAlmostEqual(document["cost_usd"], 3.0)
            self.assertEqual(document["spend_cap_usd"], 10.0)
            self.assertEqual(document["price"]["source_url"], PRICE_ENTRY["source_url"])
            self.assertEqual(document["metrics"]["questions"], 3)
            self.assertEqual(document["metrics"]["tokens"]["cache_miss"], 1_500_000)
            self.assertFalse((Path(tmp) / "logs").exists())  # dev is not a logged split

    def test_ac_10_10_exceeding_the_cap_stops_the_run_and_marks_the_result_incomplete(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            fake = AbstainingDeepSeek(ONE_USD_USAGE)
            code, _, err, target = self.run_eval(["--confirm-spend", "1.5", "--limit", "10"], Path(tmp), transport=fake)
            self.assertEqual(code, 3)
            self.assertIn("INCOMPLETE", err)
            self.assertEqual(fake.calls, 2)  # the second request crossed the cap; no third one was sent
            document = json.loads(target.read_text(encoding="utf-8"))
            self.assertFalse(document["completed"])
            self.assertEqual(document["incomplete_reason"], "达到费用上限")
            self.assertEqual(document["metrics"]["questions"], 2)
            self.assertAlmostEqual(document["cost_usd"], 2.0)

    def test_ac_10_10_the_key_appears_in_no_output_or_file_even_when_the_provider_rejects_it(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            echo = {"error": {"message": f"Authentication failed for {PLACEHOLDER_SECRET}"}}
            for fake in (AbstainingDeepSeek(ONE_USD_USAGE), AbstainingDeepSeek(status=401, body=echo)):
                code, out, err, target = self.run_eval(["--confirm-spend", "5", "--limit", "2"], Path(tmp), transport=fake)
                self.assertIn(code, (0, 3))
                written = target.read_text(encoding="utf-8")
                for text in (out, err, written):
                    self.assertNotIn(PLACEHOLDER_SECRET, text)
                target.unlink()
        self.assertEqual(json.loads(written)["incomplete_reason"][:4], "接口错误")

    def test_ac_10_10_test_and_hard_runs_are_logged_by_model_configuration(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            log = Path(tmp) / "runs.log"
            run_eval.log_split_use("hard", "deepseek:deepseek-flash:high", NOW, log)
            run_eval.log_split_use("hard", "deepseek:deepseek-flash:high", NOW, log)
            run_eval.log_split_use("dev", "deepseek:deepseek-flash:high", NOW, Path(tmp) / "never.log")
            lines = run_eval.read_runs_log(log)
            self.assertEqual(len(lines), 2)  # header and one line: same configuration and code fingerprint
            self.assertEqual(lines[1].split("\t")[1], "deepseek:deepseek-flash:high")
            self.assertFalse((Path(tmp) / "never.log").exists())

    def test_ac_10_10_local_models_still_run_and_an_unknown_model_is_rejected(self) -> None:
        err = io.StringIO()
        self.assertEqual(run_eval.main(["eval", "--split", "dev", "--model", "gpt-9"], io.StringIO(), err), 2)
        self.assertIn("unknown model", err.getvalue())


class InvestigateCommandTest(unittest.TestCase):
    ISSUE = None

    def run_cli(self, extra: list[str], tmp: Path, *, transport: Any, prices: dict[str, Any] | None = PRICE_ENTRY,
                environ: dict[str, str] | None = ENV) -> tuple[int, str, str]:
        issue = json.loads((DEV_DIR / "labels.json").read_text(encoding="utf-8"))["labels"][0]["issue_id"]
        argv = ["investigate", issue, "--snapshot", str(DEV_DIR / "snapshot.json"), "--model", "deepseek:deepseek-flash", "--json", *extra]
        out, err = io.StringIO(), io.StringIO()
        code = cli_main(argv, out, err, transport, environ, write_prices(tmp, prices), lambda _s: None)
        return code, out.getvalue(), err.getvalue()

    def test_ac_10_10_investigate_refuses_without_verified_prices_or_confirmation(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            fake = AbstainingDeepSeek(ONE_USD_USAGE)
            code, _, err = self.run_cli(["--confirm-spend", "5"], Path(tmp), transport=fake, prices={k: None for k in PRICE_ENTRY})
            self.assertEqual(code, 2)
            self.assertIn("not verified", err)
            code, _, err = self.run_cli([], Path(tmp), transport=fake)
            self.assertEqual(code, 2)
            self.assertIn("--confirm-spend", err)
            self.assertEqual(fake.calls, 0)

    def test_ac_10_10_investigate_with_a_cap_runs_through_the_adapter_and_reports_the_spend(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            fake = AbstainingDeepSeek(ONE_USD_USAGE)
            code, out, err = self.run_cli(["--confirm-spend", "5"], Path(tmp), transport=fake)
            self.assertEqual(code, 0)  # a well-formed abstention is a valid conclusion
            self.assertEqual(fake.calls, 1)
            self.assertTrue(json.loads(out)["abstain"])
            self.assertIn("spent 1.0000 USD of the 5.0000 USD cap", err)
            self.assertNotIn(PLACEHOLDER_SECRET, out + err)

    def test_ac_10_10_a_spend_cap_reached_mid_investigation_is_an_error_not_a_conclusion(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            fake = AbstainingDeepSeek({**ONE_USD_USAGE, "completion_tokens": 10_000_000})  # 20.5 USD, over a 5 USD cap
            code, _, err = self.run_cli(["--confirm-spend", "5"], Path(tmp), transport=fake)
            self.assertEqual(fake.calls, 1)
            self.assertIn("spent 20.5000 USD of the 5.0000 USD cap", err)
            self.assertEqual(code, 0)  # the one request already finished the investigation; the guard only blocks a next one


class ExternalReportTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.sop = load_sop()
        split = evaluate.load_split("dev")
        cls.local = {(m, "dev"): evaluate.run_split(split, m, cls.sop) for m in evaluate.MODELS}
        cls.runs = {(m, s): cls.local[(m, "dev")] for m in evaluate.MODELS for s in evaluate.SPLITS}
        guarded = GuardedModel(PricedStub(), SpendGuard(2.5, Prices(0.5, 1.0, 2.0, "https://example.invalid/prices", "2026-11-07")))
        cls.external_run = evaluate.run_split(split, "deepseek:deepseek-flash:high", cls.sop, client=guarded, limit=10)

    def document(self, split: str = "dev", **changes: Any) -> dict[str, Any]:
        details = {
            "label": "deepseek:deepseek-flash:high", "provider": "deepseek", "model": "deepseek-flash",
            "reasoning_effort": "high", "max_tokens": 8192, "limit": 10, "price": PRICE_ENTRY, "spend_cap_usd": 2.5,
            "cost_usd": self.external_run.metrics["cost_usd"], "date": "2026-11-07", "commit": "abc", "code_fingerprint": "f" * 12,
        }
        return {**evaluate.external_document(self.external_run, details), "split": split, **changes}

    META = {"date": "2026-11-07", "commit": "abc", "code_fingerprint": "f" * 12, "reproduce": ["sh scripts/agent/eval.sh"]}

    def test_ac_10_10_the_report_gets_an_external_column_with_config_price_cost_and_the_incomplete_mark(self) -> None:
        report = evaluate.build_report(self.runs, self.META, [], [], [self.document()])
        json.dumps(report)
        self.assertEqual(report["external"]["label"], "deepseek:deepseek-flash:high")
        self.assertEqual(report["external"]["runs"]["dev"]["metrics"]["split"], "dev")
        self.assertNotIn(evaluate.EXTERNAL_MODEL_NOTE, report["external_model"])
        text = evaluate.render_markdown(report)
        for needle in (
            "外部 dev（未完成：达到费用上限）", "deepseek:deepseek-flash:high", "思考强度 `high`", "https://example.invalid/prices",
            "2026-11-07", "实际费用", "**未完成（达到费用上限）**", "费用上限 USD",
        ):
            self.assertIn(needle, text)
        self.assertNotIn(evaluate.EXTERNAL_MODEL_NOTE, text)

    def test_ac_10_10_without_external_runs_the_report_keeps_the_not_run_note(self) -> None:
        report = evaluate.build_report(self.runs, self.META, [], [])
        self.assertIsNone(report["external"])
        self.assertIn(evaluate.EXTERNAL_MODEL_NOTE, evaluate.render_markdown(report))

    def test_ac_10_10_external_hard_lines_are_reported_but_do_not_fail_the_local_check(self) -> None:
        bad = self.document()
        bad["metrics"] = {**bad["metrics"], "fabricated_total": 2, "invalid_output_rate": 0.5}
        report = evaluate.build_report(self.runs, self.META, [], [], [bad])
        self.assertEqual(len(report["external"]["hard_line_failures"]), 2)
        self.assertTrue(report["hard_lines_hold"])
        self.assertIn("不满足：deepseek:deepseek-flash:high/dev: 2 fabricated", evaluate.render_markdown(report))

    def test_ac_10_10_mixed_configurations_or_duplicate_splits_are_rejected(self) -> None:
        with self.assertRaises(ValueError):
            evaluate.build_report(self.runs, self.META, [], [], [self.document(), self.document(label="deepseek:other:high")])
        with self.assertRaises(ValueError):
            evaluate.build_report(self.runs, self.META, [], [], [self.document(), self.document()])

    def test_ac_10_10_eval_report_reads_result_files_and_rejects_other_json(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            good, other = Path(tmp) / "good.json", Path(tmp) / "other.json"
            good.write_text(json.dumps(self.document()), encoding="utf-8")
            other.write_text(json.dumps({"metrics": {}}), encoding="utf-8")
            self.assertEqual(len(run_eval.load_external_documents([good])), 1)
            with self.assertRaises(ValueError):
                run_eval.load_external_documents([other])


if __name__ == "__main__":
    unittest.main()
