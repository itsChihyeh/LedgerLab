"""P8-T07c: budget hints, the final submit-only turn, and the investigation trace. No network."""

from __future__ import annotations

import datetime as dt
import io
import json
import tempfile
import unittest
from pathlib import Path
from typing import Any

from eval import run_eval
from eval.trace_files import write_trace
from ledgerlab_agent.agent import Budget, budget_hint, investigate
from ledgerlab_agent.baseline import BaselineInvestigator
from ledgerlab_agent.cli import main as cli_main
from ledgerlab_agent.model import ModelResponse, ReasoningBlock, ScriptedModel, TextBlock, ToolUseBlock, Usage
from ledgerlab_agent.sop import load_sop
from ledgerlab_agent.stub_model import StubModel
from ledgerlab_agent.tools import ToolRegistry
from ledgerlab_agent.trace import investigation_trace
from ledgerlab_agent.verify import verify

from tests.dev_data import DEV_DIR, load_dev_labels, load_dev_snapshot, registry_for
from tests.test_agent import ISSUE, run, submit, transport, use
from tests.test_deepseek_model import PLACEHOLDER_SECRET
from tests.test_external_eval import ENV, NOW, AbstainingDeepSeek, ONE_USD_USAGE, PRICE_ENTRY, write_prices


def hint_of(message: dict[str, Any]) -> str:
    return message["content"][-1]["text"]


def lookups(n: int) -> list[ModelResponse]:
    return [use(f"t{i}", "get_issue", issue_id=ISSUE) for i in range(n)]


class BudgetHintTest(unittest.TestCase):
    def test_ac_10_4_the_first_message_has_no_hint_and_a_tool_result_message_ends_with_one(self) -> None:
        _, model = run([*lookups(1), submit()])
        self.assertEqual(len(model.requests[0].messages[0]["content"]), 1)
        last = model.requests[1].messages[-1]
        self.assertEqual(last["content"][0]["type"], "tool_result")
        self.assertEqual(last["content"][-1]["type"], "text")
        self.assertEqual(hint_of(last), "Budget: 1/12 tool calls and 1/8 model turns used.")
        self.assertNotIn("tool_data", hint_of(last))

    def test_ac_10_4_with_three_calls_left_the_hint_says_to_submit_next(self) -> None:
        _, model = run([*lookups(9), submit()], budget=Budget(max_turns=20))
        self.assertIn("Budget: 8/12", hint_of(model.requests[8].messages[-1]))
        self.assertNotIn("left", hint_of(model.requests[8].messages[-1]))
        self.assertEqual(
            hint_of(model.requests[9].messages[-1]),
            "Budget: 9/12 tool calls and 9/20 model turns used. Only 3 tool calls left; on your next turn call submit_conclusion.",
        )

    def test_ac_10_4_one_call_left_is_singular_and_a_spent_budget_says_so(self) -> None:
        _, model = run([*lookups(11), submit()], budget=Budget(max_turns=20))
        self.assertIn("Only 1 tool call left;", hint_of(model.requests[11].messages[-1]))
        _, model = run([*lookups(12), submit()], budget=Budget(max_turns=20))
        self.assertIn("The budget is used up", hint_of(model.requests[12].messages[-1]))

    def test_ac_10_4_the_correction_message_for_an_invalid_conclusion_carries_the_hint_too(self) -> None:
        _, model = run([submit("s1", root_cause="MADE_UP"), submit("s2")])
        feedback = model.requests[1].messages[-1]["content"]
        self.assertEqual(feedback[0]["type"], "tool_result")
        self.assertTrue(hint_of(model.requests[1].messages[-1]).startswith("Budget: 0/12"))

    def test_ac_10_4_hint_function_is_plain_text_without_tool_data_markup(self) -> None:
        for calls in range(14):
            self.assertNotIn("<", budget_hint(Budget(), calls, 3))


