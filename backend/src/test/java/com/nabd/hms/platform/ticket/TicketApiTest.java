package com.nabd.hms.platform.ticket;

import com.nabd.hms.support.ApiTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.time.Duration;
import java.time.Instant;
import java.sql.Timestamp;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class TicketApiTest extends ApiTestBase {

    @Test
    void staffRaisesATicketWithComputedSlaAndRaiserSnapshot() {
        SeededTenant tenant = seedTenant();
        UUID roleId = seedRole(tenant.id(), "Doctor", false, fullGrant("patients"));
        SeededStaff staff = seedStaff(tenant, roleId, "doc@ticket.health", "+911234567890", false);
        String token = loginAndGetAccessToken(staff);

        ResponseEntity<Map> resp = http.exchange(url("/v1/support/tickets"), HttpMethod.POST,
                authedJsonBody(token, Map.of("subject", "WhatsApp not sending", "description", "OTP messages stuck since 10am",
                        "priority", "high")), Map.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        Map<String, Object> body = resp.getBody();
        assertThat(body.get("status")).isEqualTo("open");
        assertThat(body.get("raisedByRole")).isEqualTo("Doctor");
        assertThat(body.get("raisedByEmail")).isEqualTo("doc@ticket.health");
        assertThat(body.get("slaBreached")).isEqualTo(false);

        Instant slaDueAt = Instant.parse((String) body.get("slaDueAt"));
        assertThat(Duration.between(Instant.now(), slaDueAt).toHours()).isBetween(22L, 24L); // high == 24h
    }

    @Test
    void anOwnerLinkedStaffRowIsLabeledOwnerRegardlessOfItsRoleName() {
        SeededTenant tenant = seedTenant();
        UUID roleId = seedRole(tenant.id(), "Whatever", false, fullGrant("patients"));
        SeededStaff staff = seedStaff(tenant, roleId, "owner-staff@ticket.health", "+911234567891", false);
        SeededOwner owner = seedOwner("real-owner@ticket.health");
        inTenantTx(tenant.id(), () -> jdbc.update("UPDATE staff SET owner_id = ? WHERE id = ?", owner.id(), staff.id()));
        String token = loginAndGetAccessToken(staff);

        ResponseEntity<Map> resp = http.exchange(url("/v1/support/tickets"), HttpMethod.POST,
                authedJsonBody(token, Map.of("subject", "Billing question", "description", "Why the overage charge?")),
                Map.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(resp.getBody().get("raisedByRole")).isEqualTo("Owner");
    }

    @Test
    void rolesWithoutSupportTicketsViewAreForbidden() {
        SeededOperator sre = seedOperator("sre-tickets@nabd.health", "sre", false);
        String token = platformLoginAndGetAccessToken(sre);
        ResponseEntity<Map> resp = exchange("/v1/platform/support/tickets", HttpMethod.GET, authed(token), Map.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void slaBreachIsVisibleAndSortsBeforeHealthyTickets() {
        SeededTenant tenant = seedTenant();
        UUID roleId = seedRole(tenant.id(), "Reception", false, fullGrant("patients"));
        SeededStaff staff = seedStaff(tenant, roleId, "recep-breach@ticket.health", "+911234567892", false);
        String staffToken = loginAndGetAccessToken(staff);

        ResponseEntity<Map> healthy = http.exchange(url("/v1/support/tickets"), HttpMethod.POST,
                authedJsonBody(staffToken, Map.of("subject", "Healthy ticket", "description", "d")), Map.class);
        ResponseEntity<Map> breaching = http.exchange(url("/v1/support/tickets"), HttpMethod.POST,
                authedJsonBody(staffToken, Map.of("subject", "Breaching ticket", "description", "d")), Map.class);
        UUID breachingId = UUID.fromString((String) breaching.getBody().get("id"));
        jdbc.update("UPDATE master.support_tickets SET sla_due_at = ? WHERE id = ?",
                Timestamp.from(Instant.now().minus(Duration.ofHours(1))), breachingId);

        SeededOperator support = seedOperator("support-breach@nabd.health", "support_engineer", false);
        String opToken = platformLoginAndGetAccessToken(support);

        // list() returns a bare JSON array (no {data:[...]} envelope), unlike the cursor-paginated fleet list.
        ResponseEntity<Map[]> resp = exchange("/v1/platform/support/tickets", HttpMethod.GET, authed(opToken), Map[].class);
        List<Map> tickets = List.of(resp.getBody());
        Map breachRow = tickets.stream().filter(t -> breachingId.toString().equals(t.get("id"))).findFirst().orElseThrow();
        assertThat(breachRow.get("slaBreached")).isEqualTo(true);
        assertThat(tickets.indexOf(breachRow)).isZero(); // breached-and-open sorts first
    }

    @Test
    void transitionsFollowTheLifecycleAndRejectIllegalJumps() {
        SeededTenant tenant = seedTenant();
        UUID roleId = seedRole(tenant.id(), "Nurse", false, fullGrant("patients"));
        SeededStaff staff = seedStaff(tenant, roleId, "nurse-transition@ticket.health", "+911234567893", false);
        String staffToken = loginAndGetAccessToken(staff);
        ResponseEntity<Map> raised = http.exchange(url("/v1/support/tickets"), HttpMethod.POST,
                authedJsonBody(staffToken, Map.of("subject", "s", "description", "d")), Map.class);
        String id = (String) raised.getBody().get("id");

        SeededOperator support = seedOperator("support-transition@nabd.health", "support_engineer", false);
        String opToken = platformLoginAndGetAccessToken(support);

        ResponseEntity<Map> illegalJump = http.exchange(url("/v1/platform/support/tickets/" + id + "/transitions"),
                HttpMethod.POST, authedJsonBody(opToken, Map.of("toStatus", "resolved")), Map.class);
        assertThat(illegalJump.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        ResponseEntity<Map> toInProgress = http.exchange(url("/v1/platform/support/tickets/" + id + "/transitions"),
                HttpMethod.POST, authedJsonBody(opToken, Map.of("toStatus", "in_progress")), Map.class);
        assertThat(toInProgress.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(toInProgress.getBody().get("status")).isEqualTo("in_progress");

        ResponseEntity<Map> toResolved = http.exchange(url("/v1/platform/support/tickets/" + id + "/transitions"),
                HttpMethod.POST, authedJsonBody(opToken, Map.of("toStatus", "resolved")), Map.class);
        assertThat(toResolved.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(toResolved.getBody().get("resolvedAt")).isNotNull();

        ResponseEntity<Map> toClosed = http.exchange(url("/v1/platform/support/tickets/" + id + "/transitions"),
                HttpMethod.POST, authedJsonBody(opToken, Map.of("toStatus", "closed")), Map.class);
        assertThat(toClosed.getStatusCode()).isEqualTo(HttpStatus.OK);

        ResponseEntity<Map> terminalIsTerminal = http.exchange(url("/v1/platform/support/tickets/" + id + "/transitions"),
                HttpMethod.POST, authedJsonBody(opToken, Map.of("toStatus", "in_progress")), Map.class);
        assertThat(terminalIsTerminal.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }
}
