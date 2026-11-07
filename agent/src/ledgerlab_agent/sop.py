"""SOP clauses (agent/sop/reconciliation-sop.md): parsing, BM25 search and the search_sop local tool.

A clause is a `## SOP-<n>[.<m>] <title>` section. Root-cause clauses end with fixed machine-readable lines:

    root_cause: BANK_SPLIT_DEPOSIT
    required_evidence: bank_line*2, batch
    recommended_action: MATCH_MANUALLY_AFTER_REVIEW

`kind*n` asks for at least n records of that kind, a bare `kind` for at least one.
"""

from __future__ import annotations

import math
import re
from collections import Counter
from dataclasses import dataclass
from pathlib import Path
from typing import Any

from ledgerlab_agent.enums import RECOMMENDED_ACTIONS, RECORD_KINDS, ROOT_CAUSES, SOP_CLAUSE_PATTERN
from ledgerlab_agent.records import Record
from ledgerlab_agent.tools import SEARCH_SOP_MAX_QUERY_CHARS, ParamSpec, ToolSpec

SOP_PATH = Path(__file__).resolve().parents[2] / "sop" / "reconciliation-sop.md"
BM25_K1 = 1.2
BM25_B = 0.75
MAX_CLAUSE_CHARS = 1500
DEFAULT_TOP_K = 3

Evidence = tuple[str, int]  # (record kind, minimum count)

_HEADING = re.compile(r"^## (SOP-\d+(?:\.\d+)*) (.+)$")
_FIELD = re.compile(r"^(root_cause|required_evidence|recommended_action):[ \t]*(.*?)[ \t]*$", re.MULTILINE)
_LATIN = re.compile(r"[a-z0-9]+")
_CJK_RUN = re.compile(r"[㐀-䶿一-鿿]+")


class SopError(ValueError):
    """The SOP file does not follow the clause format."""


@dataclass(frozen=True)
class Clause:
    id: str
    title: str
    text: str  # whole section body, machine lines included
    root_cause: str | None = None
    required_evidence: tuple[Evidence, ...] = ()
    recommended_action: str | None = None


def tokenize(text: str) -> list[str]:
    """Latin words and numbers (lower case, punctuation and underscores split them); CJK runs as character bigrams."""
    lowered = text.lower()
    tokens = _LATIN.findall(lowered)
    for run in _CJK_RUN.findall(lowered):
        if len(run) == 1:
            tokens.append(run)
        else:
            tokens.extend(run[i : i + 2] for i in range(len(run) - 1))
    return tokens


def parse_required_evidence(value: str) -> tuple[Evidence, ...]:
    items: list[Evidence] = []
    for part in value.split(","):
        part = part.strip()
        if not part:
            raise SopError(f"empty entry in required_evidence {value!r}")
        kind, star, count = part.partition("*")
        kind = kind.strip()
        if kind not in RECORD_KINDS:
            raise SopError(f"unknown record kind {kind!r} in required_evidence")
        minimum = 1
        if star:
            if not count.strip().isdigit() or int(count) < 1:
                raise SopError(f"bad count in required_evidence entry {part!r}")
            minimum = int(count)
        items.append((kind, minimum))
    return tuple(items)


def parse_clauses(markdown: str) -> tuple[Clause, ...]:
    sections: list[tuple[str, str, list[str]]] = []
    current: tuple[str, str, list[str]] | None = None
    for line in markdown.splitlines():
        if line.startswith("## "):
            match = _HEADING.match(line)
            current = (match.group(1), match.group(2).strip(), []) if match else None
            if current is not None:
                sections.append(current)
        elif line.startswith("# "):
            current = None
        elif current is not None:
            current[2].append(line)
    clauses: list[Clause] = []
    for clause_id, title, lines in sections:
        if re.fullmatch(SOP_CLAUSE_PATTERN, clause_id) is None:
            raise SopError(f"bad clause id {clause_id}")
        text = "\n".join(lines).strip()
        fields = {name: value for name, value in _FIELD.findall(text)}
        cause = fields.get("root_cause")
        if cause is not None and cause not in ROOT_CAUSES:
            raise SopError(f"{clause_id}: unknown root_cause {cause!r}")
        action = fields.get("recommended_action")
        if action is not None and action not in RECOMMENDED_ACTIONS:
            raise SopError(f"{clause_id}: unknown recommended_action {action!r}")
        evidence = parse_required_evidence(fields["required_evidence"]) if "required_evidence" in fields else ()
        if cause is not None and (action is None):
            raise SopError(f"{clause_id}: a root-cause clause needs recommended_action")
        clauses.append(Clause(clause_id, title, text, cause, evidence, action))
    ids = [c.id for c in clauses]
    if len(set(ids)) != len(ids):
        raise SopError("duplicate clause id")
    return tuple(clauses)


