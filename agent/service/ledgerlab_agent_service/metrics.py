"""In-process investigation metrics in the Prometheus text format 0.0.4 (docs/design/observability.md section 3).

One `ServiceMetrics` instance hangs off the service; there is no module-level state. The counts restart from zero with
the process, which `rate()` copes with. Label values are closed enums, never an id.
"""

from __future__ import annotations

import threading

CONTENT_TYPE = "text/plain; version=0.0.4; charset=utf-8"
# The statuses `Investigation` can end with, plus "error" (a record the service itself marked as failed). Anything
# else is counted as "error" so the label set stays closed.
STATUSES = ("ok", "budget_exhausted", "error")
BUCKET_BOUNDS = (0.01, 0.05, 0.1, 0.5, 1.0, 2.0, 5.0, 10.0, 30.0)

INVESTIGATIONS = "ledgerlab_agent_investigations_total"
DURATION = "ledgerlab_agent_investigation_duration_seconds"
TOOL_CALLS = "ledgerlab_agent_tool_calls_total"


def escape_label_value(value: str) -> str:
    """Backslash, double quote and line feed are the three characters the exposition format escapes in a label value."""
    return value.replace("\\", "\\\\").replace('"', '\\"').replace("\n", "\\n")


def _bound_text(bound: float) -> str:
    return f"{bound:g}"


class ServiceMetrics:
    """Thread-safe counters and one histogram; `render()` returns a consistent snapshot."""

    def __init__(self) -> None:
        self._lock = threading.Lock()
        self._by_status = {status: 0 for status in STATUSES}
        self._bucket_counts = [0] * len(BUCKET_BOUNDS)  # per-bucket (not cumulative); the +Inf bucket is `_count`
        self._sum = 0.0
        self._count = 0
        self._tool_calls = 0

    def observe_investigation(self, status: str, duration_seconds: float, tool_calls: int) -> None:
        label = status if status in self._by_status else "error"
        duration = max(duration_seconds, 0.0)
        with self._lock:
            self._by_status[label] += 1
            self._sum += duration
            self._count += 1
            self._tool_calls += tool_calls
            for index, bound in enumerate(BUCKET_BOUNDS):
                if duration <= bound:
                    self._bucket_counts[index] += 1
                    break

    def render(self) -> str:
        with self._lock:
            by_status = dict(self._by_status)
            buckets = list(self._bucket_counts)
            total, count, tool_calls = self._sum, self._count, self._tool_calls
        lines = [
            f"# HELP {INVESTIGATIONS} Investigations finished, by final status.",
            f"# TYPE {INVESTIGATIONS} counter",
        ]
        lines += [f'{INVESTIGATIONS}{{status="{escape_label_value(s)}"}} {n}' for s, n in by_status.items()]
        lines += [
            f"# HELP {DURATION} Wall-clock seconds from starting an investigation to its record being written.",
            f"# TYPE {DURATION} histogram",
        ]
        cumulative = 0
        for bound, in_bucket in zip(BUCKET_BOUNDS, buckets, strict=True):
            cumulative += in_bucket
            lines.append(f'{DURATION}_bucket{{le="{_bound_text(bound)}"}} {cumulative}')
        lines += [
            f'{DURATION}_bucket{{le="+Inf"}} {count}',
            f"{DURATION}_sum {total!r}",
            f"{DURATION}_count {count}",
            f"# HELP {TOOL_CALLS} Tool calls made by finished investigations.",
            f"# TYPE {TOOL_CALLS} counter",
            f"{TOOL_CALLS} {tool_calls}",
        ]
        return "\n".join(lines) + "\n"
