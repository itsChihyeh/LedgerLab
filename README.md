# LedgerLab — Payment Settlement & Reconciliation Sandbox

A reproducible sandbox for recording simulated merchant payments, tracking settlement and refunds on an append-only double-entry ledger, and investigating reconciliation discrepancies with an evaluated, read-only AI agent.

> **Synthetic data only.** All merchants, payments, events, and settlement statements in this project are synthetic or come from Stripe test mode. LedgerLab is a sandbox: it does not handle real money, real customers, or real payment credentials.

**Status:** P1a done (ledger core and database boundary): the first migration, the posting interface, and tests that connect as each database role to prove the database itself refuses unbalanced, appended, rewritten or negative entries. Next: P1b (signed webhook intake). Hand-off notes: [docs/handoff/p1a.md](docs/handoff/p1a.md).

Run it (JDK 25 and a running Docker are the only prerequisites):

```sh
./mvnw verify            # build, every test on a real PostgreSQL (Testcontainers), all checks
cp .env.example .env     # fill in local passwords, then:
docker compose up -d     # local PostgreSQL with the three roles
```

Where to start:

- [Money model and worked examples](docs/design/money-model.md)
- [Acceptance criteria](docs/acceptance-criteria.md) and [phases](docs/design/phases.md)
- [Architecture decision records](docs/adr/)
- [Rules for coding agents](AGENTS.md)

[docs/brief.md](docs/brief.md) is the original project brief, kept as a historical snapshot; the design documents above supersede it.
