"""Docker-free tests for the evaluation set tooling: plan, labels, composition, crawl and id-free comparison."""

import copy
import hashlib
import json
import tempfile
import unittest
from collections import Counter
from pathlib import Path

from eval import catalog, crawl, dataset, generate, verify_dataset
from ledgerlab_agent.records import Record
from ledgerlab_agent.replay import ReplayTransport, SnapshotEntry, load_snapshot, request_key
from ledgerlab_agent.tools import ToolRegistry
from ledgerlab_agent.transport import TransportResponse

DATA = Path(__file__).resolve().parents[1] / "eval" / "data"


def fake_id(prefix: str, key: str, salt: str = "") -> str:
    digits = hashlib.sha256((key + "|" + salt).encode()).hexdigest()[:32]
    return f"{prefix}_{digits}"


def fake_capture(plan: catalog.Plan, salt: str = ""):
    """Issues, facts and a crawl that satisfy every expectation of the plan (ids derived from `salt`)."""
    facts, views, reached, entries = {}, [], {}, {}
    for payout in plan.payouts:
        po, ch = fake_id("po", payout.key, salt), fake_id("ch", payout.key, salt)
        facts[payout.key] = {"payout": po, "charge": ch}
        for number, e in enumerate(payout.expectations):
            ref = None if e.subject_ref is None else dataset.fill(e.subject_ref, po, ch)
            text = " ".join(dataset.fill(token, po, ch) for token in e.contains)
            issue_id = fake_id("iss", e.case_id, salt)
            views.append(
                dataset.IssueView(issue_id, e.category, "OPEN", e.subject_type, ref or "subject-" + text,
                                  {"amount": payout.case.amount}, {"seen": number}, None,
                                  json.dumps({"issue": {"id": issue_id}, "text": text})))
            records = set()
            for kind, minimum in e.evidence:
                records |= {Record(kind, f"{kind}-{e.case_id}-{i}") for i in range(minimum)}
            reached[issue_id] = records
        entries[request_key("/api/bank-lines", {"reference": po})] = SnapshotEntry(
            200, json.dumps({"lines": [{"bankTxnId": f"bt_{po}_1"}]}).encode())
        entries[request_key(f"/api/batches/{po}", {})] = SnapshotEntry(200, b"{}")
    return facts, views, crawl.CrawlResult(entries, reached)


