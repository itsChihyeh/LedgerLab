import json
import unittest
from typing import Any

from ledgerlab_agent.agent import Budget, investigate, render_tool_result
from ledgerlab_agent.baseline import BaselineInvestigator
from ledgerlab_agent.model import SUBMIT_TOOL, ModelRequest, ToolUseBlock, tool_definitions
from ledgerlab_agent.records import extract_records
from ledgerlab_agent.sop import load_sop
from ledgerlab_agent.stub_model import StubModel
from ledgerlab_agent.tools import ToolResult
from tests.dev_data import load_dev_labels, load_dev_snapshot, registry_for


def _comparable(concl: Any) -> dict[str, Any]:
    return {
        "root_cause": concl.root_cause,
        "abstain": concl.abstain,
        "citations": sorted((c.kind, c.id) for c in concl.citations),
        "sop_clauses": list(concl.sop_clauses),
        "recommended_action": concl.recommended_action,
    }


class StubMatchesBaselineTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.sop = load_sop()
        cls.labels = load_dev_labels()
        cls.snapshot = load_dev_snapshot()

    def test_ac_10_7_stub_conclusions_equal_baseline_on_every_dev_question(self) -> None:
        stub = StubModel(self.sop)
        for label in self.labels:
            base = BaselineInvestigator(registry_for(label, self.snapshot, self.sop), self.sop).investigate(
                label["issue_id"]
            )
            tools = registry_for(label, self.snapshot, self.sop)
            run = investigate(label["issue_id"], stub, tools, Budget())
            self.assertEqual(run.status, "ok", label["case_id"])
            self.assertEqual(_comparable(run.conclusion), _comparable(base.conclusion), label["case_id"])
            self.assertEqual(run.conclusion.as_dict(), base.conclusion.as_dict(), label["case_id"])
            self.assertEqual(sorted(map(_call_key, run.tool_calls)), sorted(map(_call_key, base.tool_calls)))

    def test_ac_10_7_stub_stays_inside_the_budget_and_reports_estimated_usage(self) -> None:
        stub = StubModel(self.sop)
        self.assertTrue(stub.usage_is_estimate)
        budget = Budget()
        for label in self.labels:
            run = investigate(label["issue_id"], stub, registry_for(label, self.snapshot, self.sop), budget)
            self.assertLessEqual(len(run.tool_calls), budget.max_tool_calls, label["case_id"])
            self.assertLessEqual(run.turns, budget.max_turns, label["case_id"])
            self.assertGreater(run.usage.input_tokens, 0)
            self.assertGreater(run.usage.output_tokens, 0)

    def test_ac_10_7_stub_batches_independent_lookups_into_one_turn(self) -> None:
        label = next(x for x in self.labels if x["root_cause"] == "BANK_SPLIT_DEPOSIT")
        run = investigate(label["issue_id"], StubModel(self.sop), registry_for(label, self.snapshot, self.sop))
        self.assertEqual(run.turns, 3)  # issue, evidence (two calls), submit
        self.assertEqual([c.tool for c in run.tool_calls], ["get_issue", "search_bank_lines", "get_batch"])


def _call_key(result: ToolResult) -> tuple[str, str]:
    return result.tool, json.dumps(result.args, sort_keys=True)


def _issue_payload(category: str, **extra: Any) -> dict[str, Any]:
    issue = {"id": "iss_fake", "category": category, "subjectId": "po_fake", "expected": {}, "actual": {}}
    issue.update(extra)
    return {"issue": issue, "references": {"batchIds": ["po_fake"]}}


def _conversation(issue_payload: dict[str, Any] | None, ok: bool = True) -> list[dict[str, Any]]:
    """The conversation after the model asked for get_issue and the tool layer answered."""
    result = ToolResult(
        "get_issue",
        {"issue_id": "iss_fake"},
        ok,
        200 if ok else 404,
        issue_payload,
        None,
        tuple(extract_records("get_issue", issue_payload)) if ok else (),
        False,
        None if ok else "http_404",
    )
    return [
        {
            "role": "user",
            "content": [{"type": "text", "text": "Investigate reconciliation issue iss_fake. Use the tools."}],
        },
        {
            "role": "assistant",
            "content": [{"type": "tool_use", "id": "t0", "name": "get_issue", "input": {"issue_id": "iss_fake"}}],
        },
        {
            "role": "user",
            "content": [
                {"type": "tool_result", "tool_use_id": "t0", "content": render_tool_result(result), "is_error": not ok}
            ],
        },
    ]


def _ask(messages: list[dict[str, Any]]) -> tuple[ToolUseBlock, ...]:
    definitions = tool_definitions([])
    response = StubModel(load_sop()).complete(ModelRequest("system", messages, definitions, 1024))
    return tuple(b for b in response.content if isinstance(b, ToolUseBlock))


class StubReadsOnlyTheConversationTest(unittest.TestCase):
    def test_ac_10_7_next_step_changes_with_the_tool_result_in_the_conversation(self) -> None:
        fee = _ask(_conversation(_issue_payload("FEE_MISMATCH", expected={"fee": 100}, actual={"fees": [{"fee": 150}]})))
        timing = _ask(_conversation(_issue_payload("TIMING_DIFFERENCE")))
        self.assertEqual([(b.name, b.input) for b in fee], [("get_batch", {"batch_id": "po_fake"})])
        self.assertEqual(
            [(b.name, b.input) for b in timing],
            [("get_batch", {"batch_id": "po_fake"}), ("search_bank_lines", {"reference": "po_fake"})],
        )

    def test_ac_10_7_same_category_different_reference_asks_for_different_lookups(self) -> None:
        def payload(reference: str) -> dict[str, Any]:
            return _issue_payload("UNMATCHED_BANK_LINE", actual={"reference": reference, "id": "bt_x"})

        unknown = _ask(_conversation(payload("unknown_po_fake")))
        ordinary = _ask(_conversation(payload("po_other")))
        self.assertEqual([b.name for b in unknown], ["search_bank_lines"])
        self.assertEqual([b.name for b in ordinary], ["search_bank_lines", "get_batch"])
        self.assertEqual(ordinary[1].input, {"batch_id": "po_other"})

    def test_ac_10_7_a_failed_issue_lookup_in_the_conversation_ends_in_an_abstention(self) -> None:
        blocks = _ask(_conversation(None, ok=False))
        self.assertEqual([b.name for b in blocks], [SUBMIT_TOOL])
        self.assertTrue(blocks[0].input["abstain"])
        self.assertEqual(blocks[0].input["root_cause"], "INSUFFICIENT_EVIDENCE")

    def test_ac_10_7_empty_conversation_start_asks_for_the_issue(self) -> None:
        messages = _conversation(None)[:1]
        blocks = _ask(messages)
        self.assertEqual([(b.name, b.input) for b in blocks], [("get_issue", {"issue_id": "iss_fake"})])

    def test_ac_10_7_stub_is_stateless_between_turns(self) -> None:
        stub = StubModel(load_sop())
        definitions = tool_definitions([])
        first = stub.complete(ModelRequest("s", _conversation(_issue_payload("TIMING_DIFFERENCE")), definitions, 10))
        second = stub.complete(ModelRequest("s", _conversation(_issue_payload("TIMING_DIFFERENCE")), definitions, 10))
        self.assertEqual(first.content, second.content)


if __name__ == "__main__":
    unittest.main()
