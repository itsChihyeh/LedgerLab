"""Rule baseline: the SOP's distinguishing features written as plain rules over the same read-only tools.

The judgement is split into one pure function, `next_step(issue_id, results, sop)`: given the tool results seen so
far it says either which tool calls are still needed or what the conclusion is. `BaselineInvestigator` feeds it
`ToolResult` objects straight from the tool layer; `StubModel` feeds it results parsed back out of the conversation.
Memo text is never read: a conclusion rests on amounts, currencies, statuses, versions and record identifiers only.
"""

from __future__ import annotations

import json
import re
import time
from collections.abc import Callable, Sequence
from dataclasses import dataclass
from typing import Any

from ledgerlab_agent.agent import ISSUE_ID_PATTERN, Budget, Investigation
from ledgerlab_agent.conclusion import Conclusion
from ledgerlab_agent.enums import ABSTAIN_CAUSE
from ledgerlab_agent.model import Usage
from ledgerlab_agent.records import Record
from ledgerlab_agent.sop import SopIndex, load_sop
from ledgerlab_agent.tools import ToolRegistry, ToolResult

UNKNOWN_REFERENCE_PREFIX = "unknown_"
BATCH_ID_PATTERN = re.compile(r"[A-Za-z0-9_\-]{1,64}")


@dataclass(frozen=True)
class Call:
    tool: str
    args: dict[str, Any]


@dataclass(frozen=True)
class NeedMore:
    """Tool calls still to make (never empty, never a call whose result is already known)."""

    calls: tuple[Call, ...]


@dataclass(frozen=True)
class Verdict:
    conclusion: Conclusion


Step = NeedMore | Verdict


class _Abstain(Exception):
    """Internal: the evidence is missing or does not fit any SOP clause; the message says why."""


@dataclass(frozen=True)
class _Finding:
    root_cause: str
    explanation: str
    cited: tuple[Record, ...]


# ---- lookups over what has been seen ------------------------------------------------------------------------------
def _key(tool: str, args: dict[str, Any]) -> tuple[str, str]:
    return tool, json.dumps(args, sort_keys=True, ensure_ascii=False)


def _index(results: Sequence[ToolResult]) -> dict[tuple[str, str], ToolResult]:
    seen: dict[tuple[str, str], ToolResult] = {}
    for result in results:
        seen.setdefault(_key(result.tool, result.args), result)
    return seen


def _obj(value: object) -> dict[str, Any]:
    return value if isinstance(value, dict) else {}


def _int(value: object) -> int | None:
    return value if isinstance(value, int) and not isinstance(value, bool) else None


def _need(value: object, what: str) -> Any:
    if value is None or value == "":
        raise _Abstain(f"the record does not show {what}")
    return value


def _usable(result: ToolResult) -> dict[str, Any]:
    if not result.ok:
        raise _Abstain(f"{result.tool} did not return data ({result.error or result.status})")
    if result.truncated:
        raise _Abstain(f"{result.tool} returned a truncated response")
    return _obj(result.data)


def _cite(result: ToolResult, kind: str, record_id: object) -> Record:
    record = Record(kind, str(record_id))
    if record not in result.records:
        raise _Abstain(f"{kind} {record_id} is not among the records {result.tool} returned")
    return record


# ---- the issue ------------------------------------------------------------------------------------------------------
@dataclass(frozen=True)
class _Issue:
    id: str
    category: str
    subject_id: str
    expected: dict[str, Any]
    actual: dict[str, Any]
    batch_ids: tuple[str, ...]


def _read_issue(issue_id: str, result: ToolResult) -> _Issue:
    data = _usable(result)
    issue = _obj(data.get("issue"))
    if issue.get("id") != issue_id:
        raise _Abstain("get_issue returned a different issue than the one asked for")
    refs = _obj(data.get("references"))
    batch_ids = refs.get("batchIds")
    return _Issue(
        id=issue_id,
        category=str(issue.get("category")),
        subject_id=str(issue.get("subjectId")),
        expected=_obj(issue.get("expected")),
        actual=_obj(issue.get("actual")),
        batch_ids=tuple(b for b in batch_ids if isinstance(b, str)) if isinstance(batch_ids, list) else (),
    )


