package com.nabd.hms.platform.provisioning;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static com.nabd.hms.platform.provisioning.ProvisioningModels.Job;
import static com.nabd.hms.platform.provisioning.ProvisioningModels.JobStep;

@Repository
class ProvisioningRepository {

    private final JdbcTemplate jdbc;

    ProvisioningRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final String JOB_COLUMNS =
            "id, requested_by, tenant_slug, tenant_name, region, owner_email, owner_name, brand_name, " +
                    "status, created_tenant_id, created_owner_id, created_brand_id, " +
                    "owner_newly_created, brand_newly_created ";

    void insertJob(UUID id, UUID requestedBy, String tenantSlug, String tenantName, String region,
                   String ownerEmail, String ownerName, String brandName) {
        jdbc.update("INSERT INTO master.provisioning_jobs " +
                        "(id, requested_by, tenant_slug, tenant_name, region, owner_email, owner_name, brand_name) " +
                        "VALUES (?,?,?,?,?,?,?,?)",
                id, requestedBy, tenantSlug, tenantName, region, ownerEmail, ownerName, brandName);
    }

    void insertStep(UUID id, UUID jobId, String stepName, int stepOrder) {
        jdbc.update("INSERT INTO master.provisioning_job_steps (id, job_id, step_name, step_order) VALUES (?,?,?,?)",
                id, jobId, stepName, stepOrder);
    }

    Optional<Job> findJob(UUID id) {
        return jdbc.query("SELECT " + JOB_COLUMNS + "FROM master.provisioning_jobs WHERE id = ?",
                jobMapper(), id
        ).stream().findFirst();
    }

    List<Job> listJobs() {
        return jdbc.query("SELECT " + JOB_COLUMNS + "FROM master.provisioning_jobs ORDER BY created_at DESC", jobMapper());
    }

    List<JobStep> findSteps(UUID jobId) {
        return jdbc.query(
                "SELECT id, job_id, step_name, step_order, status, started_at, completed_at, error_detail " +
                        "FROM master.provisioning_job_steps WHERE job_id = ? ORDER BY step_order",
                stepMapper(), jobId);
    }

    /** The earliest step (by order) that isn't done yet — a queued first run or a failed step being retried. */
    Optional<JobStep> findNextIncompleteStep(UUID jobId) {
        return jdbc.query(
                "SELECT id, job_id, step_name, step_order, status, started_at, completed_at, error_detail " +
                        "FROM master.provisioning_job_steps WHERE job_id = ? AND status <> 'done' " +
                        "ORDER BY step_order LIMIT 1",
                stepMapper(), jobId
        ).stream().findFirst();
    }

