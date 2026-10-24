# LedgerLab — Payment Settlement & Reconciliation Sandbox

A reproducible sandbox for recording simulated merchant payments, tracking settlement and refunds on an append-only double-entry ledger, and investigating reconciliation discrepancies with an evaluated, read-only AI agent.

> **Synthetic data only.** All merchants, payments, events, and settlement statements in this project are synthetic or come from Stripe test mode. LedgerLab is a sandbox: it does not handle real money, real customers, or real payment credentials.

**Status:** P1 done in code (ledger core, signed Stripe webhook intake with an inbox and fenced workers, payment capture, a fake Stripe and chaos relay, model-based randomized tests with empty-database rebuild, and a read-only payment detail page). Acceptance checklist and merge order: [docs/handoff/p1.md](docs/handoff/p1.md). Next: P2 (settlement, bank, reconciliation).

Run it (JDK 25 and a running Docker are the only prerequisites):

```sh
./mvnw verify            # build, every test on a real PostgreSQL (Testcontainers), all checks
./mvnw -f simulator/pom.xml verify   # the fake Stripe and chaos relay
cd frontend && npm ci && npm run check && npm run test:e2e   # page, unit and Playwright tests
cp .env.example .env     # fill in local passwords, then:
docker compose up -d     # local PostgreSQL with the three roles
```

## 三进程本地演示

Prerequisites: JDK 25, Docker with Compose v2 (`--wait` support), Python 3.9+
(standard library only), and a POSIX shell (Git Bash on Windows).
Run from the repository root:

```sh
sh scripts/smoke-p2.sh
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

Responses, the bank CSV and service logs are saved under `target/smoke-p2/`.
On success, failure or interruption, the script runs `docker compose down -v`
for its own project. CI runs the same script and retains that evidence for seven
days. This is a cross-process smoke test; the two Maven `verify` suites still run
separately.

Where to start:

- [Money model and worked examples](docs/design/money-model.md)
- [Acceptance criteria](docs/acceptance-criteria.md) and [phases](docs/design/phases.md)
- [Architecture decision records](docs/adr/)
- [Rules for coding agents](AGENTS.md)

[docs/brief.md](docs/brief.md) is the original project brief, kept as a historical snapshot; the design documents above supersede it.
