package com.nabd.hms.platform.provisioning;

import com.nabd.hms.common.EmailSender;
import com.nabd.hms.common.WabaProvisioningGateway;
import com.nabd.hms.platform.PlatformAudit;
import com.nabd.hms.platform.provisioning.dto.ProvisioningJobResponse;
import com.nabd.hms.support.ApiTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * S12 QA-2, PLT-058 (provisioning steps run exactly once), from the story's acceptance criteria. The WhatsApp gateway
 * (step 4, a real external effect) is replaced by a scripted test double so a step can block, fail transiently or
 * fail fatally through the normal HTTP advance path, and so we can count how often the external effect really ran.
 */
@SuppressWarnings({"rawtypes", "unchecked"})
class S12QaPlt058ProvisioningOnceTest extends ApiTestBase {

    /** Scripted gateway: slugs containing "block" wait on LATCH, "fatal" fail fatally, "flaky" fail once. Counts calls per slug. */
    static final Map<String, AtomicInteger> CALLS = new ConcurrentHashMap<>();
    static volatile CountDownLatch latch = new CountDownLatch(0);

    @TestConfiguration
    static class Gateway {
        @Bean
        @Primary
        WabaProvisioningGateway scriptedWaba() {
            return slug -> {
                int n = CALLS.computeIfAbsent(slug, k -> new AtomicInteger()).incrementAndGet();
                if (slug.contains("block")) {
                    try {
                        latch.await(20, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
                if (slug.contains("fatal")) throw new FatalProvisioningException("qa2: number pool permanently refused");
                if (slug.contains("flaky") && n == 1) throw new IllegalStateException("qa2: provider timeout");
            };
        }
    }

    @Autowired ProvisioningRepository repo;
    @Autowired ProvisioningStepRunner stepRunner;
    @Autowired ProvisioningReaper reaper;
    @Autowired PlatformAudit audit;
    @Autowired RealDataOnboardingGuard guard;
    @Autowired EmailSender emailSender;

    private final JdbcTemplate su = new JdbcTemplate(superuserDataSource());

    // ================= AC1: concurrent advance -> one execution, the others 409 =================

    @Test
    void ac1_sixSimultaneousAdvancesRunTheExternalStepOnceFiveGet409AndAnotherJobIsNotBlocked() throws Exception {
        String token = operatorToken("super_admin");
        String slug = "qa2-block-" + tag();
        UUID job = createJob(token, slug);
        for (int i = 0; i < 3; i++) assertThat(advance(token, job).getStatusCode()).isEqualTo(HttpStatus.OK); // up to provision_whatsapp
        UUID other = createJob(token, "qa2-free-" + tag());

        latch = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(6);
        CompletionService<ResponseEntity<Map>> cs = new ExecutorCompletionService<>(pool);
        CountDownLatch start = new CountDownLatch(1);
        try {
            for (int i = 0; i < 6; i++) {
                cs.submit(() -> {
                    start.await();
                    return advance(token, job);
                });
            }
            start.countDown();
            List<ResponseEntity<Map>> early = new ArrayList<>();
            for (int i = 0; i < 5; i++) {
                Future<ResponseEntity<Map>> f = cs.poll(15, TimeUnit.SECONDS);
                assertThat(f).as("five losers answer while the winner is still inside the step").isNotNull();
                early.add(f.get());
            }
            assertThat(early).allSatisfy(r -> {
                assertThat(r.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
                assertThat(String.valueOf(r.getBody().get("type"))).endsWith("STEP_ALREADY_RUNNING");
                assertThat(r.getHeaders().getContentType().toString()).startsWith("application/problem+json");
            });
            // isolation: a different job advances normally while this one's step is running
            assertThat(advance(token, other).getStatusCode()).isEqualTo(HttpStatus.OK);
            latch.countDown();
            ResponseEntity<Map> winner = cs.poll(20, TimeUnit.SECONDS).get();
            assertThat(winner.getStatusCode()).isEqualTo(HttpStatus.OK);
        } finally {
            latch.countDown();
            pool.shutdownNow();
        }
        assertThat(CALLS.get(slug).get()).as("external WhatsApp provisioning ran once").isEqualTo(1);
        List<String> statuses = repo.findSteps(job).stream().map(ProvisioningModels.JobStep::status).toList();
        assertThat(statuses).containsExactly("done", "done", "done", "done", "queued", "queued");
    }

    @Test
    void ac1_stepAlreadyRunningForAnotherOperatorIs409AndChangesNothing() {
        String token = operatorToken("super_admin");
        UUID job = createJob(token, "qa2-held-" + tag());
        su.update("UPDATE master.provisioning_job_steps SET status = 'running', started_at = now() WHERE job_id = ? AND step_order = 1", job);
        ResponseEntity<Map> r = advance(operatorToken("implementation"), job);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(String.valueOf(r.getBody().get("type"))).endsWith("STEP_ALREADY_RUNNING");
        assertThat(r.getBody().get("status")).isEqualTo(409);
        assertThat(repo.findJob(job).orElseThrow().createdTenantId()).isNull();
        assertThat(repo.findSteps(job).get(0).status()).isEqualTo("running");
    }

    @Test
    void ac1_operatorWithoutProvisioningPermissionAndClinicStaffCannotAdvance() {
        String token = operatorToken("super_admin");
        UUID job = createJob(token, "qa2-perm-" + tag());
        assertThat(advance(operatorToken("billing"), job).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        SeededTenant t = seedTenant();
        String staff = loginAndGetAccessToken(seedStaff(t, seedFullAccessRole(t.id()), "qa2-p@a.com", "+919320000001", false));
        assertThat(advance(staff, job).getStatusCode()).isIn(HttpStatus.UNAUTHORIZED, HttpStatus.FORBIDDEN);
        assertThat(repo.findSteps(job).get(0).status()).isEqualTo("queued");
    }

    // ================= AC2: crash after effect, before done -> retry reuses the effect =================

    @Test
    void ac2_crashAfterCreateTenantAndAfterWhatsappEffectsRetriesWithoutDuplicates() {
        String token = operatorToken("super_admin");
        String slug = "qa2-crash-" + tag();
        UUID job = createJob(token, slug);
        for (int i = 0; i < 4; i++) assertThat(advance(token, job).getStatusCode()).isEqualTo(HttpStatus.OK);
        UUID tenantId = repo.findJob(job).orElseThrow().createdTenantId();
        List<ProvisioningModels.JobStep> steps = repo.findSteps(job);

        // node died after provision_whatsapp's effect committed but before "done": the step is left running
        su.update("UPDATE master.provisioning_job_steps SET status = 'running', completed_at = NULL, started_at = now() - interval '11 minutes' WHERE id = ?",
                steps.get(3).id());
        su.update("UPDATE master.provisioning_jobs SET status = 'running' WHERE id = ?", job);
        assertThat(advance(token, job).getStatusCode()).as("still running until reaped").isEqualTo(HttpStatus.CONFLICT);
        reaper.reap();
        ResponseEntity<Map> retried = advance(token, job);
        assertThat(retried.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(stepStatus(retried, 3)).isEqualTo("done");
        assertThat(CALLS.get(slug).get()).as("the WhatsApp number was not provisioned twice").isEqualTo(1);

        // the same for create_tenant (the oldest step)
        su.update("UPDATE master.provisioning_job_steps SET status = 'failed', error_detail = 'timeout' WHERE id = ?", steps.get(0).id());
        assertThat(advance(token, job).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(repo.findSteps(job).get(0).status()).isEqualTo("done");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM tenants WHERE slug = ?::citext", Integer.class, slug)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM owners WHERE email = ?::citext", Integer.class, owner(slug))).isEqualTo(1);
        assertThat(repo.findJob(job).orElseThrow().createdTenantId()).isEqualTo(tenantId);

        Map body = null;
        for (int i = 0; i < 3; i++) body = advance(token, job).getBody();
        assertThat(body.get("status")).isEqualTo("done");
    }

    @Test
    void ac2_negativeATransientFailureCommitsNoEffectSoTheRetryReallyRunsItAndNothingIsRolledBack() {
        String token = operatorToken("super_admin");
        String slug = "qa2-flaky-" + tag();
        UUID job = createJob(token, slug);
        for (int i = 0; i < 3; i++) advance(token, job);
        ResponseEntity<Map> failed = advance(token, job);
        assertThat(failed.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(failed.getBody().get("status")).isEqualTo("failed");
        assertThat(stepStatus(failed, 3)).isEqualTo("failed");
        UUID tenantId = repo.findJob(job).orElseThrow().createdTenantId();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM tenants WHERE id = ?", Integer.class, tenantId)).as("no compensation on a transient failure").isEqualTo(1);
        ResponseEntity<Map> retried = advance(token, job);
        assertThat(stepStatus(retried, 3)).isEqualTo("done");
        assertThat(CALLS.get(slug).get()).isEqualTo(2);
    }

    // ================= AC3: listJobs is one query, < 200 ms for 200 jobs =================

    @Test
    void ac3_listOf200JobsIsOneStatementP95Under200msAndKeepsAJobWithoutSteps() throws Exception {
        SeededOperator op = seedOperator("qa2-list-" + UUID.randomUUID() + "@nabd.health", "super_admin", false);
        String token = platformLoginAndGetAccessToken(op);
        String t = tag();
        su.update("WITH j AS (INSERT INTO master.provisioning_jobs (requested_by, tenant_slug, tenant_name, region, owner_email, owner_name, brand_name) "
                + "SELECT ?, 'qa2-list-' || g || '-' || ?, 'QA2 ' || g, 'IN', 'qa2-list-' || g || '-' || ? || '@nabd.health', 'Owner', 'Brand' "
                + "FROM generate_series(1, 200) g RETURNING id) "
                + "INSERT INTO master.provisioning_job_steps (job_id, step_name, step_order) SELECT j.id, s.name, s.ord FROM j CROSS JOIN "
                + "unnest(ARRAY['create_tenant','migrate_schema','seed_masters','provision_whatsapp','verify_invite_owner','go_live']) WITH ORDINALITY AS s(name, ord)",
                op.id(), t, t);
        su.update("INSERT INTO master.provisioning_jobs (requested_by, tenant_slug, tenant_name, region, owner_email, owner_name, brand_name) "
                + "VALUES (?, ?, 'QA2 empty', 'IN', ?, 'Owner', 'Brand')", op.id(), "qa2-empty-" + t, "qa2-empty-" + t + "@nabd.health");

        AtomicInteger statements = new AtomicInteger();
        ProvisioningService counted = new ProvisioningService(new ProvisioningRepository(new JdbcTemplate(counting(jdbc.getDataSource(), statements))),
                stepRunner, audit, guard, emailSender);
        List<ProvisioningJobResponse> all = counted.listJobs();
        assertThat(statements.get()).as("SQL statements for one listJobs").isEqualTo(1);
        assertThat(all.stream().filter(j -> j.tenantSlug().startsWith("qa2-list-") && j.tenantSlug().endsWith(t))).hasSize(200)
                .allSatisfy(j -> assertThat(j.steps()).extracting(s -> s.stepOrder()).containsExactly(1, 2, 3, 4, 5, 6));
        assertThat(all.stream().filter(j -> j.tenantSlug().equals("qa2-empty-" + t)).findFirst().orElseThrow().steps()).isEmpty();

        long[] ms = new long[20];
        for (int i = -3; i < ms.length; i++) {
            long t0 = System.nanoTime();
            ResponseEntity<List> r = exchange("/v1/platform/provisioning-jobs", HttpMethod.GET, authed(token), List.class);
            long took = (System.nanoTime() - t0) / 1_000_000;
            assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(r.getBody().size()).isGreaterThanOrEqualTo(201);
            if (i >= 0) ms[i] = took;
        }
        Arrays.sort(ms);
        System.out.println("QA2 PLT-058 listJobs ms sorted: " + Arrays.toString(ms));
        assertThat(ms[18]).as("p95 ms over HTTP").isLessThan(200);
        assertThat(exchange("/v1/platform/provisioning-jobs", HttpMethod.GET, authed(operatorToken("billing")), String.class).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    // ================= AC4: failing step -> compensations in reverse, job rolled_back =================

    @Test
    void ac4_fatalFailureAtStepFourUndoesStepsThreeTwoOneInThatOrderAndTheJobIsTerminal() throws Exception {
        SeededOperator op = seedOperator("qa2-undo-" + UUID.randomUUID() + "@nabd.health", "super_admin", false);
        String token = platformLoginAndGetAccessToken(op);
        String slug = "qa2-fatal-" + tag();
        UUID job = createJob(token, slug);
        for (int i = 0; i < 3; i++) assertThat(advance(token, job).getStatusCode()).isEqualTo(HttpStatus.OK);
        UUID tenantId = repo.findJob(job).orElseThrow().createdTenantId();
        assertThat(su.queryForObject("SELECT count(*) FROM roles WHERE tenant_id = ?", Integer.class, tenantId)).isPositive();

        ResponseEntity<Map> r = advance(token, job);
        assertThat(r.getBody().get("status")).isEqualTo("rolled_back");
        List<Map<String, Object>> steps = (List<Map<String, Object>>) r.getBody().get("steps");
        assertThat(steps).extracting(s -> s.get("status")).containsExactly("rolled_back", "rolled_back", "rolled_back", "failed", "queued", "queued");
        assertThat(String.valueOf(steps.get(3).get("errorDetail"))).contains("permanently refused");
        assertThat(su.queryForObject("SELECT count(*) FROM tenants WHERE id = ?", Integer.class, tenantId)).isZero();
        assertThat(su.queryForObject("SELECT count(*) FROM roles WHERE tenant_id = ?", Integer.class, tenantId)).isZero();
        assertThat(su.queryForObject("SELECT count(*) FROM owners WHERE email = ?::citext", Integer.class, owner(slug))).as("new owner removed").isZero();

        List<Map<String, Object>> rows = new JdbcTemplate(superuserDataSource()).queryForList("SELECT after::text AS after FROM search_audit_log("
                + "NULL, 'platform_provisioning.job_rolled_back', NULL, NULL, NULL, ?::uuid, NULL, NULL, NULL, NULL, 10)", op.id());
        assertThat(rows).hasSize(1);
        Map after = objectMapper.readValue((String) rows.get(0).get("after"), Map.class);
        assertThat((List<String>) after.get("compensated")).containsExactly("seed_masters", "migrate_schema", "create_tenant");

        ResponseEntity<Map> again = advance(token, job);
        assertThat(again.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(String.valueOf(again.getBody().get("type"))).endsWith("JOB_TERMINAL");
        assertThat(CALLS.get(slug).get()).isEqualTo(1);
    }

    @Test
    void ac4_boundaryRollbackKeepsAnOwnerAccountThatExistedBeforeTheJob() {
        String token = operatorToken("super_admin");
        String slug = "qa2-fatal-own-" + tag();
        SeededOwner existing = seedOwner(owner(slug));
        UUID job = createJob(token, slug);
        for (int i = 0; i < 4; i++) advance(token, job);
        assertThat(repo.findJob(job).orElseThrow().status()).isEqualTo("rolled_back");
        assertThat(su.queryForObject("SELECT count(*) FROM owners WHERE id = ?", Integer.class, existing.id())).isEqualTo(1);
    }

    // ================= AC5: reaper =================

    @Test
    void ac5_reaperFailsOnlyStepsRunningOverTenMinutesWithTimeoutAndTheJobIsRetryable() {
        String token = operatorToken("super_admin");
        UUID stuck = createJob(token, "qa2-stuck-" + tag());
        UUID young = createJob(token, "qa2-young-" + tag());
        UUID finished = createJob(token, "qa2-old-done-" + tag());
        advance(token, finished);
        su.update("UPDATE master.provisioning_job_steps SET started_at = now() - interval '2 hours' WHERE job_id = ? AND step_order = 1", finished);
        for (UUID j : List.of(stuck, young)) {
            su.update("UPDATE master.provisioning_job_steps SET status = 'running' WHERE job_id = ? AND step_order = 1", j);
            su.update("UPDATE master.provisioning_jobs SET status = 'running' WHERE id = ?", j);
        }
        su.update("UPDATE master.provisioning_job_steps SET started_at = now() - interval '10 minutes 30 seconds' WHERE job_id = ? AND step_order = 1", stuck);
        su.update("UPDATE master.provisioning_job_steps SET started_at = now() - interval '9 minutes 30 seconds' WHERE job_id = ? AND step_order = 1", young);

        reaper.reap();

        ProvisioningModels.JobStep s = repo.findSteps(stuck).get(0);
        assertThat(s.status()).isEqualTo("failed");
        assertThat(s.errorDetail()).isEqualTo("timeout");
        assertThat(repo.findJob(stuck).orElseThrow().status()).isEqualTo("failed");
        assertThat(repo.findSteps(young).get(0).status()).as("9 m 30 s is not stuck yet").isEqualTo("running");
        assertThat(repo.findSteps(finished).get(0).status()).as("done steps are never reaped").isEqualTo("done");

        ResponseEntity<Map> retry = advance(token, stuck);
        assertThat(retry.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(stepStatus(retry, 0)).isEqualTo("done");
        assertThat(advance(token, young).getStatusCode()).as("the young one is still held").isEqualTo(HttpStatus.CONFLICT);
    }

    // ================= helpers =================

    private static String stepStatus(ResponseEntity<Map> r, int index) {
        return String.valueOf(((List<Map<String, Object>>) r.getBody().get("steps")).get(index).get("status"));
    }

    private static DataSource counting(DataSource ds, AtomicInteger statements) {
        ClassLoader cl = S12QaPlt058ProvisioningOnceTest.class.getClassLoader();
        return (DataSource) Proxy.newProxyInstance(cl, new Class<?>[] {DataSource.class}, (p, m, a) -> {
            Object r = invoke(m, ds, a);
            if (!(r instanceof Connection c)) return r;
            return Proxy.newProxyInstance(cl, new Class<?>[] {Connection.class}, (p2, m2, a2) -> {
                if (m2.getName().startsWith("prepare") || m2.getName().equals("createStatement")) statements.incrementAndGet();
                return invoke(m2, c, a2);
            });
        });
    }

    private static Object invoke(java.lang.reflect.Method m, Object target, Object[] args) throws Throwable {
        try {
            return m.invoke(target, args);
        } catch (InvocationTargetException e) {
            throw e.getCause();
        }
    }

    private String operatorToken(String role) {
        return platformLoginAndGetAccessToken(seedOperator("qa2-" + role + "-" + UUID.randomUUID() + "@nabd.health", role, false));
    }

    private ResponseEntity<Map> advance(String token, UUID jobId) {
        return exchange("/v1/platform/provisioning-jobs/" + jobId + "/advance", HttpMethod.POST, authed(token), Map.class);
    }

    private UUID createJob(String token, String slug) {
        ResponseEntity<Map> resp = exchange("/v1/platform/provisioning-jobs", HttpMethod.POST, authedJsonBody(token, Map.of(
                "tenantSlug", slug, "tenantName", "QA2 Clinic " + slug, "region", "IN", "ownerEmail", owner(slug),
                "ownerName", "QA2 Owner", "ownerMobile", "+9198765" + (10000 + Math.abs(slug.hashCode() % 89999)),
                "brandName", "QA2 Brand " + slug, "path", "self_serve")), Map.class);
        assertThat(resp.getStatusCode()).as(String.valueOf(resp.getBody())).isEqualTo(HttpStatus.CREATED);
        return UUID.fromString((String) resp.getBody().get("id"));
    }

    private static String owner(String slug) {
        return "owner-" + slug + "@nabd.health";
    }

    private static String tag() {
        return UUID.randomUUID().toString().substring(0, 8);
    }
}
