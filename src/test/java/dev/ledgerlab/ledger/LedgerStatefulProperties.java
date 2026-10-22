package dev.ledgerlab.ledger;

import static dev.ledgerlab.testsupport.LedgerFixture.T0;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import dev.ledgerlab.testsupport.LedgerFixture;
import dev.ledgerlab.testsupport.Sql;
import dev.ledgerlab.testsupport.TestDatabase;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.lifecycle.AfterTry;
import net.jqwik.api.state.Action;
import net.jqwik.api.state.ActionChain;
import net.jqwik.api.state.Transformer;

/**
 * Ledger-level randomized invariant test (ADR-0018), the seed of the P1c framework: random sequences of entries,
 * duplicate source keys and append attacks on a real PostgreSQL, checked after every step against an independent
 * reference model and the INV checks of {@link LedgerAudit}.
 *
 * <p>Actions so far: post a random balanced entry (2 to 5 postings, any accounts), repost a used source key,
 * append a balanced pair to a committed entry with direct SQL. Business actions (capture, duplicate and
 * out-of-order delivery, crash) join in P1c when the inbox exists.
 */
class LedgerStatefulProperties {

    private final List<TestDatabase.Handle> databases = new ArrayList<>();

    @AfterTry
    void dropDatabases() {
        databases.forEach(TestDatabase.Handle::drop);
        databases.clear();
    }

    @Property(tries = 40)
    void ledgerMatchesTheReferenceModelAndKeepsItsInvariants(@ForAll("ledgerActions") ActionChain<State> chain) {
        chain.withInvariant("balances match the reference model", State::assertMatchesModel)
                .withInvariant("INV-1..5, INV-8 hold", State::assertAuditClean)
                .run();
    }

    @Provide
    Arbitrary<ActionChain<State>> ledgerActions() {
        return ActionChain.startWith(() -> {
                    TestDatabase.Handle db = TestDatabase.freshMigrated();
                    databases.add(db);
                    return new State(new LedgerFixture(db));
                })
                .withAction(4, new PostRandomEntry())
                .withAction(1, new RepostUsedSourceKey())
                .withAction(1, new AppendToCommittedEntry())
                .withMaxTransformations(20);
    }

    /** Ledger under test plus the independent model; mutated in place by the actions. */
    static final class State {
        final LedgerFixture fixture;
        final ReferenceModel model = new ReferenceModel();
        final List<String> sourceKeys = new ArrayList<>();
        final List<String> entryIds = new ArrayList<>();
        int counter;

        State(LedgerFixture fixture) {
            this.fixture = fixture;
        }

        void assertMatchesModel() {
            Map<Integer, Long> actual = new HashMap<>();
            fixture.balances().forEach((account, balance) -> actual.put((int) account.code(), balance));
            assertThat(actual).isEqualTo(model.balances());
        }

        void assertAuditClean() {
            assertThat(fixture.audit().check()).isEmpty();
        }

        @Override
        public String toString() {
            return "model " + model.balances() + ", " + entryIds.size() + " entries";
        }
    }

    static final class PostRandomEntry implements Action.Independent<State> {
        @Override
        public Arbitrary<Transformer<State>> transformer() {
            return entries()
                    .map(lines -> Transformer.mutate("post " + describe(lines), state -> {
                        String key = "test:" + (++state.counter);
                        EntryRequest request = new EntryRequest(
                                EntryType.PAYMENT_CAPTURED,
                                key,
                                T0,
                                SubjectType.PAYMENT,
                                "pay_" + state.counter,
                                lines);
                        List<int[]> modelLines = lines.stream()
                                .map(line -> new int[] {line.account().code(), line.side() == Side.DEBIT ? 1 : 0})
                                .toList();
                        List<Long> amounts =
                                lines.stream().map(PostingLine::amount).toList();
                        boolean accepted = state.model.wouldAccept(modelLines, amounts);

                        Throwable thrown = catchThrowable(() -> {
                            PostedEntry posted = state.fixture.post(request);
                            state.entryIds.add(posted.entryId());
                        });
                        if (accepted) {
                            assertThat(thrown).as("model accepts this entry").isNull();
                            state.model.apply(modelLines, amounts);
                            state.sourceKeys.add(key);
                        } else {
                            assertThat(thrown).isInstanceOf(NegativeBalanceException.class);
                        }
                    }));
        }
    }

    static final class RepostUsedSourceKey implements Action.Dependent<State> {
        @Override
        public boolean precondition(State state) {
            return !state.sourceKeys.isEmpty();
        }

        @Override
        public Arbitrary<Transformer<State>> transformer(State state) {
            return Arbitraries.of(state.sourceKeys)
                    .map(key -> Transformer.mutate("repost " + key, s -> {
                        EntryRequest again = new EntryRequest(
                                EntryType.PAYMENT_CAPTURED,
                                key,
                                T0,
                                SubjectType.PAYMENT,
                                "pay_again",
                                List.of(
                                        PostingLine.debit(Account.PROCESSOR_RECEIVABLE, 1),
                                        PostingLine.credit(Account.MERCHANT_AVAILABLE, 1)));
                        assertThat(catchThrowable(() -> s.fixture.post(again)))
                                .isInstanceOf(DuplicateSourceKeyException.class);
                    }));
        }
    }

