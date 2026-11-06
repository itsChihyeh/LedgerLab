# LedgerLab — Payment Settlement & Reconciliation Sandbox

A reproducible sandbox for recording simulated merchant payments, tracking settlement and refunds on an append-only double-entry ledger, and investigating reconciliation discrepancies with an evaluated, read-only AI agent.

> **Synthetic data only.** All merchants, payments, events, and settlement statements in this project are synthetic or come from Stripe test mode. LedgerLab is a sandbox: it does not handle real money, real customers, or real payment credentials.

**Status:** P1–P5 done (ledger core and signed webhook intake, settlement and bank reconciliation, refunds, restricted corrections, payouts); P6 (real Stripe test mode) finished and awaiting review. Acceptance checklists: [docs/handoff/](docs/handoff/), P6 in [docs/handoff/p6.md](docs/handoff/p6.md).

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
```

## 真实 Stripe 测试模式

除了 fake Stripe，同一套适配器也接真实的 Stripe 测试模式（只用测试密钥，`livemode=true` 一律拒绝）。它是一套本机长期运行的栈，不进 CI；CI 只跑录制并脱敏的真实夹具（`src/test/resources/contracts/stripe-sandbox/`），不联网、不需要密钥。

前提：Stripe CLI 已 `stripe login`，测试用受限密钥放在本地 `.env`（不入库，由 `scripts/sandbox/env.sh` 加载）。

```sh
sh scripts/sandbox/up.sh                      # 起栈并监听真实 webhook（经 chaos relay 进应用）
sh scripts/sandbox/traffic.sh --count 20 --card pm_card_bypassPending --seed 1
sh scripts/sandbox/catchup.sh                 # 漏收补投、银行同步与导入
sh scripts/sandbox/accept.sh --only ac-8.4    # 也可 ac-8.5 / ac-8.6 / ac-8.8 / ac-8.9
sh scripts/sandbox/check-payout.sh            # 自动 payout 走完才退出 0，没走完 75
sh scripts/sandbox/record-fixtures.sh --scenario all   # 重录并脱敏夹具
sh scripts/sandbox/report.sh                  # 生成 docs/runs/ 下的运行报告
```

最新运行报告：[docs/runs/sandbox-sbx_20261102T224453022706Z.md](docs/runs/sandbox-sbx_20261102T224453022706Z.md)（同名 `.json` 是机器可读版）。对外提到的支付、退款、payout、重复／乱序／丢弃次数和不变量结果，一律只引用这份报告，不另算。验收清单与已知限制见 [docs/handoff/p6.md](docs/handoff/p6.md)。

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

Where to start:

- [Money model and worked examples](docs/design/money-model.md)
- [Acceptance criteria](docs/acceptance-criteria.md) and [phases](docs/design/phases.md)
- [Architecture decision records](docs/adr/)
- [Rules for coding agents](AGENTS.md)

