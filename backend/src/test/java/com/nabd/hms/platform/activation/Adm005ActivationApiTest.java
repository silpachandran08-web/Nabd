package com.nabd.hms.platform.activation;

import com.nabd.hms.common.OutboxDispatcher;
import com.nabd.hms.organization.setup.SetupStepEvents;
import com.nabd.hms.support.ApiTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** ADM-005: wizard events build the funnel idempotently; funnel per cohort with median time to go-live; 48 h nudge once per step per
 * 7 days; a ticket on the second import failure; the funnel table holds ids, steps, codes and times only, under tenant RLS. */
class Adm005ActivationApiTest extends ApiTestBase {

    @Autowired OutboxDispatcher dispatcher;
    @Autowired SetupStepEvents stepEvents;
    @Autowired ActivationService service;

    private final JdbcTemplate su = new JdbcTemplate(superuserDataSource());

    private String operator(String role) {
        return platformLoginAndGetAccessToken(seedOperator(role + "-" + UUID.randomUUID() + "@nabd.internal", role, false));
    }

    private void drain() {
        for (int i = 0; i < 5; i++) dispatcher.poll();
    }

    private void event(UUID tenant, String step, String status, String code) {
        inTenantTx(tenant, () -> stepEvents.changed(tenant, step, status, code));
    }

    private Map<String, Object> row(UUID tenant, String step) {
        return inTenantTx(tenant, () -> jdbc.queryForMap("SELECT status, completed_at, attempts, last_error_code, nudges_sent, ticket_id, first_seen_at "
                + "FROM master.activation_steps WHERE tenant_id = ? AND step = ?", tenant, step));
    }

    private record Clinic(SeededTenant t, String owner) {
    }

    private Clinic clinic() {
        SeededTenant t = seedTenant();
        String phone = String.format("+919%09d", java.util.concurrent.ThreadLocalRandom.current().nextInt(1_000_000_000));
        String owner = loginAndGetAccessToken(seedStaff(t, seedFullAccessRole(t.id()), "own-" + t.slug() + "@act.test", phone, false));
        return new Clinic(t, owner);
    }

    private void approveTemplate(UUID tenant, String name, int params) {
        inTenantTx(tenant, () -> jdbc.update("""
                INSERT INTO whatsapp_templates (tenant_id, name, meta_name, language, category, header_text, body, param_count, status)
                VALUES (?, ?, ?, 'en', 'UTILITY', 'Nabd', 'Setup: {{1}} {{2}}', ?, 'approved')""", tenant, name, name + "_" + tenant.toString().substring(0, 8), params));
    }