def _single_batch_id(issue: _Issue) -> str:
    if len(issue.batch_ids) != 1:
        raise _Abstain(f"the issue references {len(issue.batch_ids)} batches, expected exactly one")
    return issue.batch_ids[0]


def _batch_call(batch_id: object) -> Call:
    if not isinstance(batch_id, str) or BATCH_ID_PATTERN.fullmatch(batch_id) is None:
        raise _Abstain("no usable batch identifier")
    return Call("get_batch", {"batch_id": batch_id})


def _search_call(reference: object) -> Call:
    if not isinstance(reference, str) or BATCH_ID_PATTERN.fullmatch(reference) is None:
        raise _Abstain("no usable bank reference")
    return Call("search_bank_lines", {"reference": reference})


# ---- batch and bank line helpers -----------------------------------------------------------------------------------
@dataclass(frozen=True)
class _Batch:
    result: ToolResult
    id: str
    status: str
    hold_reason: str | None
    net: int | None
    currency: str
    line_refs: tuple[str, ...]


def _read_batch(result: ToolResult, expected_id: str) -> _Batch:
    data = _usable(result)
    batch = _obj(data.get("batch"))
    if batch.get("batchId") != expected_id:
        raise _Abstain("get_batch returned a different batch than the one asked for")
    lines = data.get("lines")
    refs = tuple(str(_obj(line).get("processorRef")) for line in lines) if isinstance(lines, list) else ()
    hold = batch.get("holdReason")
    return _Batch(
        result,
        expected_id,
        str(batch.get("status")),
        hold if isinstance(hold, str) else None,
        _int(batch.get("net")),
        str(batch.get("currency", "")).lower(),
        refs,
    )


@dataclass(frozen=True)
class _BankLine:
    id: str
    version: int
    status: str
    currency: str
    amount: int
    match_status: str


def _read_bank_lines(result: ToolResult) -> tuple[_BankLine, ...]:
    data = _usable(result)
    raw = data.get("lines")
    if not isinstance(raw, list) or data.get("truncated") is True:
        raise _Abstain("search_bank_lines did not return a complete list of lines")
    lines: list[_BankLine] = []
    for item in raw:
        line = _obj(item)
        amount, version = _int(line.get("amount")), _int(line.get("version"))
        if amount is None or version is None or not isinstance(line.get("bankTxnId"), str):
            raise _Abstain("a bank line is missing its identifier, version or amount")
        lines.append(
            _BankLine(
                line["bankTxnId"],
                version,
                str(line.get("status")),
                str(line.get("currency", "")).lower(),
                amount,
                str(line.get("matchStatus")),
            )
        )
    return tuple(lines)


# ---- per-category plans ------------------------------------------------------------------------------------------
Planner = Callable[[_Issue], list[Call]]
Decider = Callable[[_Issue, dict[tuple[str, str], ToolResult]], _Finding]


def _result(found: dict[tuple[str, str], ToolResult], call: Call) -> ToolResult:
    return found[_key(call.tool, call.args)]


def _plan_deposit_mismatch(issue: _Issue) -> list[Call]:
    batch_id = _need(issue.expected.get("id"), "which batch the deposit belongs to")
    return [_search_call(batch_id), _batch_call(batch_id)]


