package com.nabd.hms.queue;

import com.nabd.hms.common.ClinicClock;
import com.nabd.hms.common.ModuleGrant;
import com.nabd.hms.patient.api.PatientRefs;
import com.nabd.hms.support.ApiTestBase;
import com.nabd.hms.support.InternalTestTokens;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.JwtEncoder;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;

/** Shared fixtures of the ACC-009 / ACC-010 tests: a clinic whose doctor works every 15-minute slot of tomorrow, two patients with Edge references, an Edge token. */
abstract class BookingRequestTestBase extends ApiTestBase {

    static final String EDGE = "patient-access-edge";

    @Autowired JwtEncoder encoder;
    @Autowired ClinicClock clinicClock;
    @Autowired PatientRefs patientRefs;

    record Clinic(SeededTenant tenant, UUID doctorId, String staffToken, String edgeToken, UUID patientA, UUID patientB, String refA, String refB,
                  Instant slot, ZoneId zone) {
        String base() {
            return "/internal/v1/tenants/" + tenant.id();
        }

        /** Tomorrow at 10:00 clinic time plus n quarter hours. */
        Instant slot(int n) {
            return slot.plusSeconds(900L * n);
        }
    }

    static JdbcTemplate su() {
        return new JdbcTemplate(superuserDataSource());
    }

    static String phone() {
        return "+9198" + String.format("%08d", ThreadLocalRandom.current().nextInt(100_000_000));
    }

    private UUID registerPatient(String token, String name) {
        ResponseEntity<Map> resp = exchange("/v1/patients", HttpMethod.POST, authedJsonBody(token, Map.of(
                "name", name, "phone", phone(), "dob", "1990-01-01", "gender", "female")), Map.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return UUID.fromString((String) resp.getBody().get("id"));
    }

    Clinic clinic() {
        SeededTenant tenant = seedTenant();
        SeededStaff doctor = seedStaff(tenant, seedFullAccessRole(tenant.id()), "br" + UUID.randomUUID() + "@a.com", phone(), false);
        String staffToken = loginAndGetAccessToken(doctor);
        ZoneId zone = clinicClock.zone(tenant.id());
        LocalDate day = clinicClock.today(tenant.id()).plusDays(1);
        assertThat(exchange("/v1/doctors/" + doctor.id() + "/working-hours", HttpMethod.POST, authedJsonBody(staffToken, Map.of(
                "dayOfWeek", day.getDayOfWeek().getValue() % 7, "startTime", "00:00:00", "endTime", "23:45:00", "slotMinutes", 15)), Map.class)
                .getStatusCode()).isEqualTo(HttpStatus.CREATED);
        UUID a = registerPatient(staffToken, "Asha Rao"), b = registerPatient(staffToken, "Bina Shah");
        return new Clinic(tenant, doctor.id(), staffToken, InternalTestTokens.mint(encoder, EDGE, tenant.id()), a, b,
                inTenantTx(tenant.id(), () -> patientRefs.refFor(a)), inTenantTx(tenant.id(), () -> patientRefs.refFor(b)),
                day.atTime(10, 0).atZone(zone).toInstant(), zone);
    }

    /** A staff member who may view the queue but not book (no queue:create). */
    String viewerToken(Clinic c) {
        UUID role = seedRole(c.tenant().id(), "viewer-" + UUID.randomUUID(), false, new ModuleGrant("queue", true, false, false, false, false, false, false));
        return loginAndGetAccessToken(seedStaff(c.tenant(), role, "v" + UUID.randomUUID() + "@a.com", phone(), false));
    }

    void setPolicy(Clinic c, String mode, int cap, int slaMinutes) {
        su().update("INSERT INTO booking_routing_settings (tenant_id, acceptance_mode, daily_auto_cap, review_sla_minutes, policy_version) VALUES (?,?,?,?,7) "
                + "ON CONFLICT (tenant_id) DO UPDATE SET acceptance_mode = EXCLUDED.acceptance_mode, daily_auto_cap = EXCLUDED.daily_auto_cap, review_sla_minutes = EXCLUDED.review_sla_minutes",
                c.tenant().id(), mode, cap, slaMinutes);
    }

    /** The patient has been seen by this doctor before (a completed appointment in the past). */
    void giveHistory(Clinic c, UUID patient) {
        su().update("INSERT INTO appointments (tenant_id, patient_id, doctor_id, start_time, end_time, status) VALUES (?,?,?, now() - interval '30 days', now() - interval '30 days' + interval '15 minutes', 'completed')",
                c.tenant().id(), patient, c.doctorId());
    }

    ResponseEntity<Map> post(String path, String token, Object body) {
        return exchange(path, HttpMethod.POST, authedJsonBody(token, body), Map.class);
    }

    ResponseEntity<Map> route(Clinic c, String ref, Instant slot, Map<String, Object> extra) {
        Map<String, Object> body = new java.util.HashMap<>(Map.of("patientRef", ref, "doctorId", c.doctorId().toString(), "slotStart", slot.toString(), "channel", "whatsapp"));
        body.putAll(extra);
        return post(c.base() + "/booking-requests", c.edgeToken(), body);
    }

    /** Domain events of one type for the clinic, oldest first (envelope data maps). */
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> events(Clinic c, String type) {
        return su().queryForList("SELECT envelope::text FROM outbox_cloudevents WHERE tenant_id = ? AND envelope->>'type' = ? ORDER BY id", String.class, c.tenant().id(), type)
                .stream().map(j -> {
                    try {
                        return (Map<String, Object>) ((Map<String, Object>) objectMapper.readValue(j, Map.class)).get("data");
                    } catch (Exception e) {
                        throw new IllegalStateException(e);
                    }
                }).toList();
    }

    String statusOf(UUID appointmentId) {
        return su().queryForObject("SELECT status FROM appointments WHERE id = ?", String.class, appointmentId);
    }
}
