# LedgerLab — Payment Settlement & Reconciliation Sandbox

A reproducible sandbox for recording simulated merchant payments, tracking settlement and refunds on an append-only double-entry ledger, and investigating reconciliation discrepancies with an evaluated, read-only AI agent.

> **Synthetic data only.** All merchants, payments, events, and settlement statements in this project are synthetic or come from Stripe test mode. LedgerLab is a sandbox: it does not handle real money, real customers, or real payment credentials.

**Status:** P1–P4 done (ledger core and signed webhook intake, settlement and bank reconciliation, refunds, restricted corrections); paused before P5 (payouts). Acceptance checklists: [docs/handoff/](docs/handoff/). Where work stopped and how to resume: [docs/handoff/pause-p4.md](docs/handoff/pause-p4.md).

Run it (JDK 25 and a running Docker are the only prerequisites):

```sh
./mvnw verify            # build, every test on a real PostgreSQL (Testcontainers), all checks
./mvnw -f simulator/pom.xml verify   # the fake Stripe and chaos relay
cd frontend && npm ci && npm run check && npm run test:e2e   # page, unit and Playwright tests
cp .env.example .env     # fill in local passwords, then:
docker compose up -d     # local PostgreSQL with the three roles
# Real Stripe test sandbox (Stripe CLI login + test key in .env; persistent stack):
sh scripts/sandbox/up.sh
sh scripts/sandbox/traffic.sh --count 20 --card pm_card_bypassPending --seed 1
sh scripts/sandbox/accept.sh --only ac-8.4   # repeat without generating more payments
sh scripts/sandbox/status.sh
sh scripts/sandbox/down.sh                 # optional stop; preserves volumes

# Local break-it demo (fake Stripe + simulated bank, project ledgerlab-demo, app on 127.0.0.1:28080):
sh scripts/demo/up.sh                       # build, start, seed, check all invariants
sh scripts/demo/status.sh
sh scripts/demo/reset.sh                    # archive today's summary, down -v, up, seed (the daily reset)
sh scripts/demo/down.sh                     # remove the stack and its volumes
sh scripts/demo/check-reset.sh              # up -> actions -> reset -> assertions -> down; evidence in target/demo/
# Scripted demo video (starts its own demo-profile backend; writes docs/media/break-it-demo.webm, not committed if over 20 MB):
cd frontend && npm run video:demo
# k6 performance baseline (own project ledgerlab-perf, about an hour): sh perf/run.sh
# Report: docs/perf/baseline-2026-11-05.md
```

## 三进程本地演示

Prerequisites: JDK 25, Docker with Compose v2 (`--wait` support), Python 3.9+
(standard library only), and a POSIX shell (Git Bash on Windows).
Run from the repository root:

```sh
sh scripts/smoke-p2.sh
sh scripts/smoke-p3.sh   # S3, real refund commands, duplicate webhooks and pending execution
sh scripts/smoke-p4.sh   # full P3, then corrections and bank returns; docs/testing/smoke-p4.md
sh scripts/smoke-p5.sh   # payouts: paid, lost response recovery, rejection, return and reconciliation
```

The script builds both jars and starts PostgreSQL, the simulator (fake Stripe,
bank and relay), and the app under the `smoke` profile. It uses a unique Compose
project, fresh volumes and random loopback ports. Ordinary `docker compose up`
still starts only PostgreSQL on port 5432. The script explicitly loads public
fake credentials from `compose.smoke.env`; it does not load `.env` or contact
Stripe. Gitleaks allows only the two exact fake values in that file.

It executes the `sample-a-1-3` actions through the simulator control APIs:
charge → payout → reconciliation completed → paid → advance clock → export bank
statement, then imports the statement and runs PROCESSOR/BANK/AGING reconciliation.
One charge event passes through a relay `DUPLICATE` rule (two copies). Assertions
check both delivery outcomes, announcement before settlement, zero cash before
bank import, and the final result:

```text
SETTLED / BANKED, 9 postings
inTransit=0, bankCash=9680, merchantAvailable=9680 (CAD cents)
PROCESSOR/BANK/AGING FINISHED, 0 findings, 0 issues; ledger unchanged
```