class FinalTurnTest(unittest.TestCase):
    def test_ac_10_4_after_the_last_tool_call_the_final_turn_offers_only_submit_conclusion(self) -> None:
        result, model = run([*lookups(2), submit()], budget=Budget(max_tool_calls=2))
        self.assertGreater(len(model.requests[1].tools), 1)
        self.assertEqual([t["name"] for t in model.requests[2].tools], ["submit_conclusion"])
        self.assertIn("The budget is used up", hint_of(model.requests[2].messages[-1]))
        self.assertEqual(result.status, "ok")
        self.assertTrue(result.final_turn_submit)
        self.assertEqual(result.turns, 3)
        self.assertEqual(len(result.tool_calls), 2)

    def test_ac_10_4_running_out_of_turns_also_gives_a_final_turn(self) -> None:
        result, model = run([*lookups(8), submit()])
        self.assertEqual(result.status, "ok")
        self.assertTrue(result.final_turn_submit)
        self.assertEqual(result.turns, 9)
        self.assertEqual([t["name"] for t in model.requests[8].tools], ["submit_conclusion"])

    def test_ac_10_4_a_conclusion_before_the_budget_runs_out_is_not_a_final_turn_submit(self) -> None:
        result, _ = run([*lookups(1), submit()])
        self.assertEqual(result.status, "ok")
        self.assertFalse(result.final_turn_submit)

    def test_ac_10_4_a_final_turn_without_a_conclusion_is_budget_exhausted(self) -> None:
        text = ModelResponse((TextBlock("still thinking"),), "end_turn", Usage(1, 1))
        for reply in (use("x", "get_issue", issue_id=ISSUE), text):
            result, model = run([*lookups(2), reply, submit()], budget=Budget(max_tool_calls=2))
            self.assertEqual(result.status, "budget_exhausted")
            self.assertFalse(result.final_turn_submit)
            self.assertTrue(result.conclusion.abstain)
            self.assertEqual(len(model.requests), 3, "no further turn after the final one")
            self.assertIn("tool call limit of 2", result.conclusion.explanation)

    def test_ac_10_4_an_invalid_conclusion_on_the_final_turn_is_budget_exhausted_without_a_correction(self) -> None:
        result, model = run([*lookups(2), submit("s1", root_cause="MADE_UP"), submit("s2")], budget=Budget(max_tool_calls=2))
        self.assertEqual(result.status, "budget_exhausted")
        self.assertEqual(len(model.requests), 3)
        self.assertFalse(result.final_turn_submit)

    def test_ac_10_4_a_valid_abstention_on_the_final_turn_counts_as_submitted(self) -> None:
        abstain = submit(
            abstain=True, root_cause="INSUFFICIENT_EVIDENCE", confidence="low", citations=[], sop_clauses=["SOP-2"],
            recommended_action="ESCALATE", explanation="Not enough evidence was gathered.",
        )
        result, _ = run([*lookups(2), abstain], budget=Budget(max_tool_calls=2))
        self.assertEqual(result.status, "ok")
        self.assertTrue(result.final_turn_submit)
        self.assertTrue(result.conclusion.abstain)

    def test_ac_10_4_extra_calls_in_one_reply_are_answered_with_errors_then_the_final_turn_follows(self) -> None:
        both = ModelResponse(
            (ToolUseBlock("a", "get_issue", {"issue_id": ISSUE}), ToolUseBlock("b", "get_issue", {"issue_id": ISSUE})),
            "tool_use",
        )
        tr = transport()
        result, model = run([both, submit()], budget=Budget(max_tool_calls=1), tr=tr)
        self.assertEqual(len(tr.calls), 1)
        self.assertEqual(result.status, "ok")
        self.assertTrue(result.final_turn_submit)
        answered = [b for b in model.requests[1].messages[-1]["content"] if b["type"] == "tool_result"]
        self.assertEqual([(b["tool_use_id"], b["is_error"]) for b in answered], [("a", False), ("b", True)])
        self.assertEqual(
            [(u.name, u.call_index) for u in result.turn_log[0].tool_uses], [("get_issue", 0), ("get_issue", None)]
        )


class StandInsIgnoreHintsTest(unittest.TestCase):
    def test_ac_10_9_the_stub_reads_the_same_conclusion_with_budget_hints_in_the_conversation(self) -> None:
        sop = load_sop()
        snapshot = load_dev_snapshot()
        for label in load_dev_labels()[:12]:
            issue = label["issue_id"]
            stub = ScriptedRecorder(StubModel(sop))
            via_stub = investigate(issue, stub, registry_for(label, snapshot, sop))
            direct = BaselineInvestigator(registry_for(label, snapshot, sop), sop).investigate(issue)
            self.assertEqual(via_stub.conclusion, direct.conclusion, label["case_id"])
            if len(stub.requests) > 1:
                self.assertTrue(hint_of(stub.requests[-1].messages[-1]).startswith("Budget: "))


class ScriptedRecorder:
    """Wraps a model and keeps the requests it was sent."""

    def __init__(self, inner: Any) -> None:
        self._inner = inner
        self.requests: list[Any] = []

    def complete(self, request: Any) -> ModelResponse:
        self.requests.append(request)
        return self._inner.complete(request)


