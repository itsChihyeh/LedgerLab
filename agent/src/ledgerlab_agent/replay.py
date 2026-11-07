"""Replay transport: answers tool requests from a recorded snapshot instead of a live backend.

Snapshot file (format 1, see docs/design/investigation-agent.md section 8.1):

    {"format": 1, "generated_at": "...", "commit": "...",
     "requests": [{"path": "/api/...", "query": {"k": "v"}, "status": 200, "body": "<text>" | "body_b64": "<base64>"}]}

Bodies are stored byte for byte (`body` when they are valid UTF-8, `body_b64` otherwise). A request is identified
by its path and its query parameters sorted by name, so the order of parameters does not matter.
"""

from __future__ import annotations

import base64
import json
from collections.abc import Iterable, Mapping
from dataclasses import dataclass
from pathlib import Path
from typing import Any

from ledgerlab_agent.transport import TransportResponse

SNAPSHOT_FORMAT = 1
NOT_IN_SNAPSHOT_BODY = b'{"error":"not_in_snapshot"}'
WITHHELD_STATUS = 503
WITHHELD_BODY = b'{"error":"service_unavailable"}'

RequestKey = tuple[str, tuple[tuple[str, str], ...]]


class SnapshotError(ValueError):
    pass


def request_key(path: str, query: Mapping[str, str] | None) -> RequestKey:
    return path, tuple(sorted((str(k), str(v)) for k, v in (query or {}).items()))


@dataclass(frozen=True)
class SnapshotEntry:
    status: int
    body: bytes


@dataclass(frozen=True)
class Snapshot:
    entries: Mapping[RequestKey, SnapshotEntry]
    generated_at: str = ""
    commit: str = ""

    def __len__(self) -> int:
        return len(self.entries)


def encode_body(body: bytes) -> dict[str, str]:
    """Snapshot representation of a response body: text when it is valid UTF-8, base64 otherwise."""
    try:
        return {"body": body.decode("utf-8")}
    except UnicodeDecodeError:
        return {"body_b64": base64.b64encode(body).decode("ascii")}


def parse_snapshot(document: object) -> Snapshot:
    if not isinstance(document, dict) or document.get("format") != SNAPSHOT_FORMAT:
        raise SnapshotError(f"unsupported snapshot format (expected {SNAPSHOT_FORMAT})")
    requests = document.get("requests")
    if not isinstance(requests, list):
        raise SnapshotError("snapshot has no request list")
    entries: dict[RequestKey, SnapshotEntry] = {}
    for index, item in enumerate(requests):
        if not isinstance(item, dict):
            raise SnapshotError(f"request {index} is not an object")
        path, query, status = item.get("path"), item.get("query", {}), item.get("status")
        if not isinstance(path, str) or not isinstance(query, dict) or not isinstance(status, int):
            raise SnapshotError(f"request {index} needs a string path, an object query and an integer status")
        if "body" in item and isinstance(item["body"], str):
            body = item["body"].encode("utf-8")
        elif "body_b64" in item and isinstance(item["body_b64"], str):
            body = base64.b64decode(item["body_b64"], validate=True)
        else:
            raise SnapshotError(f"request {index} has neither body nor body_b64")
        key = request_key(path, {str(k): str(v) for k, v in query.items()})
        if key in entries:
            raise SnapshotError(f"request {index} duplicates an earlier request: {path}")
        entries[key] = SnapshotEntry(status, body)
    return Snapshot(entries, str(document.get("generated_at", "")), str(document.get("commit", "")))


def load_snapshot(path: Path) -> Snapshot:
    return parse_snapshot(json.loads(path.read_text(encoding="utf-8")))


class ReplayTransport:
    """Transport that serves recorded responses.

    `withheld` lists (path, query) requests that answer 503 even though the snapshot has them: the evaluation
    uses it to take away a piece of evidence. Anything the snapshot does not hold answers 404 not_in_snapshot.
    """

    def __init__(
        self,
        snapshot: Snapshot,
        withheld: Iterable[tuple[str, Mapping[str, str]]] = (),
    ) -> None:
        self._snapshot = snapshot
        self._withheld = frozenset(request_key(path, query) for path, query in withheld)
        self.calls: list[RequestKey] = []

    def get(self, path: str, query: dict[str, str]) -> TransportResponse:
        key = request_key(path, query)
        self.calls.append(key)
        if key in self._withheld:
            return TransportResponse(WITHHELD_STATUS, WITHHELD_BODY)
        entry = self._snapshot.entries.get(key)
        if entry is None:
            return TransportResponse(404, NOT_IN_SNAPSHOT_BODY)
        return TransportResponse(entry.status, entry.body)


def snapshot_document(
    entries: Iterable[tuple[RequestKey, SnapshotEntry]], generated_at: str, commit: str
) -> dict[str, Any]:
    """Builds the JSON document for a snapshot (the writer lives in the evaluation tooling)."""
    requests = []
    for (path, query), entry in entries:
        requests.append(
            {"path": path, "query": dict(query), "status": entry.status, **encode_body(entry.body)}
        )
    return {"format": SNAPSHOT_FORMAT, "generated_at": generated_at, "commit": commit, "requests": requests}


__all__ = [
    "NOT_IN_SNAPSHOT_BODY",
    "ReplayTransport",
    "Snapshot",
    "SnapshotEntry",
    "SnapshotError",
    "WITHHELD_STATUS",
    "encode_body",
    "load_snapshot",
    "parse_snapshot",
    "request_key",
    "snapshot_document",
]
