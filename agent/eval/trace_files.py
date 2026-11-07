"""Writes investigation traces (ledgerlab_agent.trace) as one JSON file per question. Lives outside the write-free
package, like the rest of the evaluation's file output."""

from __future__ import annotations

import json
import re
from pathlib import Path
from typing import Any


def write_trace(directory: Path, name: str, document: dict[str, Any]) -> Path:
    """`<directory>/<name>.json`; `name` is reduced to letters, digits, `_`, `-` and `.` so it cannot leave the directory."""
    safe = re.sub(r"[^A-Za-z0-9_.-]", "_", name).lstrip(".") or "trace"
    path = directory / f"{safe}.json"
    directory.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(document, ensure_ascii=False, indent=2) + "\n", encoding="utf-8", newline="\n")
    return path
