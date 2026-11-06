"""Evaluation dataset logic that needs no network: label building and checking, composition, id-free comparison.

Labels describe what the generator injected; the issue a label points at is looked up afterwards by category,
subject type and subject id (or an id that must appear in the issue). A label that cannot be matched to exactly
one issue makes the generation fail instead of being dropped.
"""

from __future__ import annotations

import json
import re
import sys
from collections import Counter
from dataclasses import dataclass
from pathlib import Path
from typing import Any

AGENT_DIR = Path(__file__).resolve().parents[1]
for _path in (AGENT_DIR, AGENT_DIR / "src"):
    if str(_path) not in sys.path:
        sys.path.insert(0, str(_path))

from eval import catalog  # noqa: E402
from eval.crawl import CrawlResult  # noqa: E402
from ledgerlab_agent.enums import RECORD_KINDS, RECOMMENDED_ACTIONS, ROOT_CAUSES  # noqa: E402
from ledgerlab_agent.replay import RequestKey, SnapshotEntry, request_key  # noqa: E402

LABELS_FORMAT = 1
EXPECTED_STATUS = "OPEN"


class DatasetError(RuntimeError):
    """The generated data does not satisfy the labels or the composition rules."""


# ---- issue views ------------------------------------------------------------------------------------------------
@dataclass(frozen=True)
class IssueView:
    id: str
    category: str
    status: str
    subject_type: str
    subject_id: str
    expected: Any
    actual: Any
    due_at: Any
    text: str  # whole detail as JSON, used for "this id appears in the issue"


def issue_view(detail: Any) -> IssueView:
    issue = detail.get("issue", detail) if isinstance(detail, dict) else {}

    def pick(*names: str) -> Any:
        for name in names:
            if issue.get(name) is not None:
                return issue[name]
        return None

    return IssueView(
        id=str(pick("id", "issueId")),
        category=str(pick("category")),
        status=str(pick("status")),
        subject_type=str(pick("subjectType", "subject_type")),
        subject_id=str(pick("subjectId", "subject_id")),
        expected=pick("expected"),
        actual=pick("actual"),
        due_at=pick("dueAt", "due_at"),
        text=json.dumps(detail, sort_keys=True, ensure_ascii=False),
    )


def fill(template: str, payout: str, charge: str) -> str:
    return template.replace("{po}", payout).replace("{ch}", charge)


def find_issues(match: dict[str, Any], issues: list[IssueView]) -> list[IssueView]:
    found = []
    for issue in issues:
        if issue.category != match["category"] or issue.subject_type != match["subject_type"]:
            continue
        if issue.status != match["status"]:
            continue
        if match["subject_ref"] is not None and issue.subject_id != match["subject_ref"]:
            continue
        if any(token not in issue.text for token in match["contains"]):
            continue
        found.append(issue)
    return found


# ---- labels ---------------------------------------------------------------------------------------------------------
def bank_txn_ids(entries: dict[RequestKey, SnapshotEntry], payout: str) -> list[str]:
    ids: list[str] = []
    for reference in (payout, f"unknown_{payout}"):
        entry = entries.get(request_key("/api/bank-lines", {"reference": reference}))
        if entry is None or entry.status != 200:
            continue
        try:
            lines = json.loads(entry.body.decode("utf-8")).get("lines", [])
        except (ValueError, AttributeError):
            continue
        ids.extend(str(line["bankTxnId"]) for line in lines if isinstance(line, dict) and "bankTxnId" in line)
    return list(dict.fromkeys(ids))


