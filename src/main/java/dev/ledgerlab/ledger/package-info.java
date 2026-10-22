/**
 * The ledger: the only way to record money movements (AGENTS.md, ADR-0003).
 *
 * <p>Other modules call {@link dev.ledgerlab.ledger.Ledger} inside their own transaction; nothing outside this
 * module writes the {@code ledger} schema. The database enforces the same rules again (seal triggers, CHECK
 * constraints, column grants), so a bug here or a direct SQL write still cannot break the books.
 */
@org.springframework.modulith.ApplicationModule(displayName = "Ledger")
package dev.ledgerlab.ledger;