def _decide_deposit_mismatch(issue: _Issue, found: dict[tuple[str, str], ToolResult]) -> _Finding:
    batch_id = issue.expected["id"]
    net = _int(issue.expected.get("net"))
    search = _result(found, _search_call(batch_id))
    batch = _read_batch(_result(found, _batch_call(batch_id)), batch_id)
    lines = _read_bank_lines(search)
    if net is None or batch.net != net:
        raise _Abstain("the issue's expected net and the batch net disagree or are missing")
    if all(line.id != issue.subject_id for line in lines):
        raise _Abstain("the disputed bank line is not among the lines for this reference")
    booked = [x for x in lines if x.status == "BOOKED" and x.currency == batch.currency]

    def cited(picked: list[_BankLine]) -> tuple[Record, ...]:
        base = [Record("issue", issue.id), _cite(batch.result, "batch", batch_id)]
        return tuple(base + [_cite(search, "bank_line", x.id) for x in picked])

    if len(booked) >= 2 and sum(x.amount for x in booked) == net:
        parts = " + ".join(str(x.amount) for x in booked)
        return _Finding(
            "BANK_SPLIT_DEPOSIT",
            f"Batch {batch_id} expects a net of {net} cents. The bank booked {len(booked)} credits for the same "
            f"reference ({', '.join(x.id for x in booked)}) of {parts} cents, which add up to the batch net: "
            "the deposit arrived split into several credits.",
            cited(booked),
        )
    if len(booked) == 1 and booked[0].id == issue.subject_id and booked[0].amount < net:
        line = booked[0]
        return _Finding(
            "BANK_SHORT_DEPOSIT",
            f"Batch {batch_id} expects a net of {net} cents but the only booked credit for this reference, "
            f"{line.id}, is {line.amount} cents: the bank deposited {net - line.amount} cents too little and no "
            "other credit makes up the difference.",
            cited(booked),
        )
    raise _Abstain("the bank lines fit neither a short deposit nor a split deposit")


def _plan_unmatched_bank_line(issue: _Issue) -> list[Call]:
    reference = _need(issue.actual.get("reference"), "the bank line's reference")
    if isinstance(reference, str) and reference.startswith(UNKNOWN_REFERENCE_PREFIX):
        return [_search_call(reference)]
    return [_search_call(reference), _batch_call(reference)]


def _decide_unmatched_bank_line(issue: _Issue, found: dict[tuple[str, str], ToolResult]) -> _Finding:
    reference = issue.actual["reference"]
    search = _result(found, _search_call(reference))
    lines = _read_bank_lines(search)
    own = next((x for x in lines if x.id == issue.subject_id), None)
    if own is None or own.status != "BOOKED":
        raise _Abstain("the disputed bank line is not a booked line among the lines for its reference")
    if reference.startswith(UNKNOWN_REFERENCE_PREFIX):
        return _Finding(
            "BANK_UNKNOWN_CREDIT",
            f"Bank line {own.id} credits {own.amount} cents under the reference {reference}, which is not the "
            "reference of any payout batch, so there is no expected deposit for it.",
            (Record("issue", issue.id), _cite(search, "bank_line", own.id)),
        )
    batch = _read_batch(_result(found, _batch_call(reference)), reference)
    base = [Record("issue", issue.id), _cite(batch.result, "batch", reference)]
    if own.currency != batch.currency:
        if batch.net is None or own.amount != batch.net:
            raise _Abstain("the foreign-currency line does not carry the batch net")
        return _Finding(
            "BANK_FOREIGN_CURRENCY_LABEL",
            f"Bank line {own.id} carries the batch reference {reference} and the batch net of {own.amount} cents, "
            f"but is labelled {own.currency.upper()} while batch {reference} is in {batch.currency.upper()}.",
            tuple(base + [_cite(search, "bank_line", own.id)]),
        )
    twin = next(
        (
            x
            for x in lines
            if x.id != own.id
            and x.status == "BOOKED"
            and x.amount == own.amount
            and x.currency == own.currency
            and x.match_status == "MATCHED"
        ),
        None,
    )
    if twin is not None and own.match_status != "MATCHED":
        return _Finding(
            "BANK_DUPLICATE_CREDIT",
            f"Bank line {own.id} repeats {twin.id}: same reference {reference}, same amount of {own.amount} cents, "
            f"same currency. {twin.id} already matches batch {reference}, so {own.id} is a second credit for the "
            "same payout.",
            tuple(base + [_cite(search, "bank_line", twin.id), _cite(search, "bank_line", own.id)]),
        )
    raise _Abstain("the unmatched bank line fits no known bank-side cause")