class PlanTest(unittest.TestCase):
    def test_ac_10_5_plan_is_deterministic_for_a_seed(self) -> None:
        for name in catalog.STANDARD_SPLITS:
            first, second = catalog.build_plan(name), catalog.build_plan(name)
            self.assertEqual([p.case for p in first.payouts], [p.case for p in second.payouts])
            self.assertEqual(first.withheld_sources, second.withheld_sources)

    def test_ac_10_5_dev_and_test_use_different_seeds_amounts_and_combinations(self) -> None:
        dev, test = catalog.SPLITS["dev"], catalog.SPLITS["test"]
        self.assertNotEqual(dev.seed, test.seed)
        self.assertLess(dev.amount_max, test.amount_min)
        self.assertNotEqual(set(dev.combos), set(test.combos))

    def test_ac_10_5_amounts_stay_in_the_split_range(self) -> None:
        for name, config in catalog.SPLITS.items():
            for payout in catalog.build_plan(name).payouts:
                self.assertTrue(config.amount_min <= payout.case.amount <= config.amount_max, payout.case)

    def test_ac_10_5_the_plan_alone_satisfies_the_composition_rules(self) -> None:
        for name in catalog.STANDARD_SPLITS:
            plan = catalog.build_plan(name)
            facts, views, crawled = fake_capture(plan)
            labels = dataset.build_labels(plan, facts, views, crawled)
            self.assertEqual(dataset.validate_composition(labels), [], name)

    def test_ac_10_5_injection_memos_demand_something_other_than_the_true_cause(self) -> None:
        for name in catalog.SPLITS:
            for e in catalog.build_plan(name).expectations():
                if e.injection is not None:
                    self.assertNotEqual(e.injection.demanded_root_cause, e.root_cause)

    def test_ac_10_5_injection_payouts_carry_the_memo_on_a_bank_fault(self) -> None:
        for payout in catalog.build_plan("dev").payouts:
            injected = any(e.injection for e in payout.expectations)
            self.assertEqual(payout.case.bank_memo is not None, injected)
            if injected:
                self.assertIsNotNone(payout.case.bank_fault)

    def test_ac_10_5_combos_pair_one_processor_fault_with_one_bank_fault(self) -> None:
        for name in catalog.STANDARD_SPLITS:
            for payout in catalog.build_plan(name).payouts:
                kinds = {e.kind for e in payout.expectations}
                if kinds == {catalog.KIND_COMBO}:
                    sides = {e.side for e in payout.expectations}
                    self.assertEqual(sides, {catalog.SIDE_BANK, catalog.SIDE_PROCESSOR})

    def test_ac_10_5_adjustment_payouts_are_alone_in_their_group(self) -> None:
        plan = catalog.build_plan("dev")
        for payout in plan.payouts:
            has_adjustment = "extra_line" in payout.case.faults_before_reconcile
            self.assertEqual(has_adjustment, payout.group == catalog.GROUP_ADJUST)
        self.assertTrue(all(p.case.bank_memo is None for p in plan.payouts if p.group == catalog.GROUP_ADJUST))

    def test_ac_10_5_withheld_sources_cover_distinct_causes(self) -> None:
        plan = catalog.build_plan("dev")
        by_case = {e.case_id: e for e in plan.expectations()}
        causes = [by_case[c].root_cause for c in plan.withheld_sources]
        self.assertEqual(len(causes), len(set(causes)))
        self.assertGreaterEqual(len(causes), catalog.MIN_WITHHELD)
        self.assertTrue(all(by_case[c].kind == catalog.KIND_SINGLE for c in plan.withheld_sources))


class LabelTest(unittest.TestCase):
    def setUp(self) -> None:
        self.plan = catalog.build_plan("dev")
        self.facts, self.views, self.crawled = fake_capture(self.plan)

    def test_ac_10_5_labels_record_what_was_injected_and_which_issue_it_matched(self) -> None:
        labels = dataset.build_labels(self.plan, self.facts, self.views, self.crawled)
        short = next(x for x in labels if x["root_cause"] == "BANK_SHORT_DEPOSIT" and x["kind"] == "single")
        self.assertEqual(short["issue_match"]["category"], "DEPOSIT_AMOUNT_MISMATCH")
        self.assertEqual(short["issue_match"]["subject_ref"], f"bt_{short['payout']}_1")
        self.assertTrue(short["issue_id"].startswith("iss_"))
        self.assertFalse(short["expected_abstain"])
        self.assertIsNone(short["withheld"])

    def test_ac_10_8_withheld_copies_abstain_and_name_the_requests_they_take_away(self) -> None:
        labels = dataset.build_labels(self.plan, self.facts, self.views, self.crawled)
        withheld = [x for x in labels if x["kind"] == catalog.KIND_WITHHELD]
        self.assertEqual(len(withheld), len(self.plan.withheld_sources))
        for label in withheld:
            self.assertEqual(label["root_cause"], catalog.ABSTAIN)
            self.assertTrue(label["expected_abstain"])
            self.assertEqual(label["case_id"], label["source_case_id"] + "-withheld")
            self.assertTrue(label["withheld"])
            for request in label["withheld"]:
                self.assertIn(request["tool"], ("get_batch", "search_bank_lines", "get_bank_line"))
            normal = next(x for x in labels if x["case_id"] == label["source_case_id"])
            self.assertEqual(normal["issue_id"], label["issue_id"])

    def test_ac_10_8_processor_causes_withhold_get_batch_and_bank_causes_withhold_bank_lines(self) -> None:
        labels = dataset.build_labels(self.plan, self.facts, self.views, self.crawled)
        for label in (x for x in labels if x["kind"] == catalog.KIND_WITHHELD):
            tools = {w["tool"] for w in label["withheld"]}
            if catalog.CAUSES[label["underlying_root_cause"]].side == catalog.SIDE_PROCESSOR:
                self.assertEqual(tools, {"get_batch"})
            else:
                self.assertIn("search_bank_lines", tools)

    def test_ac_10_5_a_label_that_matches_no_issue_fails_the_generation(self) -> None:
        victim = self.views[3]
        broken = [v for v in self.views if v is not victim]
        with self.assertRaises(dataset.DatasetError) as caught:
            dataset.build_labels(self.plan, self.facts, broken, self.crawled)
        self.assertIn("found 0", str(caught.exception))

    def test_ac_10_5_a_label_that_matches_two_issues_fails_the_generation(self) -> None:
        twin = copy.copy(self.views[3])
        twin = dataset.IssueView(**{**twin.__dict__, "id": "iss_twin"})
        with self.assertRaises(dataset.DatasetError) as caught:
            dataset.build_labels(self.plan, self.facts, [*self.views, twin], self.crawled)
        self.assertIn("found 2", str(caught.exception))

    def test_ac_10_5_a_state_other_than_open_does_not_match(self) -> None:
        moved = [dataset.IssueView(**{**v.__dict__, "status": "EXPLAINED"}) for v in self.views]
        with self.assertRaises(dataset.DatasetError):
            dataset.build_labels(self.plan, self.facts, moved, self.crawled)

    def test_ac_10_5_evidence_the_crawl_cannot_reach_fails_the_generation(self) -> None:
        reached = {k: set() for k in self.crawled.reached}
        with self.assertRaises(dataset.DatasetError) as caught:
            dataset.build_labels(self.plan, self.facts, self.views, crawl.CrawlResult(self.crawled.entries, reached))
        self.assertIn("does not reach", str(caught.exception))


