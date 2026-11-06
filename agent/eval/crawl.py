"""Snapshot crawler: records every (path, query) -> response the agent's tool layer would see.

Starts from each reconciliation issue and follows record references breadth first through the same read-only tool
layer the agent uses (so only GETs listed in routes.json can ever be issued). Depth counts tool calls away from the
issue: the issue itself is depth 0, records found in its response are depth 1, and so on.
"""

from __future__ import annotations

import json
import re
import sys
from collections import deque
from collections.abc import Callable, Iterable
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any

AGENT_DIR = Path(__file__).resolve().parents[1]
for _path in (AGENT_DIR, AGENT_DIR / "src"):
    if str(_path) not in sys.path:
        sys.path.insert(0, str(_path))

from ledgerlab_agent.records import Record  # noqa: E402
from ledgerlab_agent.replay import RequestKey, SnapshotEntry, request_key  # noqa: E402
from ledgerlab_agent.tools import ToolRegistry, ToolResult  # noqa: E402
from ledgerlab_agent.transport import Transport, TransportResponse  # noqa: E402

MAX_DEPTH = 3
REFERENCE_PATTERN = re.compile(r"^[A-Za-z0-9_\-]{1,64}$")
LIST_ISSUES_COMBINATIONS: tuple[dict[str, Any], ...] = (
    {},
    {"limit": 50},
    {"status": "OPEN"},
    {"status": "OPEN", "limit": 50},
)


class RecordingTransport:
    """Wraps a live transport; answers repeated requests from memory and keeps every usable response."""

    def __init__(self, inner: Transport) -> None:
        self._inner = inner
        self.entries: dict[RequestKey, SnapshotEntry] = {}

    def get(self, path: str, query: dict[str, str]) -> TransportResponse:
        key = request_key(path, query)
        known = self.entries.get(key)
        if known is not None:
            return TransportResponse(known.status, known.body)
        response = self._inner.get(path, query)
        if response.body is not None and response.status > 0:
            self.entries[key] = SnapshotEntry(response.status, response.body)
        return response


def record_call(record: Record) -> tuple[str, dict[str, Any]] | None:
    """The tool call that opens a record, or None when no tool reads that kind of record."""
    kind, ident = record.kind, record.id
    if kind == "issue":
        return "get_issue", {"issue_id": ident}
    if kind == "payment":
        return "get_payment_timeline", {"payment_ref": ident}
    if kind == "event":
        return "get_event", {"event_id": ident}
    if kind == "entry":
        return "get_entry", {"entry_id": ident}
    if kind == "batch":
        return "get_batch", {"batch_id": ident}
    if kind == "bank_line":
        return "get_bank_line", {"bank_txn_id": ident}
    if kind == "refund":
        return "get_refund", {"refund_id": ident}
    if kind == "payout":
        return "get_payout", {"payout_id": ident}
    if kind == "statement_line":
        file_id, _, seq = ident.rpartition("/")
        if file_id and seq.isdigit():
            return "get_statement_line", {"file_id": file_id, "seq": int(seq)}
    return None


def bank_references(result: ToolResult) -> list[str]:
    """Bank references mentioned by a result: batch (payout) ids and every `reference` field value."""
    found: list[str] = [r.id for r in result.records if r.kind == "batch"]

    def walk(node: Any, depth: int) -> None:
        if depth > 24:
            return
        if isinstance(node, dict):
            value = node.get("reference")
            if isinstance(value, str) and REFERENCE_PATTERN.fullmatch(value):
                found.append(value)
            for item in node.values():
                walk(item, depth + 1)
        elif isinstance(node, list):
            for item in node:
                walk(item, depth + 1)

    walk(result.data, 0)
    return list(dict.fromkeys(found))


@dataclass
class CrawlResult:
    entries: dict[RequestKey, SnapshotEntry]
    reached: dict[str, set[Record]] = field(default_factory=dict)  # issue id -> records seen in its crawl
    failures: list[str] = field(default_factory=list)


def crawl(
    transport: Transport,
    issue_ids: Iterable[str],
    *,
    max_depth: int = MAX_DEPTH,
    log: Callable[[str], None] | None = None,
) -> CrawlResult:
    recorder = RecordingTransport(transport)
    registry = ToolRegistry(recorder)
    memo: dict[str, ToolResult] = {}
    failures: list[str] = []

    def run(tool: str, args: dict[str, Any]) -> ToolResult:
        cache_key = tool + json.dumps(args, sort_keys=True)
        if cache_key not in memo:
            result = registry.call(tool, args)
            memo[cache_key] = result
            if result.error == "invalid_arguments":
                failures.append(f"{tool} {args}: invalid arguments")
            elif result.status is None or result.status >= 500:
                failures.append(f"{tool} {args}: {result.error or result.status}")
        return memo[cache_key]

    reached: dict[str, set[Record]] = {}
    ids = list(issue_ids)
    for number, issue_id in enumerate(ids, start=1):
        seen: set[str] = set()
        records: set[Record] = set()
        queue: deque[tuple[str, dict[str, Any], int]] = deque([("get_issue", {"issue_id": issue_id}, 0)])
        while queue:
            tool, args, depth = queue.popleft()
            call_key = tool + json.dumps(args, sort_keys=True)
            if call_key in seen:
                continue
            seen.add(call_key)
            result = run(tool, args)
            if not result.ok:
                continue
            records.update(result.records)
            if depth >= max_depth:
                continue
            for record in result.records:
                call = record_call(record)
                if call is not None:
                    queue.append((call[0], call[1], depth + 1))
            for reference in bank_references(result):
                queue.append(("search_bank_lines", {"reference": reference}, depth + 1))
        reached[issue_id] = records
        if log is not None and number % 25 == 0:
            log(f"crawled {number}/{len(ids)} issues, {len(recorder.entries)} distinct requests")
    for combination in LIST_ISSUES_COMBINATIONS:
        run("list_issues", dict(combination))
    return CrawlResult(dict(recorder.entries), reached, failures)


def entries_size(entries: dict[RequestKey, SnapshotEntry]) -> int:
    return sum(len(entry.body) for entry in entries.values())