def _plan_timing(issue: _Issue) -> list[Call]:
    return [_batch_call(issue.subject_id), _search_call(issue.subject_id)]


def _decide_timing(issue: _Issue, found: dict[tuple[str, str], ToolResult]) -> _Finding:
    batch_id = issue.subject_id
    batch = _read_batch(_result(found, _batch_call(batch_id)), batch_id)
    search = _result(found, _search_call(batch_id))
    lines = _read_bank_lines(search)
    if batch.status != "CONFIRMED":
        raise _Abstain(f"batch {batch_id} is {batch.status}, not a confirmed batch waiting for its deposit")
    base = [Record("issue", issue.id), _cite(batch.result, "batch", batch_id)]
    if not lines:
        return _Finding(
            "BANK_DEPOSIT_DELAYED",
            f"Batch {batch_id} is confirmed with a net of {batch.net} cents, and the bank line search for "
            "that reference succeeded but found no line at all: the deposit has not been reported yet.",
            tuple(base),
        )
    if all(x.status == "PENDING" for x in lines) and all(x.amount == batch.net for x in lines):
        pending = ", ".join(f"{x.id} (version {x.version})" for x in lines)
        return _Finding(
            "BANK_PENDING_FIRST",
            f"Batch {batch_id} is confirmed with a net of {batch.net} cents. The only bank line for the "
            f"reference is {pending}, still PENDING for the full amount and not yet booked.",
            tuple(base + [_cite(search, "bank_line", x.id) for x in lines]),
        )
    raise _Abstain("the bank lines include a booked line or an unexpected amount")


def _plan_by_issue_batch(issue: _Issue) -> list[Call]:
    return [_batch_call(_single_batch_id(issue))]


def _decide_duplicate_statement_line(issue: _Issue, found: dict[tuple[str, str], ToolResult]) -> _Finding:
    batch_id = _single_batch_id(issue)
    batch = _read_batch(_result(found, _batch_call(batch_id)), batch_id)
    count = _int(issue.actual.get("count"))
    line_id = issue.actual.get("statement_line_id")
    if count is None or count < 2:
        raise _Abstain("the issue does not show a repeated statement line")
    return _Finding(
        "PROCESSOR_DUPLICATE_LINE",
        f"The processor's statement lists line {line_id} {count} times for batch {batch_id}; the batch itself is "
        f"{batch.status} and the bank side is not involved.",
        (Record("issue", issue.id), _cite(batch.result, "batch", batch_id)),
    )


def _decide_missing_in_ledger(issue: _Issue, found: dict[tuple[str, str], ToolResult]) -> _Finding:
    batch_id = _single_batch_id(issue)
    batch = _read_batch(_result(found, _batch_call(batch_id)), batch_id)
    charge = _need(issue.actual.get("processor_ref"), "the processor charge reference")
    base = (Record("issue", issue.id), _cite(batch.result, "batch", batch_id))
    if batch.status == "HELD" and batch.hold_reason == "UNKNOWN_PAYMENT":
        return _Finding(
            "WEBHOOK_LOST",
            f"Charge {charge} is on the processor's statement but not in the ledger, and batch {batch_id} is "
            "HELD with reason UNKNOWN_PAYMENT: the batch refers to a payment whose event never arrived.",
            base,
        )
    if batch.status != "HELD" and charge not in batch.line_refs:
        return _Finding(
            "PROCESSOR_UNKNOWN_CHARGE_LINE",
            f"Charge {charge} is on the processor's statement but not among the lines of batch {batch_id}, and the "
            f"batch is not held ({batch.status}): the processor listed an extra charge line.",
            base,
        )
    raise _Abstain("the batch state fits neither a lost webhook nor an extra charge line")