class CompositionTest(unittest.TestCase):
    def labels(self):
        plan = catalog.build_plan("dev")
        facts, views, crawled = fake_capture(plan)
        return dataset.build_labels(plan, facts, views, crawled)

    def test_ac_10_5_too_few_questions_per_cause_is_reported(self) -> None:
        labels = [x for x in self.labels() if not (x["root_cause"] == "WEBHOOK_LOST" and x["kind"] == "single")][:]
        problems = dataset.validate_composition(labels)
        self.assertTrue(any("WEBHOOK_LOST" in p for p in problems), problems)

    def test_ac_10_5_too_few_combos_injections_withheld_and_total_are_reported(self) -> None:
        labels = [x for x in self.labels() if x["kind"] == catalog.KIND_SINGLE]
        text = " ".join(dataset.validate_composition(labels))
        for needle in ("combo", "injection", "withheld"):
            self.assertIn(needle, text)
        self.assertIn("in total", " ".join(dataset.validate_composition(labels[:10])))

    def test_ac_10_5_an_issue_with_two_normal_questions_is_reported(self) -> None:
        labels = self.labels()
        clone = dict(labels[0], case_id="dup-case")
        self.assertTrue(any("2 questions" in p for p in dataset.validate_composition([*labels, clone])))

    def test_ac_10_9_an_injection_that_demands_the_true_cause_is_reported(self) -> None:
        labels = self.labels()
        injected = next(x for x in labels if x["kind"] == catalog.KIND_INJECTION)
        injected["injection"]["demanded_root_cause"] = injected["root_cause"]
        self.assertTrue(any("demands the true cause" in p for p in dataset.validate_composition(labels)))

    def test_ac_10_9_an_injection_that_demands_escalate_is_reported(self) -> None:
        labels = self.labels()
        injected = next(x for x in labels if x["kind"] == catalog.KIND_INJECTION)
        injected["injection"]["demanded_action"] = "ESCALATE"
        self.assertTrue(any("action of every abstention" in p for p in dataset.validate_composition(labels)))

    def test_ac_10_8_a_withheld_question_that_does_not_abstain_is_reported(self) -> None:
        labels = self.labels()
        withheld = next(x for x in labels if x["kind"] == catalog.KIND_WITHHELD)
        withheld["expected_abstain"] = False
        self.assertTrue(any("disagree" in p for p in dataset.validate_composition(labels)))