def build_labels(
    plan: catalog.Plan,
    facts: dict[str, dict[str, Any]],
    issues: list[IssueView],
    crawl: CrawlResult,
) -> list[dict[str, Any]]:
    """facts: payout plan key -> {"payout": po_..., "charge": ch_...} as reported by the processor.

    Returns the labels in plan order, withheld copies last. Raises DatasetError listing every problem.
    """
    problems: list[str] = []
    labels: list[dict[str, Any]] = []
    by_case: dict[str, dict[str, Any]] = {}
    payout_of: dict[str, tuple[str, str]] = {}
    for payout in plan.payouts:
        po, ch = facts[payout.key]["payout"], facts[payout.key]["charge"]
        for e in payout.expectations:
            match = {
                "category": e.category,
                "subject_type": e.subject_type,
                "subject_ref": None if e.subject_ref is None else fill(e.subject_ref, po, ch),
                "contains": [fill(token, po, ch) for token in e.contains],
                "status": EXPECTED_STATUS,
            }
            found = find_issues(match, issues)
            if len(found) != 1:
                problems.append(
                    f"{e.case_id}: expected exactly one {e.category}/{e.subject_type} issue for {po}, found {len(found)}"
                )
                continue
            issue = found[0]
            reached = Counter(r.kind for r in crawl.reached.get(issue.id, set()))
            missing = [f"{kind}x{minimum}" for kind, minimum in e.evidence if reached[kind] < minimum]
            if missing:
                problems.append(f"{e.case_id}: crawl does not reach the evidence {', '.join(missing)}")
            label = {
                "case_id": e.case_id,
                "kind": e.kind,
                "issue_id": issue.id,
                "payout": po,
                "issue_match": match,
                "root_cause": e.root_cause,
                "required_evidence_kinds": [{"kind": kind, "min": minimum} for kind, minimum in e.evidence],
                "injection": None
                if e.injection is None
                else {
                    "text": e.injection.text,
                    "demanded_root_cause": e.injection.demanded_root_cause,
                    "demanded_action": e.injection.demanded_action,
                },
                "withheld": None,
                "expected_abstain": False,
            }
            labels.append(label)
            by_case[e.case_id] = label
            payout_of[e.case_id] = (po, e.side)
    for source_id in plan.withheld_sources:
        source = by_case.get(source_id)
        if source is None:
            continue  # Already reported as unmatched above.
        po, side = payout_of[source_id]
        wanted = catalog.withheld_requests(side, po, bank_txn_ids(crawl.entries, po))
        present = [w for w in wanted if request_key(w["path"], w["query"]) in crawl.entries]
        if not present:
            problems.append(f"{source_id}: none of the requests to withhold is in the snapshot")
            continue
        labels.append(
            {
                **source,
                "case_id": f"{source_id}-withheld",
                "kind": catalog.KIND_WITHHELD,
                "source_case_id": source_id,
                "underlying_root_cause": source["root_cause"],
                "root_cause": catalog.ABSTAIN,
                "required_evidence_kinds": [],
                "injection": None,
                "withheld": present,
                "expected_abstain": True,
            }
        )
    if problems:
        raise DatasetError("label problems:\n  " + "\n  ".join(problems))
    return labels


def validate_composition(labels: list[dict[str, Any]]) -> list[str]:
    """Problems with a label list (empty when it satisfies design section 8.2 and is internally consistent)."""
    problems: list[str] = []
    ids = [label["case_id"] for label in labels]
    for dup in sorted(k for k, n in Counter(ids).items() if n > 1):
        problems.append(f"duplicate case_id {dup}")
    normal = [label for label in labels if label["kind"] != catalog.KIND_WITHHELD]
    issue_counts = Counter(label["issue_id"] for label in normal)
    problems += [f"issue {i} has {n} questions" for i, n in sorted(issue_counts.items()) if n > 1]
    for label in labels:
        cid = label["case_id"]
        if label["root_cause"] not in ROOT_CAUSES:
            problems.append(f"{cid}: unknown root_cause {label['root_cause']}")
        for item in label["required_evidence_kinds"]:
            if item["kind"] not in RECORD_KINDS:
                problems.append(f"{cid}: unknown evidence kind {item['kind']}")
        withheld = label["kind"] == catalog.KIND_WITHHELD
        if withheld != bool(label["withheld"]):
            problems.append(f"{cid}: withheld list and kind disagree")
        if label["expected_abstain"] != (label["root_cause"] == catalog.ABSTAIN) or label["expected_abstain"] != withheld:
            problems.append(f"{cid}: expected_abstain, root_cause and kind disagree")
        injection = label["injection"]
        if (label["kind"] == catalog.KIND_INJECTION) != (injection is not None):
            problems.append(f"{cid}: injection data and kind disagree")
        if injection is not None:
            if injection["demanded_root_cause"] not in ROOT_CAUSES or injection["demanded_action"] not in RECOMMENDED_ACTIONS:
                problems.append(f"{cid}: injection demands something outside the closed vocabularies")
            if injection["demanded_root_cause"] == label["root_cause"]:
                problems.append(f"{cid}: injection demands the true cause")
    singles = Counter(label["root_cause"] for label in labels if label["kind"] == catalog.KIND_SINGLE)
    for cause in catalog.CAUSES:
        if singles[cause] < catalog.MIN_PER_CAUSE:
            problems.append(f"{cause}: {singles[cause]} single questions, need {catalog.MIN_PER_CAUSE}")
    kinds = Counter(label["kind"] for label in labels)
    for kind, minimum in (
        (catalog.KIND_COMBO, catalog.MIN_COMBO),
        (catalog.KIND_INJECTION, catalog.MIN_INJECTION),
        (catalog.KIND_WITHHELD, catalog.MIN_WITHHELD),
    ):
        if kinds[kind] < minimum:
            problems.append(f"{kind}: {kinds[kind]} questions, need {minimum}")
    if len(labels) < catalog.MIN_TOTAL:
        problems.append(f"{len(labels)} questions in total, need {catalog.MIN_TOTAL}")
    return problems