    @Test
    void theWizardsEventsBuildFunnelRows_andAReplayChangesNothing() {
        Clinic c = clinic();
        UUID t = c.t().id();
        assertThat(exchange("/v1/setup/checklist", HttpMethod.GET, authed(c.owner()), List.class).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(exchange("/v1/setup/checklist", HttpMethod.GET, authed(c.owner()), List.class).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(exchange("/v1/setup/checklist/profile/complete", HttpMethod.POST, authedJsonBody(c.owner(), Map.of()), Map.class).getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(exchange("/v1/setup/checklist/preset/skip", HttpMethod.POST, authedJsonBody(c.owner(), Map.of()), Map.class).getStatusCode().is2xxSuccessful()).isTrue();
        event(t, "import", "failed", "CHUNK_FAILED");
        drain();

        assertThat(row(t, "profile")).containsEntry("status", "done").containsEntry("attempts", 0);
        assertThat(row(t, "profile").get("completed_at")).isNotNull();
        assertThat(row(t, "preset")).containsEntry("status", "skipped");
        assertThat(row(t, "doctors")).as("the next open step is reached").containsEntry("status", "reached").containsEntry("completed_at", null);
        assertThat(row(t, "import")).containsEntry("status", "failed").containsEntry("attempts", 1).containsEntry("last_error_code", "CHUNK_FAILED");
        long rows = inTenantTx(t, () -> jdbc.queryForObject("SELECT count(*) FROM master.activation_steps WHERE tenant_id = ?", Long.class, t));
        assertThat(rows).isEqualTo(4);
        assertThat(inTenantTx(t, () -> jdbc.queryForObject("SELECT count(*) FROM outbox_events WHERE tenant_id = ? AND event_type = 'organization.setup_step.changed.v1' "
                + "AND payload->>'step' = 'profile' AND payload->>'status' = 'reached'", Long.class, t))).as("reached once, not on every read").isEqualTo(1);

        // at-least-once delivery: the same events again change nothing (attempts are not counted twice)
        su.update("UPDATE outbox_events SET status = 'pending', attempts = 0, next_attempt_at = now() WHERE tenant_id = ? AND event_type = 'organization.setup_step.changed.v1'", t);
        drain();
        assertThat(row(t, "import")).containsEntry("attempts", 1);
        assertThat(row(t, "doctors")).containsEntry("status", "reached");
        assertThat(inTenantTx(t, () -> jdbc.queryForObject("SELECT count(*) FROM master.activation_steps WHERE tenant_id = ?", Long.class, t))).isEqualTo(rows);
    }

    @Test
    @SuppressWarnings("unchecked")
    void ac1_funnelShowsCountsPerStepAndTheMedianHoursToGoLiveForTheCohort() {
        String specialty = "adm005-" + UUID.randomUUID(); // isolates this cohort from other tests' clinics
        for (int i = 0; i < 40; i++) {
            UUID t = seedTenant().id();
            int n = i;
            jdbc.update("UPDATE tenants SET created_at = timestamptz '2026-03-03 06:00:00+00' + make_interval(mins => ?), specialties = ARRAY[?], "
                    + "setup_completed_at = CASE WHEN ? < 10 THEN timestamptz '2026-03-03 06:00:00+00' + make_interval(mins => ?) + make_interval(hours => ? + 1) END "
                    + "WHERE id = ?", n, specialty, n, n, n, t);
            inTenantTx(t, () -> {
                jdbc.update("INSERT INTO master.activation_steps (tenant_id, step, status, completed_at) VALUES (?, 'profile', ?, CASE WHEN ? < 30 THEN now() END)",
                        t, n < 30 ? "done" : "reached", n);
                if (n < 20) {
                    jdbc.update("INSERT INTO master.activation_steps (tenant_id, step, status, completed_at) VALUES (?, 'whatsapp', ?, CASE WHEN ? < 10 THEN now() END)",
                            t, n < 10 ? "done" : "reached", n);
                }
            });
        }
        String op = operator("implementation");
        ResponseEntity<Map> r = exchange("/v1/platform/activation/funnel?from=2026-03-02&to=2026-03-08&specialty=" + specialty, HttpMethod.GET, authed(op), Map.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        List<Map<String, Object>> cohorts = (List<Map<String, Object>>) r.getBody().get("cohorts");
        assertThat(cohorts).hasSize(1);
        Map<String, Object> week = cohorts.get(0);
        assertThat(week).containsEntry("week", "2026-03-02").containsEntry("signups", 40).containsEntry("medianHoursToLive", 5.5);
        List<Map<String, Object>> steps = (List<Map<String, Object>>) week.get("steps");
        assertThat(steps).extracting(s -> s.get("step")).containsExactly("profile", "preset", "doctors", "prices", "whatsapp", "tax", "dpa", "import", "go_live");
        assertThat(steps.get(0)).containsEntry("reached", 40).containsEntry("completed", 30);
        assertThat(steps.get(4)).containsEntry("reached", 20).containsEntry("completed", 10);
        assertThat(steps.get(1)).containsEntry("reached", 0);
        assertThat((List<?>) exchange("/v1/platform/activation/funnel?from=2026-03-02&to=2026-03-08&specialty=" + specialty + "&market=SA",
                HttpMethod.GET, authed(op), Map.class).getBody().get("cohorts")).as("market filter").isEmpty();

        assertThat(exchange("/v1/platform/activation/funnel", HttpMethod.GET, authed(operator("support_engineer")), Map.class).getStatusCode())
                .as("onboarding:view only").isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(exchange("/v1/platform/activation/funnel", HttpMethod.GET, authed(clinic().owner()), Map.class).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @SuppressWarnings("unchecked")
    void ac2_stuck48HoursOnWhatsApp_oneNudgeWithAHelpLink_noSecondWithin7Days() {
        Clinic c = clinic();
        UUID t = c.t().id();
        approveTemplate(t, "setup_nudge", 2);
        inTenantTx(t, () -> {
            jdbc.update("INSERT INTO master.activation_steps (tenant_id, step, status, first_seen_at) VALUES (?, 'whatsapp', 'reached', now() - interval '49 hours')", t);
            jdbc.update("INSERT INTO master.activation_steps (tenant_id, step, status, first_seen_at) VALUES (?, 'tax', 'reached', now() - interval '10 hours')", t);
        });
        Clinic live = clinic(); // a live clinic is never nudged
        approveTemplate(live.t().id(), "setup_nudge", 2);
        jdbc.update("UPDATE tenants SET setup_completed_at = now() WHERE id = ?", live.t().id());
        inTenantTx(live.t().id(), () -> jdbc.update("INSERT INTO master.activation_steps (tenant_id, step, status, first_seen_at) VALUES (?, 'import', 'reached', now() - interval '60 hours')", live.t().id()));

        String op = operator("implementation");
        List<Map<String, Object>> stuck = (List<Map<String, Object>>) exchange("/v1/platform/activation/stuck?hours=48&limit=100", HttpMethod.GET, authed(op), Map.class).getBody().get("items");
        assertThat(stuck).filteredOn(i -> t.toString().equals(i.get("tenantId"))).singleElement()
                .satisfies(i -> assertThat(i).containsEntry("step", "whatsapp").containsEntry("nudgesSent", 0).containsKey("since"));
        assertThat(stuck).noneMatch(i -> live.t().id().toString().equals(i.get("tenantId")));

        service.runNudges();
        service.runNudges(); // the hourly job again: same step, inside 7 days
        List<Map<String, Object>> sent = inTenantTx(t, () -> jdbc.queryForList("SELECT purpose, subject_id FROM notification_requests WHERE tenant_id = ?", t));
        assertThat(sent).hasSize(1);
        assertThat(sent.get(0)).containsEntry("purpose", "setup_nudge");
        assertThat(row(t, "whatsapp")).containsEntry("nudges_sent", 1);
        assertThat(row(t, "tax")).containsEntry("nudges_sent", 0);
        assertThat(inTenantTx(live.t().id(), () -> jdbc.queryForObject("SELECT count(*) FROM notification_requests WHERE tenant_id = ?", Long.class, live.t().id()))).isZero();

        inTenantTx(t, () -> jdbc.update("UPDATE master.activation_steps SET last_nudged_at = now() - interval '8 days' WHERE tenant_id = ? AND step = 'whatsapp'", t));
        service.runNudges();
        assertThat(inTenantTx(t, () -> jdbc.queryForObject("SELECT count(*) FROM notification_requests WHERE tenant_id = ?", Long.class, t))).as("7 days later").isEqualTo(2);
    }

    @Test
    void aNudgeThatCannotBeSentIsPutBackForTheNextRun() {
        Clinic c = clinic(); // no approved setup_nudge template
        UUID t = c.t().id();
        inTenantTx(t, () -> jdbc.update("INSERT INTO master.activation_steps (tenant_id, step, status, first_seen_at) VALUES (?, 'prices', 'reached', now() - interval '50 hours')", t));
        service.runNudges();
        assertThat(row(t, "prices")).containsEntry("nudges_sent", 0);
        assertThat(inTenantTx(t, () -> jdbc.queryForObject("SELECT last_nudged_at IS NULL FROM master.activation_steps WHERE tenant_id = ? AND step = 'prices'", Boolean.class, t))).isTrue();
        approveTemplate(t, "setup_nudge", 2);
        service.runNudges();
        assertThat(row(t, "prices")).containsEntry("nudges_sent", 1);
    }

    @Test
    void ac3_theSecondImportFailureOpensOneTicketWithTenantStepAndCode_andNoPatientData() {
        Clinic c = clinic();
        UUID t = c.t().id();
        event(t, "import", "failed", "CHUNK_FAILED");
        drain();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM master.support_tickets WHERE tenant_id = ?", Long.class, t)).isZero();
        event(t, "import", "failed", "CHUNK_FAILED");
        drain();
        Map<String, Object> ticket = jdbc.queryForMap("SELECT id, source, subject, description, priority FROM master.support_tickets WHERE tenant_id = ?", t);
        assertThat(ticket).containsEntry("source", "system").containsEntry("priority", "high");
        assertThat((String) ticket.get("description")).contains(t.toString(), "step import", "CHUNK_FAILED")
                .matches("^[A-Za-z0-9 ,.:_-]+$"); // codes and ids only: no names, phones or row data
        assertThat(row(t, "import")).containsEntry("attempts", 2).containsEntry("ticket_id", ticket.get("id"));
        event(t, "import", "failed", "CHUNK_FAILED");
        drain();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM master.support_tickets WHERE tenant_id = ?", Long.class, t)).as("one ticket").isEqualTo(1);
    }

    @Test
    void ac4_theFunnelTableHoldsOnlyIdsStepNamesCodesCountersAndTimes_underTenantRls() {
        List<Map<String, Object>> cols = jdbc.queryForList("SELECT column_name, data_type FROM information_schema.columns "
                + "WHERE table_schema = 'master' AND table_name = 'activation_steps' ORDER BY column_name");
        assertThat(cols).extracting(m -> m.get("column_name")).containsExactlyInAnyOrder("tenant_id", "step", "status", "first_seen_at", "completed_at",
                "attempts", "last_error_code", "nudges_sent", "last_nudged_at", "ticket_id", "last_event_id", "updated_at");
        assertThat(cols).allMatch(m -> List.of("uuid", "text", "integer", "bigint", "timestamp with time zone").contains(m.get("data_type")));

        Clinic a = clinic(), b = clinic();
        event(a.t().id(), "profile", "reached", null);
        drain();
        UUID aId = a.t().id();
        assertThat(inTenantTx(aId, () -> jdbc.queryForObject("SELECT count(*) FROM master.activation_steps", Long.class))).isEqualTo(1);
        assertThat(inTenantTx(b.t().id(), () -> jdbc.queryForObject("SELECT count(*) FROM master.activation_steps WHERE tenant_id = ?", Long.class, aId))).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM master.activation_steps WHERE tenant_id = ?", Long.class, aId)).as("no tenant context").isZero();
    }
}
