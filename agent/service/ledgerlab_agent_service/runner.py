"""Assemble and run one investigation, and turn it into the record of docs/design/agent-workbench-and-mcp.md 2.3."""

from __future__ import annotations

import dataclasses
import secrets
import sys
from collections.abc import Callable
from datetime import datetime, timezone
from typing import Any, TextIO

from ledgerlab_agent.agent import Budget, Investigation, investigate
from ledgerlab_agent.baseline import BaselineInvestigator
from ledgerlab_agent.cli import BASELINE, build_model
from ledgerlab_agent.conclusion import abstention
from ledgerlab_agent.model import ModelClient, Usage
from ledgerlab_agent.replay import ReplayTransport, Snapshot
from ledgerlab_agent.sop import SopIndex, SopSearch, load_sop
from ledgerlab_agent.tools import ToolRegistry
from ledgerlab_agent.transport import HttpTransport, Transport
from ledgerlab_agent.verify import verify

CROCKFORD = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"
BACKEND_UNREACHABLE = "backend_unreachable"
INTERNAL_ERROR = "internal_error"
_ERROR_EXPLANATIONS = {
    BACKEND_UNREACHABLE: "Stopped without a conclusion: the backend could not be reached.",
    INTERNAL_ERROR: "Stopped without a conclusion: the investigation failed unexpectedly.",
}


def new_run_id(timestamp_ms: int, random_80_bits: int) -> str:
    """`inv_` + a ULID: 48 bits of time and 80 random bits, 26 Crockford base32 characters."""
    value = ((timestamp_ms & (1 << 48) - 1) << 80) | (random_80_bits & (1 << 80) - 1)
    chars = []
    for _ in range(26):
        chars.append(CROCKFORD[value & 31])
        value >>= 5
    return "inv_" + "".join(reversed(chars))


def _iso(moment: datetime) -> str:
    return moment.astimezone(timezone.utc).strftime("%Y-%m-%dT%H:%M:%S.") + f"{moment.microsecond // 1000:03d}Z"


def _utc_now() -> datetime:
    return datetime.now(timezone.utc)


def build_transport(base_url: str | None, snapshot: Snapshot | None) -> Transport:
    """Replay of the loaded snapshot, or the P8 read-only GET transport for the backend URL."""
    if snapshot is not None:
        return ReplayTransport(snapshot)
    if base_url is None:
        raise ValueError("a data source is needed: base_url or snapshot")
    return HttpTransport(base_url)


def run_investigation(
    issue_id: str, model_spec: str, tools: ToolRegistry, sop: SopIndex, budget: Budget, model: ModelClient | None
) -> Investigation:
    """Same assembly as the P8 command line: the rule baseline, or the Agent loop with the chosen model."""
    if model is None and model_spec == BASELINE:
        return BaselineInvestigator(tools, sop, budget).investigate(issue_id)
    return investigate(issue_id, model if model is not None else build_model(model_spec), tools, budget)


class Runner:
    """Runs one investigation at a time against a fixed source (backend URL or loaded snapshot) and a fixed model."""

    def __init__(
        self,
        model_spec: str,
        base_url: str | None = None,
        snapshot: Snapshot | None = None,
        budget: Budget | None = None,
        model_factory: Callable[[], ModelClient] | None = None,
        clock: Callable[[], datetime] = _utc_now,
        random_bits: Callable[[int], int] = secrets.randbits,
        stderr: TextIO | None = None,
    ) -> None:
        self.model_name = model_spec.partition(":")[0]
        self._model_spec = model_spec
        self._base_url = base_url
        self._snapshot = snapshot
        self._budget = budget or Budget()
        self._model_factory = model_factory
        self._clock = clock
        self._random_bits = random_bits
        self._stderr = stderr or sys.stderr
        self._sop = load_sop()
        self._required = self._sop.required_evidence()
        self.check_configuration()

    def check_configuration(self) -> None:
        """Raise ValueError/OSError now for anything that would fail every run (bad URL, bad script, no snapshot)."""
        build_transport(self._base_url, self._snapshot)
        if self._model_factory is None and self._model_spec != BASELINE:
            build_model(self._model_spec)

    def run(self, issue_id: str) -> dict[str, Any]:
        started = self._clock()
        run_id = new_run_id(int(started.timestamp() * 1000), self._random_bits(80))
        error: str | None = None
        investigation: Investigation | None = None
        try:
            tools = ToolRegistry(build_transport(self._base_url, self._snapshot), local_tools=[SopSearch(self._sop)])
            model = self._model_factory() if self._model_factory is not None else None
            investigation = run_investigation(issue_id, self._model_spec, tools, self._sop, self._budget, model)
            if any((call.error or "").startswith("transport_error") for call in investigation.tool_calls):
                error = BACKEND_UNREACHABLE
        except Exception as exc:  # noqa: BLE001 - never leak the original text; only the type goes to stderr
            print(f"runner: {type(exc).__name__} during {run_id}", file=self._stderr)
            error = INTERNAL_ERROR
        return self._record(run_id, issue_id, started, self._clock(), investigation, error)

    def _record(
        self,
        run_id: str,
        issue_id: str,
        started: datetime,
        finished: datetime,
        investigation: Investigation | None,
        error: str | None,
    ) -> dict[str, Any]:
        if error is not None:
            refusal = abstention(issue_id, _ERROR_EXPLANATIONS[error])
            investigation = (
                dataclasses.replace(investigation, conclusion=refusal)
                if investigation is not None
                else Investigation(refusal, "error", (), 0, Usage(), 0)
            )
        assert investigation is not None
        verification = verify(investigation, self._required)
        calls = investigation.tool_calls
        record: dict[str, Any] = {
            "runId": run_id,
            "issueId": issue_id,
            "model": self.model_name,
            "modelIsStandIn": True,
            "startedAt": _iso(started),
            "finishedAt": _iso(finished),
            "status": "error" if error is not None else investigation.status,
            "conclusion": investigation.conclusion.as_dict(),
            "toolCalls": [
                {"tool": c.tool, "args": c.args, "status": c.status, "ok": c.ok, "asOf": c.as_of} for c in calls
            ],
            "asOf": list(dict.fromkeys(c.as_of for c in calls if c.as_of)),
            "verification": {
                "fabricatedCitations": [c.as_dict() for c in verification.fabricated_citations],
                "fabricatedExplanationIds": list(verification.fabricated_explanation_ids),
                "supported": verification.supported,
            },
            "usage": {
                "inputTokens": investigation.usage.input_tokens,
                "outputTokens": investigation.usage.output_tokens,
                "isEstimate": True,
            },
        }
        if error is not None:
            record["error"] = error
        return record
