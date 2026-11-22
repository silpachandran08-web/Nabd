package com.nabd.hms.staff;

import com.nabd.hms.common.ApiException;
import com.nabd.hms.common.AuditService;
import com.nabd.hms.common.TenantContext;
import com.nabd.hms.common.TrainingRules;
import com.nabd.hms.iam.api.ActorDirectory;
import io.swagger.v3.oas.annotations.media.Schema;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * PLT-054: security-training completions of clinic staff (training_completions, tenant RLS). A staff member records their
 * own pass; the newest pass of a module is current for a year (TrainingRules); holders of staff:view see the whole team.
 */
@Service
class TrainingService {

    /** state: current (a pass within the last year), due (never passed) or overdue (the yearly refresher has lapsed). */
    @Schema(name = "TrainingStatus")
    record Status(String moduleId, int passMark, Integer score, Instant completedAt, Instant dueAt, String state) {
    }

    @Schema(name = "TrainingCompletion")
    record Completion(UUID id, String moduleId, int score, Instant completedAt, Instant dueAt, boolean created) {
    }

    @Schema(name = "TrainingRosterEntry")
    record RosterEntry(UUID staffId, String name, String role, String accountStatus, Instant completedAt, Instant dueAt, String state) {
    }

    @Schema(name = "TrainingRoster")
    record Roster(String moduleId, int total, int current, List<RosterEntry> staff) {
    }

    private final JdbcTemplate jdbc;
    private final TenantContext tenantContext;
    private final AuditService audit;
    private final ActorDirectory actors;
    private final Clock clock;

    TrainingService(JdbcTemplate jdbc, TenantContext tenantContext, AuditService audit, ActorDirectory actors, Clock clock) {
        this.jdbc = jdbc;
        this.tenantContext = tenantContext;
        this.audit = audit;
        this.actors = actors;
        this.clock = clock;
    }

    static String state(Instant completedAt, Instant now) {
        return completedAt == null ? "due" : now.isBefore(TrainingRules.refresherDue(completedAt)) ? "current" : "overdue";
    }

    /** Idempotent: a second pass of the module on the same UTC day returns the first one and writes nothing. */
    @Transactional
    public Completion record(UUID staffId, String moduleId, int score) {
        TrainingRules.check(moduleId, score);
        UUID tenantId = tenantContext.current();
        // looked up first: a token whose subject is not a staff member of this clinic gets 404, not a foreign-key error
        ActorDirectory.Actor a = actors.findActor(tenantId, staffId).orElseThrow(ApiException::notFound);
        Instant now = clock.instant();
        boolean created = jdbc.update("INSERT INTO training_completions (tenant_id, staff_id, module_id, score, completed_at) VALUES (?,?,?,?,?) ON CONFLICT DO NOTHING",
                tenantId, staffId, moduleId, score, Timestamp.from(now)) > 0;
        Completion c = jdbc.queryForObject("""
                SELECT id, score, completed_at FROM training_completions
                WHERE tenant_id = ? AND staff_id = ? AND module_id = ? ORDER BY completed_at DESC LIMIT 1""",
                (rs, i) -> {
                    Instant at = rs.getTimestamp("completed_at").toInstant();
                    return new Completion(rs.getObject("id", UUID.class), moduleId, rs.getInt("score"), at, TrainingRules.refresherDue(at), created);
                }, tenantId, staffId, moduleId);
        if (created) {
            audit.record(tenantId, "staff", staffId, a.name(), a.role(), null, "staff.training_completed", "staff", staffId, null,
                    Map.of("module", moduleId, "score", score));
        }
        return c;
    }

    @Transactional(readOnly = true)
    public Status mine(UUID staffId) {
        Instant now = clock.instant();
        return jdbc.query("""
                        SELECT score, completed_at FROM training_completions
                        WHERE tenant_id = ? AND staff_id = ? AND module_id = ? ORDER BY completed_at DESC LIMIT 1""",
                        (rs, i) -> {
                            Instant at = rs.getTimestamp("completed_at").toInstant();
                            return new Status(TrainingRules.REQUIRED_MODULE, TrainingRules.PASS_MARK, rs.getInt("score"), at, TrainingRules.refresherDue(at), state(at, now));
                        }, tenantContext.current(), staffId, TrainingRules.REQUIRED_MODULE)
                .stream().findFirst().orElse(new Status(TrainingRules.REQUIRED_MODULE, TrainingRules.PASS_MARK, null, null, null, "due"));
    }

    /** Every invited or active account with its newest pass. shortcut: one unpaged list (a clinic's team); page it if a tenant passes a few hundred accounts. */
    @Transactional(readOnly = true)
    public Roster roster() {
        Instant now = clock.instant();
        List<RosterEntry> staff = jdbc.query("""
                SELECT s.id, s.name, r.name AS role, s.status,
                       (SELECT max(c.completed_at) FROM training_completions c
                         WHERE c.tenant_id = s.tenant_id AND c.staff_id = s.id AND c.module_id = ?) AS completed_at
                FROM staff s JOIN roles r ON r.id = s.role_id
                WHERE s.tenant_id = ? AND s.status IN ('invited','active') ORDER BY s.name, s.id LIMIT 1000""",
                (rs, i) -> {
                    Timestamp t = rs.getTimestamp("completed_at");
                    Instant at = t == null ? null : t.toInstant();
                    return new RosterEntry(rs.getObject("id", UUID.class), rs.getString("name"), rs.getString("role"), rs.getString("status"),
                            at, at == null ? null : TrainingRules.refresherDue(at), state(at, now));
                }, TrainingRules.REQUIRED_MODULE, tenantContext.current());
        return new Roster(TrainingRules.REQUIRED_MODULE, staff.size(), (int) staff.stream().filter(e -> "current".equals(e.state())).count(), staff);
    }
}