Business dates are fixed at October 19–22, 2026 (the host clock must be after
October 22); this preserves the sample's amounts and actions while allowing the
app's real injected clock to enforce reconciliation timestamps. Webhooks use the
simulator's separate real signing clock. The script waits on the app clock for
the normal 60-second evidence grace; it does not shorten that protection.
The scenario export endpoint is intentionally unused because it rolls back and
does not deliver webhooks.

`smoke-p3.sh` first runs all P2 assertions in its own fresh project, then follows
sample A steps 4–7. It creates P2 for 5000 cents and submits R1 (2000 cents against
P1) to the app, with `Idempotency-Key` and `merchant_refund_ref`:

```http
POST /api/refunds
Content-Type: application/json
Idempotency-Key: smoke-p3-r1

{"payment_ref":"ch_REPLACE_WITH_P1","merchant_refund_ref":"smoke-p3-r1","amount":2000}
```

The app's outbound worker calls the simulator's `POST /v1/refunds`. The relay
duplicates `refund.created`; the script waits for both deliveries and command
completion, checks one `REFUND_EXECUTED`, and repeats the API request to check the
identical original response. B2 then contains P2 5000, fee 175 and R1 −2000:

```text
R1 SUCCEEDED, 1 REFUND_EXECUTED, NEW + DUPLICATE deliveries
B2 CONFIRMED: merchantAvailable=12505, inTransit=2825, bankCash=9680
```

For the second refund, the script sets
`POST /sim/refunds/faults {"charge":"ch_REPLACE_WITH_P2","force_pending":true}`,
submits R2 for 3000 cents, and observes `SUBMITTED` with no execution entry.
A new 5000-cent charge supplies processor funds; clearing that fault and calling
`POST /sim/refunds/process` emits `refund.updated` and completes R2 exactly once.
This exercises processor pending execution, not the merchant FIFO waiting queue.

The script checks the step-7 balances before completing B2/B3 bank deposits and
fetching the October 22 processor snapshot. Final ALL reconciliation includes
R2's execution before the cutoff and the new external evidence, with zero findings
and issues; it also verifies reconciliation leaves the ledger unchanged. P3 uses
October 19–23 business dates and an October 24 Toronto-midnight cutoff, so the host
clock must be at or after `2026-10-24T04:00:00Z`. The normal 60-second evidence grace
is preserved. An observer-only SQL query using the simulator role discovers
refund event IDs for the HTTP delivery assertions; the app never reads simulator
tables, and the script never reads `sim_truth`.

Responses, bank CSVs and service logs are saved under `target/smoke-p2/` and
`target/smoke-p3/`. On success, failure or interruption, each script runs
`docker compose down -v` for its own project. CI runs P2 then P3 and retains both
evidence directories for seven days. These are cross-process smoke tests; the two
Maven `verify` suites still run separately.

## Investigation agent (P8)

A read-only agent that investigates one reconciliation issue: it calls a fixed set of GET-only tools against the investigation API, then returns a validated conclusion (root cause, cited records, recommended action) or abstains when the evidence is insufficient. It cannot write anything; the read-only guarantee is checked in the tool layer, in server-side route tests and by the `readonly` database role. **No external model is connected yet**: the evaluation report only covers a rule baseline and a local stand-in (the baseline run through the model interface), so its numbers say nothing about any language model. Standard-library Python, no network needed to try it:

```sh
sh scripts/agent/eval.sh --check   # replay the frozen dev/test/hard sets; exit 1 if a hard line fails
```

See [agent/README.md](agent/README.md), the [P8 handoff](docs/handoff/p8.md) and the [evaluation report](docs/eval/agent-2026-11-06.md).

### In the workbench and over MCP (P9)

The issue page of the reconciliation workbench has an investigation panel: a small local service runs the agent on request, and the panel shows its conclusion with clickable citations. A person can adopt only two kinds of conclusion (a split bank deposit, a processor fee difference); adopting just opens a pre-filled form, and nothing is written until the person submits it through the existing correction or accept endpoint. The agent itself still has no write access. The same read-only tools are also offered as a stdio MCP server for other agent clients. Still no external model: the panel is labelled as a local stand-in. See the [P9 handoff](docs/handoff/p9.md) and the [design](docs/design/agent-workbench-and-mcp.md).

Where to start:

- [Money model and worked examples](docs/design/money-model.md)
- [Acceptance criteria](docs/acceptance-criteria.md) and [phases](docs/design/phases.md)
- [Architecture decision records](docs/adr/)
- [Rules for coding agents](AGENTS.md)