def _decide_unsupported_line(issue: _Issue, found: dict[tuple[str, str], ToolResult]) -> _Finding:
    batch_id = issue.subject_id
    batch = _read_batch(_result(found, _batch_call(batch_id)), batch_id)
    line = _obj(issue.actual.get("line"))
    if line.get("type") != "adjustment" or batch.status != "HELD" or batch.hold_reason != "UNSUPPORTED_LINE":
        raise _Abstain("the line is not an adjustment that holds the batch as UNSUPPORTED_LINE")
    return _Finding(
        "PROCESSOR_UNSUPPORTED_ADJUSTMENT",
        f"Batch {batch_id} contains an adjustment line of {line.get('net')} cents, a line type the platform does "
        "not support, and the batch is HELD with reason UNSUPPORTED_LINE.",
        (Record("issue", issue.id), _cite(batch.result, "batch", batch_id)),
    )


def _decide_fee_mismatch(issue: _Issue, found: dict[tuple[str, str], ToolResult]) -> _Finding:
    batch_id = _single_batch_id(issue)
    batch = _read_batch(_result(found, _batch_call(batch_id)), batch_id)
    expected = _int(issue.expected.get("fee"))
    fees = issue.actual.get("fees")
    actual = _int(_obj(fees[0]).get("fee")) if isinstance(fees, list) and fees else None
    if expected is None or actual is None or expected == actual:
        raise _Abstain("the issue does not show two different fee amounts")
    return _Finding(
        "PROCESSOR_FEE_DIFFERS",
        f"The processor charged a fee of {actual} cents on a payment in batch {batch_id}, while the contract fee "
        f"is {expected} cents ({actual - expected:+d}); the amounts and the bank deposit otherwise agree.",
        (Record("issue", issue.id), _cite(batch.result, "batch", batch_id)),
    )


def _plan_by_subject_batch(issue: _Issue) -> list[Call]:
    return [_batch_call(issue.subject_id)]


def _decide_total_mismatch(issue: _Issue, found: dict[tuple[str, str], ToolResult]) -> _Finding:
    batch_id = issue.subject_id
    batch = _read_batch(_result(found, _batch_call(batch_id)), batch_id)
    if issue.actual.get("reason") != "TOTAL_MISMATCH" or batch.status != "HELD" or batch.hold_reason != "TOTAL_MISMATCH":
        raise _Abstain("the batch is not held for a payout total mismatch")
    line = _obj(issue.actual.get("line"))
    return _Finding(
        "PROCESSOR_PAYOUT_AMOUNT_DIFFERS",
        f"Batch {batch_id} is HELD with reason TOTAL_MISMATCH: the processor announced a payout of "
        f"{line.get('payout_amount')} cents but its lines add up to {line.get('line_net')} cents.",
        (Record("issue", issue.id), _cite(batch.result, "batch", batch_id)),
    )


_RULES: dict[str, tuple[Planner, Decider]] = {
    "DEPOSIT_AMOUNT_MISMATCH": (_plan_deposit_mismatch, _decide_deposit_mismatch),
    "UNMATCHED_BANK_LINE": (_plan_unmatched_bank_line, _decide_unmatched_bank_line),
    "TIMING_DIFFERENCE": (_plan_timing, _decide_timing),
    "DUPLICATE_STATEMENT_LINE": (_plan_by_issue_batch, _decide_duplicate_statement_line),
    "MISSING_IN_LEDGER": (_plan_by_issue_batch, _decide_missing_in_ledger),
    "UNSUPPORTED_BATCH_LINE": (_plan_by_subject_batch, _decide_unsupported_line),
    "FEE_MISMATCH": (_plan_by_issue_batch, _decide_fee_mismatch),
    "BATCH_TOTAL_MISMATCH": (_plan_by_subject_batch, _decide_total_mismatch),
}


# ---- the pure step function --------------------------------------------------------------------------------------
def _describe(call: Call) -> str:
    return f"{call.tool}({', '.join(f'{k}={v}' for k, v in call.args.items())})"


