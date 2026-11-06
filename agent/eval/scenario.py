"""Scenario primitives: thin wrappers over the public HTTP interfaces of the app and the simulator.

No database access and no simulator truth tables. The HTTP layer is injectable (`Sender`) so request construction is
unit-testable without Docker. Time and sleeping are injectable as well.
"""

from __future__ import annotations

import json
import time
import urllib.error
import urllib.request
from collections.abc import Callable, Mapping
from datetime import UTC, datetime, timedelta
from typing import Any

Sender = Callable[[str, str, bytes | None, Mapping[str, str]], tuple[int, bytes]]


ISSUE_STATUSES = ("OPEN", "EXPLAINED", "RESOLVED", "ACCEPTED")
ISSUE_PAGE_LIMIT = 50


class ScenarioError(RuntimeError):
    pass


def urllib_sender(timeout: float = 30.0) -> Sender:
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))

    def send(method: str, url: str, body: bytes | None, headers: Mapping[str, str]) -> tuple[int, bytes]:
        request = urllib.request.Request(url, data=body, headers=dict(headers), method=method)
        try:
            with opener.open(request, timeout=timeout) as response:
                return response.status, response.read()
        except urllib.error.HTTPError as error:
            return error.code, error.read()

    return send


def parse_instant(value: str) -> datetime:
    return datetime.fromisoformat(value.replace("Z", "+00:00"))


