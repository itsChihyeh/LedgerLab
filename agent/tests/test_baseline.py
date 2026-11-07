import statistics
import unittest
from collections import Counter

from ledgerlab_agent import conclusion as conclusion_mod
from ledgerlab_agent.agent import Budget, Investigation
from ledgerlab_agent.baseline import BaselineInvestigator, NeedMore, Verdict, next_step
from ledgerlab_agent.replay import ReplayTransport
from ledgerlab_agent.sop import load_sop
from ledgerlab_agent.tools import ToolRegistry, ToolResult
from tests.dev_data import (
    load_dev_labels,
    load_dev_snapshot,
    neutral_memo_snapshot,
    registry_for,
)
from tests.fakes import RecordingTransport


class BaselineDevSetTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.sop = load_sop()
        cls.labels = load_dev_labels()
        cls.snapshot = load_dev_snapshot()
        cls.runs: dict[str, Investigation] = {}
        for label in cls.labels:
            tools = registry_for(label, cls.snapshot, cls.sop)
            cls.runs[label["case_id"]] = BaselineInvestigator(tools, cls.sop).investigate(label["issue_id"])

    def test_ac_10_7_baseline_answers_every_dev_question_with_a_valid_conclusion(self) -> None:
        self.assertGreater(len(self.labels), 40)
        for label in self.labels:
            run = self.runs[label["case_id"]]
            self.assertEqual(run.status, "ok", label["case_id"])
            self.assertEqual(conclusion_mod.validate(run.conclusion.as_dict(), label["issue_id"]), [], label["case_id"])
            self.assertLessEqual(len(run.tool_calls), Budget.max_tool_calls)

    def test_ac_10_7_baseline_dev_accuracy_report(self) -> None:
        answerable = [x for x in self.labels if not x["expected_abstain"]]
        right = [x for x in answerable if self.runs[x["case_id"]].conclusion.root_cause == x["root_cause"]]
        calls = [len(run.tool_calls) for run in self.runs.values()]
        by_kind = Counter(x["kind"] for x in answerable if x not in right)
        print(
            f"\n[baseline dev] accuracy {len(right)}/{len(answerable)} on answerable questions; "
            f"wrong by kind {dict(by_kind)}; mean tool calls {statistics.mean(calls):.2f}, max {max(calls)}"
        )
        for label in answerable:
            if label not in right:
                got = self.runs[label["case_id"]].conclusion
                print(f"  wrong: {label['case_id']} expected {label['root_cause']} got {got.root_cause}")

    def test_ac_10_7_baseline_abstains_on_every_withheld_question(self) -> None:
        withheld = [x for x in self.labels if x["kind"] == "withheld"]
        self.assertGreaterEqual(len(withheld), 5)
        for label in withheld:
            concl = self.runs[label["case_id"]].conclusion
            self.assertTrue(concl.abstain, label["case_id"])
            self.assertEqual(concl.root_cause, "INSUFFICIENT_EVIDENCE")
            self.assertEqual(concl.recommended_action, "ESCALATE")
            self.assertEqual(concl.sop_clauses, ("SOP-2",))

    def test_ac_10_7_baseline_never_abstains_on_answerable_questions(self) -> None:
        for label in self.labels:
            if not label["expected_abstain"]:
                self.assertFalse(self.runs[label["case_id"]].conclusion.abstain, label["case_id"])

    def test_ac_10_7_baseline_citations_were_seen_and_cover_the_required_evidence(self) -> None:
        for label in self.labels:
            run = self.runs[label["case_id"]]
            seen = {record for result in run.tool_calls for record in result.records}
            for citation in run.conclusion.citations:
                self.assertIn(citation, seen, label["case_id"])
            if run.conclusion.root_cause != label["root_cause"]:
                continue
            counts = Counter(c.kind for c in run.conclusion.citations)
            for need in label["required_evidence_kinds"]:
                self.assertGreaterEqual(counts[need["kind"]], need["min"], f"{label['case_id']} {need}")

    def test_ac_10_7_baseline_uses_the_clause_and_action_of_its_cause(self) -> None:
        for label in self.labels:
            concl = self.runs[label["case_id"]].conclusion
            clause = self.sop.for_cause(concl.root_cause)
            assert clause is not None
            self.assertEqual(concl.sop_clauses, (clause.id,))
            self.assertEqual(concl.recommended_action, clause.recommended_action)

    def test_ac_10_7_memo_injection_does_not_change_baseline(self) -> None:
        neutral, replaced = neutral_memo_snapshot(self.snapshot)
        self.assertTrue(replaced)
        injections = [x for x in self.labels if x["kind"] == "injection"]
        self.assertGreaterEqual(len(injections), 5)
        for label in injections:
            text = label["injection"]["text"]
            seen_texts = [m for r in self.runs[label["case_id"]].tool_calls for m in _memos(r)]
            self.assertIn(text, seen_texts, "the injected memo must actually reach the baseline")
            plain = BaselineInvestigator(registry_for(label, neutral, self.sop), self.sop).investigate(label["issue_id"])
            original = self.runs[label["case_id"]]
            self.assertEqual(original.conclusion.as_dict(), plain.conclusion.as_dict(), label["case_id"])
            self.assertNotIn(text, original.conclusion.explanation)
            self.assertNotEqual(original.conclusion.root_cause, label["injection"]["demanded_root_cause"])


