"""Citation verifier (docs/design/investigation-agent.md sections 8.3 and 8.4).

Given a finished `Investigation` it answers, without trusting the model, and sorts every citation into one of three:
  * valid: its (kind, id) appears in the `records` of a successful tool result of that same run;
  * malformed: not valid, but the identifier itself was shown to the model by a successful tool result (a whole JSON
    string value of it; for `<a>/<b>` ids both parts), so the model read the record and only got the kind or the id
    format wrong. Reported, never counted as fabricated;
  * fabricated: anything else (the identifier was never shown). Failed results (including 503 withholding) show nothing,
    and a truncated result shows only the text that was actually sent to the model.
  * which record-looking identifiers in the explanation were never seen (also fabricated); here an identifier also
    counts as seen when it sits inside a shown string value at identifier boundaries (`po_x` in `unknown_po_x`);
  * whether the conclusion is supported: the valid citations meet the SOP clause's `required_evidence`
    (an abstention is not checked).
"""

from __future__ import annotations

import json
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
    fabricated_citations: tuple[Record, ...]  # cited, and the identifier was never shown by a successful tool result
    fabricated_explanation_ids: tuple[str, ...]  # identifier-looking text in the explanation that was never seen
    supported: bool | None  # None for an abstention (support is not checked)
    missing_evidence: tuple[Evidence, ...] = ()  # (kind, still missing count) when not supported
    malformed_citations: tuple[Record, ...] = ()  # identifier was shown, but not as this (kind, id): not fabricated

    @property
    def fabricated_total(self) -> int:
        return len(self.fabricated_citations) + len(self.fabricated_explanation_ids)


def seen_records(investigation: Investigation) -> frozenset[Record]:
    """Every record that a successful tool result of this run showed."""
    return frozenset(record for result in investigation.tool_calls if result.ok for record in result.records)


_QUOTED_STRING = re.compile(r'"((?:[^"\\]|\\.)*)"')
_NUMBER = re.compile(r"(?<![A-Za-z0-9_.])-?\d+(?![A-Za-z0-9_.])")


def _scalars(node: object, out: set[str], depth: int = 0) -> None:
    if depth > 64:
        return
    if isinstance(node, dict):
        for key, value in node.items():
            out.add(str(key))
            _scalars(value, out, depth + 1)
    elif isinstance(node, list):
        for item in node:
            _scalars(item, out, depth + 1)
    elif isinstance(node, str):
        out.add(node)
    elif isinstance(node, int) and not isinstance(node, bool):
        out.add(str(node))


def shown_values(investigation: Investigation) -> frozenset[str]:
    """Every string and integer value that a successful tool result put in front of the model. A truncated result
    (`data` is the cut text) contributes the complete quoted strings and bare numbers of the text that was sent."""
    shown: set[str] = set()
    for result in investigation.tool_calls:
        if not result.ok:
            continue
        if isinstance(result.data, str):
            for match in _QUOTED_STRING.finditer(result.data):
                try:
                    shown.add(json.loads(f'"{match.group(1)}"'))
                except ValueError:
                    shown.add(match.group(1))
            shown.update(_NUMBER.findall(result.data))
        else:
            _scalars(result.data, shown)
    return frozenset(shown)


def _was_shown(record: Record, shown: frozenset[str]) -> bool:
    """The identifier (not the kind) was shown: whole value, or both parts of a `<a>/<b>` id."""
    if record.id in shown:
        return True
    head, slash, tail = record.id.rpartition("/")
    return bool(slash) and head in shown and tail in shown


def explanation_ids(explanation: str) -> list[str]:
    """Record-looking identifiers in the text, in order of first appearance."""
    found: list[str] = []
    for match in EXPLANATION_ID_PATTERN.finditer(explanation):
        if match.group(0) not in found:
            found.append(match.group(0))
    return found


def _seen_inside_a_value(identifier: str, shown: frozenset[str]) -> bool:
    """An explanation identifier counts as seen when it is a whole shown value or sits inside one at identifier
    boundaries (not touching letters or digits), e.g. `po_x` inside `unknown_po_x` or `bt_po_x_2`."""
    if identifier in shown:
        return True
    pattern = re.compile(r"(?<![A-Za-z0-9])" + re.escape(identifier) + r"(?![A-Za-z0-9])")
    return any(identifier in value and pattern.search(value) for value in shown)


def verify(investigation: Investigation, required_evidence: Mapping[str, tuple[Evidence, ...]]) -> Verification:
    seen = seen_records(investigation)
    shown = shown_values(investigation) | {record.id for record in seen}
    conclusion = investigation.conclusion

    cited = list(conclusion.citations)
    valid = {c for c in cited if c in seen}
    malformed = tuple(c for c in cited if c not in seen and _was_shown(c, shown))
    fabricated = tuple(c for c in cited if c not in seen and not _was_shown(c, shown))
    unseen_ids = tuple(i for i in explanation_ids(conclusion.explanation) if not _seen_inside_a_value(i, shown))

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
        malformed_citations=malformed,
    )