def _abstain(issue_id: str, sop: SopIndex, reason: str, cited: tuple[Record, ...] = ()) -> Verdict:
    clause = sop.for_cause(ABSTAIN_CAUSE)
    return Verdict(
        Conclusion(
            issue_id=issue_id,
            root_cause=ABSTAIN_CAUSE,
            confidence="low",
            abstain=True,
            explanation=f"No conclusion: {reason}. Escalating to a person.",
            citations=cited,
            sop_clauses=(clause.id,) if clause else (),
            recommended_action="ESCALATE",
        )
    )


def next_step(issue_id: str, results: Sequence[ToolResult], sop: SopIndex) -> Step:
    """What to do next given every tool result seen so far (see the module docstring)."""
    seen = _index(results)
    issue_call = Call("get_issue", {"issue_id": issue_id})
    issue_result = seen.get(_key(issue_call.tool, issue_call.args))
    if issue_result is None:
        return NeedMore((issue_call,))
    issue_record = (Record("issue", issue_id),) if issue_result.ok and Record("issue", issue_id) in issue_result.records else ()
    try:
        issue = _read_issue(issue_id, issue_result)
        _cite(issue_result, "issue", issue_id)
        rules = _RULES.get(issue.category)
        if rules is None:
            raise _Abstain(f"issue category {issue.category} is not covered by the SOP")
        plan, decide = rules
        calls = plan(issue)
        missing = tuple(c for c in calls if _key(c.tool, c.args) not in seen)
        if missing:
            return NeedMore(missing)
        for call in calls:
            _usable(seen[_key(call.tool, call.args)])
        finding = decide(issue, seen)
    except _Abstain as why:
        return _abstain(issue_id, sop, str(why), issue_record)
    clause = sop.for_cause(finding.root_cause)
    if clause is None or clause.recommended_action is None:
        raise ValueError(f"the SOP has no clause for {finding.root_cause}")
    return Verdict(
        Conclusion(
            issue_id=issue_id,
            root_cause=finding.root_cause,
            confidence="high",
            abstain=False,
            explanation=finding.explanation,
            citations=finding.cited,
            sop_clauses=(clause.id,),
            recommended_action=clause.recommended_action,
        )
    )


# ---- the investigator --------------------------------------------------------------------------------------------
class BaselineInvestigator:
    """Runs next_step against the real tool layer under the same budget as the model-driven agent."""

    def __init__(
        self,
        tools: ToolRegistry,
        sop: SopIndex | None = None,
        budget: Budget | None = None,
        clock: Callable[[], float] = time.monotonic,
    ) -> None:
        self._tools = tools
        self._sop = sop or load_sop()
        self._budget = budget or Budget()
        self._clock = clock

    def investigate(self, issue_id: str) -> Investigation:
        if re.fullmatch(ISSUE_ID_PATTERN, issue_id) is None:
            raise ValueError("issue_id must be 1-64 characters of letters, digits, '_' or '-'")
        started = self._clock()
        results: list[ToolResult] = []
        turns = 0

        def finish(status: str, conclusion: Conclusion) -> Investigation:
            elapsed = int((self._clock() - started) * 1000)
            return Investigation(conclusion, status, tuple(results), turns, Usage(), elapsed)

        def exhausted(reason: str) -> Investigation:
            verdict = _abstain(issue_id, self._sop, f"stopped without a conclusion: {reason}")
            return finish("budget_exhausted", verdict.conclusion)

        while True:
            if turns >= self._budget.max_turns:
                return exhausted(f"step limit of {self._budget.max_turns} reached")
            step = next_step(issue_id, results, self._sop)
            turns += 1
            if isinstance(step, Verdict):
                return finish("ok", step.conclusion)
            for call in step.calls:
                if len(results) >= self._budget.max_tool_calls:
                    return exhausted(f"tool call limit of {self._budget.max_tool_calls} reached")
                results.append(self._tools.call(call.tool, call.args))


__all__ = ["BaselineInvestigator", "Call", "NeedMore", "Step", "Verdict", "next_step"]