    boolean hasIncompleteSteps(UUID jobId) {
        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM master.provisioning_job_steps WHERE job_id = ? AND status <> 'done'",
                Integer.class, jobId);
        return count != null && count > 0;
    }

    void updateJobStatus(UUID jobId, String status) {
        jdbc.update("UPDATE master.provisioning_jobs SET status = ? WHERE id = ?", status, jobId);
    }

    void setJobCreatedRefs(UUID jobId, UUID tenantId, UUID ownerId, UUID brandId) {
        jdbc.update("UPDATE master.provisioning_jobs SET created_tenant_id = ?, created_owner_id = ?, created_brand_id = ? WHERE id = ?",
                tenantId, ownerId, brandId, jobId);
    }

    void setJobCreationFlags(UUID jobId, boolean ownerNewlyCreated, boolean brandNewlyCreated) {
        jdbc.update("UPDATE master.provisioning_jobs SET owner_newly_created = ?, brand_newly_created = ? WHERE id = ?",
                ownerNewlyCreated, brandNewlyCreated, jobId);
    }

    void markStepRunning(UUID stepId) {
        jdbc.update("UPDATE master.provisioning_job_steps SET status = 'running', started_at = now(), error_detail = NULL WHERE id = ?", stepId);
    }

    void markStepDone(UUID stepId) {
        jdbc.update("UPDATE master.provisioning_job_steps SET status = 'done', completed_at = now() WHERE id = ?", stepId);
    }

    void markStepFailed(UUID stepId, String errorDetail) {
        jdbc.update("UPDATE master.provisioning_job_steps SET status = 'failed', completed_at = now(), error_detail = ? WHERE id = ?",
                errorDetail, stepId);
    }

    void markStepRolledBack(UUID stepId) {
        jdbc.update("UPDATE master.provisioning_job_steps SET status = 'rolled_back' WHERE id = ?", stepId);
    }

    // ---- owner/brand/tenant creation (public schema) — the provisioning flow V6's migration left for later ----

    Optional<UUID> findOwnerByEmail(String email) {
        return jdbc.query("SELECT id FROM owners WHERE email = ?::citext", (rs, i) -> UUID.fromString(rs.getString("id")), email)
                .stream().findFirst();
    }

    UUID insertOwner(String name, String email) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO owners (id, name, email, status) VALUES (?,?,?,'active')", id, name, email);
        return id;
    }

    /** Any one existing region tells us the owner's region lock (NB-002) — an owner never spans regions. */
    Optional<String> findAnyRegionForOwner(UUID ownerId) {
        return jdbc.query("SELECT t.region FROM tenants t JOIN brands b ON t.brand_id = b.id WHERE b.owner_id = ? LIMIT 1",
                (rs, i) -> rs.getString("region"), ownerId
        ).stream().findFirst();
    }

    Optional<UUID> findBrandByOwnerAndName(UUID ownerId, String name) {
        return jdbc.query("SELECT id FROM brands WHERE owner_id = ? AND name = ?",
                (rs, i) -> UUID.fromString(rs.getString("id")), ownerId, name
        ).stream().findFirst();
    }

    UUID insertBrand(UUID ownerId, String name) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO brands (id, owner_id, name, status) VALUES (?,?,?,'active')", id, ownerId, name);
        return id;
    }

    /** Unique on slug — callers must translate the resulting DuplicateKeyException into a readable step failure. */
    UUID insertTenant(String slug, String name, String region, UUID brandId) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO tenants (id, slug, name, region, status, brand_id) VALUES (?,?,?,?,'trial',?)",
                id, slug, name, region, brandId);
        return id;
    }

    /** Mirrors ApiTestBase's seedFullAccessRole shape — full grants on every module an Owner's shadow staff row needs. */
    void insertBuiltInOwnerRole(UUID tenantId, String grantsJson) {
        jdbc.update("INSERT INTO roles (id, tenant_id, name, built_in, grants) VALUES (?,?,?,true,?::jsonb)",
                UUID.randomUUID(), tenantId, "Owner", grantsJson);
    }

    boolean hasBuiltInRole(UUID tenantId) {
        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM roles WHERE tenant_id = ? AND built_in = true", Integer.class, tenantId);
        return count != null && count > 0;
    }

    // ---- rollback (NB-259) — reverse of the creates above, same FK-ordering constraints ----

    void deleteBuiltInRole(UUID tenantId) {
        jdbc.update("DELETE FROM roles WHERE tenant_id = ? AND built_in = true", tenantId);
    }

    void deleteTenant(UUID tenantId) {
        jdbc.update("DELETE FROM tenants WHERE id = ?", tenantId);
    }

    void deleteBrand(UUID brandId) {
        jdbc.update("DELETE FROM brands WHERE id = ?", brandId);
    }

    void deleteOwner(UUID ownerId) {
        jdbc.update("DELETE FROM owners WHERE id = ?", ownerId);
    }

    private RowMapper<Job> jobMapper() {
        return (rs, i) -> new Job(
                UUID.fromString(rs.getString("id")),
                UUID.fromString(rs.getString("requested_by")),
                rs.getString("tenant_slug"),
                rs.getString("tenant_name"),
                rs.getString("region"),
                rs.getString("owner_email"),
                rs.getString("owner_name"),
                rs.getString("brand_name"),
                rs.getString("status"),
                uuidOrNull(rs.getString("created_tenant_id")),
                uuidOrNull(rs.getString("created_owner_id")),
                uuidOrNull(rs.getString("created_brand_id")),
                rs.getBoolean("owner_newly_created"),
                rs.getBoolean("brand_newly_created"));
    }

    private RowMapper<JobStep> stepMapper() {
        return (rs, i) -> {
            Timestamp startedAt = rs.getTimestamp("started_at");
            Timestamp completedAt = rs.getTimestamp("completed_at");
            return new JobStep(
                    UUID.fromString(rs.getString("id")),
                    UUID.fromString(rs.getString("job_id")),
                    rs.getString("step_name"),
                    rs.getInt("step_order"),
                    rs.getString("status"),
                    startedAt == null ? null : startedAt.toInstant(),
                    completedAt == null ? null : completedAt.toInstant(),
                    rs.getString("error_detail"));
        };
    }

    private static UUID uuidOrNull(String s) {
        return s == null ? null : UUID.fromString(s);
    }
}
