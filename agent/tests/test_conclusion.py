import copy
import unittest
from typing import Any

from ledgerlab_agent.conclusion import abstention, from_dict, validate
from ledgerlab_agent.enums import ROOT_CAUSES

ISSUE = "iss_01HX0000000000000000000001"


def good() -> dict[str, Any]:
    return {
        "issue_id": ISSUE,
        "root_cause": "BANK_SPLIT_DEPOSIT",
        "confidence": "high",
        "abstain": False,
        "explanation": "The bank booked two lines for one payout.",
        "citations": [{"kind": "bank_line", "id": "bt_mpo_x_1"}, {"kind": "bank_line", "id": "bt_mpo_x_2"}],
        "sop_clauses": ["SOP-4.2"],
        "recommended_action": "MATCH_MANUALLY_AFTER_REVIEW",
    }


class ConclusionValidationTest(unittest.TestCase):
    def bad(self, **changes: Any) -> list[str]:
        raw = good()
        raw.update(changes)
        errors = validate(raw, ISSUE)
        self.assertTrue(errors, f"expected errors for {changes}")
        return errors

    def test_ac_10_4_valid_conclusion_has_no_errors(self) -> None:
        self.assertEqual(validate(good(), ISSUE), [])
        self.assertEqual(from_dict(good()).root_cause, "BANK_SPLIT_DEPOSIT")

    def test_ac_10_4_every_documented_root_cause_is_accepted(self) -> None:
        for cause in ROOT_CAUSES:
            raw = good()
            raw["root_cause"] = cause
            raw["abstain"] = cause == "INSUFFICIENT_EVIDENCE"
            self.assertEqual(validate(raw, ISSUE), [], cause)

    def test_ac_10_4_root_cause_must_be_in_enum(self) -> None:
        self.bad(root_cause="SOMETHING_ELSE")

    def test_ac_10_4_recommended_action_must_be_in_enum(self) -> None:
        self.bad(recommended_action="RESOLVE_ISSUE")
        raw = good()
        raw["recommended_action"] = "ESCALATE"
        self.assertEqual(validate(raw, ISSUE), [])

    def test_ac_10_4_confidence_must_be_in_enum(self) -> None:
        self.bad(confidence="certain")
        raw = good()
        raw["confidence"] = "low"
        self.assertEqual(validate(raw, ISSUE), [])

    def test_ac_10_4_abstain_and_insufficient_evidence_go_together(self) -> None:
        self.bad(abstain=True)
        self.bad(root_cause="INSUFFICIENT_EVIDENCE", abstain=False)
        self.assertEqual(validate(dict(good(), root_cause="INSUFFICIENT_EVIDENCE", abstain=True), ISSUE), [])

    def test_ac_10_4_abstain_must_be_a_real_boolean(self) -> None:
        self.bad(abstain="false")
        self.bad(abstain=0)

    def test_ac_10_4_explanation_must_be_nonempty_and_at_most_800_chars(self) -> None:
        self.bad(explanation="")
        self.bad(explanation="   ")
        self.bad(explanation="x" * 801)
        self.assertEqual(validate(dict(good(), explanation="x" * 800), ISSUE), [])

    def test_ac_10_4_citations_limited_to_20_with_known_kinds(self) -> None:
        many = [{"kind": "bank_line", "id": f"bt_{i}"} for i in range(21)]
        self.bad(citations=many)
        self.assertEqual(validate(dict(good(), citations=many[:20]), ISSUE), [])
        self.bad(citations=[{"kind": "ledger_row", "id": "x"}])
        self.bad(citations=[{"kind": "bank_line"}])
        self.bad(citations=[{"kind": "bank_line", "id": ""}])
        self.bad(citations=[{"kind": "bank_line", "id": "x", "extra": 1}])
        self.bad(citations="bt_1")
        self.assertEqual(validate(dict(good(), citations=[]), ISSUE), [])

    def test_ac_10_4_sop_clauses_must_look_like_sop_numbers(self) -> None:
        self.bad(sop_clauses=["SOP-"])
        self.bad(sop_clauses=["sop-3.2"])
        self.bad(sop_clauses=["SOP-3.2.x"])
        self.bad(sop_clauses="SOP-3.2")
        for ok in (["SOP-3"], ["SOP-3.2"], ["SOP-12.4.1", "SOP-1"], []):
            self.assertEqual(validate(dict(good(), sop_clauses=ok), ISSUE), [], ok)

    def test_ac_10_4_issue_id_must_match_the_investigated_issue(self) -> None:
        self.bad(issue_id="iss_other")
        self.assertEqual(validate(dict(good(), issue_id="iss_other")), [])

    def test_ac_10_4_missing_and_unknown_fields_are_reported(self) -> None:
        raw = good()
        del raw["citations"]
        self.assertIn("missing field citations", validate(raw, ISSUE))
        self.assertIn("unknown field notes", validate(dict(good(), notes="hi"), ISSUE))

    def test_ac_10_4_non_object_is_rejected_without_raising(self) -> None:
        for raw in (None, "text", [], 3):
            self.assertTrue(validate(raw, ISSUE))

    def test_ac_10_4_validation_does_not_mutate_input(self) -> None:
        raw = good()
        snapshot = copy.deepcopy(raw)
        validate(raw, ISSUE)
        self.assertEqual(raw, snapshot)

    def test_ac_10_4_abstention_helper_is_itself_valid(self) -> None:
        concl = abstention(ISSUE, "x" * 2000)
        self.assertEqual(validate(concl.as_dict(), ISSUE), [])
        self.assertTrue(concl.abstain)


if __name__ == "__main__":
    unittest.main()
