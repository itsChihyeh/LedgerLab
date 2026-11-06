"""Citation verifier (docs/design/investigation-agent.md sections 8.3 and 8.4).

Given a finished `Investigation` it answers, without trusting the model:
  * which citations are real: a citation counts only if its (kind, id) appears in the `records` of a successful tool
    result of that same run; anything else is a fabricated citation;
  * which record-looking identifiers in the explanation were never seen (also fabricated);
  * whether the conclusion is supported: the valid citations meet the SOP clause's `required_evidence`
    (an abstention is not checked).
"""

from __future__ import annotations

import re
from collections import Counter
from collections.abc import Mapping
from dataclasses import dataclass

from ledgerlab_agent.agent import Investigation
from ledgerlab_agent.records import RECORD_ID_PREFIXES, Record
from ledgerlab_agent.sop import Evidence

# An identifier-looking token: a known record prefix followed by word characters, not part of a longer word.
EXPLANATION_ID_PATTERN = re.compile(
    r"(?<![A-Za-z0-9_])(?:" + "|".join(re.escape(p) for p in RECORD_ID_PREFIXES) + r")[A-Za-z0-9_]*[A-Za-z0-9]"
)


@dataclass(frozen=True)
class Verification:
    citations_total: int
    citations_valid: int
    fabricated_citations: tuple[Record, ...]  # cited but never seen in a successful tool result
    fabricated_explanation_ids: tuple[str, ...]  # identifier-looking text in the explanation that was never seen
    supported: bool | None  # None for an abstention (support is not checked)
    missing_evidence: tuple[Evidence, ...] = ()  # (kind, still missing count) when not supported

    @property
    def fabricated_total(self) -> int:
        return len(self.fabricated_citations) + len(self.fabricated_explanation_ids)


def seen_records(investigation: Investigation) -> frozenset[Record]:
    """Every record that a successful tool result of this run showed."""
    return frozenset(record for result in investigation.tool_calls if result.ok for record in result.records)


def explanation_ids(explanation: str) -> list[str]:
    """Record-looking identifiers in the text, in order of first appearance."""
    found: list[str] = []
    for match in EXPLANATION_ID_PATTERN.finditer(explanation):
        if match.group(0) not in found:
            found.append(match.group(0))
    return found


def verify(investigation: Investigation, required_evidence: Mapping[str, tuple[Evidence, ...]]) -> Verification:
    seen = seen_records(investigation)
    seen_ids = {record.id for record in seen}
    conclusion = investigation.conclusion

    cited = list(conclusion.citations)
    fabricated = tuple(c for c in cited if c not in seen)
    valid = {c for c in cited if c in seen}
    unseen_ids = tuple(i for i in explanation_ids(conclusion.explanation) if i not in seen_ids)

    supported: bool | None = None
    missing: list[Evidence] = []
    if not conclusion.abstain:
        have = Counter(c.kind for c in valid)
        for kind, minimum in required_evidence.get(conclusion.root_cause, ()):
            if have[kind] < minimum:
                missing.append((kind, minimum - have[kind]))
        supported = not missing
    return Verification(
        citations_total=len(cited),
        citations_valid=len([c for c in cited if c in seen]),
        fabricated_citations=fabricated,
        fabricated_explanation_ids=unseen_ids,
        supported=supported,
        missing_evidence=tuple(missing),
    )
