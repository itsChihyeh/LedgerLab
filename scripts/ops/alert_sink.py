"""Local receiver for Alertmanager webhooks (docs/design/alerting-and-tracing.md section 2.2).

Standard library only. Accepts `POST /alerts` with an Alertmanager v4 payload and appends one JSON line per
notification to a file. Every error body is `{"error": "<code>"}`; an error never writes to the file.
"""

from __future__ import annotations

import argparse
import http.server
import json
import sys
from collections.abc import Callable, Sequence
from datetime import UTC, datetime
from typing import Any, TextIO

MAX_BODY_BYTES = 65536
PATH_ALERTS = "/alerts"
DEFAULT_HOST = "127.0.0.1"
DEFAULT_PORT = 9095
STATUSES = frozenset({"firing", "resolved"})
KEPT_ALERT_FIELDS = ("status", "labels", "annotations", "startsAt", "endsAt", "fingerprint")


class InvalidPayload(ValueError):
    """The body is not a usable Alertmanager webhook payload."""


def _is_text_map(value: Any) -> bool:
    return isinstance(value, dict) and all(isinstance(k, str) and isinstance(v, str) for k, v in value.items())


def parse_payload(raw: bytes) -> dict[str, Any]:
    """Validate the Alertmanager payload and return the fields worth keeping (no `generatorURL`)."""
    try:
        body = json.loads(raw.decode("utf-8"))
    except ValueError as exc:  # UnicodeDecodeError and JSONDecodeError are both ValueErrors
        raise InvalidPayload("not json") from exc
    if not isinstance(body, dict):
        raise InvalidPayload("not an object")
    if body.get("version") != "4":
        raise InvalidPayload("unsupported version")
    if body.get("status") not in STATUSES:
        raise InvalidPayload("bad status")
    for name in ("groupKey", "receiver"):
        if not isinstance(body.get(name), str):
            raise InvalidPayload(f"missing {name}")
    alerts = body.get("alerts")
    if not isinstance(alerts, list) or not alerts:
        raise InvalidPayload("no alerts")
    kept: list[dict[str, Any]] = []
    for alert in alerts:
        if not isinstance(alert, dict) or alert.get("status") not in STATUSES:
            raise InvalidPayload("bad alert status")
        labels = alert.get("labels")
        if not _is_text_map(labels) or not isinstance(labels.get("alertname"), str):
            raise InvalidPayload("alert without alertname")
        if "annotations" in alert and not _is_text_map(alert["annotations"]):
            raise InvalidPayload("bad annotations")
        kept.append({name: alert[name] for name in KEPT_ALERT_FIELDS if name in alert})
    return {
        "status": body["status"],
        "groupKey": body["groupKey"],
        "receiver": body["receiver"],
        "alerts": kept,
    }


def format_received_at(moment: datetime) -> str:
    return moment.astimezone(UTC).isoformat(timespec="milliseconds").replace("+00:00", "Z")


def record_line(notification: dict[str, Any], received_at: datetime) -> str:
    """One JSONL line (without the newline): sorted keys, UTF-8 text kept as is."""
    return json.dumps({"receivedAt": format_received_at(received_at), **notification}, ensure_ascii=False, sort_keys=True)


def append_line(out: TextIO, line: str) -> None:
    out.write(line + "\n")
    out.flush()


def console_lines(notification: dict[str, Any]) -> list[str]:
    return [
        f"alert {alert['status']} {alert['labels']['alertname']} {alert['labels'].get('severity', 'unknown')}"
        for alert in notification["alerts"]
    ]


def make_handler(
    out: TextIO, stdout: TextIO, clock: Callable[[], datetime], stderr: TextIO
) -> type[http.server.BaseHTTPRequestHandler]:
    class Handler(http.server.BaseHTTPRequestHandler):
        server_version = "ledgerlab-alert-sink"
        sys_version = ""

        def log_message(self, format: str, *args: Any) -> None:  # noqa: A002
            print(f"http: {self.command} {self.path.split('?')[0]} -> {args[1] if len(args) > 1 else '-'}", file=stderr)

        def _send(self, status: int, body: dict[str, Any], extra: dict[str, str] | None = None) -> None:
            raw = json.dumps(body, separators=(",", ":")).encode("utf-8")
            self.send_response(status)
            self.send_header("Content-Type", "application/json; charset=utf-8")
            self.send_header("Content-Length", str(len(raw)))
            for name, value in (extra or {}).items():
                self.send_header(name, value)
            self.end_headers()
            self.wfile.write(raw)

        def _reject(self, status: int, code: str, length: int | None, extra: dict[str, str] | None = None) -> None:
            # Read a body that was announced and is small, so closing cannot reset the client; never wait for a huge one.
            if length is not None and 0 < length <= MAX_BODY_BYTES:
                self.rfile.read(length)
            elif length is None or length > MAX_BODY_BYTES:
                self.close_connection = True
            self._send(status, {"error": code}, extra)

        def _handle(self) -> None:
            length_text = self.headers.get("Content-Length", "")
            length = int(length_text) if length_text.isascii() and length_text.isdigit() else None
            if self.path.partition("?")[0] != PATH_ALERTS:
                return self._reject(404, "not_found", length)
            if self.command != "POST":
                return self._reject(405, "method_not_allowed", length, {"Allow": "POST"})
            media_type = self.headers.get("Content-Type", "").partition(";")[0].strip().lower()
            if media_type != "application/json" or length is None:
                return self._reject(400, "invalid_request", length)
            if length > MAX_BODY_BYTES:
                return self._reject(413, "payload_too_large", length)
            raw = self.rfile.read(length)
            try:
                notification = parse_payload(raw)
            except InvalidPayload:
                return self._send(400, {"error": "invalid_request"})
            try:
                append_line(out, record_line(notification, clock()))
            except OSError:
                print("alert-sink: could not write the output file", file=stderr)
                return self._send(500, {"error": "internal_error"})
            for line in console_lines(notification):
                print(line, file=stdout, flush=True)
            self._send(200, {"ok": True})

        def __getattr__(self, name: str) -> Callable[[], None]:
            # Every verb (POST included) goes through one routine, so path is judged before method.
            if name.startswith("do_"):
                return self._handle
            raise AttributeError(name)

    return Handler


def make_server(
    host: str,
    port: int,
    out: TextIO,
    stdout: TextIO,
    clock: Callable[[], datetime],
    stderr: TextIO,
) -> http.server.HTTPServer:
    return http.server.HTTPServer((host, port), make_handler(out, stdout, clock, stderr))


def parse_args(argv: Sequence[str] | None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Append Alertmanager webhook notifications to a JSONL file.")
    parser.add_argument("--out", required=True, help="JSONL file to append to")
    parser.add_argument("--host", default=DEFAULT_HOST)
    parser.add_argument("--port", type=int, default=DEFAULT_PORT)
    return parser.parse_args(argv)


def main(argv: Sequence[str] | None = None, clock: Callable[[], datetime] | None = None) -> int:
    args = parse_args(argv)
    now = clock or (lambda: datetime.now(UTC))
    with open(args.out, "a", encoding="utf-8", newline="\n") as out:
        server = make_server(args.host, args.port, out, sys.stdout, now, sys.stderr)
        print(f"alert-sink: listening on {args.host}:{server.server_port}, writing {args.out}", file=sys.stderr, flush=True)
        try:
            server.serve_forever()
        except KeyboardInterrupt:
            pass
        finally:
            server.server_close()
    return 0
