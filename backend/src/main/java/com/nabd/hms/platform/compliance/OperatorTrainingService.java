package com.nabd.hms.platform.compliance;

import com.nabd.hms.common.TrainingRules;
import com.nabd.hms.platform.PlatformAudit;
import io.swagger.v3.oas.annotations.media.Schema;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * PLT-054: security-training completions of Nabd's own operators (master.operator_training_completions). The first pass
 * is due within a week of the account being created, then a refresher every year (TrainingRules).
 */
@Service
public class OperatorTrainingService {

    static final Duration FIRST_WEEK = Duration.ofDays(7);

    /** state: current (a pass within the last year), due (not yet past dueAt) or overdue. dueAt: joined + 7 days until the first pass, then pass + 1 year. */
    @Schema(name = "OperatorTrainingStatus")
    public record Status(String moduleId, int passMark, Integer score, Instant completedAt, Instant dueAt, String state) {
    }

    @Schema(name = "OperatorTrainingCompletion")
    public record Completion(UUID id, String moduleId, int score, Instant completedAt, Instant dueAt, boolean created) {
    }

    @Schema(name = "OperatorTrainingRosterEntry")
    public record RosterEntry(UUID operatorId, String name, String role, Instant joinedAt, Instant completedAt, Instant dueAt, String state) {
    }

    @Schema(name = "OperatorTrainingRoster")
    public record Roster(String moduleId, int total, int current, List<RosterEntry> operators) {
    }

    private final JdbcTemplate jdbc;
    private final PlatformAudit audit;
    private final Clock clock;

    OperatorTrainingService(JdbcTemplate jdbc, PlatformAudit audit, Clock clock) {
        this.jdbc = jdbc;
        this.audit = audit;
        this.clock = clock;
    }

    private static Instant dueAt(Instant joinedAt, Instant completedAt) {
        return completedAt == null ? joinedAt.plus(FIRST_WEEK) : TrainingRules.refresherDue(completedAt);
    }

    private static String state(Instant completedAt, Instant dueAt, Instant now) {
        return now.isBefore(dueAt) ? (completedAt == null ? "due" : "current") : "overdue";
    }

    /** Idempotent: a second pass of the module on the same UTC day returns the first one and writes nothing. */
    @Transactional
    public Completion record(UUID operatorId, String ip, String moduleId, int score) {
        TrainingRules.check(moduleId, score);
        boolean created = jdbc.update("INSERT INTO master.operator_training_completions (operator_id, module_id, score, completed_at) VALUES (?,?,?,?) ON CONFLICT DO NOTHING",
                operatorId, moduleId, score, Timestamp.from(clock.instant())) > 0;
        Completion c = jdbc.queryForObject("""
                SELECT id, score, completed_at FROM master.operator_training_completions
                WHERE operator_id = ? AND module_id = ? ORDER BY completed_at DESC LIMIT 1""",
                (rs, i) -> {
                    Instant at = rs.getTimestamp("completed_at").toInstant();
                    return new Completion(rs.getObject("id", UUID.class), moduleId, rs.getInt("score"), at, TrainingRules.refresherDue(at), created);
                }, operatorId, moduleId);
        if (created) {
            audit.record(null, operatorId, ip, "operator.training_completed", "operator", operatorId, Map.of("module", moduleId, "score", score));
        }
        return c;
    }

    @Transactional(readOnly = true)
    public Status mine(UUID operatorId) {
        Instant now = clock.instant();
        return jdbc.queryForObject(ROSTER_SQL + " WHERE o.id = ?", (rs, i) -> {
            RosterEntry e = entry(rs, now);
            return new Status(TrainingRules.REQUIRED_MODULE, TrainingRules.PASS_MARK, (Integer) rs.getObject("score"), e.completedAt(), e.dueAt(), e.state());
        }, TrainingRules.REQUIRED_MODULE, operatorId);
    }

    /** Every active operator with the newest pass, for the DPO (audit_compliance:view). */
    @Transactional(readOnly = true)
    public Roster roster() {
        Instant now = clock.instant();
        List<RosterEntry> rows = jdbc.query(ROSTER_SQL + " WHERE o.status = 'active' ORDER BY o.name, o.id", (rs, i) -> entry(rs, now), TrainingRules.REQUIRED_MODULE);
        return new Roster(TrainingRules.REQUIRED_MODULE, rows.size(), (int) rows.stream().filter(e -> "current".equals(e.state())).count(), rows);
    }

    private static final String ROSTER_SQL = """
            SELECT o.id, o.name, o.role, o.created_at, c.completed_at, c.score FROM master.operators o
            LEFT JOIN LATERAL (SELECT completed_at, score FROM master.operator_training_completions c
                               WHERE c.operator_id = o.id AND c.module_id = ? ORDER BY completed_at DESC LIMIT 1) c ON true""";

    private static RosterEntry entry(ResultSet rs, Instant now) throws SQLException {
        Instant joined = rs.getTimestamp("created_at").toInstant();
        Timestamp t = rs.getTimestamp("completed_at");
        Instant at = t == null ? null : t.toInstant();
        Instant due = dueAt(joined, at);
        return new RosterEntry(rs.getObject("id", UUID.class), rs.getString("name"), rs.getString("role"), joined, at, due, state(at, due, now));
    }
}
