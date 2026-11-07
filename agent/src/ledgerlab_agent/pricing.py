"""Price table, cost arithmetic and the spend cap that keeps an external model from costing more than the user allowed.

`agent/pricing.json` holds USD per million tokens per provider. Every field starts as null: a price nobody has checked
is not a price, and a provider with a null field is refused (`PriceNotVerified`) before any request is built.
"""

from __future__ import annotations

import json
from dataclasses import dataclass
from pathlib import Path

from ledgerlab_agent.model import ModelClient, ModelRequest, ModelResponse, Usage

PRICING_PATH = Path(__file__).resolve().parents[2] / "pricing.json"
_PRICE_FIELDS = ("input_cache_hit", "input_cache_miss", "output")
_TEXT_FIELDS = ("source_url", "checked_at")


class PriceNotVerified(ValueError):
    """The provider has no complete, checked price entry."""


class SpendLimitReached(RuntimeError):
    """The cumulative cost went over the cap the user confirmed; nothing more may be requested."""


@dataclass(frozen=True)
class Prices:
    """USD per million tokens, with where and when the numbers were read."""

    input_cache_hit: float
    input_cache_miss: float
    output: float
    source_url: str
    checked_at: str


def load_prices(provider: str, path: Path = PRICING_PATH) -> Prices:
    table = json.loads(path.read_text(encoding="utf-8"))
    entry = table.get(provider) if isinstance(table, dict) else None
    if not isinstance(entry, dict):
        raise PriceNotVerified(f"{path.name} has no entry for {provider!r}; add it after checking the provider's price page")
    missing = [name for name in (*_PRICE_FIELDS, *_TEXT_FIELDS) if entry.get(name) is None]
    if missing:
        raise PriceNotVerified(
            f"{provider} prices are not verified yet ({', '.join(missing)} is null in {path.name}); "
            "check the provider's price page, fill in the file with the source and date, then run again"
        )
    numbers = {name: entry[name] for name in _PRICE_FIELDS}
    if any(isinstance(v, bool) or not isinstance(v, (int, float)) or v < 0 for v in numbers.values()):
        raise PriceNotVerified(f"{provider} prices in {path.name} must be non-negative numbers (USD per million tokens)")
    return Prices(
        float(numbers["input_cache_hit"]),
        float(numbers["input_cache_miss"]),
        float(numbers["output"]),
        str(entry["source_url"]),
        str(entry["checked_at"]),
    )


def usage_cost_usd(usage: Usage, prices: Prices) -> float:
    """Input is billed per cache hit and miss; a usage without that breakdown is billed entirely at the miss price."""
    if usage.cache_hit_tokens + usage.cache_miss_tokens > 0:
        hit, miss = usage.cache_hit_tokens, usage.cache_miss_tokens
    else:
        hit, miss = 0, usage.input_tokens
    return (hit * prices.input_cache_hit + miss * prices.input_cache_miss + usage.output_tokens * prices.output) / 1_000_000


def estimate_run_usd(questions: int, stub_input_tokens: int, prices: Prices, input_multiplier: float, output_tokens_per_question: int) -> float:
    """Upper-ish estimate before a run: the stand-in's (estimated) input tokens times a multiplier, all at the cache-miss
    price, plus a flat output per question. It is a planning number, not a quote."""
    return (stub_input_tokens * input_multiplier * prices.input_cache_miss + questions * output_tokens_per_question * prices.output) / 1_000_000


class SpendGuard:
    """Adds up what the requests cost; `exceeded` turns true as soon as the total is above the confirmed cap."""

    def __init__(self, cap_usd: float, prices: Prices) -> None:
        if not cap_usd > 0:
            raise ValueError("the spend cap must be greater than 0 USD")
        self.cap_usd = cap_usd
        self.prices = prices
        self.spent_usd = 0.0
        self.usage = Usage()

    def add(self, usage: Usage) -> None:
        self.usage = self.usage + usage
        self.spent_usd += usage_cost_usd(usage, self.prices)

    @property
    def exceeded(self) -> bool:
        return self.spent_usd > self.cap_usd


class GuardedModel:
    """Wraps a model client: refuses a request once the cap is exceeded, and books every reply's usage."""

    def __init__(self, inner: ModelClient, guard: SpendGuard) -> None:
        self._inner = inner
        self.guard = guard

    def complete(self, request: ModelRequest) -> ModelResponse:
        if self.guard.exceeded:
            raise SpendLimitReached(f"spent {self.guard.spent_usd:.4f} USD, above the cap of {self.guard.cap_usd:.4f} USD")
        response = self._inner.complete(request)
        self.guard.add(response.usage)
        return response
