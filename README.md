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

Where to start:

- [Money model and worked examples](docs/design/money-model.md)
- [Acceptance criteria](docs/acceptance-criteria.md) and [phases](docs/design/phases.md)
- [Architecture decision records](docs/adr/)
- [Rules for coding agents](AGENTS.md)

[docs/brief.md](docs/brief.md) is the original project brief, kept as a historical snapshot; the design documents above supersede it.
