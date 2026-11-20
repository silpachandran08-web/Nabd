package com.nabd.hms.platform.provisioning;

import com.nabd.hms.common.ConfigBundle;
import com.nabd.hms.messaging.TemplateCatalogueService;
import com.nabd.hms.shared.security.Audience;
import com.nabd.hms.shared.security.TokenType;
import com.nabd.hms.support.ApiTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;

import java.sql.Connection;
import java.sql.Statement;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.nabd.hms.platform.provisioning.ProvisioningModels.Branch;
import static com.nabd.hms.platform.provisioning.ProvisioningModels.Job;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * PLT-022: an owner adds a branch from their own console. Copy of the configuration (and nothing else), region pinned to the
 * owner's, plan limit 402 with the upgrade link, a failed step shown and retried, undo of every step, owner-only + step-up bound
 * to the branch code, and no branch under (or copy from) another owner's clinic.
 */
class Plt022OwnerBranchApiTest extends ApiTestBase {

    private static final List<String> ALL = ConfigBundle.SECTIONS;

    @Autowired JwtEncoder encoder;
    @Autowired TemplateCatalogueService catalogue;
    @Autowired ProvisioningRepository repo;
    @Autowired ProvisioningStepRunner stepRunner;
    @Autowired ProvisioningService provisioning;
    @Autowired ConfigBundle configBundle;

    private record Owned(SeededOwner owner, SeededBrand brand, SeededTenant clinic, String token) {
    }

    private JdbcTemplate su() {
        return new JdbcTemplate(superuserDataSource());
    }

    private static String tag() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    /** An owner with one clinic, signed in to it through the owner login (workspace token = the brand's owner account). */
    private Owned owned() {
        SeededOwner owner = seedOwner("owner-" + tag() + "@plt022.test");
        SeededBrand brand = seedBrand(owner, "Brand " + tag());
        SeededTenant clinic = seedClinicInBrand(brand);
        return new Owned(owner, brand, clinic, workspace(owner, clinic.id()));
    }

    private String workspace(SeededOwner owner, UUID clinicId) {
        ResponseEntity<Map> r = exchange("/v1/owners/workspaces/select", HttpMethod.POST,
                authedJsonBody(ownerLoginPendingToken(owner), Map.of("clinicId", clinicId.toString())), Map.class);
        assertThat(r.getStatusCode()).as(String.valueOf(r.getBody())).isEqualTo(HttpStatus.OK);
        return (String) r.getBody().get("accessToken");
    }

    /** What /v1/auth/mfa/verify mints for an authenticated caller who asked for {@code target}. */
    private String stepUp(String access, String target) throws Exception {
        Map<String, Object> c = objectMapper.readValue(java.util.Base64.getUrlDecoder().decode(access.split("\\.")[1]), Map.class);
        Instant now = Instant.now();
        JwtClaimsSet.Builder b = JwtClaimsSet.builder().issuer("https://api.nabd.health").issuedAt(now).expiresAt(now.plusSeconds(300))
                .id(UUID.randomUUID().toString()).subject((String) c.get("sub")).claim("purpose", "step_up").claim("sid", c.get("sid"))
                .claim("tenantId", c.get("tenantId")).claim("target", target);
        TokenType.STEP_UP.stamp(b, Audience.STEP_UP);
        return encoder.encode(JwtEncoderParameters.from(b.build())).getTokenValue();
    }

    private Map<String, Object> body(String code, UUID copyFrom) {
        return new HashMap<>(Map.of("name", "Branch " + code, "branchCode", code, "address", "12 Synthetic Road", "timezone", "Asia/Kolkata",
                "copyFromTenantId", copyFrom.toString(), "copy", ALL));
    }

    private ResponseEntity<Map> add(String token, String stepUp, String idempotencyKey, Map<String, Object> body) throws Exception {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(token);
        h.setContentType(MediaType.APPLICATION_JSON);
        h.set("Idempotency-Key", idempotencyKey);
        if (stepUp != null) h.set("X-Step-Up-Token", stepUp);
        return exchange("/v1/owner/branches", HttpMethod.POST, new HttpEntity<>(objectMapper.writeValueAsString(body), h), Map.class);
    }