    static final class AppendToCommittedEntry implements Action.Dependent<State> {
        @Override
        public boolean precondition(State state) {
            return !state.entryIds.isEmpty();
        }

        @Override
        public Arbitrary<Transformer<State>> transformer(State state) {
            return Arbitraries.of(state.entryIds)
                    .map(entryId -> Transformer.mutate("append to " + entryId, s -> {
                        String suffix = "%026d".formatted(++s.counter);
                        Sql.assertRejected(
                                "LL002",
                                s.fixture.db().app(),
                                "INSERT INTO ledger.posting (id, entry_id, line_no, account_code, side, amount,"
                                        + " account_version, balance_after, recorded_at) VALUES ('pst_" + suffix
                                        + "', '"
                                        + entryId + "', 90, 1101, 'D', 1, 1, 1, now()),"
                                        + " ('pst_X" + suffix.substring(1) + "', '" + entryId
                                        + "', 91, 2101, 'C', 1, 1, 1, now())");
                    }));
        }
    }

    /** Balanced entries of 2 to 5 postings over random accounts: d debits and c credits with the same total. */
    static Arbitrary<List<PostingLine>> entries() {
        Arbitrary<Account> accounts = Arbitraries.of(Account.values());
        return Combinators.combine(
                        Arbitraries.integers().between(1, 3),
                        Arbitraries.integers().between(1, 2),
                        Arbitraries.longs().between(5, 20_000),
                        accounts.list().ofSize(5),
                        Arbitraries.longs().between(1, 1000).list().ofSize(5))
                .as((debits, credits, total, picked, weights) -> {
                    List<PostingLine> lines = new ArrayList<>();
                    List<Long> debitParts = split(total, weights.subList(0, debits));
                    List<Long> creditParts = split(total, weights.subList(debits, debits + credits));
                    for (int i = 0; i < debits; i++) {
                        lines.add(PostingLine.debit(picked.get(i), debitParts.get(i)));
                    }
                    for (int i = 0; i < credits; i++) {
                        lines.add(PostingLine.credit(picked.get(debits + i), creditParts.get(i)));
                    }
                    return lines;
                });
    }

    /** Splits total into weights.size() positive parts that add up to total (total >= number of parts). */
    static List<Long> split(long total, List<Long> weights) {
        long sumOfWeights = weights.stream().mapToLong(Long::longValue).sum();
        long spare = total - weights.size();
        List<Long> parts = new ArrayList<>();
        long used = 0;
        for (int i = 0; i < weights.size() - 1; i++) {
            long part = 1 + spare * weights.get(i) / sumOfWeights;
            parts.add(part);
            used += part;
        }
        parts.add(total - used);
        return parts;
    }

    private static String describe(List<PostingLine> lines) {
        StringBuilder text = new StringBuilder();
        for (PostingLine line : lines) {
            text.append(line.side() == Side.DEBIT ? "D" : "C")
                    .append(line.account().code())
                    .append(' ')
                    .append(line.amount())
                    .append("; ");
        }
        return text.toString();
    }

    /**
     * Independent reference model (ADR-0013, ADR-0018): its own chart of accounts and arithmetic, no business
     * code. Balances follow the normal side; a posting is accepted unless an account that may not go negative
     * would end below zero.
     */
    static final class ReferenceModel {
        // code -> {normal side is debit (1) or credit (0), may go negative (1) or not (0)}
        private static final Map<Integer, int[]> CHART = Map.of(
                1101, new int[] {1, 1},
                1102, new int[] {1, 0},
                1103, new int[] {1, 0},
                2101, new int[] {0, 0},
                2102, new int[] {0, 1},
                2103, new int[] {0, 0},
                2104, new int[] {0, 0},
                2105, new int[] {0, 0});

        private final Map<Integer, Long> balances = new HashMap<>();

        ReferenceModel() {
            CHART.keySet().forEach(code -> balances.put(code, 0L));
        }

        Map<Integer, Long> balances() {
            return Map.copyOf(balances);
        }

        /** lines: {account code, 1 for debit / 0 for credit}. */
        boolean wouldAccept(List<int[]> lines, List<Long> amounts) {
            Map<Integer, Long> after = new HashMap<>(balances);
            applyTo(after, lines, amounts);
            return after.entrySet().stream().allMatch(e -> chart(e.getKey())[1] == 1 || e.getValue() >= 0);
        }

        void apply(List<int[]> lines, List<Long> amounts) {
            applyTo(balances, lines, amounts);
        }

        private static int[] chart(int code) {
            return Objects.requireNonNull(CHART.get(code), "unknown account " + code);
        }

        private static void applyTo(Map<Integer, Long> target, List<int[]> lines, List<Long> amounts) {
            for (int i = 0; i < lines.size(); i++) {
                int code = lines.get(i)[0];
                boolean debit = lines.get(i)[1] == 1;
                boolean normalDebit = chart(code)[0] == 1;
                long amount = amounts.get(i);
                target.merge(code, debit == normalDebit ? amount : -amount, Long::sum);
            }
        }
    }
}