class IdFreeComparisonTest(unittest.TestCase):
    def normalized_for(self, salt: str, mutate=None):
        plan = catalog.build_plan("dev")
        facts, views, crawled = fake_capture(plan, salt)
        labels = dataset.build_labels(plan, facts, views, crawled)
        if mutate:
            mutate(labels, views)
        return dataset.normalized(labels, dataset.issue_digest(views))

    def test_ac_10_5_data_that_differs_only_in_ids_is_the_same(self) -> None:
        self.assertEqual(dataset.differences(self.normalized_for("a"), self.normalized_for("b")), [])

    def test_ac_10_5_a_different_amount_is_a_difference(self) -> None:
        def mutate(labels, views):
            views[0] = dataset.IssueView(**{**views[0].__dict__, "actual": {"unapplied": 1}})

        problems = dataset.differences(self.normalized_for("a"), self.normalized_for("a", mutate))
        self.assertTrue(any("issue only in" in p for p in problems), problems)

    def test_ac_10_5_a_changed_label_is_a_difference(self) -> None:
        def mutate(labels, views):
            labels[0]["root_cause"] = "WEBHOOK_LOST"

        problems = dataset.differences(self.normalized_for("a"), self.normalized_for("a", mutate))
        self.assertTrue(any("differs in root_cause" in p for p in problems), problems)

    def test_ac_10_5_rows_that_differ_only_in_unlabelled_ids_compare_equal_in_any_order(self) -> None:
        def row(txn: str) -> dict:
            return {"category": "DUPLICATE_STATEMENT_LINE", "status": "OPEN", "subject_type": "statement_line",
                    "subject_id": txn, "expected": {"count": 1}, "actual": {"count": 2}, "due_at": None}

        a, b, c, d = (f"txn_{x * 31}1" for x in "abcd")
        first = dataset.normalized([], [row(a), row(b)])
        second = dataset.normalized([], [row(d), row(c)])
        self.assertEqual(dataset.differences(first, second), [])
        third = dataset.normalized([], [row(a)])
        self.assertNotEqual(dataset.differences(first, third), [])

    def test_ac_10_5_id_runs_are_mapped_in_order_of_first_appearance_and_prefixes_are_kept(self) -> None:
        mapper = dataset.IdMapper()
        text = mapper('bt_po_0123456789abcdef0123456789abcdef_2 and pay_01M6QHVK8Y257E0HQ24VS2HA25 again '
                      'bt_po_0123456789abcdef0123456789abcdef_1 and TIMING_DIFFERENCE 2026-10-22')
        self.assertEqual(text, "bt_po_<id1>_2 and pay_<id2> again bt_po_<id1>_1 and TIMING_DIFFERENCE 2026-10-22")

    def test_ac_10_5_compare_directories_ignores_ids_between_two_written_datasets(self) -> None:
        plan = catalog.build_plan("dev")
        with tempfile.TemporaryDirectory() as tmp:
            roots = []
            for salt in ("a", "b"):
                facts, views, crawled = fake_capture(plan, salt)
                labels = dataset.build_labels(plan, facts, views, crawled)
                root = Path(tmp) / salt
                root.mkdir()
                (root / "labels.json").write_text(json.dumps({"labels": labels}), encoding="utf-8")
                counts = dict(dataset.composition_counts(labels), payouts=49, issues_in_snapshot=len(views),
                              issues_with_a_question=len(views))
                manifest = {"seed": 1, "amount_range_cents": [1, 2], "time_plan": {}, "counts": counts,
                            "issue_digest": dataset.issue_digest(views)}
                (root / "manifest.json").write_text(json.dumps(manifest), encoding="utf-8")
                roots.append(root)
            self.assertEqual(verify_dataset.compare_directories(roots[0], roots[1]), [])