def _memos(result: ToolResult) -> list[str]:
    found: list[str] = []

    def walk(node: object) -> None:
        if isinstance(node, dict):
            for key, value in node.items():
                if key == "memo" and isinstance(value, str):
                    found.append(value)
                walk(value)
        elif isinstance(node, list):
            for item in node:
                walk(item)

    walk(result.data)
    return found


class BaselineBehaviourTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.sop = load_sop()

    def test_ac_10_7_first_step_is_always_get_issue(self) -> None:
        step = next_step("iss_x", [], self.sop)
        self.assertIsInstance(step, NeedMore)
        assert isinstance(step, NeedMore)
        self.assertEqual([(c.tool, c.args) for c in step.calls], [("get_issue", {"issue_id": "iss_x"})])

    def test_ac_10_7_failed_get_issue_abstains_without_citations(self) -> None:
        failed = ToolResult("get_issue", {"issue_id": "iss_x"}, False, 404, error="http_404")
        step = next_step("iss_x", [failed], self.sop)
        self.assertIsInstance(step, Verdict)
        assert isinstance(step, Verdict)
        self.assertTrue(step.conclusion.abstain)
        self.assertEqual(step.conclusion.citations, ())

    def test_ac_10_7_unknown_issue_category_abstains(self) -> None:
        issue = {"issue": {"id": "iss_x", "category": "UNAPPLIED_CASH_OPEN", "subjectId": "s"}, "references": {}}
        transport = RecordingTransport({"/api/recon/issues/iss_x": issue})
        run = BaselineInvestigator(ToolRegistry(transport), self.sop).investigate("iss_x")
        self.assertTrue(run.conclusion.abstain)
        self.assertEqual(len(run.tool_calls), 1)

    def test_ac_10_7_baseline_respects_the_tool_call_budget(self) -> None:
        labels = load_dev_labels()
        label = next(x for x in labels if x["root_cause"] == "BANK_SPLIT_DEPOSIT")
        tools = registry_for(label, load_dev_snapshot(), self.sop)
        run = BaselineInvestigator(tools, self.sop, Budget(max_tool_calls=2)).investigate(label["issue_id"])
        self.assertEqual(run.status, "budget_exhausted")
        self.assertTrue(run.conclusion.abstain)
        self.assertLessEqual(len(run.tool_calls), 2)

    def test_ac_10_7_baseline_rejects_a_malformed_issue_id(self) -> None:
        with self.assertRaises(ValueError):
            BaselineInvestigator(ToolRegistry(ReplayTransport(load_dev_snapshot())), self.sop).investigate("../x")


if __name__ == "__main__":
    unittest.main()
