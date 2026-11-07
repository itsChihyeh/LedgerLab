"""HTTP transport: GET only, no redirects, plain http(s) base URL without credentials."""

from __future__ import annotations

import urllib.error
import urllib.parse
import urllib.request
from dataclasses import dataclass
from typing import Protocol

# Never read more than this from a response; the tool layer truncates much earlier.
MAX_READ_BYTES = 64 * 1024


@dataclass(frozen=True)
class TransportResponse:
    status: int
    body: bytes | None = None
    error: str | None = None


class Transport(Protocol):
    def get(self, path: str, query: dict[str, str]) -> TransportResponse: ...


class _NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):  # type: ignore[no-untyped-def]
        return None


def validate_base_url(base_url: str) -> str:
    if not isinstance(base_url, str) or not base_url.startswith(("http://", "https://")):
        raise ValueError("base_url must start with http:// or https://")
    if any(ch.isspace() or ord(ch) < 0x20 for ch in base_url):
        raise ValueError("base_url must not contain whitespace or control characters")
    parts = urllib.parse.urlsplit(base_url)
    if not parts.hostname:
        raise ValueError("base_url needs a host")
    if "@" in parts.netloc or parts.username is not None or parts.password is not None:
        raise ValueError("base_url must not contain user info")
    if parts.query or parts.fragment:
        raise ValueError("base_url must not contain a query or fragment")
    return base_url.rstrip("/")


class HttpTransport:
    """Issues GET requests against a fixed base URL."""

    def __init__(self, base_url: str, timeout: float = 10) -> None:
        self._base_url = validate_base_url(base_url)
        self._timeout = timeout
        self._opener = urllib.request.build_opener(_NoRedirect)

    def get(self, path: str, query: dict[str, str]) -> TransportResponse:
        if not path.startswith("/") or path.startswith("//"):
            return TransportResponse(0, None, "invalid_path")
        url = self._base_url + path
        if query:
            url += "?" + urllib.parse.urlencode(query)
        request = urllib.request.Request(url, method="GET", headers={"Accept": "application/json"})
        try:
            with self._opener.open(request, timeout=self._timeout) as response:
                return TransportResponse(response.status, response.read(MAX_READ_BYTES + 1))
        except urllib.error.HTTPError as err:
            if 300 <= err.code < 400:
                return TransportResponse(err.code, None, "redirect_not_followed")
            try:
                return TransportResponse(err.code, err.read(MAX_READ_BYTES + 1))
            finally:
                err.close()
        except (urllib.error.URLError, TimeoutError, OSError) as err:
            return TransportResponse(0, None, f"transport_error: {type(err).__name__}")
