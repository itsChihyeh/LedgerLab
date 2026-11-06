"""Test doubles: a recording transport and a real local HTTP backend on a random port."""

from __future__ import annotations

import json
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from typing import Any
from urllib.parse import parse_qsl, urlsplit

from ledgerlab_agent.transport import TransportResponse


class RecordingTransport:
    """Records every request; answers from a (path -> body) table or a fixed response."""

    def __init__(self, responses: dict[str, Any] | None = None, default: TransportResponse | None = None) -> None:
        self.calls: list[tuple[str, dict[str, str]]] = []
        self._responses = responses or {}
        self._default = default or TransportResponse(404, b'{"error":"not found"}')

    def get(self, path: str, query: dict[str, str]) -> TransportResponse:
        self.calls.append((path, dict(query)))
        if path in self._responses:
            body = self._responses[path]
            raw = body if isinstance(body, bytes) else json.dumps(body).encode("utf-8")
            return TransportResponse(200, raw)
        return self._default


class FakeBackend:
    """Serves canned responses on 127.0.0.1:<random port>. routes: path -> (status, body bytes, headers)."""

    def __init__(self, routes: dict[str, tuple[int, bytes, dict[str, str]]]) -> None:
        self.requests: list[tuple[str, str, dict[str, str]]] = []
        backend = self

        class Handler(BaseHTTPRequestHandler):
            def _serve(self, method: str) -> None:
                parts = urlsplit(self.path)
                backend.requests.append((method, parts.path, dict(parse_qsl(parts.query))))
                status, body, headers = routes.get(parts.path, (404, b'{"error":"not found"}', {}))
                self.send_response(status)
                self.send_header("Content-Type", "application/json")
                self.send_header("Content-Length", str(len(body)))
                for key, value in headers.items():
                    self.send_header(key, value)
                self.end_headers()
                self.wfile.write(body)

            def do_GET(self) -> None:  # noqa: N802
                self._serve("GET")

            def do_POST(self) -> None:  # noqa: N802
                self._serve("POST")

            def log_message(self, format: str, *args: Any) -> None:  # noqa: A002
                pass

        self._server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        self._thread = threading.Thread(target=self._server.serve_forever, daemon=True)

    @property
    def base_url(self) -> str:
        return f"http://127.0.0.1:{self._server.server_address[1]}"

    def __enter__(self) -> FakeBackend:
        self._thread.start()
        return self

    def __exit__(self, *exc: object) -> None:
        self._server.shutdown()
        self._server.server_close()
        self._thread.join(timeout=5)


def json_body(value: Any) -> bytes:
    return json.dumps(value).encode("utf-8")
