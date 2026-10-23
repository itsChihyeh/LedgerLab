package dev.ledgerlab.reconciliation.internal;

import dev.ledgerlab.shared.IdGenerator;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

@Service
@EnableConfigurationProperties(ReconProperties.class)
public final class ReconRuns {
    private final JdbcClient jdbc;
    private final PlatformTransactionManager manager;
    private final Clock clock;
    private final IdGenerator ids;
    private final ReconProperties properties;
    private final List<ReconRule> rules;
    private final ReconJson json;
    private final ObjectMapper mapper;
    private final IssueStore issues;

    public ReconRuns(
            JdbcClient jdbc,
            PlatformTransactionManager manager,
            Clock clock,
            IdGenerator ids,
            ReconProperties properties,
            List<ReconRule> rules,
            ReconJson json,
            ObjectMapper mapper,
            IssueStore issues) {
        this.jdbc = jdbc;
        this.manager = manager;
        this.clock = clock;
        this.ids = ids;
        this.properties = properties;
        this.rules = List.copyOf(rules);
        this.json = json;
        this.mapper = mapper;
        this.issues = issues;
    }

    public RunReport run(RunRequest request) {
        Instant started = clock.instant();
        if (!request.cutoffAt()
                        .atZone(ZoneId.of("America/Toronto"))
                        .toLocalTime()
                        .equals(LocalTime.MIDNIGHT)
                || request.cutoffAt().isAfter(request.evidenceAsOf())
                || request.evidenceAsOf().isAfter(started.minus(properties.evidenceGrace()))) {
            throw new IllegalArgumentException(
                    "Require Toronto midnight cutoff <= evidenceAsOf <= now - evidenceGrace");
        }
        var tx = new TransactionTemplate(manager);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        tx.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        tx.setReadOnly(request.dryRun());
        return Objects.requireNonNull(tx.execute(status -> execute(request, started)));
    }

    private RunReport execute(RunRequest request, Instant started) {
        // PostgreSQL also enforces the dry-run boundary against accidental writes inside a rule.
        if (request.dryRun()) jdbc.sql("SET TRANSACTION READ ONLY").update();
        Map<String, String> inputs = inputs(request);
        var context = new ReconRule.Context(request, inputs);
        List<RunReport.ObservedFinding> findings = new ArrayList<>();
        Map<Finding.Key, ReconRule.Explanation> scope = new LinkedHashMap<>();
        var keys = new HashSet<Finding.Key>();
        for (ReconRule rule : rules) {
            if (rule.kind() != request.kind()) continue;
            if (!rule.version().equals(request.ruleVersion()))
                throw new IllegalArgumentException("Unsupported rule version");
            List<Finding> evaluated = rule.evaluate(context);
            var checked = rule.checkedScope(context);
            checked.forEach((key, explanation) -> {
                json.array(explanation.evidenceRefs());
                if (scope.putIfAbsent(key, explanation) != null)
                    throw new IllegalArgumentException("Overlapping rule scope");
            });
            for (Finding finding : evaluated) {
                if (!checked.containsKey(finding.key()) || !keys.add(finding.key())) {
                    throw new IllegalArgumentException("Finding must have a unique checked key");
                }
                Finding normalized = new Finding(
                        finding.category(),
                        finding.subjectType(),
                        finding.subjectId(),
                        json.canonical(finding.expected()),
                        json.canonical(finding.actual()),
                        json.array(finding.evidenceRefs()),
                        finding.dueAt());
                findings.add(new RunReport.ObservedFinding(normalized, json.fingerprint(normalized)));
            }
        }
        findings.sort(Comparator.comparing(
                        (RunReport.ObservedFinding f) -> f.finding().category())
                .thenComparing(f -> f.finding().subjectType())
                .thenComparing(f -> f.finding().subjectId()));
        Map<String, Long> summary = new TreeMap<>();
        findings.forEach(f -> summary.merge(f.finding().category(), 1L, Math::addExact));
        if (request.dryRun()) return report(null, request, inputs, summary, findings);
        String runId = ids.next("rr");
        OffsetDateTime now = started.atOffset(ZoneOffset.UTC);
        jdbc.sql("""
                INSERT INTO recon.run (id, kind, status, input_file_ids, input_hashes, rule_version, cutoff_at,
                    evidence_as_of, started_at, summary, recorded_at)
                VALUES (?, ?, 'RUNNING', CAST(? AS jsonb), CAST(? AS jsonb), ?, ?, ?, ?, '{}'::jsonb, ?)
                """)
                .params(
                        runId,
                        request.kind().name(),
                        json.write(inputs.keySet()),
                        json.write(inputs),
                        request.ruleVersion(),
                        request.cutoffAt().atOffset(ZoneOffset.UTC),
                        request.evidenceAsOf().atOffset(ZoneOffset.UTC),
                        now,
                        now)
                .update();
        for (var observed : findings) {
            Finding f = observed.finding();
            String issueId = issues.found(runId, request.kind(), f, observed.fingerprint(), now);
            jdbc.sql("""
                    INSERT INTO recon.finding (id, run_id, category, subject_type, subject_id, expected, actual,
                        evidence_refs, fingerprint, due_at, issue_id, recorded_at)
                    VALUES (:id, :run, :category, :type, :subject, CAST(:expected AS jsonb), CAST(:actual AS jsonb),
                        CAST(:evidence AS jsonb), :fingerprint, :due, :issue, :now)
                    """)
                    .param("id", ids.next("fnd"))
                    .param("run", runId)
                    .param("category", f.category())
                    .param("type", f.subjectType())
                    .param("subject", f.subjectId())
                    .param("expected", f.expected())
                    .param("actual", f.actual())
                    .param("evidence", f.evidenceRefs())
                    .param("fingerprint", observed.fingerprint())
                    .param("due", f.dueAt() == null ? null : f.dueAt().atOffset(ZoneOffset.UTC))
                    .param("issue", issueId)
                    .param("now", now)
                    .update();
        }
        scope.forEach((key, explanation) -> {
            if (!keys.contains(key)) issues.absent(runId, request.kind(), key, explanation, now);
        });
        jdbc.sql("UPDATE recon.run SET status='FINISHED', finished_at=?, summary=CAST(? AS jsonb) WHERE id=?")
                .params(clock.instant().atOffset(ZoneOffset.UTC), json.write(summary), runId)
                .update();
        return report(runId, request, inputs, summary, findings);
    }

