package dev.ledgerlab.reconciliation.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import dev.ledgerlab.ledger.LedgerAudit;
import dev.ledgerlab.ledger.internal.JdbcLedgerAudit;
import dev.ledgerlab.shared.IdGenerator;
import dev.ledgerlab.testsupport.LedgerFixture;
import dev.ledgerlab.testsupport.TestDatabase;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.lifecycle.AfterTry;
import net.jqwik.api.state.Action;
import net.jqwik.api.state.ActionChain;
import net.jqwik.api.state.Transformer;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import tools.jackson.databind.json.JsonMapper;

class ReconStatefulProperties {
    private final List<TestDatabase.Handle> databases = new ArrayList<>();
    private final List<SingleConnectionDataSource> connections = new ArrayList<>();

    @AfterTry
    void cleanup() {
        connections.forEach(SingleConnectionDataSource::destroy);
        connections.clear();
        databases.forEach(TestDatabase.Handle::drop);
        databases.clear();
    }

    @Property(seed = "2026102309")
    void ac_5_21_dispositionsMatchIndependentModelAndNeverChangeMoney(@ForAll("operations") ActionChain<State> chain) {
        chain.withInvariant("dispositions, history and money match", State::verify)
                .run();
    }

    @Provide
    Arbitrary<ActionChain<State>> operations() {
        return ActionChain.startWith(() -> {
                    var db = TestDatabase.freshMigrated();
                    databases.add(db);
                    var source = new SingleConnectionDataSource(db.url(), "app", TestDatabase.APP_PASSWORD, true);
                    connections.add(source);
                    return new State(db, source);
                })
                .withAction(new Operations())
                .withMaxTransformations(20);
    }

    static final class Operations implements Action.Independent<State> {
        @Override
        public Arbitrary<Transformer<State>> transformer() {
            return Arbitraries.of(ReconReference.Operation.values())
                    .map(operation -> Transformer.mutate(operation.name(), state -> state.apply(operation)));
        }
    }

    static final class State {
        final ReconReference model = new ReconReference();
        final JdbcClient jdbc;
        final IssueStore issues;
        final ReconRuns runs;
        final ReconRunsTest.FakeRule rule = new ReconRunsTest.FakeRule();
        final List<String> moneyBefore;
        final LedgerAudit audit;

        State(TestDatabase.Handle db, SingleConnectionDataSource source) {
            jdbc = JdbcClient.create(source);
            var manager = new DataSourceTransactionManager(source);
            var clock = Clock.fixed(Instant.parse("2026-11-13T14:00:00Z"), ZoneOffset.UTC);
            var ids = IdGenerator.seeded(clock, 9);
            var mapper = JsonMapper.builder().build();
            issues = new IssueStore(jdbc, ids, clock, manager);
            runs = new ReconRuns(
                    jdbc,
                    manager,
                    clock,
                    ids,
                    new ReconProperties(Duration.ofSeconds(60), "v1"),
                    List.of(rule),
                    new ReconJson(mapper),
                    mapper,
                    issues);
            new LedgerFixture(db).s1();
            audit = new JdbcLedgerAudit(source, manager);
            moneyBefore = money();
        }

        void apply(ReconReference.Operation operation) {
            boolean allowed = model.apply(operation);
            if (operation == ReconReference.Operation.ACCEPT || operation == ReconReference.Operation.RESOLVE) {
                String id = jdbc.sql("SELECT id FROM recon.issue")
                        .query(String.class)
                        .optional()
                        .orElse("missing");
                Throwable failure = catchThrowable(() -> {
                    if (operation == ReconReference.Operation.ACCEPT) issues.accept(id, "accepted");
                    else issues.resolve(id, "resolved");
                });
                if (allowed) assertThat(failure).isNull();
                else assertThat(failure).isInstanceOfAny(IllegalArgumentException.class, IllegalStateException.class);
                return;
            }
            long amount = operation == ReconReference.Operation.FIND_206 ? 206 : 205;
            var finding = new Finding("FEE_MISMATCH", "payment", "p2", "175", Long.toString(amount), "[]", null);
            rule.findings =
                    operation == ReconReference.Operation.ABSENT || operation == ReconReference.Operation.OUT_OF_SCOPE
                            ? List.of()
                            : List.of(finding);
            rule.scope = operation == ReconReference.Operation.OUT_OF_SCOPE
                    ? Map.of()
                    : Map.of(finding.key(), new ReconRule.Explanation("matched", "[\"later\"]"));
            runs.run(new RunRequest(
                    RunRequest.Kind.PROCESSOR,
                    Instant.parse("2026-11-13T05:00:00Z"),
                    Instant.parse("2026-11-13T13:00:00Z"),
                    "v1",
                    List.of(),
                    operation == ReconReference.Operation.DRY_RUN));
        }

        List<String> money() {
            return jdbc.sql("""
                    SELECT 'account:' || to_jsonb(a)::text FROM ledger.account a
                    UNION ALL SELECT 'posting:' || to_jsonb(p)::text FROM ledger.posting p
                    ORDER BY 1
                    """)
                    .query((rs, row) -> java.util.Objects.requireNonNull(rs.getString(1)))
                    .list();
        }

        void verify() {
            assertThat(jdbc.sql("SELECT status FROM recon.issue")
                            .query(String.class)
                            .optional()
                            .orElse("NONE"))
                    .isEqualTo(model.state);
            assertThat(jdbc.sql("SELECT coalesce(max(reopen_count),0) FROM recon.issue")
                            .query(Integer.class)
                            .single())
                    .isEqualTo(model.reopens);
            assertThat(jdbc.sql("SELECT count(*) FROM recon.run")
                            .query(Long.class)
                            .single())
                    .isEqualTo(model.runs);
            assertThat(jdbc.sql("SELECT count(*) FROM recon.finding")
                            .query(Long.class)
                            .single())
                    .isEqualTo(model.findings);
            assertThat(jdbc.sql("SELECT count(*) FROM recon.issue_transition")
                            .query(Long.class)
                            .single())
                    .isEqualTo(model.transitions);
            assertThat(money()).isEqualTo(moneyBefore);
            assertThat(audit.check()).isEmpty();
        }
    }
}