@dataclass(frozen=True)
class SearchHit:
    clause: Clause
    score: float


class SopIndex:
    """The parsed SOP plus a BM25 index over clause title and text."""

    def __init__(self, clauses: tuple[Clause, ...]) -> None:
        self.clauses = clauses
        self._by_id = {c.id: c for c in clauses}
        self._by_cause: dict[str, Clause] = {}
        for clause in clauses:
            if clause.root_cause is None:
                continue
            if clause.root_cause in self._by_cause:
                raise SopError(f"root_cause {clause.root_cause} has more than one clause")
            self._by_cause[clause.root_cause] = clause
        self._term_counts = [Counter(tokenize(f"{c.id} {c.title} {c.text}")) for c in clauses]
        self._lengths = [sum(counts.values()) for counts in self._term_counts]
        self._avg_length = (sum(self._lengths) / len(clauses)) if clauses else 0.0
        document_frequency: Counter[str] = Counter()
        for counts in self._term_counts:
            document_frequency.update(counts.keys())
        n = len(clauses)
        self._idf = {t: math.log(1 + (n - df + 0.5) / (df + 0.5)) for t, df in document_frequency.items()}

    def clause(self, clause_id: str) -> Clause | None:
        return self._by_id.get(clause_id)

    def for_cause(self, root_cause: str) -> Clause | None:
        return self._by_cause.get(root_cause)

    def required_evidence(self) -> dict[str, tuple[Evidence, ...]]:
        """root_cause -> required evidence kinds (what the citation verifier checks)."""
        return {cause: clause.required_evidence for cause, clause in self._by_cause.items()}

    def search(self, query: str, k: int = DEFAULT_TOP_K) -> list[SearchHit]:
        terms = tokenize(query)
        hits: list[SearchHit] = []
        for position, clause in enumerate(self.clauses):
            counts = self._term_counts[position]
            norm = 1 - BM25_B + BM25_B * self._lengths[position] / self._avg_length if self._avg_length else 1.0
            score = 0.0
            for term in terms:
                tf = counts.get(term, 0)
                if tf:
                    score += self._idf[term] * tf * (BM25_K1 + 1) / (tf + BM25_K1 * norm)
            if score > 0:
                hits.append(SearchHit(clause, score))
        hits.sort(key=lambda hit: -hit.score)  # stable: ties keep document order
        return hits[:k]


def load_sop(path: Path = SOP_PATH) -> SopIndex:
    return SopIndex(parse_clauses(path.read_text(encoding="utf-8")))


def required_evidence_by_cause(path: Path = SOP_PATH) -> dict[str, tuple[Evidence, ...]]:
    """root_cause -> required evidence kinds, straight from the SOP (used by the citation verifier)."""
    return load_sop(path).required_evidence()


class SopSearch:
    """The search_sop tool: answered in-process, no HTTP."""

    spec = ToolSpec(
        name="search_sop",
        description=(
            "Search the reconciliation operating manual (SOP) and return the best matching clauses "
            "(id, title, text). Use it to look up how to tell causes apart and what evidence each needs."
        ),
        params=(
            ParamSpec(
                name="query",
                where="query",
                pattern=rf"^[^\x00-\x1f]{{1,{SEARCH_SOP_MAX_QUERY_CHARS}}}$",
                required=True,
            ),
        ),
    )

    def __init__(self, index: SopIndex | None = None) -> None:
        self._index = index or load_sop()

    def run(self, args: dict[str, Any]) -> tuple[Any, list[Record]]:
        hits = self._index.search(str(args["query"]), DEFAULT_TOP_K)
        results = [
            {"clause": h.clause.id, "title": h.clause.title, "text": h.clause.text[:MAX_CLAUSE_CHARS]} for h in hits
        ]
        return {"results": results}, []
