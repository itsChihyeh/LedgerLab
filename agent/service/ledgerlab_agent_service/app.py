"""HTTP side of the investigation service (docs/design/agent-workbench-and-mcp.md section 2.2).

Loopback only, JSON only, no cross-origin headers. Every error body is `{"error": "<code>"}`.
"""

from __future__ import annotations

import http.server
import json
import re
import threading
import time
from collections.abc import Callable
from typing import Any, TextIO

from ledgerlab_agent.tools import load_specs

from ledgerlab_agent_service.metrics import CONTENT_TYPE as METRICS_CONTENT_TYPE
from ledgerlab_agent_service.metrics import ServiceMetrics
from ledgerlab_agent_service.runner import Runner
from ledgerlab_agent_service.store import RUN_ID_PATTERN, JsonlStore

MAX_BODY_BYTES = 4096
MAX_DRAIN_BYTES = 1024 * 1024
LOOPBACK_HOSTS = frozenset({"127.0.0.1", "::1", "localhost"})
PATH_HEALTH = "/agent/health"
PATH_METRICS = "/agent/metrics"
PATH_INVESTIGATIONS = "/agent/investigations"
RUN_PREFIX = PATH_INVESTIGATIONS + "/"
_HOST_HEADER = re.compile(r"^(?:\[([0-9A-Fa-f:.]+)\]|([^:\[\]]+))(?::\d{1,5})?$")


def issue_id_pattern() -> re.Pattern[str]:
    """The format P8's routes.json demands of an issue id (one source of truth for the tool layer and the service)."""
    spec = load_specs()["get_issue"]
    return re.compile(next(p.pattern for p in spec.params if p.name == "issue_id"))


class InvestigationService:
    """What the HTTP handler needs: the runner, the store, a one-at-a-time guard and the facts for /agent/health."""

    def __init__(
        self,
        runner: Runner,
        store: JsonlStore,
        source: str,
        monotonic: Callable[[], float] = time.monotonic,
    ) -> None:
        self.runner = runner
        self.store = store
        self.source = source
        self.monotonic = monotonic
        self.metrics = ServiceMetrics()
        self.issue_id_re = issue_id_pattern()
        self.busy = threading.Lock()

    def health(self) -> dict[str, Any]:
        return {"status": "ok", "model": self.runner.model_name, "modelIsStandIn": True, "source": self.source}


