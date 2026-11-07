"""Setting up an external model run so that nothing is requested before every guard has passed.

The order is fixed: a verified price, then the printed estimate, then the user's explicit `--confirm-spend`, then the
API key. Any failure raises before a model object exists, so no request can have been sent.
"""

from __future__ import annotations

import time
from collections.abc import Callable, Mapping
from dataclasses import dataclass
from pathlib import Path
from typing import TextIO

from ledgerlab_agent.deepseek_model import PROVIDER, DeepSeekModel, Transport
from ledgerlab_agent.pricing import PRICING_PATH, GuardedModel, Prices, SpendGuard, load_prices

DEFAULT_REASONING_EFFORT = "high"
DEFAULT_MAX_TOKENS = 8192
DEFAULT_INPUT_MULTIPLIER = 3.0
DEFAULT_OUTPUT_TOKENS_PER_QUESTION = 2000
# A single `investigate` has no stand-in run to measure: about three times the stand-in's average input per question
# (about 4,600 estimated tokens on dev and test).
SINGLE_INVESTIGATION_INPUT_TOKENS = 14_000


@dataclass(frozen=True)
class ExternalConfig:
    model: str
    reasoning_effort: str = DEFAULT_REASONING_EFFORT
    max_tokens: int = DEFAULT_MAX_TOKENS

    @property
    def label(self) -> str:
        """Model configuration as written in runs.log and reports."""
        return f"{PROVIDER}:{self.model}:{self.reasoning_effort}"


def is_external(spec: str) -> bool:
    return spec.partition(":")[0] == PROVIDER


def parse_spec(spec: str, reasoning_effort: str, max_tokens: int) -> ExternalConfig:
    name, _, model = spec.partition(":")
    if name != PROVIDER:
        raise ValueError(f"unknown external model {name!r}")
    return ExternalConfig(model.strip(), reasoning_effort, max_tokens)


def prepare(
    config: ExternalConfig,
    confirm_spend: float | None,
    estimate: Callable[[Prices], float],
    out: TextIO,
    transport: Transport | None = None,
    environ: Mapping[str, str] | None = None,
    sleep: Callable[[float], None] = time.sleep,
    pricing_path: Path = PRICING_PATH,
) -> tuple[GuardedModel, Prices]:
    prices = load_prices(PROVIDER, pricing_path)
    expected = estimate(prices)
    print(
        f"external model {config.label}: estimated cost {expected:.4f} USD "
        f"(prices from {prices.source_url}, checked {prices.checked_at})",
        file=out,
    )
    if confirm_spend is None:
        raise ValueError(
            "this run would be billed by the provider: nothing was requested. "
            "Re-run with --confirm-spend <USD cap> to allow spending up to that amount"
        )
    guard = SpendGuard(confirm_spend, prices)  # also rejects a cap that is not above 0
    model = DeepSeekModel(config.model, config.reasoning_effort, config.max_tokens, transport, environ, sleep)
    return GuardedModel(model, guard), prices
