package com.nabd.hms.organization.trial;

import com.nabd.hms.common.TenantContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
class TrialRepository {

    record Job(UUID id, UUID requestedBy, String status, long rowsDeleted) {
    }

    private final JdbcTemplate jdbc;
    private final TenantContext tenant;

    TrialRepository(JdbcTemplate jdbc, TenantContext tenant) {
        this.jdbc = jdbc;
        this.tenant = tenant;
    }

    String tenantStatus() {
        return jdbc.queryForObject("SELECT status FROM tenants WHERE id = ?", String.class, tenant.current());
    }

    boolean hasDpa() {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM dpa_acceptances WHERE tenant_id = ?)", Boolean.class, tenant.current()));
    }

    /** The newest job of the clinic (RLS), if any. */
    Optional<Job> latestJob() {
        return jdbc.query("SELECT id, requested_by, status, synthetic_rows_deleted FROM trial_conversion_jobs WHERE tenant_id = ? ORDER BY created_at DESC LIMIT 1", jobMapper(), tenant.current()).stream().findFirst();
    }

    boolean converted() {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM trial_conversion_jobs WHERE tenant_id = ? AND status = 'done')", Boolean.class, tenant.current()));
    }

    Optional<Job> openJob() {
        return jdbc.query("SELECT id, requested_by, status, synthetic_rows_deleted FROM trial_conversion_jobs WHERE tenant_id = ? AND status IN ('queued','running')", jobMapper(), tenant.current()).stream().findFirst();
    }

    /** empty when the clinic already has an open job (partial unique index; DO NOTHING keeps the transaction usable). */
    Optional<UUID> insertJob(UUID staffId) {
        UUID id = UUID.randomUUID();
        int n = jdbc.update("INSERT INTO trial_conversion_jobs (id, tenant_id, requested_by) VALUES (?,?,?) ON CONFLICT DO NOTHING", id, tenant.current(), staffId);
        return n == 1 ? Optional.of(id) : Optional.empty();
    }

    boolean markRunning(UUID jobId) {
        return jdbc.update("UPDATE trial_conversion_jobs SET status = 'running' WHERE id = ? AND tenant_id = ? AND status IN ('queued','running')", jobId, tenant.current()) == 1;
    }

    boolean finish(UUID jobId) {
        return jdbc.update("UPDATE trial_conversion_jobs SET status = 'done', completed_at = now() WHERE id = ? AND tenant_id = ? AND status = 'running'", jobId, tenant.current()) == 1;
    }

    void fail(UUID jobId, String errorCode) {
        jdbc.update("UPDATE trial_conversion_jobs SET status = 'failed', error_code = ?, completed_at = now() WHERE id = ? AND tenant_id = ?", errorCode, jobId, tenant.current());
    }

    void addRows(UUID jobId, long rows) {
        jdbc.update("UPDATE trial_conversion_jobs SET synthetic_rows_deleted = synthetic_rows_deleted + ? WHERE id = ? AND tenant_id = ?", rows, jobId, tenant.current());
    }

    long rowsSoFar(UUID jobId) {
        return jdbc.queryForObject("SELECT synthetic_rows_deleted FROM trial_conversion_jobs WHERE id = ? AND tenant_id = ?", Long.class, jobId, tenant.current());
    }

    List<UUID> trialingTenants() {
        return jdbc.queryForList("SELECT id FROM tenants WHERE status = 'trialing'", UUID.class);
    }

    List<UUID> openJobs() {
        return jdbc.queryForList("SELECT id FROM trial_conversion_jobs WHERE tenant_id = ? AND status IN ('queued','running')", UUID.class, tenant.current());
    }

    private static org.springframework.jdbc.core.RowMapper<Job> jobMapper() {
        return (rs, i) -> new Job(rs.getObject("id", UUID.class), rs.getObject("requested_by", UUID.class), rs.getString("status"), rs.getLong("synthetic_rows_deleted"));
    }
}