def make_handler(service: InvestigationService, stderr: TextIO) -> type[http.server.BaseHTTPRequestHandler]:
    class Handler(http.server.BaseHTTPRequestHandler):
        server_version = "ledgerlab-agent-service"
        sys_version = ""

        # ---- plumbing -------------------------------------------------------------------------------------------
        def log_message(self, format: str, *args: Any) -> None:  # noqa: A002
            print(f"http: {self.command} {self.path.split('?')[0]} -> {args[1] if len(args) > 1 else '-'}", file=stderr)

        def _send(self, status: int, body: dict[str, Any], extra: dict[str, str] | None = None) -> None:
            raw = json.dumps(body, ensure_ascii=False).encode("utf-8")
            self.send_response(status)
            self.send_header("Content-Type", "application/json; charset=utf-8")
            self.send_header("Content-Length", str(len(raw)))
            self.send_header("Cache-Control", "no-store")
            self.send_header("X-Content-Type-Options", "nosniff")
            for name, value in (extra or {}).items():
                self.send_header(name, value)
            self.end_headers()
            self.wfile.write(raw)

        def _send_metrics(self) -> None:
            raw = service.metrics.render().encode("utf-8")
            self.send_response(200)
            self.send_header("Content-Type", METRICS_CONTENT_TYPE)
            self.send_header("Content-Length", str(len(raw)))
            self.send_header("Cache-Control", "no-store")
            self.send_header("X-Content-Type-Options", "nosniff")
            self.end_headers()
            self.wfile.write(raw)

        def _error(self, status: int, code: str, extra: dict[str, str] | None = None) -> None:
            self._send(status, {"error": code}, extra)

        def _host_allowed(self) -> bool:
            """Refuses a Host header that is not a loopback name, so a page cannot reach us through DNS rebinding."""
            match = _HOST_HEADER.match(self.headers.get("Host", ""))
            return match is not None and (match.group(1) or match.group(2)).lower() in LOOPBACK_HOSTS

        def _method_not_allowed(self) -> None:
            allowed = ", ".join(sorted(name[3:] for name in vars(type(self)) if name.startswith("do_")))
            self._error(405, "method_not_allowed", {"Allow": allowed})

        def __getattr__(self, name: str) -> Callable[[], None]:
            # Every method the service does not implement (anything but the two handlers below) is answered 405.
            if name.startswith("do_"):
                return self._method_not_allowed
            raise AttributeError(name)

        # ---- the two verbs --------------------------------------------------------------------------------------
        def do_GET(self) -> None:  # noqa: N802
            if not self._host_allowed():
                return self._error(403, "forbidden_host")
            path, _, query = self.path.partition("?")
            if path == PATH_HEALTH and not query:
                return self._send(200, service.health())
            if path == PATH_METRICS:
                return self._error(400, "invalid_request") if query else self._send_metrics()
            if path == PATH_INVESTIGATIONS:
                return self._list(query)
            if path.startswith(RUN_PREFIX):
                run_id = path[len(RUN_PREFIX) :]
                record = service.store.get(run_id) if RUN_ID_PATTERN.fullmatch(run_id) else None
                if record is None or query:
                    return self._error(404, "not_found")
                return self._send(200, record)
            return self._error(404, "not_found")

        def _list(self, query: str) -> None:
            # An issue id has no characters that need percent-decoding, so the query is read as it comes.
            name, equals, value = query.partition("=")
            if name != "issueId" or not equals or not service.issue_id_re.fullmatch(value):
                return self._error(400, "invalid_request")
            self._send(200, {"investigations": service.store.for_issue(value)})

        def do_POST(self) -> None:  # noqa: N802
            length_text = self.headers.get("Content-Length", "")
            length = int(length_text) if length_text.isascii() and length_text.isdigit() else None

            unread = length

            def reject(status: int, code: str) -> None:
                self._drain(unread)
                self._error(status, code)

            if not self._host_allowed():
                return reject(403, "forbidden_host")
            if self.path != PATH_INVESTIGATIONS:
                return reject(404, "not_found")
            media_type = self.headers.get("Content-Type", "").partition(";")[0].strip().lower()
            if media_type != "application/json":
                return reject(415, "unsupported_media_type")
            if length is None:
                return reject(400, "invalid_request")
            if length > MAX_BODY_BYTES:
                return reject(413, "payload_too_large")
            body = self.rfile.read(length)
            unread = 0
            issue_id = self._issue_id_from(body)
            if issue_id is None:
                return reject(400, "invalid_request")
            if not service.busy.acquire(blocking=False):
                return reject(409, "busy")
            started = service.monotonic()
            try:
                record = service.runner.run(issue_id)
                service.store.append(record)
                service.metrics.observe_investigation(
                    record["status"], service.monotonic() - started, len(record["toolCalls"])
                )
            except Exception as exc:  # noqa: BLE001 - the original text stays out of the response and the store
                print(f"service: {type(exc).__name__} while handling an investigation", file=stderr)
                service.metrics.observe_investigation("error", service.monotonic() - started, 0)
                return reject(500, "internal_error")
            finally:
                service.busy.release()
            self._send(201, record)

        def _drain(self, length: int | None) -> None:
            """Read and drop an unread request body (up to a cap) so closing the connection cannot reset it."""
            remaining = min(length or 0, MAX_DRAIN_BYTES)
            while remaining > 0:
                chunk = self.rfile.read(min(remaining, 8192))
                if not chunk:
                    break
                remaining -= len(chunk)

        def _issue_id_from(self, raw: bytes) -> str | None:
            try:
                body = json.loads(raw.decode("utf-8"))
            except ValueError:
                return None
            if not isinstance(body, dict) or set(body) != {"issueId"}:
                return None
            issue_id = body["issueId"]
            if not isinstance(issue_id, str) or not service.issue_id_re.fullmatch(issue_id):
                return None
            return issue_id

    return Handler


def make_server(
    host: str, port: int, service: InvestigationService, stderr: TextIO
) -> http.server.ThreadingHTTPServer:
    server_class = http.server.ThreadingHTTPServer
    if ":" in host:
        # IPv6 needs the address family the standard library itself picks for `python -m http.server`.
        pick_family = getattr(http.server, "_get_best_family", None)
        if pick_family is None:
            raise ValueError("this Python cannot listen on an IPv6 address; use 127.0.0.1")
        family, _ = pick_family(host, port)
        server_class = type("Ipv6Server", (http.server.ThreadingHTTPServer,), {"address_family": family})
    server = server_class((host, port), make_handler(service, stderr))
    server.daemon_threads = True
    return server
