package com.nabd.hms.staff;

import com.nabd.hms.support.ApiTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PLT-054: /v1/training/completions. Completion is tracked per staff member, a pass is idempotent per day and audited, the
 * go-live blocker needs 100% of active accounts, the yearly refresher lapses a pass, the team list needs staff:view, and
 * one clinic never sees another's rows.
 */
@SuppressWarnings({"unchecked", "rawtypes"})
class Plt054TrainingApiTest extends ApiTestBase {

    private static final AtomicInteger SEQ = new AtomicInteger((int) (System.nanoTime() % 400_000) + 100_000);
    private final JdbcTemplate su = new JdbcTemplate(superuserDataSource());

    private SeededStaff staff(SeededTenant t, UUID role) {
        return seedStaff(t, role, "p54-" + SEQ.incrementAndGet() + "@a.com", "+9194" + (10000000 + SEQ.get() * 7), false);
    }

    private ResponseEntity<Map> pass(String token, Object module, Object score) {
        Map<String, Object> body = new java.util.HashMap<>();
        if (module != null) body.put("moduleId", module);
        if (score != null) body.put("score", score);
        return exchange("/v1/training/completions", HttpMethod.POST, authedJsonBody(token, body), Map.class);
    }

    private Map<String, Object> mine(String token) {
        ResponseEntity<Map> r = exchange("/v1/training/completions/me", HttpMethod.GET, authed(token), Map.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        return r.getBody();
    }

    private boolean trainingBlockerOk(String token) {
        Map<?, ?> checks = exchange("/v1/setup/go-live/checks", HttpMethod.GET, authed(token), Map.class).getBody();
        Map<?, ?> b = ((List<Map<?, ?>>) checks.get("blockers")).stream().filter(m -> "staff_phi_training".equals(m.get("code"))).findFirst().orElseThrow();
        assertThat(b.get("fixUrl")).isEqualTo("/staff");
        return Boolean.TRUE.equals(b.get("ok"));
    }

    private int rows(SeededTenant t) {
        return inTenantTx(t.id(), () -> jdbc.queryForObject("SELECT count(*) FROM training_completions", Integer.class));
    }

    @Test
    void aPassIsRecordedPerStaffMember_idempotentAndAudited_andOnlyAPassCounts() {
        SeededTenant t = seedTenant();
        SeededStaff s = staff(t, seedFullAccessRole(t.id()));
        String token = loginAndGetAccessToken(s);
        assertThat(mine(token)).containsEntry("moduleId", "phi-handling").containsEntry("passMark", 80).containsEntry("state", "due")
                .containsEntry("completedAt", null).containsEntry("dueAt", null);

        ResponseEntity<Map> low = pass(token, "phi-handling", 79);
        assertThat(low.getStatusCode().value()).isEqualTo(422);
        assertThat(low.getBody().get("code")).isEqualTo("TRAINING_NOT_PASSED");
        assertThat(pass(token, "phi-handling", 101).getStatusCode().value()).isEqualTo(422);
        ResponseEntity<Map> unknown = pass(token, "made-up-module", 100);
        assertThat(unknown.getStatusCode().value()).isEqualTo(422);
        assertThat(unknown.getBody().get("code")).isEqualTo("UNKNOWN_TRAINING_MODULE");
        assertThat(pass(token, "phi-handling", null).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(pass(token, null, 100).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(exchange("/v1/training/completions", HttpMethod.POST, jsonBody(Map.of("moduleId", "phi-handling", "score", 100)), Map.class).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(rows(t)).isZero();

        ResponseEntity<Map> first = pass(token, "phi-handling", 90);
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(first.getBody()).containsEntry("moduleId", "phi-handling").containsEntry("score", 90);
        Instant completed = Instant.parse((String) first.getBody().get("completedAt"));
        assertThat(Instant.parse((String) first.getBody().get("dueAt"))).isEqualTo(completed.atZone(java.time.ZoneOffset.UTC).plusYears(1).toInstant());

        ResponseEntity<Map> again = pass(token, "phi-handling", 100); // a double submit or a retry: the same completion
        assertThat(again.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(again.getBody()).containsEntry("id", first.getBody().get("id")).containsEntry("score", 90);
        assertThat(rows(t)).isEqualTo(1);
        assertThat(inTenantTx(t.id(), () -> jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE action = 'staff.training_completed' AND actor_id = ?", Integer.class, s.id()))).isEqualTo(1);
        assertThat(mine(token)).containsEntry("state", "current").containsEntry("score", 90);
    }

    @Test
    void goLiveNeedsEveryActiveAccountTrained_andTheYearlyRefresherLapsesAPass() {
        SeededTenant t = seedTenant();
        UUID role = seedFullAccessRole(t.id());
        SeededStaff owner = staff(t, role);
        SeededStaff nurse = staff(t, role);
        inTenantTx(t.id(), () -> jdbc.update("INSERT INTO staff (id, tenant_id, role_id, email, name, mobile_phone, status) VALUES (?,?,?,?,?,?,'invited')",
                UUID.randomUUID(), t.id(), role, "p54-inv-" + SEQ.incrementAndGet() + "@a.com", "Invitee", "+9194" + (10000000 + SEQ.get() * 7)));
        String ownerToken = loginAndGetAccessToken(owner);
        String nurseToken = loginAndGetAccessToken(nurse);

        assertThat(trainingBlockerOk(ownerToken)).isFalse();
        assertThat(pass(ownerToken, "phi-handling", 100).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(trainingBlockerOk(ownerToken)).as("one of two active accounts is not 100%").isFalse();
        assertThat(pass(nurseToken, "phi-handling", 80).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(trainingBlockerOk(ownerToken)).as("an invited account that cannot sign in yet does not count").isTrue();

        // thirteen months later the nurse's pass has lapsed: due again, the blocker reopens, a new pass is a new row
        su.update("UPDATE training_completions SET completed_at = now() - interval '13 months' WHERE staff_id = ?", nurse.id());
        assertThat(mine(nurseToken)).containsEntry("state", "overdue");
        assertThat(trainingBlockerOk(ownerToken)).isFalse();
        assertThat(pass(nurseToken, "phi-handling", 100).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(mine(nurseToken)).containsEntry("state", "current").containsEntry("score", 100);
        assertThat(trainingBlockerOk(ownerToken)).isTrue();
        assertThat(rows(t)).isEqualTo(3);
    }

    @Test
    void theTeamListNeedsStaffView_showsEachAccount_andStaysInsideTheClinic() {
        SeededTenant t = seedTenant();
        SeededStaff owner = staff(t, seedFullAccessRole(t.id()));
        SeededStaff desk = staff(t, seedRole(t.id(), "Desk", false, fullGrant("queue")));
        String ownerToken = loginAndGetAccessToken(owner);
        String deskToken = loginAndGetAccessToken(desk);
        assertThat(pass(deskToken, "phi-handling", 100).getStatusCode()).as("any signed-in staff member records their own pass").isEqualTo(HttpStatus.CREATED);
        assertThat(exchange("/v1/training/completions", HttpMethod.GET, authed(deskToken), Map.class).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        Map<String, Object> roster = exchange("/v1/training/completions", HttpMethod.GET, authed(ownerToken), Map.class).getBody();
        assertThat(roster).containsEntry("moduleId", "phi-handling").containsEntry("total", 2).containsEntry("current", 1);
        List<Map<String, Object>> rows = (List<Map<String, Object>>) roster.get("staff");
        assertThat(rows).extracting(r -> r.get("staffId") + ":" + r.get("state") + ":" + r.get("role"))
                .containsExactlyInAnyOrder(owner.id() + ":due:Owner", desk.id() + ":current:Desk");

        SeededTenant other = seedTenant();
        String otherOwner = loginAndGetAccessToken(staff(other, seedFullAccessRole(other.id())));
        Map<String, Object> theirs = exchange("/v1/training/completions", HttpMethod.GET, authed(otherOwner), Map.class).getBody();
        assertThat(theirs).containsEntry("total", 1).containsEntry("current", 0);
        assertThat(rows(other)).isZero();
        assertThat(rows(t)).isEqualTo(1);
    }
}
