package com.nabd.hms.platform.provisioning;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nabd.hms.common.EmailSender;
import com.nabd.hms.platform.PlatformAudit;
import com.nabd.hms.platform.provisioning.dto.ProvisioningJobResponse;
import com.nabd.hms.support.ApiTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static com.nabd.hms.platform.provisioning.ProvisioningModels.Job;
import static com.nabd.hms.platform.provisioning.ProvisioningModels.JobStep;
import static org.assertj.core.api.Assertions.assertThat;

/** PLT-058: CAS step claims, per-step idempotency key, one-query listJobs, reverse-order rollback, stuck-step reaper. */
class Plt058ProvisioningCasApiTest extends ApiTestBase {

    @Autowired ProvisioningRepository repo;
    @Autowired ProvisioningStepRunner stepRunner;
    @Autowired ProvisioningService service;
    @Autowired ProvisioningReaper reaper;
    @Autowired PlatformAudit audit;
    @Autowired RealDataOnboardingGuard guard;
    @Autowired EmailSender emailSender;
    @Autowired ObjectMapper objectMapper;

    // AC1: two concurrent advance calls -> exactly one step execution, the other 409 STEP_ALREADY_RUNNING.
    @Test
    void twoConcurrentAdvancesRunTheStepOnceAndTheOtherGets409() throws Exception {
        SeededOperator op = operator();
        String token = platformLoginAndGetAccessToken(op);
        String slug = "plt058-race-" + tag();
        UUID jobId = createJob(token, slug);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try (Connection lock = superuserConnection()) {
            // Hold owners against INSERT so whichever call claims create_tenant blocks inside the step until we let go.
            lock.setAutoCommit(false);
            try (Statement st = lock.createStatement()) {
                st.execute("LOCK TABLE public.owners IN SHARE ROW EXCLUSIVE MODE");
            }
            CountDownLatch start = new CountDownLatch(1);
            List<CompletableFuture<ResponseEntity<Map>>> calls = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                calls.add(CompletableFuture.supplyAsync(() -> {
                    try {
                        start.await();
                    } catch (InterruptedException e) {
                        throw new IllegalStateException(e);
                    }
                    return exchange("/v1/platform/provisioning-jobs/" + jobId + "/advance", HttpMethod.POST, authed(token), Map.class);
                }, pool));
            }
            start.countDown();
            ResponseEntity<Map> first = (ResponseEntity<Map>) CompletableFuture.anyOf(calls.toArray(CompletableFuture[]::new))
                    .get(4, TimeUnit.SECONDS);
            assertThat(first.getStatusCode()).as(String.valueOf(first.getBody())).isEqualTo(HttpStatus.CONFLICT);
            assertThat(first.getBody().get("type")).asString().endsWith("STEP_ALREADY_RUNNING");
            assertThat(calls.stream().filter(CompletableFuture::isDone).count()).isEqualTo(1); // the winner is still inside the step
            lock.rollback();
        } finally {
            pool.shutdown();
        }
        assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();

        List<JobStep> steps = repo.findSteps(jobId);
        assertThat(steps.get(0).status()).isEqualTo("done");
        assertThat(steps.subList(1, 6)).allMatch(s -> "queued".equals(s.status())); // exactly one step ran
        assertThat(jdbc.queryForObject("SELECT count(*) FROM tenants WHERE slug = ?::citext", Integer.class, slug)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM owners WHERE email = ?::citext", Integer.class, owner(slug))).isEqualTo(1);
        assertThat(auditRows(op.id(), "platform_provisioning.step_run")).hasSize(1);
    }