class CrawlTest(unittest.TestCase):
    ISSUE = "iss_" + "A" * 26
    PAYOUT = "po_" + "b" * 32

    def responses(self):
        bank = f"bt_{self.PAYOUT}_1"
        return {
            f"/api/recon/issues/{self.ISSUE}": {"issue": {"id": self.ISSUE, "subjectType": "batch",
                                                         "subjectId": self.PAYOUT}},
            f"/api/batches/{self.PAYOUT}": {"batch": {"batchId": self.PAYOUT}, "lines": []},
            "/api/bank-lines": {"lines": [{"bankTxnId": bank, "reference": self.PAYOUT}]},
            f"/api/bank-lines/{bank}": {"bankLine": {"bankTxnId": bank, "reference": self.PAYOUT},
                                        "versions": [], "entryId": "je_" + "1" * 26},
            "/api/entries/je_" + "1" * 26: {"entry": {"id": "je_" + "1" * 26}},
            "/api/recon/issues": {"issues": []},
        }

    def transport(self):
        table = self.responses()

        class Fake:
            def __init__(self) -> None:
                self.calls = []

            def get(self, path, query):
                self.calls.append((path, dict(query)))
                if path in table:
                    return TransportResponse(200, json.dumps(table[path]).encode())
                return TransportResponse(404, b'{"error":"not found"}')

        return Fake()

    def test_ac_10_5_crawl_follows_records_and_searches_bank_references(self) -> None:
        fake = self.transport()
        result = crawl.crawl(fake, [self.ISSUE])
        paths = {p for p, _ in fake.calls}
        self.assertIn(f"/api/batches/{self.PAYOUT}", paths)
        self.assertIn("/api/bank-lines", paths)
        self.assertIn(request_key("/api/bank-lines", {"reference": self.PAYOUT}), result.entries)
        self.assertIn(Record("bank_line", f"bt_{self.PAYOUT}_1"), result.reached[self.ISSUE])

    def test_ac_10_5_crawl_stops_at_the_depth_limit(self) -> None:
        shallow = crawl.crawl(self.transport(), [self.ISSUE], max_depth=1)
        self.assertNotIn(request_key("/api/entries/je_" + "1" * 26, {}), shallow.entries)
        deep = crawl.crawl(self.transport(), [self.ISSUE], max_depth=3)
        self.assertIn(request_key("/api/entries/je_" + "1" * 26, {}), deep.entries)

    def test_ac_10_5_crawl_records_the_common_issue_listings(self) -> None:
        result = crawl.crawl(self.transport(), [self.ISSUE])
        self.assertIn(request_key("/api/recon/issues", {}), result.entries)
        self.assertIn(request_key("/api/recon/issues", {"status": "OPEN"}), result.entries)

    def test_ac_10_5_crawl_only_uses_get_and_does_not_record_failures_as_responses(self) -> None:
        fake = self.transport()
        crawl.crawl(fake, [self.ISSUE])
        self.assertTrue(all(isinstance(path, str) and path.startswith("/api/") for path, _ in fake.calls))
        result = crawl.crawl(fake, ["iss_" + "Z" * 26])
        self.assertTrue(all(entry.status == 200 or entry.status == 404 for entry in result.entries.values()))


class SnapshotFileTest(unittest.TestCase):
    def test_ac_10_5_rendered_snapshot_is_valid_json_one_request_per_line_and_loads_back(self) -> None:
        from ledgerlab_agent.replay import snapshot_document

        entries = [(request_key("/a", {"q": "1"}), SnapshotEntry(200, b'{"x":1}')),
                   (request_key("/b", {}), SnapshotEntry(404, b"nope"))]
        text = generate.render_snapshot(snapshot_document(entries, "t", "c"))
        parsed = json.loads(text)
        self.assertEqual(len(parsed["requests"]), 2)
        self.assertEqual(text.count("\n"), 4)
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "snapshot.json"
            path.write_text(text, encoding="utf-8")
            self.assertEqual(len(load_snapshot(path)), 2)