    private ResponseEntity<Map> add(Owned o, String code, UUID copyFrom) throws Exception {
        return add(o.token(), stepUp(o.token(), "branch-add:" + code.toLowerCase()), UUID.randomUUID().toString(), body(code, copyFrom));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> list(String token) {
        ResponseEntity<Map> r = exchange("/v1/owner/branches", HttpMethod.GET, authed(token), Map.class);
        assertThat(r.getStatusCode()).as(String.valueOf(r.getBody())).isEqualTo(HttpStatus.OK);
        return r.getBody();
    }

    private String jobStatus(UUID jobId) {
        return jdbc.queryForObject("SELECT status FROM master.provisioning_jobs WHERE id = ?", String.class, jobId);
    }

    private String awaitJob(UUID jobId, String... until) throws InterruptedException {
        for (int i = 0; i < 300; i++) {
            String s = jobStatus(jobId);
            if (List.of(until).contains(s)) return s;
            Thread.sleep(100);
        }
        return jobStatus(jobId);
    }

    private long jobsOf(SeededOwner owner) {
        return jdbc.queryForObject("SELECT count(*) FROM master.provisioning_jobs WHERE owner_email = ?::citext", Long.class, owner.email());
    }

    private long count(UUID tenant, String table) {
        return inTenantTx(tenant, () -> jdbc.queryForObject("SELECT count(*) FROM " + table + " WHERE tenant_id = ?", Long.class, tenant));
    }

    private List<String> strings(UUID tenant, String sql) {
        return inTenantTx(tenant, () -> jdbc.queryForList(sql, String.class, tenant));
    }

    /** Configuration of every section, plus a patient and a doctor that must stay behind. */
    private void configure(Owned o) {
        UUID t = o.clinic().id();
        inTenantTx(t, () -> {
            jdbc.update("UPDATE departments SET is_default = false WHERE tenant_id = ?", t);
            jdbc.update("INSERT INTO departments (tenant_id, name, is_default, display_order) VALUES (?, 'Dental', true, 2)", t);
            jdbc.update("INSERT INTO service_points (tenant_id, department_id, kind, label) "
                    + "SELECT ?, id, 'chair', 'Chair 1' FROM departments WHERE tenant_id = ? AND name = 'Dental'", t, t);
            jdbc.update("INSERT INTO charge_catalogue (tenant_id, code, name, category, base_amount) VALUES (?, 'CONS', 'Consultation', 'Consultation', 500)", t);
            jdbc.update("INSERT INTO charge_catalogue (tenant_id, code, name, category, base_amount, stock_qty) VALUES (?, 'PARA', 'Paracetamol', 'Pharmacy', 20, 40)", t);
            jdbc.update("INSERT INTO clinic_policies (tenant_id, policy_key, value) VALUES (?, 'cancellation_window_hours', '12')", t);
            jdbc.update("INSERT INTO clinic_holidays (tenant_id, holiday_date, name) VALUES (?, ?::date, 'Founders day')", t, LocalDate.now().plusDays(40).toString());
            jdbc.update("INSERT INTO clinic_quiet_hours (tenant_id, enabled) VALUES (?, true)", t);
        });
        UUID desk = seedRole(t, "Front Desk Lead", false, fullGrant("queue"));
        seedStaff(o.clinic(), desk, "doc-" + tag() + "@plt022.test", "+9195" + (10000000 + (int) (Math.random() * 8999999)), false);
        assertThat(catalogue.instantiate(t, List.of("en"))).isPositive(); // an Indian clinic would default to en + hi
        ResponseEntity<Map> p = exchange("/v1/patients", HttpMethod.POST, authedJsonBody(o.token(), Map.of("name", "Synthetic Patient", "phone",
                "+9198" + (10000000 + (int) (Math.random() * 8999999)), "dob", "1980-01-01", "gender", "female")), Map.class);
        assertThat(p.getStatusCode().is2xxSuccessful()).as(String.valueOf(p.getBody())).isTrue();
    }

    // AC1 + AC2 + AC3 (+ the owner's half of AC6): wizard -> usable branch, configuration equal to the source, nothing else copied,
    // region the owner's, one login.
    @Test
    @SuppressWarnings("unchecked")
    void ownerAddsABranch_configurationIsCopied_noPatientOrStaffRow_regionPinned_ownerEntersItWithTheSameLogin() throws Exception {
        Owned o = owned();
        configure(o);
        long started = System.nanoTime();
        Map<String, Object> req = body("BLR-2", o.clinic().id());
        req.put("region", "AE"); // not a field of the request: the branch stays in the owner's region
        ResponseEntity<Map> r = add(o.token(), stepUp(o.token(), "branch-add:blr-2"), UUID.randomUUID().toString(), req);
        assertThat(r.getStatusCode()).as(String.valueOf(r.getBody())).isEqualTo(HttpStatus.ACCEPTED);
        UUID jobId = UUID.fromString((String) r.getBody().get("provisioningJobId"));
        UUID branch = UUID.fromString((String) r.getBody().get("branchTenantId"));

        assertThat(awaitJob(jobId, "done", "failed", "rolled_back")).isEqualTo("done");
        assertThat((System.nanoTime() - started) / 1_000_000_000L).as("wizard to usable branch, seconds").isLessThan(15 * 60);

        Map<String, Object> listed = list(o.token());
        Map<String, Object> item = ((List<Map<String, Object>>) listed.get("items")).stream().filter(i -> branch.toString().equals(i.get("tenantId")))
                .findFirst().orElseThrow();
        assertThat(item).containsEntry("name", "Branch BLR-2").containsEntry("branchCode", "BLR-2").containsEntry("status", "trialing")
                .containsEntry("region", "IN").containsEntry("timezone", "Asia/Kolkata");
        assertThat((List<?>) listed.get("jobs")).isEmpty();
        assertThat(jdbc.queryForMap("SELECT region_code, brand_id, branch_address, slug FROM tenants WHERE id = ?", branch))
                .containsEntry("region_code", "IN").containsEntry("brand_id", o.brand().id()).containsEntry("branch_address", "12 Synthetic Road")
                .containsEntry("slug", o.clinic().slug() + "-blr-2");

        UUID src = o.clinic().id();
        for (String sql : List.of(
                "SELECT name || ':' || is_default FROM departments WHERE tenant_id = ? ORDER BY 1",
                "SELECT d.name || ':' || s.kind || ':' || s.label FROM service_points s JOIN departments d ON d.id = s.department_id WHERE s.tenant_id = ? ORDER BY 1",
                "SELECT code || ':' || name || ':' || base_amount FROM charge_catalogue WHERE tenant_id = ? ORDER BY 1",
                "SELECT policy_key || '=' || value FROM clinic_policies WHERE tenant_id = ? ORDER BY 1",
                "SELECT name || ':' || grants::text FROM roles WHERE tenant_id = ? AND NOT built_in ORDER BY 1",
                "SELECT DISTINCT language FROM whatsapp_templates WHERE tenant_id = ? AND catalogue_key IS NOT NULL ORDER BY 1",
                "SELECT catalogue_key || ':' || language FROM whatsapp_templates WHERE tenant_id = ? AND catalogue_key IS NOT NULL ORDER BY 1",
                "SELECT holiday_date || ':' || name FROM clinic_holidays WHERE tenant_id = ? ORDER BY 1",
                "SELECT enabled::text FROM clinic_quiet_hours WHERE tenant_id = ?")) {
            assertThat(strings(branch, sql)).as(sql).isNotEmpty().isEqualTo(strings(src, sql));
        }
        assertThat(strings(branch, "SELECT name FROM departments WHERE tenant_id = ? AND is_default")).containsExactly("Dental");
        // stock stays with the source: the stock-tracked item starts at 0, the untracked one stays untracked
        assertThat(strings(branch, "SELECT code || ':' || coalesce(stock_qty::text, 'none') FROM charge_catalogue WHERE tenant_id = ? ORDER BY 1"))
                .containsExactly("CONS:none", "PARA:0");
        assertThat(strings(src, "SELECT stock_qty::text FROM charge_catalogue WHERE tenant_id = ? AND code = 'PARA'")).containsExactly("40");
        // exactly one built-in Owner role, as provisioning seeds it
        assertThat(count(branch, "roles") - strings(branch, "SELECT name FROM roles WHERE tenant_id = ? AND NOT built_in").size()).isEqualTo(1);

        // no patient, staff or clinical row came along (the source has a patient and two staff)
        assertThat(count(src, "patients")).isEqualTo(1);
        assertThat(count(src, "staff")).isEqualTo(2);
        for (String table : List.of("patients", "staff", "appointments", "queue_entries", "prescriptions", "clinical_notes", "invoices", "consents",
                "charge_doctor_fees", "stock_batches")) {
            assertThat(count(branch, table)).as(table).isZero();
        }
        assertThat(inTenantTx(branch, () -> jdbc.queryForMap("SELECT source_tenant_id, status, array_to_string(sections, ',') AS s FROM config_bundle_imports WHERE tenant_id = ?", branch)))
                .containsEntry("source_tenant_id", src).containsEntry("status", "done").containsEntry("s", String.join(",", ALL));

        // region pinned + the branch event (ids only)
        JdbcTemplate su = su();
        assertThat(su.queryForObject("SELECT count(*) FROM outbox_cloudevents WHERE tenant_id = ? AND envelope ->> 'type' = 'organization.tenant.region_pinned.v1' "
                + "AND envelope -> 'data' ->> 'regionCode' = 'IN'", Long.class, branch)).isEqualTo(1);
        Map<String, Object> data = (Map<String, Object>) objectMapper.readValue(su.queryForObject("SELECT envelope::text FROM outbox_cloudevents "
                + "WHERE tenant_id = ? AND envelope ->> 'type' = 'organization.branch.created.v1'", String.class, branch), Map.class).get("data");
        assertThat(data.keySet()).containsExactlyInAnyOrder("tenantId", "ownerId", "brandId", "sourceTenantId", "version");
        assertThat(data).containsEntry("tenantId", branch.toString()).containsEntry("ownerId", o.owner().id().toString())
                .containsEntry("brandId", o.brand().id().toString()).containsEntry("sourceTenantId", src.toString());
        assertThat(inTenantTx(src, () -> jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE tenant_id = ? AND action = 'setup.branch_add_started' "
                + "AND entity_id = ?", Long.class, src, jobId))).isEqualTo(1);

        // one login: the same owner account enters the new branch with the built-in Owner role there and reads its own (empty) data
        String inBranch = workspace(o.owner(), branch);
        assertThat(exchange("/v1/staff", HttpMethod.GET, authed(inBranch), Map.class).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(count(branch, "staff")).isEqualTo(1);
        assertThat(strings(branch, "SELECT r.name FROM staff s JOIN roles r ON r.id = s.role_id WHERE s.tenant_id = ? AND r.built_in")).hasSize(1);
        assertThat((List<?>) list(inBranch).get("items")).hasSize(2); // the owner sees both branches from either one
    }

    // AC4: a plan that allows one branch answers 402 PLAN_LIMIT with the upgrade link, before any confirmation, and starts nothing.
    @Test
    void planLimit_402WithUpgradeLink_andNoJob() throws Exception {
        Owned o = owned();
        String op = platformLoginAndGetAccessToken(seedOperator("sa-" + UUID.randomUUID() + "@nabd.internal", "super_admin", false));
        String code = "solo-" + tag();
        assertThat(exchange("/v1/platform/plans", HttpMethod.POST, authedJsonBody(op, Map.of("code", code, "name", "Solo", "monthlyPriceCents", 50000,
                "currency", "INR", "active", true, "modules", List.of("billing"), "limits", Map.of("seats", 10, "branches", 1))), Map.class)
                .getStatusCode()).isEqualTo(HttpStatus.CREATED);
        UUID planId = jdbc.queryForObject("SELECT id FROM master.plans WHERE code = ?", UUID.class, code);
        assertThat(exchange("/v1/platform/billing/subscriptions/" + o.clinic().id(), HttpMethod.POST, authedJsonBody(op, Map.of("planId", planId.toString(),
                "mrrCents", 50000, "renewalDate", LocalDate.now().plusDays(20).toString())), Map.class).getStatusCode()).isEqualTo(HttpStatus.OK);

        assertThat(list(o.token())).containsEntry("branchLimit", 1).containsEntry("branchesInUse", 1);
        ResponseEntity<Map> r = add(o.token(), null, UUID.randomUUID().toString(), body("B2", o.clinic().id()));
        assertThat(r.getStatusCode()).as(String.valueOf(r.getBody())).isEqualTo(HttpStatus.PAYMENT_REQUIRED);
        assertThat(r.getBody().get("type")).asString().endsWith("PLAN_LIMIT");
        assertThat(r.getBody()).containsEntry("upgradeUrl", "/plan");
        assertThat(jobsOf(o.owner())).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM tenants WHERE brand_id = ?", Long.class, o.brand().id())).isEqualTo(1);
    }

    // AC5: a step fails -> its transaction rolled back (no half-made clinic), the owner sees which step, retry finishes the job.
    @Test
    @SuppressWarnings("unchecked")
    void aFailedStepIsShownToTheOwner_nothingHalfMade_andRetryFinishesTheBranch() throws Exception {
        Owned o = owned();
        UUID jobId;
        UUID branch;
        UUID squatter = UUID.randomUUID();
        try (Connection lock = superuserConnection()) {
            // Hold tenants against INSERT so create_tenant waits; then take the job's sign-in code, so its INSERT fails when released.
            lock.setAutoCommit(false);
            try (Statement st = lock.createStatement()) {
                st.execute("LOCK TABLE public.tenants IN SHARE ROW EXCLUSIVE MODE");
                ResponseEntity<Map> r = add(o, "F1", o.clinic().id());
                assertThat(r.getStatusCode()).as(String.valueOf(r.getBody())).isEqualTo(HttpStatus.ACCEPTED);
                jobId = UUID.fromString((String) r.getBody().get("provisioningJobId"));
                branch = UUID.fromString((String) r.getBody().get("branchTenantId"));
                st.execute("INSERT INTO public.tenants (id, slug, name, region_code, status, timezone) VALUES ('" + squatter + "', '"
                        + o.clinic().slug() + "-f1', 'Squatter', 'IN', 'active', 'UTC')");
            }
            lock.commit();
        }
        assertThat(awaitJob(jobId, "failed", "done", "rolled_back")).isEqualTo("failed");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM tenants WHERE id = ?", Long.class, branch)).isZero();

        Map<String, Object> job = ((List<Map<String, Object>>) list(o.token()).get("jobs")).get(0);
        assertThat(job).containsEntry("provisioningJobId", jobId.toString()).containsEntry("branchTenantId", branch.toString())
                .containsEntry("status", "failed").containsEntry("failedStep", "create_tenant").containsEntry("branchCode", "F1");
        assertThat(job.toString()).doesNotContain("errorDetail").doesNotContain("duplicate"); // the step, never the database's words
        assertThat(((List<Map<String, Object>>) job.get("steps")).stream().map(s -> s.get("stepName") + ":" + s.get("status")).toList())
                .containsExactly("create_tenant:failed", "seed_masters:queued", "copy_config:queued", "provision_whatsapp:queued", "go_live:queued");

        // another owner cannot retry (or see) this job
        Owned stranger = owned();
        assertThat(exchange("/v1/owner/branches/jobs/" + jobId + "/retry", HttpMethod.POST, authed(stranger.token()), Map.class).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat((List<?>) list(stranger.token()).get("jobs")).isEmpty();

        su().update("UPDATE tenants SET slug = ? WHERE id = ?", "squat-" + tag(), squatter); // frees the sign-in code
        ResponseEntity<Map> retry = exchange("/v1/owner/branches/jobs/" + jobId + "/retry", HttpMethod.POST, authed(o.token()), Map.class);
        assertThat(retry.getStatusCode()).as(String.valueOf(retry.getBody())).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(awaitJob(jobId, "done", "rolled_back")).isEqualTo("done");
        assertThat(jdbc.queryForObject("SELECT status FROM tenants WHERE id = ?", String.class, branch)).isEqualTo("trialing");
        assertThat((List<?>) list(o.token()).get("jobs")).isEmpty();
    }

    // AC5 (undo) + the bundle round trip: applying the same bundle twice changes nothing; undoing every step leaves no row behind.
    @Test
    void bundleRoundTripIsIdempotent_andUndoingEveryStepRemovesTheBranchCompletely() {
        Owned o = owned();
        configure(o);
        UUID jobId = UUID.randomUUID();
        Branch b = new Branch(o.clinic().id(), UUID.randomUUID(), UUID.randomUUID(), "U1", "1 Undo Street", "Asia/Kolkata", ALL);
        inTenantTx(o.clinic().id(), () -> {
            repo.insertBranchJob(jobId, b, o.clinic().slug() + "-u1", "Undo Branch", "IN", o.owner().email(), "Test Owner", o.brand().name(), UUID.randomUUID());
            List<String> steps = List.of("create_tenant", "seed_masters", "copy_config");
            for (int i = 0; i < steps.size(); i++) repo.insertStep(UUID.randomUUID(), jobId, steps.get(i), i + 1);
        });
        stepRunner.run(repo.findJob(jobId).orElseThrow(), "create_tenant");
        Job job = repo.findJob(jobId).orElseThrow();
        UUID branch = job.createdTenantId();
        assertThat(branch).isEqualTo(b.plannedTenantId());
        stepRunner.run(job, "seed_masters");
        ConfigBundle.Bundle bundle = configBundle.export(o.clinic().id(), ALL);
        assertThat(bundle.rows().keySet()).containsExactly("departments", "service_points", "department_workflow_selection", "charge_catalogue",
                "clinic_policies", "roles", "clinic_holidays", "clinic_quiet_hours", "tenant_messaging_settings"); // no patient/staff/clinical table
        stepRunner.run(job, "copy_config", bundle);

        List<String> tables = List.of("departments", "service_points", "charge_catalogue", "clinic_policies", "roles", "clinic_holidays",
                "clinic_quiet_hours", "whatsapp_templates");
        List<Long> first = tables.stream().map(t -> count(branch, t)).toList();
        assertThat(first).allMatch(n -> n > 0);
        inTenantTx(branch, () -> configBundle.apply(branch, bundle)); // the same bundle again
        assertThat(tables.stream().map(t -> count(branch, t)).toList()).isEqualTo(first);

        inTenantTx(o.clinic().id(), () -> {
            for (String s : List.of("create_tenant", "seed_masters", "copy_config")) {
                jdbc.update("UPDATE master.provisioning_job_steps SET status = 'done' WHERE job_id = ? AND step_name = ?", jobId, s);
            }
        });
        provisioning.rollback(jobId, job, null, null);
        assertThat(jobStatus(jobId)).isEqualTo("rolled_back");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM tenants WHERE id = ?", Long.class, branch)).isZero();
        JdbcTemplate su = su();
        for (String t : List.of("departments", "service_points", "charge_catalogue", "clinic_policies", "roles", "clinic_holidays", "clinic_quiet_hours",
                "whatsapp_templates", "config_bundle_imports")) {
            assertThat(su.queryForObject("SELECT count(*) FROM " + t + " WHERE tenant_id = ?", Long.class, branch)).as(t).isZero();
        }
        // the owner, the brand and the source clinic are untouched
        assertThat(jdbc.queryForObject("SELECT count(*) FROM tenants WHERE brand_id = ?", Long.class, o.brand().id())).isEqualTo(1);
        assertThat(count(o.clinic().id(), "charge_catalogue")).isEqualTo(2);
    }

    // Owner only, step-up bound to the branch code and used once, Idempotency-Key replay, and never across owners.
    @Test
    @SuppressWarnings("unchecked")
    void onlyTheBrandsOwner_withAStepUpForThisCode_andOnlyFromTheirOwnClinics() throws Exception {
        Owned o = owned();
        Owned other = owned();
        UUID src = o.clinic().id();

        // a manager with setup rights who is not the owner: list and add are refused
        SeededStaff manager = seedStaff(o.clinic(), seedRole(src, "Manager", false, fullGrant("setup")), "mgr-" + tag() + "@plt022.test",
                "+9195" + (10000000 + (int) (Math.random() * 8999999)), false);
        String mgr = loginAndGetAccessToken(manager);
        assertThat(exchange("/v1/owner/branches", HttpMethod.GET, authed(mgr), Map.class).getBody().get("type")).asString().endsWith("NOT_OWNER");
        ResponseEntity<Map> byManager = add(mgr, stepUp(mgr, "branch-add:m1"), UUID.randomUUID().toString(), body("M1", src));
        assertThat(byManager.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(byManager.getBody().get("type")).asString().endsWith("NOT_OWNER");
        // a staff member holding the built-in Owner role who is not the brand's owner account is refused too
        UUID ownerRole = inTenantTx(src, () -> jdbc.queryForObject("SELECT id FROM roles WHERE tenant_id = ? AND built_in", UUID.class, src));
        SeededStaff coOwner = seedStaff(o.clinic(), ownerRole, "co-" + tag() + "@plt022.test", "+9195" + (10000000 + (int) (Math.random() * 8999999)), false);
        assertThat(exchange("/v1/owner/branches", HttpMethod.GET, authed(loginAndGetAccessToken(coOwner)), Map.class).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        // no step-up, and a step-up confirmed for another branch code
        assertThat(add(o.token(), null, UUID.randomUUID().toString(), body("S1", src)).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(add(o.token(), stepUp(o.token(), "branch-add:other"), UUID.randomUUID().toString(), body("S1", src)).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        // invalid input
        Map<String, Object> badZone = body("S1", src);
        badZone.put("timezone", "IST");
        assertThat(add(o.token(), stepUp(o.token(), "branch-add:s1"), UUID.randomUUID().toString(), badZone).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        Map<String, Object> badSection = body("S1", src);
        badSection.put("copy", List.of("patients"));
        assertThat(add(o.token(), stepUp(o.token(), "branch-add:s1"), UUID.randomUUID().toString(), badSection).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        // another owner's clinic as the source answers like one that does not exist
        assertThat(add(o, "S1", other.clinic().id()).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(jobsOf(o.owner())).isZero();
        assertThat(jobsOf(other.owner())).isZero();

        // the real one; the same Idempotency-Key replays the same answer and starts nothing new
        String su = stepUp(o.token(), "branch-add:s1");
        String key = UUID.randomUUID().toString();
        ResponseEntity<Map> first = add(o.token(), su, key, body("S1", src));
        assertThat(first.getStatusCode()).as(String.valueOf(first.getBody())).isEqualTo(HttpStatus.ACCEPTED);
        ResponseEntity<Map> replay = add(o.token(), su, key, body("S1", src));
        assertThat(replay.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(replay.getBody()).isEqualTo(first.getBody());
        // the used confirmation with a new key: the code is taken (and the confirmation is spent), still one job
        ResponseEntity<Map> again = add(o.token(), su, UUID.randomUUID().toString(), body("S1", src));
        assertThat(again.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(again.getBody().get("type")).asString().endsWith("BRANCH_CODE_TAKEN");
        assertThat(jobsOf(o.owner())).isEqualTo(1);
        UUID jobId = UUID.fromString((String) first.getBody().get("provisioningJobId"));
        UUID branch = UUID.fromString((String) first.getBody().get("branchTenantId"));
        assertThat(awaitJob(jobId, "done", "failed", "rolled_back")).isEqualTo("done");
        assertThat(jdbc.queryForObject("SELECT brand_id FROM tenants WHERE id = ?", UUID.class, branch)).isEqualTo(o.brand().id());

        // the other owner sees only their own clinic, and cannot copy from the new branch either
        assertThat(((List<Map<String, Object>>) list(other.token()).get("items")).stream().map(i -> i.get("tenantId")).toList())
                .containsExactly(other.clinic().id().toString());
        assertThat(add(other, "X1", branch).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(jobsOf(other.owner())).isZero();
    }
}