class Scenario:
    def __init__(
        self,
        app: str,
        sim: str,
        sender: Sender | None = None,
        *,
        sleep: Callable[[float], None] = time.sleep,
        monotonic: Callable[[], float] = time.monotonic,
        now: Callable[[], datetime] = lambda: datetime.now(UTC),
    ) -> None:
        self.app_base = "http://" + app.strip()
        self.sim_base = "http://" + sim.strip()
        self._send = sender or urllib_sender()
        self._sleep = sleep
        self._monotonic = monotonic
        self._now = now

    # ---- raw HTTP -------------------------------------------------------------------------------------
    def call(
        self,
        base: str,
        path: str,
        method: str = "GET",
        data: Any = None,
        *,
        content_type: str = "application/json",
        raw: bool = False,
    ) -> tuple[int, Any]:
        """Returns (status, parsed JSON | bytes when raw | text when the body is not JSON). Never raises on HTTP errors."""
        if isinstance(data, bytes):
            body: bytes | None = data
        elif data is None:
            body = None
        else:
            body = json.dumps(data).encode("utf-8")
        headers = {"Content-Type": content_type} if body is not None else {}
        status, payload = self._send(method, base + path, body, headers)
        if raw:
            return status, payload
        text = payload.decode("utf-8", errors="replace")
        try:
            return status, json.loads(text) if text else None
        except ValueError:
            return status, text

    def expect(self, base: str, path: str, method: str = "GET", data: Any = None, **kwargs: Any) -> Any:
        status, body = self.call(base, path, method, data, **kwargs)
        if not 200 <= status < 300:
            raise ScenarioError(f"{method} {path}: HTTP {status}: {str(body)[:300]}")
        return body

    def app_get(self, path: str) -> tuple[int, Any]:
        return self.call(self.app_base, path)

    def wait_for(self, label: str, fetch: Callable[[], Any], ready: Callable[[Any], bool], timeout: float = 60.0) -> Any:
        deadline = self._monotonic() + timeout
        last: Any = None
        while self._monotonic() < deadline:
            try:
                last = fetch()
                if ready(last):
                    return last
            except (ScenarioError, urllib.error.URLError) as error:
                last = str(error)
            self._sleep(0.5)  # Bounded condition polling, not a timing assumption.
        raise ScenarioError(f"Timed out waiting for {label}: {str(last)[:300]}")

    # ---- simulator clock ------------------------------------------------------------------------------
    def sim_now(self) -> datetime:
        return parse_instant(self.expect(self.sim_base, "/sim/clock")["now"])

    def advance_to(self, target: str) -> None:
        seconds = int((parse_instant(target) - self.sim_now()).total_seconds())
        if seconds < 0:
            raise ScenarioError(f"Simulator clock is already past {target}")
        self.expect(self.sim_base, "/sim/clock/advance", "POST", {"seconds": seconds})

    # ---- processor side ---------------------------------------------------------------------------------
    def create_charge(self, amount: int, fee: int | None = None, wait: bool = True) -> dict[str, Any]:
        body: dict[str, Any] = {"amount": amount}
        if fee is not None:
            body["fee"] = fee
        created = self.expect(self.sim_base, "/sim/charges", "POST", body)
        if wait:
            ref = created["charge"]["id"]
            self.wait_for(
                f"capture of {ref}",
                lambda: self.expect(self.app_base, "/api/payments/" + ref),
                lambda p: p["status"] == "CAPTURED",
            )
        return created

    def create_payout(self, amount: int | None = None, wait: bool = True, timeout: float = 20.0) -> dict[str, Any]:
        created = self.expect(self.sim_base, "/sim/payouts", "POST", {} if amount is None else {"amount": amount})
        if wait:
            ref = created["payout"]["id"]
            try:
                self.wait_for(
                    f"announcement of {ref}",
                    lambda: self.expect(self.app_base, "/api/batches/" + ref),
                    lambda b: True,
                    timeout=timeout,
                )
            except ScenarioError:
                pass  # The probe records whatever state the app ended up in.
        return created

    def payout_faults(self, payout: str, **faults: Any) -> Any:
        return self.expect(self.sim_base, f"/sim/payouts/{payout}/faults", "POST", faults)

    def reconcile_payout(self, payout: str, settle_timeout: float = 15.0) -> dict[str, Any]:
        event = self.expect(self.sim_base, f"/sim/payouts/{payout}/reconcile", "POST", {})
        status, batch = self.call(self.app_base, "/api/batches/" + payout)
        try:
            batch = self.wait_for(
                f"batch {payout} to leave ANNOUNCED",
                lambda: self.expect(self.app_base, "/api/batches/" + payout),
                lambda b: b["batch"]["status"] != "ANNOUNCED",
                timeout=settle_timeout,
            )
        except ScenarioError:
            status, batch = self.call(self.app_base, "/api/batches/" + payout)
        return {"event": event, "batch": batch}

    def mark_paid(self, payout: str, timeout: float = 20.0) -> dict[str, Any]:
        paid = self.expect(self.sim_base, f"/sim/payouts/{payout}/paid", "POST", {})
        try:
            self.wait_for(
                f"event {paid['event_id']}",
                lambda: self.expect(self.app_base, "/api/events/" + paid["event_id"]),
                lambda e: e["status"] in ("PROCESSED", "PARKED"),
                timeout=timeout,
            )
        except ScenarioError:
            pass
        return paid

    # ---- plans ------------------------------------------------------------------------------------------
    def set_bank_plan(
        self,
        payout: str = "*",
        fault: str = "NONE",
        parameter: int = 0,
        memo: str | None = None,
        scenario: str | None = None,
    ) -> Any:
        body: dict[str, Any] = {"payout_ref": payout, "fault": fault, "parameter": parameter}
        if memo is not None:
            body["memo"] = memo
        if scenario is not None:
            body["scenario"] = scenario
        return self.expect(self.sim_base, "/sim/bank/plan", "PUT", body)

    def set_relay_plan(self, rules: list[dict[str, Any]]) -> Any:
        return self.expect(self.sim_base, "/sim/relay/plan", "PUT", {"rules": rules})

    # ---- bank and processor evidence --------------------------------------------------------------------
    def export_bank_statement(self, day: str, corrupt: str | None = None) -> tuple[int, bytes]:
        suffix = f"?corrupt={corrupt}" if corrupt else ""
        return self.call(self.sim_base, f"/sim/bank/statements/{day}{suffix}", raw=True)

    def import_bank_statement(self, csv_bytes: bytes) -> tuple[int, Any]:
        return self.call(self.app_base, "/api/bank-statements", "POST", csv_bytes, content_type="text/csv")

    def processor_snapshot(self, business_date: str) -> tuple[int, Any]:
        return self.call(self.app_base, "/api/recon/processor-snapshots", "POST", {"businessDate": business_date})

    # ---- reconciliation ---------------------------------------------------------------------------------
    def evidence_boundary(self) -> str:
        """Round the app's current time UP to the next second (what the smoke scripts call `evidence`)."""
        at = parse_instant(self.expect(self.app_base, "/api/overview")["asOf"]["at"])
        return (at.replace(microsecond=0) + timedelta(seconds=1)).isoformat()

    def wait_for_evidence_grace(self, evidence_as_of: str, grace_seconds: int = 60, timeout: float = 150.0) -> None:
        target = parse_instant(evidence_as_of) + timedelta(seconds=grace_seconds)
        self.wait_for(
            "the evidence grace period",
            lambda: self.expect(self.app_base, "/api/overview"),
            lambda o: parse_instant(o["asOf"]["at"]) >= target,
            timeout=timeout,
        )

    def run_reconciliation(self, cutoff_at: str, evidence_as_of: str, dry_run: bool = False) -> tuple[int, Any]:
        body = {"kind": "ALL", "cutoffAt": cutoff_at, "evidenceAsOf": evidence_as_of, "dryRun": dry_run}
        return self.call(self.app_base, "/api/recon/runs", "POST", body)

    def list_issues(self, limit: int = 50) -> list[dict[str, Any]]:
        listing = self.expect(self.app_base, f"/api/recon/issues?limit={limit}")
        issues = listing["issues"] if isinstance(listing, dict) else listing
        return list(issues)

    def list_all_issues(self, categories: tuple[str, ...] = ()) -> list[dict[str, Any]]:
        """Every issue. The endpoint has no paging (limit <= 50), so split by status, then by category when a
        slice still comes back full. Raises if a status+category slice itself reaches the limit."""
        found: dict[str, dict[str, Any]] = {}
        for status in ISSUE_STATUSES:
            rows = self._issue_slice(f"status={status}")
            if len(rows) >= ISSUE_PAGE_LIMIT:
                rows = []
                for category in categories:
                    part = self._issue_slice(f"status={status}&category={category}")
                    if len(part) >= ISSUE_PAGE_LIMIT:
                        raise ScenarioError(f"More than {ISSUE_PAGE_LIMIT} {status} {category} issues")
                    rows.extend(part)
                if not categories:
                    raise ScenarioError(f"More than {ISSUE_PAGE_LIMIT} {status} issues and no categories to split by")
            for row in rows:
                found[row["id"]] = row
        return list(found.values())

    def _issue_slice(self, query: str) -> list[dict[str, Any]]:
        listing = self.expect(self.app_base, f"/api/recon/issues?{query}&limit={ISSUE_PAGE_LIMIT}")
        return list(listing["issues"] if isinstance(listing, dict) else listing)

    def get_issue(self, issue_id: str) -> Any:
        return self.expect(self.app_base, "/api/recon/issues/" + issue_id)
