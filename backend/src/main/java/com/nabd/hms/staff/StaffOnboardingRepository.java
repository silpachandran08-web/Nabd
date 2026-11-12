package com.nabd.hms.staff;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** ACC-035: staff_onboarding_progress (tenant RLS), own rows of one staff member. */
@Repository
class StaffOnboardingRepository {

    private final JdbcTemplate jdbc;

    StaffOnboardingRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    List<String> completed(UUID tenantId, UUID staffId) {
        return jdbc.queryForList("SELECT tour_key FROM staff_onboarding_progress WHERE tenant_id = ? AND staff_id = ? AND completed_at IS NOT NULL ORDER BY tour_key",
                String.class, tenantId, staffId);
    }

    /** Idempotent: the first completion time is kept. */
    void complete(UUID tenantId, UUID staffId, String key, Instant at) {
        jdbc.update("""
                INSERT INTO staff_onboarding_progress (tenant_id, staff_id, tour_key, completed_at) VALUES (?,?,?,?)
                ON CONFLICT (tenant_id, staff_id, tour_key) DO UPDATE SET completed_at = COALESCE(staff_onboarding_progress.completed_at, EXCLUDED.completed_at)""",
                tenantId, staffId, key, Timestamp.from(at));
    }

    Optional<Instant> passedAt(UUID tenantId, UUID staffId, String key) {
        return jdbc.queryForList("SELECT competency_passed_at FROM staff_onboarding_progress WHERE tenant_id = ? AND staff_id = ? AND tour_key = ? AND competency_passed_at IS NOT NULL",
                Timestamp.class, tenantId, staffId, key).stream().findFirst().map(Timestamp::toInstant);
    }

    /** True when this call recorded the pass (false when it was already recorded). */
    boolean pass(UUID tenantId, UUID staffId, String key, Instant at) {
        return jdbc.update("""
                INSERT INTO staff_onboarding_progress (tenant_id, staff_id, tour_key, competency_passed_at) VALUES (?,?,?,?)
                ON CONFLICT (tenant_id, staff_id, tour_key) DO UPDATE SET competency_passed_at = EXCLUDED.competency_passed_at
                WHERE staff_onboarding_progress.competency_passed_at IS NULL""",
                tenantId, staffId, key, Timestamp.from(at)) > 0;
    }
}
