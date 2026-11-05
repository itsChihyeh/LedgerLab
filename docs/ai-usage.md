# How AI was used

Almost all of the code, tests and documents in this repository were written by AI coding agents. This page says who did what, based on the repository's own records.

## What the agents did

| Agent | Work |
|---|---|
| OpenAI Codex | Implemented the task-sized pieces of P1 through P6 T06: application code, migrations, tests, simulator, frontend and scripts. It ran the related tests and committed locally. It stopped on 2026-11-03 |
| Claude Code | Wrote the design documents, ADRs and acceptance criteria, split the work into tasks with test scenarios, reviewed the diffs, reran tests independently, pushed and opened pull requests. It also wrote some commits directly, and has done the remaining work since Codex stopped |

The division of work is written down in [AGENTS.md](../AGENTS.md). Commits with a `Co-Authored-By` trailer name the agent involved. The `Zhiye` author name on those commits is the identity of the machine they were made on, not a claim that a person typed that code.

## What Zhiye did

- Chose the project, its goal and its scope, including using Stripe test mode and what not to build.
- Made the calls the agents were not allowed to make, such as how the agents work together and when to pause.
- Registered the Stripe test account and holds its keys.
- Decides what merges to `main`.

## Mistakes that were caught

These are real, and each one has a record in the repository or its task branches.

| What went wrong | How it was caught | Fix |
|---|---|---|
| A bank query returning `RETURNED` was treated as unknown, so a finished payout command would retry until it hit the limit | Claude's review of Codex's T04 | Task T04b, commit `05de0ce`, with a regression test |
| `core.payment.received_at` recorded processing time instead of when the evidence arrived | Review of the P1 implementation ([handoff](handoff/p1.md)) | Changed to the inbox `received_at` |
| The ledger's tests left real gaps: a leaked funds-lock binding went undetected and four audit invariants were never shown to fail | First PIT mutation run scored 78% against an 80% threshold ([handoff](handoff/p1a.md)) | Added tests. It then killed 60 of 65 mutants; the survivors are documented |
| The first AC-8.5 sandbox run failed because the acceptance script compared the wrong identifiers | Running it against real Stripe test events | Corrected the comparison without weakening the checks, then reran it (branch `p6`) |

## Limits

- Passing tests show the behaviour they check, not that a person has read every line.
- Agent-written tests can share an agent's misunderstanding. That is why the reference model, the simulator and the hand-calculated samples are kept separate from the business code ([ADR-0013](adr/0013-modular-monolith-and-simulators.md)).
