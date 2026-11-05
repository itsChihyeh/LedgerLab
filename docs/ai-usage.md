# How this was built

Zhiye directed this project, and AI coding agents wrote almost all of the code, tests and documents. This page separates the two, using the repository's own records.

## Zhiye's decisions

These are the decisions the repository attributes to Zhiye.

| Decision | Why | Record |
|---|---|---|
| Replace the invented processor side with real Stripe test mode webhooks, and use Stripe's balance transactions and payouts as reconciliation data | The project had no real users or live data, and everything on the processor side was made up, which limited how far it could be trusted | [ADR-0016](adr/0016-stripe-test-mode-and-simulator-roles.md) |
| Register a Canadian Stripe account and use its default test sandbox instead of creating another one, with a restricted key kept only in a local `.env` | The account has to settle in CAD. The record gives no reason for choosing the default sandbox | [Stripe integration, section 7](design/stripe-integration.md) |
| Confirm the split of work: Claude Code plans, sets acceptance criteria and reviews; Codex implements | The record gives the split, not the reasoning | [AGENTS.md](../AGENTS.md), [P1a handoff](handoff/p1a.md) |
| Approve full access for Codex. Each task then names the files it may change, and Codex doesn't push | On Windows, the default sandbox can't run the Maven tests or write to `.git` | [AGENTS.md](../AGENTS.md) |
| Run the full `./mvnw verify` once on the local machine after Codex finishes, rather than having Codex run it too | Avoids running it twice on the critical path | [AGENTS.md](../AGENTS.md) |
| Stop P6 partway through, leaving T07 and T08 undone | Zhiye's Codex membership expired. The stop was recorded as a pause, not as completion | [P6 pause notes](https://github.com/itsChihyeh/LedgerLab/blob/p6/docs/handoff/p6-pause.md) |

Zhiye also merges the pull requests, including all of those in this repository so far.

## Scope left out

The design excludes the items below. The records don't say who made each call, so they aren't listed above as Zhiye's decisions.

- Multiple merchants or currencies, disputes, and separate authorization and capture ([money model](design/money-model.md)).
- Stripe Connect, because it would turn the model into a multi-account platform ([ADR-0016](adr/0016-stripe-test-mode-and-simulator-roles.md)).
- Partial payouts, cancelling an accepted payout, payout fees and instant payouts ([ADR-0006](adr/0006-payout-unknown-outcome.md)).
- Kafka, Redis, Kubernetes and microservices, which agents must ask about before adding ([AGENTS.md](../AGENTS.md)).

## What the AI agents did

Zhiye did not write this code by hand.

| Agent | Work |
|---|---|
| OpenAI Codex | Implemented the task-sized pieces of P1 through P6 T06: application code, migrations, tests, simulator, frontend and scripts. It ran the related tests and committed locally. It stopped on 2026-11-02 |
| Claude Code | Wrote the design documents, ADRs and acceptance criteria, split the work into tasks with test scenarios, reviewed the diffs, reran tests independently, pushed and opened pull requests. It also wrote some commits directly, and has done the remaining work since Codex stopped |

Agents also made design calls:

- Claude finalized the P4 and P5 designs under Zhiye's authorization ([ADR-0011](adr/0011-restricted-correction.md), [ADR-0006](adr/0006-payout-unknown-outcome.md)).
- The phase handoffs list smaller choices made on Zhiye's behalf that Zhiye could overturn, for example in [P5](handoff/p5.md).

Commits with a `Co-Authored-By` trailer name the agent involved. The `Zhiye` author name on those commits is the identity of the machine they were made on, not a claim that a person typed that code.

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