    private RunReport report(
            @org.jspecify.annotations.Nullable String id,
            RunRequest request,
            Map<String, String> inputs,
            Map<String, Long> summary,
            List<RunReport.ObservedFinding> findings) {
        return new RunReport(
                id,
                request.kind(),
                "FINISHED",
                request.dryRun(),
                request.cutoffAt(),
                request.evidenceAsOf(),
                request.ruleVersion(),
                inputs,
                summary,
                findings);
    }

    private Map<String, String> inputs(RunRequest request) {
        Map<String, String> available = new TreeMap<>();
        jdbc.sql("""
                SELECT id, sha256 FROM recon.v_input_evidence WHERE ready
                    AND (kind=? OR ?='AGING') AND observed_at < ? AND recorded_at < ? ORDER BY id
                """)
                .params(
                        request.kind().name(),
                        request.kind().name(),
                        request.evidenceAsOf().atOffset(ZoneOffset.UTC),
                        request.evidenceAsOf().atOffset(ZoneOffset.UTC))
                .query((rs, row) -> available.put(rs.getString("id"), rs.getString("sha256")))
                .list();
        if (request.inputIds().isEmpty()) return available;
        if (!available.keySet().containsAll(request.inputIds()))
            throw new IllegalArgumentException("Input unavailable at evidenceAsOf");
        available.keySet().retainAll(request.inputIds());
        return available;
    }

    public Optional<RunReport> get(String id) {
        return jdbc.sql("SELECT * FROM recon.run WHERE id=?")
                .param(id)
                .query((rs, row) -> {
                    Map<String, String> inputs = new TreeMap<>();
                    mapper.readTree(rs.getString("input_hashes"))
                            .properties()
                            .forEach(e -> inputs.put(e.getKey(), e.getValue().asText()));
                    Map<String, Long> summary = new TreeMap<>();
                    mapper.readTree(rs.getString("summary"))
                            .properties()
                            .forEach(e -> summary.put(e.getKey(), e.getValue().longValue()));
                    var findings = jdbc.sql(
                                    "SELECT * FROM recon.finding WHERE run_id=? ORDER BY category, subject_type, subject_id")
                            .param(id)
                            .query((f, n) -> {
                                OffsetDateTime due = f.getObject("due_at", OffsetDateTime.class);
                                return new RunReport.ObservedFinding(
                                        new Finding(
                                                f.getString("category"),
                                                f.getString("subject_type"),
                                                f.getString("subject_id"),
                                                json.canonical(
                                                        Objects.requireNonNullElse(f.getString("expected"), "null")),
                                                json.canonical(
                                                        Objects.requireNonNullElse(f.getString("actual"), "null")),
                                                json.array(f.getString("evidence_refs")),
                                                due == null ? null : due.toInstant()),
                                        f.getString("fingerprint"));
                            })
                            .list();
                    return new RunReport(
                            id,
                            RunRequest.Kind.valueOf(rs.getString("kind")),
                            rs.getString("status"),
                            false,
                            rs.getObject("cutoff_at", OffsetDateTime.class).toInstant(),
                            rs.getObject("evidence_as_of", OffsetDateTime.class).toInstant(),
                            rs.getString("rule_version"),
                            inputs,
                            summary,
                            findings);
                })
                .optional();
    }
}
