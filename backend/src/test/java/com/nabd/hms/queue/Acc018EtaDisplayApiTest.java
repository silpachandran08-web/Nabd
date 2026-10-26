package com.nabd.hms.queue;

import com.nabd.hms.common.ModuleGrant;
import com.nabd.hms.support.ApiTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** ACC-018: display mode per doctor / session / facility, who may change it, tenant isolation, audit. */
class Acc018EtaDisplayApiTest extends ApiTestBase {

    @Autowired com.nabd.hms.common.ClinicClock clock;

    SeededTenant tenant;
    SeededStaff owner;
    SeededStaff doctor;
    String ownerToken;

    @BeforeEach
    void seed() {
        tenant = seedTenant();
        owner = seedStaff(tenant, seedFullAccessRole(tenant.id()), "o" + UUID.randomUUID().toString().substring(0, 6) + "@a.com", "+9196" + (10000000 + (int) (Math.random() * 8999999)), false);
        UUID plainRole = seedRole(tenant.id(), "Doctor", false, new ModuleGrant("queue", true, true, true, false, false, false, false));
        doctor = seedStaff(tenant, plainRole, "d" + UUID.randomUUID().toString().substring(0, 6) + "@a.com", "+9195" + (10000000 + (int) (Math.random() * 8999999)), false);
        ownerToken = loginAndGetAccessToken(owner);
    }

    ResponseEntity<Map> put(String token, UUID doctorId, Map<String, Object> body) {
        return exchange("/v1/doctors/" + doctorId + "/queue-settings", HttpMethod.PUT, authedJsonBody(token, body), Map.class);
    }

    Map get(String token, UUID doctorId) {
        return exchange("/v1/doctors/" + doctorId + "/queue-settings", HttpMethod.GET, authed(token), Map.class).getBody();
    }

    String addHours(String token, UUID doctorId) {
        int dow = clock.today(tenant.id()).getDayOfWeek().getValue() % 7;
        return (String) exchange("/v1/doctors/" + doctorId + "/working-hours", HttpMethod.POST, authedJsonBody(token, Map.of(
                "dayOfWeek", dow, "startTime", "00:00:00", "endTime", "23:45:00", "slotMinutes", 15)), Map.class).getBody().get("id");
    }

    @Test
    void noOverrideMeansTheFacilityDefaultWhichIsRangeUntilSet() {
        Map s = get(ownerToken, doctor.id());
        assertThat(s.get("etaDisplayMode")).isNull();
        assertThat(s).containsEntry("effectiveMode", "range").containsEntry("effectiveSource", "default");

        // the facility default is the clinic policy eta_display_mode (PUT /queue-settings/facility)
        ResponseEntity<Map> bad = exchange("/v1/queue-settings/facility", HttpMethod.PUT, authedJsonBody(ownerToken, Map.of("etaDisplayMode", "soon")), Map.class);
        assertThat(bad.getStatusCode().value()).isEqualTo(422);
        assertThat(exchange("/v1/queue-settings/facility", HttpMethod.PUT, authedJsonBody(ownerToken, Map.of("etaDisplayMode", "position")), Map.class)
                .getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(get(ownerToken, doctor.id())).containsEntry("effectiveMode", "position").containsEntry("effectiveSource", "facility");
    }

    @Test
    void doctorSettingBeatsFacilitySessionBeatsDoctorAndNullInherits() {
        ResponseEntity<Map> r = put(ownerToken, doctor.id(), Map.of("etaDisplayMode", "delay_only"));
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(r.getBody()).containsEntry("etaDisplayMode", "delay_only").containsEntry("effectiveSource", "doctor");

        String hours = addHours(ownerToken, doctor.id());
        ResponseEntity<Map> s = put(ownerToken, doctor.id(), Map.of("etaDisplayMode", "hidden", "workingHoursId", hours));
        assertThat(s.getBody()).containsEntry("etaDisplayMode", "hidden").containsEntry("effectiveSource", "session");
        Map settings = get(ownerToken, doctor.id());
        assertThat(settings).containsEntry("etaDisplayMode", "delay_only").containsEntry("effectiveMode", "hidden");
        assertThat((java.util.List<Map>) settings.get("sessions")).singleElement().satisfies(x -> assertThat(x).containsEntry("etaDisplayMode", "hidden"));

        Map<String, Object> clearSession = new java.util.HashMap<>();
        clearSession.put("etaDisplayMode", null);
        clearSession.put("workingHoursId", hours);
        assertThat(put(ownerToken, doctor.id(), clearSession).getBody()).containsEntry("etaDisplayMode", "delay_only").containsEntry("effectiveSource", "doctor");
        Map<String, Object> clearDoctor = new java.util.HashMap<>();
        clearDoctor.put("etaDisplayMode", null);
        assertThat(put(ownerToken, doctor.id(), clearDoctor).getBody()).containsEntry("etaDisplayMode", "range").containsEntry("effectiveSource", "default");
    }

    @Test
    void validationUnknownDoctorAndWorkingHoursOfAnotherDoctor() {
        assertThat(put(ownerToken, doctor.id(), Map.of("etaDisplayMode", "soon")).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(put(ownerToken, UUID.randomUUID(), Map.of("etaDisplayMode", "exact")).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(put(ownerToken, doctor.id(), Map.of("etaDisplayMode", "exact", "workingHoursId", UUID.randomUUID().toString())).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void aDoctorSetsOnlyTheirOwnAndOthersNeedSetupEdit() {
        String doctorToken = loginAndGetAccessToken(doctor);
        assertThat(put(doctorToken, doctor.id(), Map.of("etaDisplayMode", "position")).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(put(doctorToken, owner.id(), Map.of("etaDisplayMode", "position")).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void changesAreAuditedAndTenantIsolated() {
        put(ownerToken, doctor.id(), Map.of("etaDisplayMode", "exact"));
        int audits = inTenantTx(tenant.id(), () -> jdbc.queryForObject(
                "SELECT count(*) FROM audit_log WHERE action = 'queue.eta_display_mode_set' AND entity_id = ?", Integer.class, doctor.id()));
        assertThat(audits).isEqualTo(1);

        SeededTenant other = seedTenant();
        SeededStaff otherOwner = seedStaff(other, seedFullAccessRole(other.id()), "x" + UUID.randomUUID().toString().substring(0, 6) + "@b.com", "+9194" + (10000000 + (int) (Math.random() * 8999999)), false);
        String otherToken = loginAndGetAccessToken(otherOwner);
        assertThat(put(otherToken, doctor.id(), Map.of("etaDisplayMode", "hidden")).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND); // not their doctor
        assertThat(inTenantTx(other.id(), () -> jdbc.queryForObject("SELECT count(*) FROM doctor_queue_settings", Integer.class))).isZero(); // RLS
        assertThat(get(ownerToken, doctor.id())).containsEntry("etaDisplayMode", "exact");
    }
}
