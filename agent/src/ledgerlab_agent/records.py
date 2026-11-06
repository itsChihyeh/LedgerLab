"""Extract record identifiers ({kind, id}) from read-only API responses.

Two layers: a per-tool table naming each response's primary records, and a generic walk that
recognises identifier-carrying keys and object shapes anywhere in the JSON (evidenceRefs,
references, postings, deliveries ...). Result is de-duplicated and keeps first-seen order.
"""

from __future__ import annotations

from dataclasses import dataclass
from typing import Any

from ledgerlab_agent.enums import RECORD_KINDS

_MAX_DEPTH = 24


@dataclass(frozen=True)
class Record:
    kind: str
    id: str

    def as_dict(self) -> dict[str, str]:
        return {"kind": self.kind, "id": self.id}


# (container path, kind, id key). "[]" in a path iterates a list.
_PRIMARY: dict[str, tuple[tuple[tuple[str, ...], str, str], ...]] = {
    "get_issue": ((("issue",), "issue", "id"),),
    "list_issues": ((("issues", "[]"), "issue", "id"),),
    "get_payment_timeline": (((), "payment", "id"),),
    "get_batch": (
        (("batch",), "batch", "batchId"),
        (("lines", "[]"), "batch", "batchId"),
    ),
    "get_bank_line": (
        (("bankLine",), "bank_line", "bankTxnId"),
        (("versions", "[]"), "bank_line", "bankTxnId"),
    ),
    "search_bank_lines": ((("bankLines", "[]"), "bank_line", "bankTxnId"),),
    "get_event": (((), "event", "eventId"),),
    "get_entry": ((("entry",), "entry", "id"),),
    "get_refund": ((("refund",), "refund", "refund_id"),),
    "get_payout": ((("payout",), "payout", "payoutId"),),
}

_KEY_KINDS: dict[str, str] = {
    **dict.fromkeys(
        (
            "batch_id", "batchId", "batchIds", "candidate_batch_id", "candidateBatchId",
            "matched_batch_id", "matchedBatchId",
        ),
        "batch",
    ),
    **dict.fromkeys(
        ("bank_txn_id", "bankTxnId", "bankTxnIds", "return_bank_txn_id", "returnBankTxnId"),
        "bank_line",
    ),
    **dict.fromkeys(("payment_id", "paymentId", "paymentIds"), "payment"),
    **dict.fromkeys(("refund_id", "refundId", "refundIds"), "refund"),
    **dict.fromkeys(
        ("event_id", "eventId", "eventIds", "source_event_id", "sourceEventId"), "event"
    ),
    **dict.fromkeys(
        (
            "entry_id", "entryId", "entry_ids", "entryIds", "reverses_entry_id", "reversesEntryId",
            "reversedBy",
        ),
        "entry",
    ),
    **dict.fromkeys(("correction_id", "correctionId", "resolutionCorrectionId"), "correction"),
    **dict.fromkeys(("run_id", "runId", "firstRunId", "lastRunId"), "run"),
    **dict.fromkeys(("payout_id", "payoutId", "matchedPayoutId"), "payout"),
    **dict.fromkeys(("issue_id", "issueId"), "issue"),
}

_ID_PREFIX_KINDS: tuple[tuple[str, str], ...] = (
    ("iss_", "issue"),
    ("pay_", "payment"),
    ("evt_", "event"),
    ("je_", "entry"),
    ("rfd_", "refund"),
    ("mpo_", "payout"),
    ("po_", "batch"),
    ("cor_", "correction"),
    ("run_", "run"),
)

_TABLE_KINDS: dict[str, str] = {
    "core.settlement_batch": "batch",
    "core.payment": "payment",
    "core.refund": "refund",
    "io.inbox_event": "event",
    "ledger.journal_entry": "entry",
}

_SUBJECT_KINDS: dict[str, str] = {
    "payment": "payment",
    "batch": "batch",
    "bank_txn": "bank_line",
    "refund": "refund",
    "event": "event",
    "payout": "payout",
}

_EVIDENCE_KINDS: dict[str, str] = {"inbox_event": "event", "WEBHOOK": "event"}


class _Collector:
    def __init__(self) -> None:
        self._seen: set[Record] = set()
        self.records: list[Record] = []

    def add(self, kind: str | None, value: object) -> None:
        if kind is None or kind not in RECORD_KINDS:
            return
        if isinstance(value, bool) or not isinstance(value, (str, int)):
            return
        text = str(value).strip()
        if not text:
            return
        record = Record(kind, text)
        if record not in self._seen:
            self._seen.add(record)
            self.records.append(record)

    def add_each(self, kind: str, value: object) -> None:
        if isinstance(value, list):
            for item in value:
                self.add(kind, item)
        else:
            self.add(kind, value)


def extract_records(tool: str, data: object) -> list[Record]:
    collector = _Collector()
    for path, kind, id_key in _PRIMARY.get(tool, ()):
        for container in _containers(data, path):
            collector.add(kind, container.get(id_key))
    _walk(data, collector, 0)
    return collector.records


def _containers(data: object, path: tuple[str, ...]) -> list[dict[str, Any]]:
    nodes: list[object] = [data]
    for step in path:
        nxt: list[object] = []
        for node in nodes:
            if step == "[]":
                if isinstance(node, list):
                    nxt.extend(node)
            elif isinstance(node, dict) and step in node:
                nxt.append(node[step])
        nodes = nxt
    return [n for n in nodes if isinstance(n, dict)]


def _walk(node: object, out: _Collector, depth: int) -> None:
    if depth > _MAX_DEPTH:
        return
    if isinstance(node, list):
        for item in node:
            _walk(item, out, depth + 1)
        return
    if not isinstance(node, dict):
        return
    _shape_rules(node, out)
    for key, value in node.items():
        kind = _KEY_KINDS.get(key)
        if kind is not None:
            out.add_each(kind, value)
        elif key == "payment_ref" and isinstance(value, str) and value.startswith("pay_"):
            out.add("payment", value)
        _walk(value, out, depth + 1)


def _shape_rules(node: dict[str, Any], out: _Collector) -> None:
    file_id, line_seq = node.get("fileId"), node.get("lineSeq")
    if isinstance(file_id, str) and isinstance(line_seq, int) and not isinstance(line_seq, bool):
        out.add("statement_line", f"{file_id}/{line_seq}")
    batch_id, line_no = node.get("batchId"), node.get("lineNo")
    if isinstance(batch_id, str) and isinstance(line_no, int) and not isinstance(line_no, bool):
        out.add("batch_line", f"{batch_id}/{line_no}")
    node_id = node.get("id")
    if isinstance(node_id, str) and node_id:
        table = node.get("table")
        kind = _TABLE_KINDS.get(table) if isinstance(table, str) else None
        if kind is None:
            kind = next((k for prefix, k in _ID_PREFIX_KINDS if node_id.startswith(prefix)), None)
        out.add(kind, node_id)
    for type_key, id_key in (("subjectType", "subjectId"), ("subject_type", "subject_id"), ("subject", "subjectId")):
        subject = node.get(type_key)
        if isinstance(subject, str):
            out.add(_SUBJECT_KINDS.get(subject), node.get(id_key))
    for type_key, id_key in (
        ("evidenceType", "evidenceId"),
        ("evidence_type", "evidence_id"),
        ("evidence_source", "evidence_ref"),
    ):
        evidence = node.get(type_key)
        if isinstance(evidence, str):
            out.add(_EVIDENCE_KINDS.get(evidence), node.get(id_key))