class FrozenDataTest(unittest.TestCase):
    """Smoke tests over the committed dev and test sets (generated by scripts/agent/generate-eval.sh)."""

    def test_ac_10_5_frozen_sets_exist_and_satisfy_the_composition_rules(self) -> None:
        for name in catalog.STANDARD_SPLITS:
            directory = DATA / name
            for file in generate.FILES:
                self.assertTrue((directory / file).is_file(), f"{directory / file} is missing")
            labels = json.loads((directory / "labels.json").read_text(encoding="utf-8"))["labels"]
            self.assertEqual(dataset.validate_composition(labels), [], name)
            manifest = json.loads((directory / "manifest.json").read_text(encoding="utf-8"))
            self.assertEqual(manifest["seed"], catalog.SPLITS[name].seed)
            self.assertEqual(manifest["counts"]["questions"], len(labels))
            self.assertLessEqual((directory / "snapshot.json").stat().st_size, generate.MAX_SNAPSHOT_BYTES)

    def test_ac_10_5_every_labelled_issue_can_be_fetched_from_the_dev_snapshot(self) -> None:
        directory = DATA / "dev"
        snapshot = load_snapshot(directory / "snapshot.json")
        labels = json.loads((directory / "labels.json").read_text(encoding="utf-8"))["labels"]
        registry = ToolRegistry(ReplayTransport(snapshot))
        for label in labels:
            result = registry.call("get_issue", {"issue_id": label["issue_id"]})
            self.assertTrue(result.ok, label["case_id"])
            self.assertEqual(result.status, 200)

    def test_ac_10_8_withheld_requests_are_in_the_snapshot_and_answer_503_in_replay(self) -> None:
        for name in catalog.SPLITS:
            snapshot = load_snapshot(DATA / name / "snapshot.json")
            labels = json.loads((DATA / name / "labels.json").read_text(encoding="utf-8"))["labels"]
            for label in (x for x in labels if x["withheld"]):
                withheld = [(w["path"], w["query"]) for w in label["withheld"]]
                for path, query in withheld:
                    self.assertIn(request_key(path, query), snapshot.entries, label["case_id"])
                replay = ReplayTransport(snapshot, withheld=withheld)
                self.assertTrue(all(replay.get(p, q).status == 503 for p, q in withheld))

    def test_ac_10_9_the_injected_text_is_visible_in_the_snapshot_of_each_injection_question(self) -> None:
        for name in catalog.SPLITS:
            snapshot = load_snapshot(DATA / name / "snapshot.json")
            labels = json.loads((DATA / name / "labels.json").read_text(encoding="utf-8"))["labels"]
            registry = ToolRegistry(ReplayTransport(snapshot))
            for label in (x for x in labels if x["injection"]):
                marker = label["injection"]["text"][:30]
                seen = [
                    json.dumps(registry.call(tool, args).data, ensure_ascii=False)
                    for tool, args in (
                        ("get_issue", {"issue_id": label["issue_id"]}),
                        ("search_bank_lines", {"reference": label["payout"]}),
                        ("search_bank_lines", {"reference": "unknown_" + label["payout"]}),
                    )
                ]
                self.assertTrue(any(marker in text for text in seen), label["case_id"])

    def test_ac_10_5_each_question_set_counts_match_the_manifest(self) -> None:
        for name in catalog.STANDARD_SPLITS:
            labels = json.loads((DATA / name / "labels.json").read_text(encoding="utf-8"))["labels"]
            manifest = json.loads((DATA / name / "manifest.json").read_text(encoding="utf-8"))
            self.assertEqual(manifest["counts"]["by_kind"], dict(sorted(Counter(x["kind"] for x in labels).items())))


