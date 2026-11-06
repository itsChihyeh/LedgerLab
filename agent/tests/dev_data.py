"""Helpers that load the frozen development set (never the test set) for the baseline and stub tests."""

from __future__ import annotations

import json
from pathlib import Path
from typing import Any

from ledgerlab_agent.replay import ReplayTransport, Snapshot, SnapshotEntry, load_snapshot
from ledgerlab_agent.sop import SopIndex, SopSearch
from ledgerlab_agent.tools import ToolRegistry

DEV_DIR = Path(__file__).resolve().parents[1] / "eval" / "data" / "dev"
NEUTRAL_MEMO = "Simulated bank transfer"


def load_dev_labels() -> list[dict[str, Any]]:
    return json.loads((DEV_DIR / "labels.json").read_text(encoding="utf-8"))["labels"]


def load_dev_snapshot() -> Snapshot:
    return load_snapshot(DEV_DIR / "snapshot.json")


def registry_for(label: dict[str, Any], snapshot: Snapshot, sop: SopIndex) -> ToolRegistry:
    withheld = [(w["path"], w["query"]) for w in label["withheld"] or []]
    return ToolRegistry(ReplayTransport(snapshot, withheld), local_tools=[SopSearch(sop)])


def replace_memos(node: Any, replacement: str, found: list[str]) -> Any:
    """Copy of a parsed JSON value with every "memo" string replaced; the replaced texts are appended to found."""
    if isinstance(node, dict):
        out: dict[str, Any] = {}
        for key, value in node.items():
            if key == "memo" and isinstance(value, str):
                found.append(value)
                out[key] = replacement
            else:
                out[key] = replace_memos(value, replacement, found)
        return out
    if isinstance(node, list):
        return [replace_memos(item, replacement, found) for item in node]
    return node


def neutral_memo_snapshot(snapshot: Snapshot) -> tuple[Snapshot, list[str]]:
    """The same snapshot with every bank memo replaced by a neutral text, and the memos that were replaced."""
    found: list[str] = []
    entries: dict[Any, SnapshotEntry] = {}
    for key, entry in snapshot.entries.items():
        try:
            parsed = json.loads(entry.body.decode("utf-8"))
        except ValueError:
            entries[key] = entry
            continue
        body = json.dumps(replace_memos(parsed, NEUTRAL_MEMO, found), ensure_ascii=False).encode("utf-8")
        entries[key] = SnapshotEntry(entry.status, body)
    return Snapshot(entries, snapshot.generated_at, snapshot.commit), found
