package com.nabd.hms.platform.provisioning;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
            "id, requested_by, tenant_slug, tenant_name, region, owner_email, owner_name, owner_mobile, brand_name, " +
                    "status, path, created_tenant_id, created_owner_id, created_brand_id, " +
                    "owner_newly_created, brand_newly_created, approved_at, approved_by, " +
                    "source_tenant_id, requested_by_staff, planned_tenant_id, branch_code, branch_address, timezone, copy_sections ";

    void insertJob(UUID id, UUID requestedBy, String tenantSlug, String tenantName, String region,
                   String ownerEmail, String ownerName, String ownerMobile, String brandName, String path) {
        jdbc.update("INSERT INTO master.provisioning_jobs " +
                        "(id, requested_by, tenant_slug, tenant_name, region, owner_email, owner_name, owner_mobile, brand_name, path) " +
                        "VALUES (?,?,?,?,?,?,?,?,?,?)",
                id, requestedBy, tenantSlug, tenantName, region, ownerEmail, ownerName, ownerMobile, brandName, path);
    }

    /** PLT-022: an owner's add-branch job. @return false when the step-up confirmation was already used (unique step_up_id). */
    boolean insertBranchJob(UUID id, ProvisioningModels.Branch b, String tenantSlug, String tenantName, String region,
                            String ownerEmail, String ownerName, String brandName, UUID stepUpId) {
        return jdbc.update("INSERT INTO master.provisioning_jobs " +
                        "(id, tenant_slug, tenant_name, region, owner_email, owner_name, brand_name, path, source_tenant_id, requested_by_staff, " +
                        "planned_tenant_id, branch_code, branch_address, timezone, copy_sections, step_up_id) " +
                        "VALUES (?,?,?,?,?,?,?,'self_serve',?,?,?,?,?,?,?,?) ON CONFLICT (step_up_id) DO NOTHING",
                id, tenantSlug, tenantName, region, ownerEmail, ownerName, brandName, b.sourceTenantId(), b.requestedByStaff(),
                b.plannedTenantId(), b.code(), b.address(), b.timezone(), b.copySections().toArray(new String[0]), stepUpId) == 1;
    }

    /** PLT-022: the caller as the owner of the clinic they are signed in to: built-in Owner role AND the owner account of the
     * clinic's brand (linked staff row, or the provisioned row under the owner's own email). Empty for everyone else. */
    Optional<OwnerLink> ownerLink(UUID tenantId, UUID staffId) {
        return jdbc.query("SELECT o.id, o.name, o.email, b.id, b.name, t.region_code, t.slug, s.name " +
                        "FROM staff s JOIN roles r ON r.id = s.role_id AND r.tenant_id = s.tenant_id AND r.built_in " +
                        "JOIN tenants t ON t.id = s.tenant_id JOIN brands b ON b.id = t.brand_id JOIN owners o ON o.id = b.owner_id " +
                        "WHERE s.id = ? AND s.tenant_id = ? AND s.status = 'active' AND (s.owner_id = o.id OR (s.owner_id IS NULL AND s.email = o.email))",
                (rs, i) -> new OwnerLink(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), rs.getObject(4, UUID.class),
                        rs.getString(5), rs.getString(6), rs.getString(7), rs.getString(8)), staffId, tenantId).stream().findFirst();
    }

    record OwnerLink(UUID ownerId, String ownerName, String ownerEmail, UUID brandId, String brandName, String region, String slug, String staffName) {
    }

    record BranchRow(UUID tenantId, String name, String branchCode, String status, String region, String timezone) {
    }

    /** Every clinic under any brand of this owner, oldest first. */
    List<BranchRow> branchesOf(UUID ownerId) {
        return jdbc.query("SELECT t.id, t.name, t.branch_code, t.status, t.region_code, t.timezone FROM tenants t JOIN brands b ON b.id = t.brand_id " +
                        "WHERE b.owner_id = ? ORDER BY t.created_at, t.id",
                (rs, i) -> new BranchRow(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5), rs.getString(6)),
                ownerId);
    }

    /** Serialises add-branch requests of one brand, so the plan limit and the branch code are checked without a race. */
    void lockBrand(UUID brandId) {
        jdbc.query("SELECT pg_advisory_xact_lock(hashtextextended(?, 22))", rs -> { }, brandId.toString());
    }

    /** Clinics of the brand plus add-branch jobs that have not made their clinic yet (they hold a place too). */
    long branchesInUse(UUID brandId, String ownerEmail, String brandName) {
        Long n = jdbc.queryForObject("SELECT (SELECT count(*) FROM tenants WHERE brand_id = ?) + " + PENDING_BRANCH_JOBS, Long.class,
                brandId, ownerEmail, brandName);
        return n == null ? 0 : n;
    }

    private static final String PENDING_BRANCH_JOBS = "(SELECT count(*) FROM master.provisioning_jobs WHERE source_tenant_id IS NOT NULL " +
            "AND created_tenant_id IS NULL AND status IN ('queued','running','failed') AND owner_email = ?::citext AND brand_name = ?)";

    boolean branchCodeTaken(UUID brandId, String ownerEmail, String brandName, String code) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM tenants WHERE brand_id = ? AND lower(branch_code) = lower(?)) " +
                        "OR EXISTS (SELECT 1 FROM master.provisioning_jobs WHERE source_tenant_id IS NOT NULL AND status IN ('queued','running','failed') " +
                        "AND owner_email = ?::citext AND brand_name = ? AND lower(branch_code) = lower(?))",
                Boolean.class, brandId, code, ownerEmail, brandName, code));
    }

    boolean slugTaken(String slug) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM tenants WHERE slug = ?::citext) " +
                "OR EXISTS (SELECT 1 FROM master.provisioning_jobs WHERE tenant_slug = ?::citext AND status IN ('queued','running','failed'))",
                Boolean.class, slug, slug));
    }

    /** The region of a clinic this owner owns; empty when it is not theirs (or does not exist). */
    Optional<String> regionOfOwnedClinic(UUID ownerId, UUID tenantId) {
        return jdbc.query("SELECT t.region_code FROM tenants t JOIN brands b ON b.id = t.brand_id WHERE t.id = ? AND b.owner_id = ?",
                (rs, i) -> rs.getString(1), tenantId, ownerId).stream().findFirst();
    }

    /** This owner's add-branch jobs that are not finished, newest first. */
    List<Job> openBranchJobs(String ownerEmail) {
        return jdbc.query("SELECT " + JOB_COLUMNS + "FROM master.provisioning_jobs WHERE source_tenant_id IS NOT NULL AND status <> 'done' " +
                "AND owner_email = ?::citext ORDER BY created_at DESC", jobMapper(), ownerEmail);
    }

    /** Only meaningful for an enterprise-path job — self-serve jobs have nothing to gate. */
    boolean approveJob(UUID jobId, UUID approvedBy) {
        int rows = jdbc.update(
                "UPDATE master.provisioning_jobs SET approved_at = now(), approved_by = ? " +
                        "WHERE id = ? AND path = 'enterprise' AND approved_at IS NULL",
                approvedBy, jobId);
        return rows == 1;
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

    /** PLT-058: every job with its steps in ONE query (was 1 + 2 per job). */
    List<ProvisioningModels.JobWithSteps> listJobsWithSteps() {
        Map<UUID, ProvisioningModels.JobWithSteps> out = new LinkedHashMap<>();
        RowMapper<Job> jobs = jobMapper();
        jdbc.query("SELECT j.*, s.* FROM (SELECT " + JOB_COLUMNS + ", created_at FROM master.provisioning_jobs) j " +
                "LEFT JOIN (SELECT id AS step_id, job_id, step_name, step_order, status AS step_status, started_at, completed_at, error_detail " +
                "FROM master.provisioning_job_steps) s ON s.job_id = j.id ORDER BY j.created_at DESC, j.id, s.step_order", (RowCallbackHandler) rs -> {
            UUID id = UUID.fromString(rs.getString("id"));
            ProvisioningModels.JobWithSteps js = out.get(id);
            if (js == null) {
                js = new ProvisioningModels.JobWithSteps(jobs.mapRow(rs, out.size()), new ArrayList<>());
                out.put(id, js);
            }
            if (rs.getString("step_id") != null) {
                Timestamp startedAt = rs.getTimestamp("started_at");
                Timestamp completedAt = rs.getTimestamp("completed_at");
                js.steps().add(new JobStep(UUID.fromString(rs.getString("step_id")), id, rs.getString("step_name"),
                        rs.getInt("step_order"), rs.getString("step_status"),
                        startedAt == null ? null : startedAt.toInstant(), completedAt == null ? null : completedAt.toInstant(),
                        rs.getString("error_detail")));
            }
        });
        return new ArrayList<>(out.values());
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

    /** PLT-058: compare-and-set claim. Only one caller can move a queued/failed step to running; false means someone else holds it. */
    boolean claimStep(UUID stepId) {
        return jdbc.update("UPDATE master.provisioning_job_steps SET status = 'running', started_at = now(), completed_at = NULL, error_detail = NULL " +
                "WHERE id = ? AND status IN ('queued', 'failed')", stepId) == 1;
    }

    /** PLT-058: the step's idempotency key, keyed by (job_id, step_name). Called inside the step's own transaction. */
    boolean effectCommitted(UUID jobId, String stepName) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT effect_committed_at IS NOT NULL FROM master.provisioning_job_steps WHERE job_id = ? AND step_name = ?",
                Boolean.class, jobId, stepName));
    }

    void recordEffect(UUID jobId, String stepName) {
        jdbc.update("UPDATE master.provisioning_job_steps SET effect_committed_at = now() WHERE job_id = ? AND step_name = ?", jobId, stepName);
    }

    /** PLT-058 reaper: steps running since before the cutoff fail with 'timeout'; their running jobs become failed (retryable). */
    int failStuckSteps(Instant cutoff) {
        Integer n = jdbc.queryForObject(
                "WITH s AS (UPDATE master.provisioning_job_steps SET status = 'failed', completed_at = now(), error_detail = 'timeout' " +
                        "WHERE status = 'running' AND started_at < ? RETURNING job_id), " +
                        "j AS (UPDATE master.provisioning_jobs SET status = 'failed' WHERE status = 'running' AND id IN (SELECT job_id FROM s) RETURNING id) " +
                        "SELECT count(*) FROM s",
                Integer.class, Timestamp.from(cutoff));
        return n == null ? 0 : n;
    }

    void markStepDone(UUID stepId) {
        jdbc.update("UPDATE master.provisioning_job_steps SET status = 'done', completed_at = now() WHERE id = ?", stepId);
    }

    void markStepFailed(UUID stepId, String errorDetail) {
        jdbc.update("UPDATE master.provisioning_job_steps SET status = 'failed', completed_at = now(), error_detail = ? WHERE id = ?",
                errorDetail, stepId);
    }

    /** OPS-012: keeps a done step done but records why something best-effort (the invite email) did not happen. */
    void recordStepNote(UUID stepId, String note) {
        jdbc.update("UPDATE master.provisioning_job_steps SET error_detail = ? WHERE id = ?", note, stepId);
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
        return jdbc.query("SELECT t.region_code AS region FROM tenants t JOIN brands b ON t.brand_id = b.id WHERE b.owner_id = ? LIMIT 1",
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
        // timezone is left out: the V1991 trigger gives a new clinic its region's zone
        jdbc.update("INSERT INTO tenants (id, slug, name, region_code, status, brand_id) VALUES (?,?,?,?,'provisioning',?)",
                id, slug, name, region, brandId);
        return id;
    }

    /** PLT-022: a branch gets the id the owner was told, its own timezone, code and address, and the source clinic's region
     * (pinned: never taken from the request), locale, facility type, billing mode, specialties and day start. */
    UUID insertBranchTenant(ProvisioningModels.Branch b, String slug, String name, UUID brandId) {
        jdbc.update("INSERT INTO tenants (id, slug, name, region_code, status, brand_id, timezone, branch_code, branch_label, branch_address, " +
                        "default_locale, facility_type, billing_mode, specialties, day_start_minute) " +
                        "SELECT ?,?,?,s.region_code,'provisioning',?,?,?,?,?, s.default_locale, s.facility_type, s.billing_mode, s.specialties, s.day_start_minute " +
                        "FROM tenants s WHERE s.id = ?",
                b.plannedTenantId(), slug, name, brandId, b.timezone(), b.code(), name, b.address(), b.sourceTenantId());
        return b.plannedTenantId();
    }

    /** Mirrors ApiTestBase's seedFullAccessRole shape — full grants on every module an Owner's shadow staff row needs. */
    /** PLT-016: one preset role from RolePresets (only the Owner preset is built_in). */
    void insertPresetRole(UUID tenantId, String name, boolean builtIn, String grantsJson, String presetCode, int presetVersion) {
        jdbc.update("INSERT INTO roles (id, tenant_id, name, built_in, grants, preset_code, preset_version) VALUES (?,?,?,?,?::jsonb,?,?)",
                UUID.randomUUID(), tenantId, name, builtIn, grantsJson, presetCode, presetVersion);
    }

    boolean hasBuiltInRole(UUID tenantId) {
        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM roles WHERE tenant_id = ? AND built_in = true", Integer.class, tenantId);
        return count != null && count > 0;
    }

    /** Same backfill V37's migration gave every already-provisioned tenant — a brand-new tenant
     * needs this too, so check-in never breaks for a doctor nobody has assigned a department to yet.
     * No department_workflow_selection row is seeded here — DepartmentService.resolveStatusSequence()
     * already falls back to vitals+consultation (the platform's clinic_walkin default) when a
     * department hasn't picked a template yet. */
    void seedDefaultDepartment(UUID tenantId) {
        jdbc.update("INSERT INTO departments (tenant_id, name, is_default) VALUES (?,'General',true)",
                tenantId);
    }

    /** The Owner role seed_masters just created — verify_invite_owner needs its id to invite the owner as staff. */
    Optional<UUID> findBuiltInRoleId(UUID tenantId) {
        return jdbc.query("SELECT id FROM roles WHERE tenant_id = ? AND built_in = true LIMIT 1",
                (rs, i) -> UUID.fromString(rs.getString("id")), tenantId
        ).stream().findFirst();
    }

    /** NB-353: a brand-new tenant has no staff yet, so "any row at all" means the owner was already
     * invited — same retry-after-a-later-step-failed idempotency shape as hasBuiltInRole. */
    boolean hasOwnerStaff(UUID tenantId) {
        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM staff WHERE tenant_id = ?", Integer.class, tenantId);
        return count != null && count > 0;
    }

    // ---- rollback (NB-259) — reverse of the creates above, same FK-ordering constraints ----

    void deleteBuiltInRole(UUID tenantId) {
        jdbc.update("DELETE FROM roles WHERE tenant_id = ? AND (built_in = true OR preset_code IS NOT NULL)", tenantId);
    }

    /** Reverses seedDefaultDepartment — departments.tenant_id references tenants(id), so this must
     * run before undoCreateTenant's deleteTenant or that DELETE hits a FK violation. */
    void deleteDepartments(UUID tenantId) {
        jdbc.update("DELETE FROM departments WHERE tenant_id = ?", tenantId);
    }

    /** NB-353's undo of verify_invite_owner — a fatal later-step failure must leave no login behind either. */
    void deleteStaffByTenant(UUID tenantId) {
        jdbc.update("DELETE FROM staff WHERE tenant_id = ?", tenantId);
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
        return (rs, i) -> {
            Timestamp approvedAt = rs.getTimestamp("approved_at");
            return new Job(
                    UUID.fromString(rs.getString("id")),
                    uuidOrNull(rs.getString("requested_by")),
                    rs.getString("tenant_slug"),
                    rs.getString("tenant_name"),
                    rs.getString("region"),
                    rs.getString("owner_email"),
                    rs.getString("owner_name"),
                    rs.getString("owner_mobile"),
                    rs.getString("brand_name"),
                    rs.getString("status"),
                    rs.getString("path"),
                    uuidOrNull(rs.getString("created_tenant_id")),
                    uuidOrNull(rs.getString("created_owner_id")),
                    uuidOrNull(rs.getString("created_brand_id")),
                    rs.getBoolean("owner_newly_created"),
                    rs.getBoolean("brand_newly_created"),
                    approvedAt == null ? null : approvedAt.toInstant(),
                    uuidOrNull(rs.getString("approved_by")),
                    rs.getString("source_tenant_id") == null ? null : new ProvisioningModels.Branch(
                            uuidOrNull(rs.getString("source_tenant_id")), uuidOrNull(rs.getString("requested_by_staff")),
                            uuidOrNull(rs.getString("planned_tenant_id")), rs.getString("branch_code"), rs.getString("branch_address"),
                            rs.getString("timezone"), List.of((String[]) rs.getArray("copy_sections").getArray())));
        };
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