class HardPlanTest(unittest.TestCase):
    """The hard split is a held-out set of by-product questions, new combinations and new injection texts."""

    def setUp(self) -> None:
        self.plan = catalog.build_plan("hard")
        self.facts, self.views, self.crawled = fake_capture(self.plan)
        self.labels = dataset.build_labels(self.plan, self.facts, self.views, self.crawled)

    def test_ac_10_5_the_hard_split_has_its_own_seed_and_amount_range(self) -> None:
        hard = catalog.SPLITS["hard"]
        others = [catalog.SPLITS[name] for name in catalog.STANDARD_SPLITS]
        self.assertEqual((hard.amount_min, hard.amount_max), (90001, 99000))
        self.assertNotIn(hard.seed, {o.seed for o in others})
        self.assertTrue(all(hard.amount_min > o.amount_max for o in others))
        self.assertTrue(hard.hard)
        self.assertIn("must not", hard.note)

    def test_ac_10_5_the_plan_alone_satisfies_the_hard_composition_rules(self) -> None:
        self.assertEqual(dataset.validate_hard_composition(self.labels), [])
        kinds = Counter(x["kind"] for x in self.labels)
        self.assertGreaterEqual(kinds[catalog.KIND_COLLATERAL], 20)
        self.assertGreaterEqual(kinds[catalog.KIND_COMBO], 6)
        self.assertGreaterEqual(kinds[catalog.KIND_INJECTION], 6)
        self.assertGreaterEqual(kinds[catalog.KIND_WITHHELD], 4)
        self.assertGreaterEqual(len(self.labels), 40)
        self.assertEqual(kinds[catalog.KIND_SINGLE], 0)

    def test_ac_10_5_a_by_product_question_sits_on_a_payout_with_exactly_one_fault(self) -> None:
        for payout in self.plan.payouts:
            for e in payout.expectations:
                if e.kind in (catalog.KIND_COLLATERAL, catalog.KIND_INJECTION):
                    self.assertEqual(len(e.payout_causes), 1, e.case_id)
                    self.assertEqual(e.payout_causes[0], e.root_cause)
                    self.assertTrue(e.collateral)
                    self.assertEqual(payout.case.injection, e.root_cause)  # nothing else was put on it

    def test_ac_10_5_by_product_questions_are_about_categories_the_cause_does_not_own(self) -> None:
        for spec in catalog.COLLATERAL.values():
            self.assertNotEqual(spec.category, catalog.CAUSES[spec.root_cause].category, spec.key)

    def test_ac_10_5_hard_combos_are_new_pairs(self) -> None:
        used = {pair for name in catalog.STANDARD_SPLITS for pair in catalog.SPLITS[name].combos}
        hard = set(catalog.SPLITS["hard"].combos)
        self.assertFalse(hard & used)
        self.assertGreaterEqual(len(hard), 6)

    def test_ac_10_9_hard_injection_texts_are_new_and_sit_on_by_product_questions(self) -> None:
        old = {t.text for t in catalog.INJECTION_TEXTS[: catalog.FIRST_HARD_INJECTION_TEXT]}
        injected = [x for x in self.labels if x["kind"] == catalog.KIND_INJECTION]
        self.assertTrue(all(x["collateral"] for x in injected))
        self.assertTrue(all(x["injection"]["text"] not in old for x in injected))
        for payout in self.plan.payouts:
            injected_here = any(e.injection for e in payout.expectations)
            self.assertEqual(payout.case.bank_memo is not None, injected_here)
            if injected_here:
                self.assertIsNotNone(payout.case.bank_fault)

    def test_ac_10_8_hard_withheld_copies_come_from_by_product_questions(self) -> None:
        by_id = {x["case_id"]: x for x in self.labels}
        withheld = [x for x in self.labels if x["kind"] == catalog.KIND_WITHHELD]
        self.assertGreaterEqual(len(withheld), 4)
        for label in withheld:
            self.assertEqual(by_id[label["source_case_id"]]["kind"], catalog.KIND_COLLATERAL)

    def test_ac_10_5_adjustment_payouts_stay_alone_in_their_group(self) -> None:
        for payout in self.plan.payouts:
            self.assertEqual("extra_line" in payout.case.faults_before_reconcile, payout.group == catalog.GROUP_ADJUST)

    def test_ac_10_5_standard_labels_keep_their_old_shape(self) -> None:
        plan = catalog.build_plan("dev")
        facts, views, crawled = fake_capture(plan)
        for label in dataset.build_labels(plan, facts, views, crawled):
            self.assertNotIn("payout_causes", label)
            self.assertNotIn("collateral", label)

    def test_ac_10_5_a_hard_payout_with_two_faults_is_reported_as_a_by_product_question(self) -> None:
        labels = copy.deepcopy(self.labels)
        victim = next(x for x in labels if x["kind"] == catalog.KIND_COLLATERAL)
        victim["payout_causes"] = [victim["root_cause"], "PROCESSOR_FEE_DIFFERS"]
        self.assertTrue(any("exactly one injected fault" in p for p in dataset.validate_hard_composition(labels)))

    def test_ac_10_5_hard_composition_reports_each_shortfall(self) -> None:
        labels = [x for x in self.labels if x["kind"] == catalog.KIND_COLLATERAL][:5]
        text = " ".join(dataset.validate_hard_composition(labels))
        for needle in ("collateral", "combo", "injection", "withheld", "in total"):
            self.assertIn(needle, text)

    def test_ac_10_9_a_reused_or_one_sided_injection_text_set_is_reported(self) -> None:
        labels = copy.deepcopy(self.labels)
        injected = [x for x in labels if x["kind"] == catalog.KIND_INJECTION]
        injected[0]["injection"]["text"] = catalog.INJECTION_TEXTS[0].text
        self.assertTrue(any("reused" in p for p in dataset.validate_hard_composition(labels)))
        for label in injected:
            label["injection"]["text"] = "Plain English only " + label["case_id"]
        text = " ".join(dataset.validate_hard_composition(labels))
        self.assertIn("at least 2 of each", text)
        self.assertIn("disguised", text)

    def test_ac_10_5_hard_is_never_part_of_the_all_selection(self) -> None:
        self.assertEqual(catalog.STANDARD_SPLITS, ("dev", "test"))
        with tempfile.TemporaryDirectory() as tmp:
            calls: list[str] = []

            def fail(*args, **kwargs):
                calls.append(args[0])
                raise generate.ScenarioError("stop")

            original = generate.generate_split
            generate.generate_split = fail
            try:
                generate.main(["--split", "all", "--out-dir", tmp, "--raw-dir", tmp])
            finally:
                generate.generate_split = original
            self.assertEqual(calls, ["dev"])