def composition_counts(labels: list[dict[str, Any]]) -> dict[str, Any]:
    return {
        "questions": len(labels),
        "by_kind": dict(sorted(Counter(label["kind"] for label in labels).items())),
        "single_by_root_cause": dict(
            sorted(Counter(label["root_cause"] for label in labels if label["kind"] == catalog.KIND_SINGLE).items())
        ),
    }


# ---- id-free comparison --------------------------------------------------------------------------------------------
# Generated ids (32 hex digits, 26-character ULIDs, ...) differ between runs. Long alphanumeric runs that contain a
# digit are treated as ids; everything else (prefixes such as `bt_po_`, suffixes such as `_1`, words) is kept.
_ID_RUN = re.compile(r"(?<![0-9A-Za-z])(?=[0-9A-Za-z]*\d)[0-9A-Za-z]{24,}(?![0-9A-Za-z])")


class IdMapper:
    def __init__(self) -> None:
        self._seen: dict[str, str] = {}

    def __call__(self, text: str) -> str:
        def replace(match: re.Match[str]) -> str:
            return self._seen.setdefault(match.group(0), f"<id{len(self._seen) + 1}>")

        return _ID_RUN.sub(replace, text)


def issue_digest(issues: list[IssueView]) -> list[dict[str, Any]]:
    """One row per issue without its own id, sorted without looking at ids."""
    rows = [
        {
            "category": i.category,
            "status": i.status,
            "subject_type": i.subject_type,
            "subject_id": i.subject_id,
            "expected": i.expected,
            "actual": i.actual,
            "due_at": i.due_at,
        }
        for i in issues
    ]
    return sorted(rows, key=lambda row: _ID_RUN.sub("#", json.dumps(row, sort_keys=True, ensure_ascii=False)))


def normalized(labels: list[dict[str, Any]], digest: list[dict[str, Any]]) -> dict[str, Any]:
    """Labels and issue digest with ids replaced by placeholders numbered in order of first appearance."""
    mapper = IdMapper()
    labels_text = mapper(json.dumps(sorted(labels, key=lambda x: x["case_id"]), sort_keys=True, ensure_ascii=False))
    digest_text = mapper(json.dumps(digest, sort_keys=True, ensure_ascii=False))
    return {"labels": json.loads(labels_text), "issues": json.loads(digest_text)}


def differences(left: dict[str, Any], right: dict[str, Any]) -> list[str]:
    """Human readable differences between two normalized documents (empty when equal)."""
    out: list[str] = []
    left_labels = {label["case_id"]: label for label in left["labels"]}
    right_labels = {label["case_id"]: label for label in right["labels"]}
    for case_id in sorted(set(left_labels) | set(right_labels)):
        if case_id not in left_labels:
            out.append(f"label {case_id} only in the regenerated data")
        elif case_id not in right_labels:
            out.append(f"label {case_id} only in the committed data")
        elif left_labels[case_id] != right_labels[case_id]:
            keys = sorted(k for k in left_labels[case_id] if left_labels[case_id][k] != right_labels[case_id].get(k))
            out.append(f"label {case_id} differs in {', '.join(keys)}")
    if left["issues"] != right["issues"]:
        counts_left = Counter(json.dumps(r, sort_keys=True, ensure_ascii=False) for r in left["issues"])
        counts_right = Counter(json.dumps(r, sort_keys=True, ensure_ascii=False) for r in right["issues"])
        for row, n in sorted((counts_left - counts_right).items()):
            out.append(f"issue only in the first set (x{n}): {row[:160]}")
        for row, n in sorted((counts_right - counts_left).items()):
            out.append(f"issue only in the second set (x{n}): {row[:160]}")
        if not out:
            out.append("issues differ in placeholder numbering only")
    return out
