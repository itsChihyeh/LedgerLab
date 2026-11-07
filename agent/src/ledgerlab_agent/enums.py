"""Closed vocabularies shared by the tool layer, the conclusion validator and later tasks."""

from __future__ import annotations

RECORD_KINDS: tuple[str, ...] = (
    "issue",
    "payment",
    "event",
    "entry",
    "batch",
    "batch_line",
    "bank_line",
    "statement_line",
    "refund",
    "payout",
    "correction",
    "run",
)

# docs/design/investigation-agent.md section 6.
ROOT_CAUSES: tuple[str, ...] = (
    "BANK_SHORT_DEPOSIT",
    "BANK_SPLIT_DEPOSIT",
    "BANK_DUPLICATE_CREDIT",
    "BANK_FOREIGN_CURRENCY_LABEL",
    "BANK_UNKNOWN_CREDIT",
    "BANK_DEPOSIT_DELAYED",
    "BANK_PENDING_FIRST",
    "PROCESSOR_DUPLICATE_LINE",
    "PROCESSOR_UNKNOWN_CHARGE_LINE",
    "PROCESSOR_UNSUPPORTED_ADJUSTMENT",
    "PROCESSOR_FEE_DIFFERS",
    "PROCESSOR_PAYOUT_AMOUNT_DIFFERS",
    "WEBHOOK_LOST",
    "INSUFFICIENT_EVIDENCE",
)

ABSTAIN_CAUSE = "INSUFFICIENT_EVIDENCE"

RECOMMENDED_ACTIONS: tuple[str, ...] = (
    "WAIT_FOR_NEXT_STATEMENT",
    "REQUEST_BANK_TRACE",
    "CONTACT_PROCESSOR",
    "PROPOSE_CORRECTION_FOR_REVIEW",
    "MATCH_MANUALLY_AFTER_REVIEW",
    "ESCALATE",
)

CONFIDENCES: tuple[str, ...] = ("high", "medium", "low")

SOP_CLAUSE_PATTERN = r"^SOP-\d+(\.\d+)*$"

MAX_EXPLANATION_CHARS = 800
MAX_CITATIONS = 20
