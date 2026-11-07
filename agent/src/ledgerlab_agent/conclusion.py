"""Structured conclusion and its validation (docs/design/investigation-agent.md section 6)."""

from __future__ import annotations

import re
from dataclasses import dataclass
from typing import Any

from ledgerlab_agent.enums import (
    ABSTAIN_CAUSE,
    CONFIDENCES,
    MAX_CITATIONS,
    MAX_EXPLANATION_CHARS,
    RECOMMENDED_ACTIONS,
    RECORD_KINDS,
    ROOT_CAUSES,
    SOP_CLAUSE_PATTERN,
)
from ledgerlab_agent.records import Record

FIELDS = (
    "issue_id",
    "root_cause",
    "confidence",
    "abstain",
    "explanation",
    "citations",
    "sop_clauses",
    "recommended_action",
)
MAX_CITATION_ID_CHARS = 128


@dataclass(frozen=True)
class Conclusion:
    issue_id: str
    root_cause: str
    confidence: str
    abstain: bool
    explanation: str
    citations: tuple[Record, ...]
    sop_clauses: tuple[str, ...]
    recommended_action: str

    def as_dict(self) -> dict[str, Any]:
        return {
            "issue_id": self.issue_id,
            "root_cause": self.root_cause,
            "confidence": self.confidence,
            "abstain": self.abstain,
            "explanation": self.explanation,
            "citations": [c.as_dict() for c in self.citations],
            "sop_clauses": list(self.sop_clauses),
            "recommended_action": self.recommended_action,
        }


def abstention(issue_id: str, explanation: str) -> Conclusion:
    return Conclusion(
        issue_id=issue_id,
        root_cause=ABSTAIN_CAUSE,
        confidence="low",
        abstain=True,
        explanation=explanation[:MAX_EXPLANATION_CHARS],
        citations=(),
        sop_clauses=(),
        recommended_action="ESCALATE",
    )


def validate(raw: object, expected_issue_id: str | None = None) -> list[str]:
    """Return a list of problems; empty means the conclusion is well formed."""
    if not isinstance(raw, dict):
        return ["conclusion must be a JSON object"]
    errors: list[str] = []
    for name in FIELDS:
        if name not in raw:
            errors.append(f"missing field {name}")
    for name in raw:
        if name not in FIELDS:
            errors.append(f"unknown field {name}")
    if errors:
        return errors

    issue_id = raw["issue_id"]
    if not isinstance(issue_id, str) or not issue_id:
        errors.append("issue_id must be a non-empty string")
    elif expected_issue_id is not None and issue_id != expected_issue_id:
        errors.append(f"issue_id must be {expected_issue_id}")

    root_cause = raw["root_cause"]
    if root_cause not in ROOT_CAUSES:
        errors.append(f"root_cause must be one of {', '.join(ROOT_CAUSES)}")
    if raw["confidence"] not in CONFIDENCES:
        errors.append(f"confidence must be one of {', '.join(CONFIDENCES)}")
    if raw["recommended_action"] not in RECOMMENDED_ACTIONS:
        errors.append(f"recommended_action must be one of {', '.join(RECOMMENDED_ACTIONS)}")

    abstain = raw["abstain"]
    if not isinstance(abstain, bool):
        errors.append("abstain must be a boolean")
    elif root_cause in ROOT_CAUSES:
        if abstain and root_cause != ABSTAIN_CAUSE:
            errors.append(f"abstain=true requires root_cause {ABSTAIN_CAUSE}")
        if not abstain and root_cause == ABSTAIN_CAUSE:
            errors.append(f"root_cause {ABSTAIN_CAUSE} requires abstain=true")

    explanation = raw["explanation"]
    if not isinstance(explanation, str) or not explanation.strip():
        errors.append("explanation must be a non-empty string")
    elif len(explanation) > MAX_EXPLANATION_CHARS:
        errors.append(f"explanation must be at most {MAX_EXPLANATION_CHARS} characters")

    errors.extend(_validate_citations(raw["citations"]))

    clauses = raw["sop_clauses"]
    if not isinstance(clauses, list):
        errors.append("sop_clauses must be a list")
    else:
        for clause in clauses:
            if not isinstance(clause, str) or re.fullmatch(SOP_CLAUSE_PATTERN, clause) is None:
                errors.append(f"sop_clauses entry {clause!r} must look like SOP-3.2")
    return errors


def _validate_citations(citations: object) -> list[str]:
    if not isinstance(citations, list):
        return ["citations must be a list"]
    errors: list[str] = []
    if len(citations) > MAX_CITATIONS:
        errors.append(f"citations must have at most {MAX_CITATIONS} entries")
    for item in citations:
        if not isinstance(item, dict) or set(item) != {"kind", "id"}:
            errors.append("each citation must be an object with exactly kind and id")
            continue
        if item["kind"] not in RECORD_KINDS:
            errors.append(f"citation kind must be one of {', '.join(RECORD_KINDS)}")
        cid = item["id"]
        if not isinstance(cid, str) or not cid.strip() or len(cid) > MAX_CITATION_ID_CHARS:
            errors.append("citation id must be a non-empty string")
    return errors


def from_dict(raw: dict[str, Any]) -> Conclusion:
    """Build a Conclusion from a dict that already passed validate()."""
    return Conclusion(
        issue_id=raw["issue_id"],
        root_cause=raw["root_cause"],
        confidence=raw["confidence"],
        abstain=raw["abstain"],
        explanation=raw["explanation"],
        citations=tuple(Record(c["kind"], c["id"]) for c in raw["citations"]),
        sop_clauses=tuple(raw["sop_clauses"]),
        recommended_action=raw["recommended_action"],
    )
