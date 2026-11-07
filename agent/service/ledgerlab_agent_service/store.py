"""Append-only JSONL store for investigation records (docs/design/agent-workbench-and-mcp.md section 2.3).

One record per line. Lines are never changed or removed; the file is the service's own state, not a ledger, and can be
deleted to start over. A line that cannot be read back is skipped and reported on stderr.
"""

from __future__ import annotations

import json
import os
import re
import threading
from pathlib import Path
from typing import Any, TextIO

RUN_ID_PATTERN = re.compile(r"inv_[0-9A-HJKMNP-TV-Z]{26}")
MAX_LISTED = 20


class JsonlStore:
    def __init__(self, path: Path, stderr: TextIO) -> None:
        self._path = path
        self._stderr = stderr
        self._lock = threading.Lock()
        self._by_id: dict[str, dict[str, Any]] = {}
        self._by_issue: dict[str, list[str]] = {}
        os.makedirs(path.parent, exist_ok=True)
        self._starts_mid_line = False
        self._load()

    def _load(self) -> None:
        if not self._path.exists():
            return
        raw = self._path.read_bytes()
        self._starts_mid_line = bool(raw) and not raw.endswith(b"\n")
        for number, line in enumerate(raw.split(b"\n"), start=1):
            if not line.strip():
                continue
            record = self._parse(line)
            if record is None:
                print(f"store: skipped unreadable line {number}", file=self._stderr)
            elif record["runId"] in self._by_id:
                print(f"store: skipped repeated runId on line {number}", file=self._stderr)
            else:
                self._index(record)

    @staticmethod
    def _parse(line: bytes) -> dict[str, Any] | None:
        try:
            record = json.loads(line.decode("utf-8"))
        except ValueError:
            return None
        if not isinstance(record, dict):
            return None
        run_id, issue_id = record.get("runId"), record.get("issueId")
        if not isinstance(run_id, str) or RUN_ID_PATTERN.fullmatch(run_id) is None or not isinstance(issue_id, str):
            return None
        return record

    def _index(self, record: dict[str, Any]) -> None:
        self._by_id[record["runId"]] = record
        self._by_issue.setdefault(record["issueId"], []).append(record["runId"])

    def append(self, record: dict[str, Any]) -> None:
        """Add one record at the end of the file, then to the in-memory index (only after the write worked)."""
        line = json.dumps(record, ensure_ascii=False, separators=(",", ":"))
        with self._lock:
            with open(self._path, "a", encoding="utf-8", newline="\n") as handle:
                handle.write(("\n" if self._starts_mid_line else "") + line + "\n")
            self._starts_mid_line = False
            self._index(record)

    def get(self, run_id: str) -> dict[str, Any] | None:
        with self._lock:
            return self._by_id.get(run_id)

    def for_issue(self, issue_id: str, limit: int = MAX_LISTED) -> list[dict[str, Any]]:
        """Newest first (file order is time order), at most `limit` records."""
        with self._lock:
            ids = self._by_issue.get(issue_id, [])
            return [self._by_id[run_id] for run_id in reversed(ids[-limit:])]