    // AC2: crash after the effect committed but before "done" -> retry detects the idempotency key, marks done, no duplicate.
    // AC5: a step running > 10 min is failed by the reaper with error_detail 'timeout' and the job can be retried.
    @Test
    void aCrashedStepIsReapedAfterTenMinutesAndTheRetryReusesItsCommittedEffect() {
        String token = platformLoginAndGetAccessToken(operator());
        String slug = "plt058-crash-" + tag();
        UUID jobId = createJob(token, slug);
        Job job = repo.findJob(jobId).orElseThrow();
        UUID stepId = repo.findSteps(jobId).get(0).id();

        // What advance() does up to the crash: claim, run the step's transaction (effect commits), then the node dies.
        assertThat(repo.claimStep(stepId)).isTrue();
        repo.updateJobStatus(jobId, "running");
        stepRunner.run(job, "create_tenant");
        UUID tenantId = repo.findJob(jobId).orElseThrow().createdTenantId();
        assertThat(tenantId).isNotNull();

        ResponseEntity<Map> held = advance(token, jobId);
        assertThat(held.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(held.getBody().get("type")).asString().endsWith("STEP_ALREADY_RUNNING");

        backdate(stepId, 9); // boundary: under 10 minutes is left alone
        reaper.reap();
        assertThat(repo.findSteps(jobId).get(0).status()).isEqualTo("running");

        backdate(stepId, 11);
        assertThat(reaper.reap()).isGreaterThanOrEqualTo(1);
        JobStep reaped = repo.findSteps(jobId).get(0);
        assertThat(reaped.status()).isEqualTo("failed");
        assertThat(reaped.errorDetail()).isEqualTo("timeout");
        assertThat(repo.findJob(jobId).orElseThrow().status()).isEqualTo("failed");

        ResponseEntity<Map> retried = advance(token, jobId);
        assertThat(retried.getStatusCode()).as(String.valueOf(retried.getBody())).isEqualTo(HttpStatus.OK);
        List<Map<String, Object>> steps = (List<Map<String, Object>>) retried.getBody().get("steps");
        assertThat(steps.get(0).get("status")).isEqualTo("done");
        assertThat(steps.get(0).get("errorDetail")).isNull();
        assertThat(retried.getBody().get("createdTenantId")).isEqualTo(tenantId.toString());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM tenants WHERE slug = ?::citext", Integer.class, slug)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM owners WHERE email = ?::citext", Integer.class, owner(slug))).isEqualTo(1);

