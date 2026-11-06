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
python agent/ledgerlab-agent.py investigate <issue_id> --base-url http://127.0.0.1:8080 --model scripted:<script.json> [--max-tool-calls 12] [--json]
# or, with uv:
uv run --project agent python agent/ledgerlab-agent.py investigate ...
```

`--model` currently accepts `scripted:<path.json>`: a JSON list of canned replies (`{"content": [{"type": "tool_use", "id": ..., "name": ..., "input": {...}}], "usage": {...}}`). Local stand-in and rule-baseline models arrive in later tasks. No external model API is called.

Exit code: `0` conclusion accepted, `3` the run ended as `invalid_output` or `budget_exhausted` (an abstention is printed), `2` bad arguments.

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
