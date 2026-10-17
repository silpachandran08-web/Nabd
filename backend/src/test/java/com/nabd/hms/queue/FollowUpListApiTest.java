package com.nabd.hms.queue;

import com.nabd.hms.support.ApiTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.sql.Timestamp;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** DOC-006. Written to the existing ApiTestBase conventions; NOT executed (no Docker/Postgres here). */
class FollowUpListApiTest extends ApiTestBase {

    @Test
    void emptyForADoctorWithNoFollowUpsAndRejectsBadFilters() {
        SeededTenant tenant = seedTenant();
        UUID roleId = seedFullAccessRole(tenant.id());
        SeededStaff doc = seedStaff(tenant, roleId, "fu1@a.com", "+919800050001", false);
        String token = loginAndGetAccessToken(doc);

        var ok = exchange("/v1/appointments/follow-ups", HttpMethod.GET, authed(token), Map.class);
        assertThat(ok.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat((List<?>) ok.getBody().get("data")).isEmpty();

        assertThat(exchange("/v1/appointments/follow-ups?status=bogus", HttpMethod.GET, authed(token), Map.class).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(exchange("/v1/appointments/follow-ups?from=2026-02-02&to=2026-01-01", HttpMethod.GET, authed(token), Map.class).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    // M3 (written, NOT executed: no Docker/Postgres in the authoring environment). Rows are inserted directly
    // (inside the tenant transaction, as RLS requires) so the test does not depend on working-hours rules.
    private UUID insertAppointment(SeededTenant tenant, String patientId, UUID doctorId, Instant start, boolean followUp) {
        UUID id = UUID.randomUUID();
        inTenantTx(tenant.id(), () -> jdbc.update(
                "INSERT INTO appointments (id, tenant_id, patient_id, doctor_id, start_time, end_time, is_follow_up) VALUES (?,?,?,?,?,?,?)",
                id, tenant.id(), UUID.fromString(patientId), doctorId, Timestamp.from(start), Timestamp.from(start.plus(15, ChronoUnit.MINUTES)),
                followUp));
        return id;
    }

    @Test
    void listsOnlyTheCallersOwnFollowUpsSoonestFirstWithPagingAndFilters() {
        SeededTenant tenant = seedTenant();
        UUID roleId = seedFullAccessRole(tenant.id());
        SeededStaff doc = seedStaff(tenant, roleId, "fu2@a.com", "+919800050002", false);
        SeededStaff other = seedStaff(tenant, roleId, "fu3@a.com", "+919800050003", false);
        String token = loginAndGetAccessToken(doc);
        String patientId = (String) exchange("/v1/patients", HttpMethod.POST, authedJsonBody(token, Map.of(
                "name", "FU Patient", "phone", "+919999950002", "dob", "1990-01-01", "gender", "male")), Map.class).getBody().get("id");

        Instant base = Instant.now().plus(2, ChronoUnit.DAYS).truncatedTo(ChronoUnit.HOURS);
        UUID first = insertAppointment(tenant, patientId, doc.id(), base, true);
        UUID second = insertAppointment(tenant, patientId, doc.id(), base.plus(1, ChronoUnit.HOURS), true);
        insertAppointment(tenant, patientId, doc.id(), base.plus(2, ChronoUnit.HOURS), false);  // not a follow-up
        insertAppointment(tenant, patientId, other.id(), base.plus(3, ChronoUnit.HOURS), true); // someone else's

        var all = exchange("/v1/appointments/follow-ups", HttpMethod.GET, authed(token), Map.class);
        assertThat(all.getStatusCode()).isEqualTo(HttpStatus.OK);
        List<?> data = (List<?>) all.getBody().get("data");
        assertThat(data).hasSize(2);
        Map<?, ?> row = (Map<?, ?>) data.get(0);
        assertThat(row.get("appointmentId")).isEqualTo(first.toString());
        assertThat(row.get("patientId")).isEqualTo(patientId);
        assertThat(row.get("status")).isEqualTo("scheduled");
        assertThat(((Map<?, ?>) data.get(1)).get("appointmentId")).isEqualTo(second.toString());

        // limit=1 cursor walk: soonest first, no repeats, last page has no cursor
        var page1 = exchange("/v1/appointments/follow-ups?limit=1", HttpMethod.GET, authed(token), Map.class);
        assertThat(((Map<?, ?>) ((List<?>) page1.getBody().get("data")).get(0)).get("appointmentId")).isEqualTo(first.toString());
        String cursor = (String) ((Map<?, ?>) page1.getBody().get("page")).get("nextCursor");
        assertThat(cursor).isNotNull();
        var page2 = exchange("/v1/appointments/follow-ups?limit=1&cursor=" + cursor, HttpMethod.GET, authed(token), Map.class);
        assertThat(((Map<?, ?>) ((List<?>) page2.getBody().get("data")).get(0)).get("appointmentId")).isEqualTo(second.toString());
        assertThat(((Map<?, ?>) page2.getBody().get("page")).get("nextCursor")).isNull();

        // status and date filters
        var cancelled = exchange("/v1/appointments/follow-ups?status=cancelled", HttpMethod.GET, authed(token), Map.class);
        assertThat((List<?>) cancelled.getBody().get("data")).isEmpty();
        String farFrom = base.plus(30, ChronoUnit.DAYS).toString().substring(0, 10);
        var farFuture = exchange("/v1/appointments/follow-ups?from=" + farFrom, HttpMethod.GET, authed(token), Map.class);
        assertThat((List<?>) farFuture.getBody().get("data")).isEmpty();

        // the other doctor sees only their own
        var theirs = exchange("/v1/appointments/follow-ups", HttpMethod.GET, authed(loginAndGetAccessToken(other)), Map.class);
        assertThat((List<?>) theirs.getBody().get("data")).hasSize(1);

        // QA-06: a cancelled follow-up carries its reason; scheduled ones carry null
        assertThat(row.containsKey("cancelReason")).isTrue();
        assertThat(row.get("cancelReason")).isNull();
        inTenantTx(tenant.id(), () -> jdbc.update(
                "UPDATE appointments SET status = 'cancelled', cancel_reason = 'Patient travelling' WHERE id = ?", second));
        var nowCancelled = exchange("/v1/appointments/follow-ups?status=cancelled", HttpMethod.GET, authed(token), Map.class);
        assertThat(((Map<?, ?>) ((List<?>) nowCancelled.getBody().get("data")).get(0)).get("cancelReason")).isEqualTo("Patient travelling");
    }
}