        Map<String, Object> body = retried.getBody();
        for (int i = 0; i < 5; i++) {
            body = advance(token, jobId).getBody();
        }
        assertThat(body.get("status")).isEqualTo("done"); // the rest of the job runs normally after the recovered step
    }

    // AC3: 200 jobs -> listJobs is one query and returns in < 200 ms (p95 of 20 calls through the API).
    @Test
    void listJobsIsOneQueryAndFastFor200Jobs() throws Exception {
        SeededOperator op = operator();
        String token = platformLoginAndGetAccessToken(op);
        String t = tag();
        jdbc.update("WITH j AS (INSERT INTO master.provisioning_jobs (requested_by, tenant_slug, tenant_name, region, owner_email, owner_name, brand_name) "
                + "SELECT ?, 'plt058-list-' || g || '-' || ?, 'List clinic ' || g, 'IN', 'plt058-list-' || g || '-' || ? || '@nabd.health', 'Owner', 'Brand' "
                + "FROM generate_series(1, 200) g RETURNING id) "
                + "INSERT INTO master.provisioning_job_steps (job_id, step_name, step_order) SELECT j.id, s.name, s.ord FROM j CROSS JOIN "
                + "unnest(ARRAY['create_tenant','migrate_schema','seed_masters','provision_whatsapp','verify_invite_owner','go_live']) WITH ORDINALITY AS s(name, ord)",
                op.id(), t, t);

        AtomicInteger statements = new AtomicInteger();
        ProvisioningService counted = new ProvisioningService(new ProvisioningRepository(new JdbcTemplate(counting(jdbc.getDataSource(), statements))),
                stepRunner, audit, guard, emailSender);
        List<ProvisioningJobResponse> jobs = counted.listJobs();
        assertThat(statements.get()).isEqualTo(1);
        List<ProvisioningJobResponse> mine = jobs.stream().filter(j -> j.tenantSlug().endsWith("-" + t)).toList();
        assertThat(mine).hasSize(200);
        assertThat(mine).allSatisfy(j -> assertThat(j.steps()).extracting(s -> s.stepName()).containsExactly(
                "create_tenant", "migrate_schema", "seed_masters", "provision_whatsapp", "verify_invite_owner", "go_live"));

        long[] ms = new long[20];
        for (int i = -2; i < ms.length; i++) { // two warm-up calls
            long t0 = System.nanoTime();
            ResponseEntity<List> r = exchange("/v1/platform/provisioning-jobs", HttpMethod.GET, authed(token), List.class);
            long took = (System.nanoTime() - t0) / 1_000_000;
            assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(r.getBody().size()).isGreaterThanOrEqualTo(200);
            if (i >= 0) ms[i] = took;
        }
        Arrays.sort(ms);
        System.out.println("PLT-058 listJobs ms (sorted): " + Arrays.toString(ms));
        assertThat(ms[18]).as("p95 ms").isLessThan(200);
    }

    // AC4: a failing step -> compensations run in reverse order, the job ends rolled_back and is then terminal.
    @Test
    void aFailedLateStepRollsBackInReverseOrderAndTheJobIsTerminal() throws Exception {
        SeededOperator op = operator();
        String token = platformLoginAndGetAccessToken(op);
        String slug = "plt058-undo-" + tag();
        UUID jobId = createJob(token, slug);
        for (int i = 0; i < 5; i++) { // through verify_invite_owner
            assertThat(advance(token, jobId).getStatusCode()).isEqualTo(HttpStatus.OK);
        }
        Job job = repo.findJob(jobId).orElseThrow();
        UUID tenantId = job.createdTenantId();
        // go_live fails fatally (no real later-step fatal check exists yet; this is the path advance() takes on one).
        UUID goLive = repo.findSteps(jobId).get(5).id();
        assertThat(repo.claimStep(goLive)).isTrue();
        repo.markStepFailed(goLive, "simulated fatal failure");
        service.rollback(jobId, job, op.id(), null);

        List<JobStep> steps = repo.findSteps(jobId);
        assertThat(steps.subList(0, 5)).allMatch(s -> "rolled_back".equals(s.status()));
        assertThat(steps.get(5).status()).isEqualTo("failed");
        assertThat(repo.findJob(jobId).orElseThrow().status()).isEqualTo("rolled_back");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM tenants WHERE id = ?", Integer.class, tenantId)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM owners WHERE email = ?::citext", Integer.class, owner(slug))).isZero();

        List<Map<String, Object>> rows = auditRows(op.id(), "platform_provisioning.job_rolled_back");
        assertThat(rows).hasSize(1);
        Map<String, Object> after = objectMapper.readValue((String) rows.get(0).get("after"), Map.class);
        assertThat((List<String>) after.get("compensated")).containsExactly(
                "verify_invite_owner", "provision_whatsapp", "seed_masters", "migrate_schema", "create_tenant");

        ResponseEntity<Map> again = advance(token, jobId);
        assertThat(again.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(again.getBody().get("type")).asString().endsWith("JOB_TERMINAL");
    }

    // ---- helpers ----

    private static DataSource counting(DataSource ds, AtomicInteger statements) {
        ClassLoader cl = Plt058ProvisioningCasApiTest.class.getClassLoader();
        return (DataSource) Proxy.newProxyInstance(cl, new Class<?>[] {DataSource.class}, (p, m, a) -> {
            Object r = call(m, ds, a);
            if (!(r instanceof Connection c)) return r;
            return Proxy.newProxyInstance(cl, new Class<?>[] {Connection.class}, (p2, m2, a2) -> {
                if (m2.getName().startsWith("prepare") || m2.getName().equals("createStatement")) statements.incrementAndGet();
                return call(m2, c, a2);
            });
        });
    }

    private static Object call(Method m, Object target, Object[] args) throws Throwable {
        try {
            return m.invoke(target, args);
        } catch (InvocationTargetException e) {
            throw e.getCause();
        }
    }

    private List<Map<String, Object>> auditRows(UUID operatorId, String action) {
        return new JdbcTemplate(superuserDataSource()).queryForList("SELECT entity_id, after::text AS after FROM search_audit_log("
                + "NULL, ?, NULL, NULL, NULL, ?::uuid, NULL, NULL, NULL, NULL, 10)", action, operatorId);
    }

    private void backdate(UUID stepId, int minutes) {
        jdbc.update("UPDATE master.provisioning_job_steps SET started_at = now() - make_interval(mins => ?) WHERE id = ?", minutes, stepId);
    }

    private ResponseEntity<Map> advance(String token, UUID jobId) {
        return exchange("/v1/platform/provisioning-jobs/" + jobId + "/advance", HttpMethod.POST, authed(token), Map.class);
    }

    private UUID createJob(String token, String slug) {
        ResponseEntity<Map> resp = exchange("/v1/platform/provisioning-jobs", HttpMethod.POST, authedJsonBody(token, Map.of(
                "tenantSlug", slug, "tenantName", "Test Clinic " + slug, "region", "IN", "ownerEmail", owner(slug),
                "ownerName", "Test Owner", "ownerMobile", "+9198765" + (10000 + Math.abs(slug.hashCode() % 89999)),
                "brandName", "Test Brand " + slug, "path", "self_serve")), Map.class);
        assertThat(resp.getStatusCode()).as(String.valueOf(resp.getBody())).isEqualTo(HttpStatus.CREATED);
        return UUID.fromString((String) resp.getBody().get("id"));
    }

    private SeededOperator operator() {
        return seedOperator("plt058-" + UUID.randomUUID() + "@nabd.health", "super_admin", false);
    }

    private static String owner(String slug) {
        return "owner-" + slug + "@nabd.health";
    }

    private static String tag() {
        return UUID.randomUUID().toString().substring(0, 8);
    }
}