class TraceTest(unittest.TestCase):
    REASONING = "PRIVATE-CHAIN-OF-THOUGHT-TEXT"

    def traced(self) -> dict[str, Any]:
        thinking = ModelResponse(
            (
                ReasoningBlock(self.REASONING, "deepseek"),
                TextBlock("Looking at the issue first."),
                ToolUseBlock("t1", "get_issue", {"issue_id": ISSUE}),
            ),
            "tool_use",
            Usage(10, 5, 4, 6, 2),
        )
        bad = submit("s1", citations=[{"kind": "bank_line", "id": "bt_never_seen"}])
        result, _ = run([thinking, bad])
        return investigation_trace(ISSUE, result, verify(result, load_sop().required_evidence()), {"case_id": "dev-x"})

    def test_ac_10_4_trace_has_the_turns_calls_and_verification(self) -> None:
        doc = self.traced()
        for key in (
            "issue_id", "case_id", "status", "final_turn_submit", "conclusion", "turns", "usage", "verification",
            "turn_log", "tool_calls", "validation_errors",
        ):
            self.assertIn(key, doc)
        first = doc["turn_log"][0]
        self.assertEqual(first["text"], "Looking at the issue first.")
        self.assertEqual(first["reasoning_chars"], len(self.REASONING))
        self.assertEqual(first["stop_reason"], "tool_use")
        self.assertEqual(first["usage"]["reasoning_tokens"], 2)
        self.assertEqual(first["tool_uses"], [{"name": "get_issue", "input": {"issue_id": ISSUE}, "call_index": 0, "note": None}])
        call = doc["tool_calls"][0]
        self.assertEqual((call["tool"], call["args"], call["ok"], call["status"], call["truncated"]), ("get_issue", {"issue_id": ISSUE}, True, 200, False))
        self.assertTrue(call["records"])
        self.assertEqual(doc["verification"]["fabricated_citations"], ["bank_line:bt_never_seen"])
        self.assertEqual(doc["status"], "ok")
        self.assertEqual(doc["conclusion"]["citations"], [{"kind": "bank_line", "id": "bt_never_seen"}])

    def test_ac_10_4_trace_never_holds_the_reasoning_text(self) -> None:
        self.assertNotIn(self.REASONING, json.dumps(self.traced(), ensure_ascii=False))

    def test_ac_10_4_write_trace_keeps_the_file_inside_the_directory(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            path = write_trace(Path(tmp) / "sub", "../../evil name", {"a": 1})
            self.assertEqual(path.parent, Path(tmp) / "sub")
            self.assertEqual(json.loads(path.read_text(encoding="utf-8")), {"a": 1})

    def test_ac_10_4_investigate_writes_a_trace_only_with_trace_dir(self) -> None:
        label = load_dev_labels()[0]
        argv = ["investigate", label["issue_id"], "--snapshot", str(DEV_DIR / "snapshot.json"), "--model", "stub"]
        with tempfile.TemporaryDirectory() as tmp:
            code = cli_main(argv, io.StringIO(), io.StringIO(), trace_writer=write_trace)
            self.assertEqual((code, list(Path(tmp).iterdir())), (0, []))
            code = cli_main([*argv, "--trace-dir", tmp], io.StringIO(), io.StringIO(), trace_writer=write_trace)
            self.assertEqual(code, 0)
            doc = json.loads((Path(tmp) / f"{label['issue_id']}.json").read_text(encoding="utf-8"))
            self.assertEqual(doc["status"], "ok")
            self.assertTrue(doc["turn_log"])
            err = io.StringIO()
            self.assertEqual(cli_main([*argv, "--trace-dir", tmp], io.StringIO(), err), 2)
            self.assertIn("--trace-dir", err.getvalue())

    def test_ac_10_4_eval_writes_one_trace_per_question_and_the_report_counts_final_turn_submits(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            out = io.StringIO()
            code = run_eval.main(["eval", "--split", "dev", "--model", "stub", "--trace-dir", tmp], out, io.StringIO(), NOW)
            self.assertEqual(code, 0)
            files = sorted(Path(tmp).glob("*.json"))
            self.assertEqual(len(files), len(load_dev_labels()))
            doc = json.loads(files[0].read_text(encoding="utf-8"))
            self.assertEqual(
                {"case_id", "kind", "expected_root_cause", "expected_abstain"} - set(doc), set()
            )
            self.assertIn("submitted on the final turn 0", out.getvalue())

    def test_ac_10_10_an_external_run_trace_holds_no_key(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            argv = [
                "eval", "--split", "dev", "--model", "deepseek:deepseek-flash", "--confirm-spend", "5", "--limit", "2",
                "--out", str(root / "result.json"), "--trace-dir", str(root / "traces"),
            ]
            code = run_eval.main(
                argv, io.StringIO(), io.StringIO(), NOW, AbstainingDeepSeek(ONE_USD_USAGE), ENV,
                write_prices(root, PRICE_ENTRY), lambda _s: None, root / "logs",
            )
            self.assertEqual(code, 0)
            files = list((root / "traces").glob("*.json"))
            self.assertEqual(len(files), 2)
            for file in files:
                self.assertNotIn(PLACEHOLDER_SECRET, file.read_text(encoding="utf-8"))


if __name__ == "__main__":
    unittest.main()
