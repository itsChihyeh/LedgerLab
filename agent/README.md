# LedgerLab investigation agent

Read-only agent that investigates one reconciliation issue: it calls a fixed set of GET-only tools against the main application's investigation API, then submits a structured, validated conclusion (root cause, citations, recommended action) or abstains. Design: [`docs/design/investigation-agent.md`](../docs/design/investigation-agent.md), decision: ADR-0019, acceptance: AC-10.

Standard library only (Python 3.12+); no packages to install and no network access beyond the `--base-url` you give it. All data is synthetic.

## Run the tests

From the repository root:

```sh
python -m unittest discover -s agent/tests -t agent
```

## Investigate an issue

```sh
python agent/ledgerlab-agent.py investigate <issue_id> (--base-url http://127.0.0.1:8080 | --snapshot agent/eval/data/dev/snapshot.json) --model baseline|stub|scripted:<script.json> [--max-tool-calls 12] [--json]
# or, with uv:
uv run --project agent python agent/ledgerlab-agent.py investigate ...
```

Exactly one data source: `--base-url` reads a live backend, `--snapshot` replays a recorded snapshot (no backend needed). `--model` accepts:

- `baseline`: the rule baseline (`baseline.py`), the SOP's distinguishing features as plain rules over the same read-only tools.
- `stub`: a local stand-in for a language model (`stub_model.py`). It runs through the real Agent loop and validator and reads only the conversation it is given; its answers equal the baseline's. It is not a measure of any language model's ability.
- `scripted:<path.json>`: a JSON list of canned replies (`{"content": [{"type": "tool_use", "id": ..., "name": ..., "input": {...}}], "usage": {...}}`).

`search_sop` searches `agent/sop/reconciliation-sop.md` (BM25). No external model API is called.

Exit code: `0` conclusion accepted, `3` the run ended as `invalid_output` or `budget_exhausted` (an abstention is printed), `2` bad arguments.

## Evaluate

```sh
sh scripts/agent/eval.sh            # baseline and stand-in on dev, test and hard (replay, no Docker), writes docs/eval/agent-<date>.md/.json
sh scripts/agent/eval.sh --check    # writes nothing; exit 1 when a hard line fails (CI runs this)
python agent/ledgerlab-agent.py eval --split dev|test|hard --model baseline|stub [--out run.json]
```

Evaluating the test or the hard split appends a line to `agent/eval/data/test/runs.log` or `agent/eval/data/hard/runs.log` unless this model configuration has already been run with the same code (source + SOP fingerprint). Tune rules and prompts on dev only; hard is a held-out set that is only reported, never tuned against.

## Layout

| Path | Role |
|---|---|
| `src/ledgerlab_agent/routes.json` | The only source of tool routes: every entry is a GET with a fixed path template and regex-checked parameters |
| `transport.py` | GET-only HTTP, no redirects, `http(s)` base URL without credentials |
| `tools.py`, `records.py` | Tool registry: validates arguments, truncates bodies at 16 KB, extracts `{kind, id}` record identifiers |
| `model.py` | `ModelClient` interface, `ScriptedModel`, tool JSON Schemas |
| `conclusion.py`, `enums.py` | Conclusion format, validation, closed vocabularies |
| `agent.py` | The loop: budgets (12 tool calls, 8 model turns), one correction chance, `<tool_data>` wrapping |
| `prompts/system.md` | System prompt |
| `replay.py` | `ReplayTransport`: serves a recorded snapshot (404 `not_in_snapshot` for unknown requests, 503 for withheld ones) |
| `eval/` | Evaluation tooling (not part of the agent runtime): stack, scenario, probe, catalog, crawl, generate, verify |
| `verify.py`, `evaluate.py`, `eval/run_eval.py` | Citation verifier, metrics and report rendering (read-only), and the command line that writes reports and the test-set run log |
| `eval/data/{dev,test,hard}/` | Frozen evaluation sets: `snapshot.json`, `labels.json`, `manifest.json` (hard: by-product questions, new combinations and injections; report-only) |

## Investigation service (P9)

A local HTTP service that runs the agent on request and keeps the results. Design: [`docs/design/agent-workbench-and-mcp.md`](../docs/design/agent-workbench-and-mcp.md) section 2, acceptance AC-11.1 and AC-11.2. Standard library only; it never writes to the main application.

```sh
python agent/ledgerlab-agent-service.py (--base-url http://127.0.0.1:8080 | --snapshot agent/eval/data/dev/snapshot.json) --model stub|baseline|scripted:<path.json> [--port 8097] [--store agent/.runs/investigations.jsonl]
python -m unittest discover -s agent/service/tests -t agent/service
```

`--host` accepts loopback addresses only; `--port 0` picks a free port and the service prints `listening on http://127.0.0.1:<port>` on stderr. The model is fixed at start-up and is always a local stand-in; no external model API is called.

| Request | Answer |
|---|---|
| `GET /agent/health` | `200` model, `modelIsStandIn`, source |
| `POST /agent/investigations` with `{"issueId":"iss_…"}` (JSON only) | `201` investigation record; `400`, `409 busy`, `413`, `415` |
| `GET /agent/investigations?issueId=iss_…` | `200` the last 20 records, newest first |
| `GET /agent/investigations/{runId}` | `200` record, or `404` |

Records are appended to a JSONL file (`agent/.runs/` is git-ignored); the code is in `agent/service/ledgerlab_agent_service/` (`app.py` HTTP, `runner.py` one investigation, `store.py` the file). The service reaches the backend only through the agent's read-only tool layer.

## MCP service (P9)

A stdio [MCP](https://modelcontextprotocol.io) server that exposes the agent's read-only tools to any MCP client, so the client's own model can do the reasoning. Design: [`docs/design/agent-workbench-and-mcp.md`](../docs/design/agent-workbench-and-mcp.md) section 4, acceptance AC-11.7 and AC-11.8. Standard library only; it lives in its own package (`agent/mcp/ledgerlab_agent_mcp/`) so the evaluation fingerprint of the core package does not change.

```sh
python agent/ledgerlab-agent-mcp.py (--base-url http://127.0.0.1:8080 | --snapshot agent/eval/data/dev/snapshot.json) [--max-calls 500]
python -m unittest discover -s agent/mcp/tests -t agent/mcp
```

Protocol versions 2025-11-25, 2025-06-18 and 2025-03-26; tools only (no resources, prompts or sampling). The tools are the Agent's tools (`routes.json` plus `search_sop`), with the same argument schemas and the same validation, 16 KB truncation and GET-only transport; `submit_conclusion` is not offered. Each result is one `text` block plus `structuredContent`; a failed lookup sets `isError`. After `--max-calls` calls the server answers `call_limit_reached`. stdout carries protocol messages only, logs go to stderr.

Example Claude Code configuration (kept out of the repository's `.mcp.json` on purpose):

```json
{"mcpServers": {"ledgerlab-tools": {"type": "stdio", "command": "python",
  "args": ["agent/ledgerlab-agent-mcp.py", "--base-url", "http://127.0.0.1:8080"]}}}
```

## Evaluation sets

```sh
sh scripts/agent/generate-eval.sh --split dev|test|hard|all [--force]   # all = dev and test; needs Docker and both jars; replacing frozen data needs --force
sh scripts/agent/verify-eval.sh --split dev|test|hard                   # regenerate from the manifest seed, compare ignoring ids
```

The snapshot only holds what the crawl reached (depth 3 from each issue, plus bank-line searches); anything else answers 404 in replay.
