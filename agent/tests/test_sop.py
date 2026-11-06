import re
import unittest

from ledgerlab_agent.enums import ABSTAIN_CAUSE, RECOMMENDED_ACTIONS, ROOT_CAUSES
from ledgerlab_agent.sop import (
    MAX_CLAUSE_CHARS,
    SOP_PATH,
    SopError,
    SopSearch,
    load_sop,
    parse_clauses,
    parse_required_evidence,
    required_evidence_by_cause,
    tokenize,
)
from ledgerlab_agent.tools import ToolRegistry
from tests.dev_data import load_dev_labels
from tests.fakes import RecordingTransport


class SopParsingTest(unittest.TestCase):
    def setUp(self) -> None:
        self.index = load_sop()

    def test_ac_10_7_every_final_root_cause_has_exactly_one_clause(self) -> None:
        for cause in ROOT_CAUSES:
            matching = [c for c in self.index.clauses if c.root_cause == cause]
            self.assertEqual(len(matching), 1, cause)
            self.assertIs(self.index.for_cause(cause), matching[0])

    def test_ac_10_7_every_cause_clause_has_required_evidence_and_a_valid_action(self) -> None:
        for cause in ROOT_CAUSES:
            clause = self.index.for_cause(cause)
            assert clause is not None
            self.assertIn(clause.recommended_action, RECOMMENDED_ACTIONS, cause)
            if cause != ABSTAIN_CAUSE:
                self.assertTrue(clause.required_evidence, cause)
                for kind, minimum in clause.required_evidence:
                    self.assertGreaterEqual(minimum, 1)

    def test_ac_10_7_general_clauses_exist_and_have_no_cause(self) -> None:
        for clause_id in ("SOP-1", "SOP-3"):
            clause = self.index.clause(clause_id)
            assert clause is not None
            self.assertIsNone(clause.root_cause)
        abstain = self.index.clause("SOP-2")
        assert abstain is not None
        self.assertEqual(abstain.root_cause, ABSTAIN_CAUSE)

    def test_ac_10_7_overview_table_agrees_with_the_clauses(self) -> None:
        rows = re.findall(
            r"^\| (SOP-\d+(?:\.\d+)*) \| [^|]+ \| ([A-Z_]+) \| ([A-Z_]+) \|$",
            SOP_PATH.read_text(encoding="utf-8"),
            re.MULTILINE,
        )
        by_id = {clause_id: (cause, action) for clause_id, cause, action in rows}
        self.assertEqual(len(rows), len(by_id))
        for cause in ROOT_CAUSES:
            clause = self.index.for_cause(cause)
            assert clause is not None
            self.assertEqual(by_id.get(clause.id), (cause, clause.recommended_action), cause)

    def test_ac_10_7_required_evidence_matches_the_frozen_dev_labels(self) -> None:
        table = required_evidence_by_cause()
        checked = 0
        for label in load_dev_labels():
            if label["kind"] == "withheld":
                continue
            wanted = {(item["kind"], item["min"]) for item in label["required_evidence_kinds"]}
            self.assertEqual(set(table[label["root_cause"]]), wanted, label["case_id"])
            checked += 1
        self.assertGreater(checked, 40)

    def test_ac_10_7_parser_rejects_malformed_clauses(self) -> None:
        with self.assertRaises(SopError):
            parse_required_evidence("bank_line*0")
        with self.assertRaises(SopError):
            parse_required_evidence("spaceship")
        with self.assertRaises(SopError):
            parse_clauses("## SOP-1 a\nroot_cause: NOT_A_CAUSE\n")
        with self.assertRaises(SopError):
            parse_clauses("## SOP-1 a\nroot_cause: WEBHOOK_LOST\nrequired_evidence: batch\n")
        with self.assertRaises(SopError):
            parse_clauses("## SOP-1 a\nbody\n## SOP-1 b\nbody\n")


class SopSearchTest(unittest.TestCase):
    def setUp(self) -> None:
        self.index = load_sop()

    def top(self, query: str) -> str:
        hits = self.index.search(query)
        self.assertTrue(hits, query)
        return hits[0].clause.id

    def test_ac_10_7_tokenizer_splits_english_by_word_and_chinese_by_bigram(self) -> None:
        self.assertEqual(tokenize("BANK_SPLIT_DEPOSIT, SOP-4.2"), ["bank", "split", "deposit", "sop", "4", "2"])
        self.assertEqual(tokenize("少到账"), ["少到", "到账"])
        self.assertEqual(tokenize("拆 split"), ["split", "拆"])

    def test_ac_10_7_category_names_find_their_clause(self) -> None:
        self.assertEqual(self.top("FEE_MISMATCH"), "SOP-6.4")
        self.assertEqual(self.top("DUPLICATE_STATEMENT_LINE"), "SOP-6.1")
        self.assertEqual(self.top("UNSUPPORTED_BATCH_LINE"), "SOP-6.3")
        self.assertEqual(self.top("BATCH_TOTAL_MISMATCH"), "SOP-6.5")

    def test_ac_10_7_cause_names_find_their_clause(self) -> None:
        for cause in ROOT_CAUSES:
            clause = self.index.for_cause(cause)
            assert clause is not None
            self.assertEqual(self.top(cause), clause.id, cause)

    def test_ac_10_7_chinese_keywords_find_their_clause(self) -> None:
        self.assertEqual(self.top("拆分"), "SOP-4.2")
        self.assertEqual(self.top("少到账"), "SOP-4.1")
        self.assertEqual(self.top("币种标签错"), "SOP-4.4")
        self.assertEqual(self.top("来历不明的来款"), "SOP-4.5")
        self.assertEqual(self.top("重复入账"), "SOP-4.3")
        self.assertEqual(self.top("提示注入 memo"), "SOP-3")
        self.assertEqual(self.top("拒答"), "SOP-2")

    def test_ac_10_7_search_returns_at_most_k_and_nothing_for_unknown_words(self) -> None:
        self.assertEqual(len(self.index.search("deposit", k=3)), 3)
        self.assertEqual(len(self.index.search("deposit", k=1)), 1)
        self.assertEqual(self.index.search("zzzqqq"), [])

    def test_ac_10_7_search_sop_tool_returns_clause_id_title_and_capped_text(self) -> None:
        tools = ToolRegistry(RecordingTransport(), local_tools=[SopSearch(self.index)])
        result = tools.call("search_sop", {"query": "BANK_SPLIT_DEPOSIT"})
        self.assertTrue(result.ok)
        first = result.data["results"][0]
        self.assertEqual(first["clause"], "SOP-4.2")
        self.assertTrue(first["title"])
        self.assertLessEqual(len(result.data["results"]), 3)
        for item in result.data["results"]:
            self.assertLessEqual(len(item["text"]), MAX_CLAUSE_CHARS)
        self.assertFalse(tools.call("search_sop", {"query": ""}).ok)


if __name__ == "__main__":
    unittest.main()