class HardFrozenDataTest(unittest.TestCase):
    def test_ac_10_5_the_committed_hard_set_satisfies_the_hard_rules(self) -> None:
        directory = DATA / "hard"
        for file in generate.FILES:
            self.assertTrue((directory / file).is_file(), f"{directory / file} is missing")
        labels = json.loads((directory / "labels.json").read_text(encoding="utf-8"))["labels"]
        self.assertEqual(dataset.validate_hard_composition(labels), [])
        manifest = json.loads((directory / "manifest.json").read_text(encoding="utf-8"))
        self.assertEqual(manifest["seed"], catalog.SPLITS["hard"].seed)
        self.assertEqual(manifest["amount_range_cents"], [90001, 99000])
        self.assertIn("must not", manifest["note"])
        self.assertEqual(manifest["counts"]["questions"], len(labels))
        self.assertEqual(manifest["counts"]["by_kind"], dict(sorted(Counter(x["kind"] for x in labels).items())))
        self.assertLessEqual((directory / "snapshot.json").stat().st_size, generate.MAX_SNAPSHOT_BYTES)

    def test_ac_10_5_every_hard_question_points_at_one_fetchable_issue_of_the_asked_category(self) -> None:
        directory = DATA / "hard"
        labels = json.loads((directory / "labels.json").read_text(encoding="utf-8"))["labels"]
        registry = ToolRegistry(ReplayTransport(load_snapshot(directory / "snapshot.json")))
        for label in labels:
            result = registry.call("get_issue", {"issue_id": label["issue_id"]})
            self.assertTrue(result.ok, label["case_id"])
            issue = result.data.get("issue", result.data)
            self.assertEqual(issue["category"], label["issue_match"]["category"], label["case_id"])
            self.assertEqual(issue["status"], "OPEN", label["case_id"])


if __name__ == "__main__":
    unittest.main()
