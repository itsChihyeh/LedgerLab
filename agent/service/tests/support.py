"""Test helpers: a service on a random loopback port in a thread, and a tiny HTTP client."""

from __future__ import annotations

import http.client
import io
import json
import tempfile
import threading
from collections.abc import Callable
from datetime import datetime, timedelta, timezone
from pathlib import Path
from typing import Any

from ledgerlab_agent.model import ModelClient
from ledgerlab_agent.replay import Snapshot, load_snapshot

from ledgerlab_agent_service.app import InvestigationService, make_server
from ledgerlab_agent_service.runner import Runner
from ledgerlab_agent_service.store import JsonlStore

DEV_DIR = Path(__file__).resolve().parents[2] / "eval" / "data" / "dev"
_snapshot: Snapshot | None = None


def dev_snapshot() -> Snapshot:
    global _snapshot
    if _snapshot is None:
        _snapshot = load_snapshot(DEV_DIR / "snapshot.json")
    return _snapshot


def dev_issue_id(root_cause: str) -> str:
    labels = json.loads((DEV_DIR / "labels.json").read_text(encoding="utf-8"))["labels"]
    return next(x["issue_id"] for x in labels if x["root_cause"] == root_cause)


class FixedClock:
    """A clock that moves one second per reading, starting at a fixed moment."""

    def __init__(self) -> None:
        self._now = datetime(2026, 11, 6, 8, 0, 0, tzinfo=timezone.utc)

    def __call__(self) -> datetime:
        self._now += timedelta(seconds=1)
        return self._now


class RunningService:
    def __init__(
        self,
        store_path: Path,
        model_spec: str = "stub",
        base_url: str | None = None,
        use_snapshot: bool = True,
        model_factory: Callable[[], ModelClient] | None = None,
        monotonic: Callable[[], float] | None = None,
    ) -> None:
        self.stderr = io.StringIO()
        counter = iter(range(1, 1000))
        runner = Runner(
            model_spec,
            base_url=base_url,
            snapshot=dev_snapshot() if use_snapshot else None,
            model_factory=model_factory,
            clock=FixedClock(),
            random_bits=lambda bits: next(counter),
            stderr=self.stderr,
        )
        self.store = JsonlStore(store_path, self.stderr)
        source = "snapshot" if use_snapshot else "base-url"
        self.service = (
            InvestigationService(runner, self.store, source)
            if monotonic is None
            else InvestigationService(runner, self.store, source, monotonic)
        )
        self.server = make_server("127.0.0.1", 0, self.service, self.stderr)
        self.port = self.server.server_address[1]
        self._thread = threading.Thread(target=self.server.serve_forever, daemon=True)

    def __enter__(self) -> RunningService:
        self._thread.start()
        return self

    def __exit__(self, *exc: object) -> None:
        self.server.shutdown()
        self.server.server_close()
        self._thread.join(timeout=5)

    def request(
        self, method: str, path: str, body: bytes | None = None, headers: dict[str, str] | None = None
    ) -> tuple[int, dict[str, str], Any]:
        connection = http.client.HTTPConnection("127.0.0.1", self.port, timeout=30)
        try:
            connection.request(method, path, body=body, headers=headers or {})
            response = connection.getresponse()
            raw = response.read()
            return response.status, {k.lower(): v for k, v in response.getheaders()}, json.loads(raw) if raw else None
        finally:
            connection.close()

    def request_text(
        self, path: str, headers: dict[str, str] | None = None
    ) -> tuple[int, dict[str, str], str]:
        connection = http.client.HTTPConnection("127.0.0.1", self.port, timeout=30)
        try:
            connection.request("GET", path, headers=headers or {})
            response = connection.getresponse()
            raw = response.read()
            return response.status, {k.lower(): v for k, v in response.getheaders()}, raw.decode("utf-8")
        finally:
            connection.close()

    def post_issue(self, issue_id: str) -> tuple[int, dict[str, str], Any]:
        return self.request(
            "POST",
            "/agent/investigations",
            json.dumps({"issueId": issue_id}).encode("utf-8"),
            {"Content-Type": "application/json"},
        )


def temp_store(test: Any) -> Path:
    directory = tempfile.TemporaryDirectory()
    test.addCleanup(directory.cleanup)
    return Path(directory.name) / "runs" / "investigations.jsonl"
