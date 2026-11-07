"""Read-only tool layer: validates arguments against routes.json and issues fixed-template GETs."""

from __future__ import annotations

import json
import re
import urllib.parse
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Protocol

from ledgerlab_agent.records import Record, extract_records
from ledgerlab_agent.transport import Transport

MAX_BODY_BYTES = 16 * 1024
ROUTES_PATH = Path(__file__).resolve().parent / "routes.json"
SEARCH_SOP_MAX_QUERY_CHARS = 200


@dataclass(frozen=True)
class ParamSpec:
    name: str
    where: str  # "path" | "query"
    pattern: str
    required: bool
    type: str = "string"  # JSON Schema type shown to the model


@dataclass(frozen=True)
class ToolSpec:
    name: str
    description: str
    params: tuple[ParamSpec, ...]
    path_template: str = ""


@dataclass(frozen=True)
class ToolResult:
    tool: str
    args: dict[str, Any]
    ok: bool
    status: int | None = None
    data: Any = None
    as_of: str | None = None
    records: tuple[Record, ...] = ()
    truncated: bool = False
    error: str | None = None


class LocalTool(Protocol):
    """A tool answered in-process (no HTTP). Implemented for search_sop in a later task."""

    spec: ToolSpec

    def run(self, args: dict[str, Any]) -> tuple[Any, list[Record]]: ...


class EmptySopSearch:
    """Placeholder until the SOP index exists."""

    spec = ToolSpec(
        name="search_sop",
        description="Search the reconciliation operating manual (SOP) for clauses relevant to a query.",
        params=(
            ParamSpec(
                name="query",
                where="query",
                pattern=rf"^[^\x00-\x1f]{{1,{SEARCH_SOP_MAX_QUERY_CHARS}}}$",
                required=True,
            ),
        ),
    )

    def run(self, args: dict[str, Any]) -> tuple[Any, list[Record]]:
        return {"results": []}, []


def load_specs(path: Path = ROUTES_PATH) -> dict[str, ToolSpec]:
    specs: dict[str, ToolSpec] = {}
    for route in json.loads(path.read_text(encoding="utf-8")):
        if route["method"] != "GET":
            raise ValueError(f"route {route['tool']} is not GET")
        params = tuple(
            ParamSpec(
                name=name,
                where=p["in"],
                pattern=p["pattern"],
                required=bool(p["required"]),
                type=p.get("type", "string"),
            )
            for name, p in route["params"].items()
        )
        specs[route["tool"]] = ToolSpec(
            name=route["tool"],
            description=route.get("description", ""),
            params=params,
            path_template=route["path_template"],
        )
    return specs


class ToolRegistry:
    def __init__(
        self,
        transport: Transport,
        specs: dict[str, ToolSpec] | None = None,
        local_tools: list[LocalTool] | None = None,
    ) -> None:
        self._transport = transport
        self._specs = dict(specs if specs is not None else load_specs())
        self._local: dict[str, LocalTool] = {}
        for local in local_tools if local_tools is not None else [EmptySopSearch()]:
            self._local[local.spec.name] = local

    @property
    def specs(self) -> list[ToolSpec]:
        return [*self._specs.values(), *(t.spec for t in self._local.values())]

    def call(self, name: str, args: object) -> ToolResult:
        spec = self._specs.get(name)
        local = self._local.get(name)
        if spec is None and local is None:
            return _invalid(name, args, f"unknown tool {name!r}")
        spec = spec or local.spec  # type: ignore[union-attr]
        checked = _check_args(spec, args)
        if isinstance(checked, str):
            return _invalid(name, args, checked)
        if local is not None:
            data, records = local.run(dict(args))  # type: ignore[arg-type]
            return ToolResult(name, dict(args), True, None, data, None, tuple(records))  # type: ignore[arg-type]
        return self._fetch(spec, dict(args), checked)  # type: ignore[arg-type]

    def _fetch(self, spec: ToolSpec, args: dict[str, Any], values: dict[str, str]) -> ToolResult:
        path = spec.path_template
        query: dict[str, str] = {}
        for param in spec.params:
            if param.name not in values:
                continue
            if param.where == "path":
                path = path.replace("{" + param.name + "}", urllib.parse.quote(values[param.name], safe=""))
            else:
                query[param.name] = values[param.name]
        response = self._transport.get(path, query)
        if response.body is None:
            return ToolResult(spec.name, args, False, response.status, error=response.error or "no_response")
        truncated = len(response.body) > MAX_BODY_BYTES
        text = response.body[:MAX_BODY_BYTES].decode("utf-8", errors="replace")
        data: Any = text
        as_of: str | None = None
        records: list[Record] = []
        if not truncated:
            try:
                data = json.loads(text)
            except ValueError:
                data = text
            else:
                as_of = _as_of(data)
                if 200 <= response.status < 300:
                    records = extract_records(spec.name, data)
        ok = 200 <= response.status < 300
        return ToolResult(
            spec.name,
            args,
            ok,
            response.status,
            data,
            as_of,
            tuple(records),
            truncated,
            None if ok else f"http_{response.status}",
        )


def _invalid(name: str, args: object, detail: str) -> ToolResult:
    shown = dict(args) if isinstance(args, dict) else {}
    return ToolResult(name, shown, False, None, {"detail": detail}, error="invalid_arguments")


def _check_args(spec: ToolSpec, args: object) -> dict[str, str] | str:
    if not isinstance(args, dict):
        return "arguments must be an object"
    known = {p.name: p for p in spec.params}
    extra = sorted(str(k) for k in args if k not in known)
    if extra:
        return f"unknown arguments: {', '.join(extra)}"
    values: dict[str, str] = {}
    for param in spec.params:
        if param.name not in args:
            if param.required:
                return f"missing required argument {param.name!r}"
            continue
        raw = args[param.name]
        if isinstance(raw, bool) or not isinstance(raw, (str, int)):
            return f"argument {param.name!r} must be a string or integer"
        text = str(raw)
        if re.fullmatch(param.pattern, text) is None:
            return f"argument {param.name!r} does not match the allowed format"
        values[param.name] = text
    return values


def _as_of(data: object) -> str | None:
    if isinstance(data, dict):
        as_of = data.get("asOf")
        if isinstance(as_of, dict) and isinstance(as_of.get("at"), str):
            return as_of["at"]
    return None


__all__ = [
    "EmptySopSearch",
    "LocalTool",
    "ParamSpec",
    "ToolRegistry",
    "ToolResult",
    "ToolSpec",
    "load_specs",
]
