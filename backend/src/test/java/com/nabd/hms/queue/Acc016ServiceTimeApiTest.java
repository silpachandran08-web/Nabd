package com.nabd.hms.queue;

import com.nabd.hms.common.ModuleGrant;
import com.nabd.hms.support.ApiTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/** ACC-016: online update, caps, separate visit types, nightly robust recompute (also at 1,000 doctors), access and tenant isolation. */
class Acc016ServiceTimeApiTest extends ApiTestBase {

    @Autowired ServiceTimeNightlyJob nightly;

    private SeededTenant tenant;
    private SeededStaff doctor;
    private String token;

    private void setUp() {
        tenant = seedTenant();
        String tag = UUID.randomUUID().toString().substring(0, 6);
        doctor = seedStaff(tenant, seedFullAccessRole(tenant.id()), "s" + tag + "@a.com", "+9196" + (10000000 + (int) (Math.random() * 8999999)), false);
        token = loginAndGetAccessToken(doctor);
    }

    private ResponseEntity<Map> patch(String id, String status) {
        return exchange("/v1/queue/" + id + "/status", HttpMethod.PATCH, authedJsonBody(token, Map.of("status", status)), Map.class);
    }

    /** Checks a patient in, walks to in_consult, backdates the start by `seconds`, then ends the consult. Returns the entry id. */
    private UUID consult(long seconds, String visitType) {
        ResponseEntity<Map> p = exchange("/v1/patients", HttpMethod.POST, authedJsonBody(token, Map.of(
                "name", "St" + UUID.randomUUID().toString().substring(0, 5), "phone", "+91977" + (100000 + (int) (Math.random() * 899999)) + "0",
                "dob", "1980-01-01", "gender", "male")), Map.class);
        ResponseEntity<Map> c = exchange("/v1/queue/check-in", HttpMethod.POST, authedJsonBody(token, Map.of(
                "patientId", p.getBody().get("id"), "doctorId", doctor.id().toString())), Map.class);
        UUID entry = UUID.fromString((String) c.getBody().get("id"));
        if ("procedure".equals(visitType)) {
            inTenantTx(tenant.id(), () -> jdbc.update("UPDATE queue_entries SET class = 'procedure' WHERE id = ?", entry));
        } else if ("follow_up".equals(visitType)) {
            UUID appt = UUID.randomUUID();
            Instant start = Instant.now().plusSeconds(86400L * 400 + (long) (Math.random() * 100000) * 900);
            try (Connection su = superuserConnection();
                 PreparedStatement ps = su.prepareStatement("INSERT INTO appointments (id, tenant_id, patient_id, doctor_id, start_time, end_time, status, is_follow_up) VALUES (?,?,?,?,?,?,'scheduled',true)")) {
                ps.setObject(1, appt);
                ps.setObject(2, tenant.id());
                ps.setObject(3, UUID.fromString((String) p.getBody().get("id")));
                ps.setObject(4, doctor.id());
                ps.setTimestamp(5, Timestamp.from(start));
                ps.setTimestamp(6, Timestamp.from(start.plusSeconds(900)));
                ps.executeUpdate();
                try (PreparedStatement up = su.prepareStatement("UPDATE queue_entries SET appointment_id = ? WHERE id = ?")) {
                    up.setObject(1, appt);
                    up.setObject(2, entry);
                    up.executeUpdate();
                }
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }
        for (String s : List.of("waiting", "vitals_pending", "vitals_done", "in_consult")) {
            assertThat(patch(entry.toString(), s).getStatusCode()).isEqualTo(HttpStatus.OK);
        }
        inTenantTx(tenant.id(), () -> jdbc.update("UPDATE queue_entry_events SET occurred_at = now() - (? || ' seconds')::interval WHERE queue_entry_id = ? AND to_status = 'in_consult'",
                String.valueOf(seconds), entry));
        assertThat(patch(entry.toString(), "checkout_pending").getStatusCode()).isEqualTo(HttpStatus.OK);
        return entry;
    }

    private Map<String, Object> stats(String visitType) {
        return inTenantTx(tenant.id(), () -> jdbc.queryForList("SELECT * FROM service_time_stats WHERE doctor_id = ? AND visit_type = ?", doctor.id(), visitType))
                .stream().findFirst().orElse(null);
    }

    @Test
    void endingAConsultUpdatesTheStatsRowAtOnceAndTheEndpointShowsIt() {
        setUp();
        assertThat(stats("new")).isNull();
        consult(600, "new");
        Map<String, Object> row = stats("new");
        assertThat(row).isNotNull();
        assertThat(row.get("n")).isEqualTo(1);
        // no working hours and no clinic data: the specialty default (15 min median) is the prior, so the ewma moved a tenth of the way to 10 min
        assertThat((Double) row.get("ewma_mu")).isCloseTo(Math.log(900) + 0.1 * (Math.log(600) - Math.log(900)), within(0.01));
        assertThat(row.get("prior_source")).isEqualTo("specialty");

        ResponseEntity<Map> r = exchange("/v1/doctors/" + doctor.id() + "/service-times", HttpMethod.GET, authed(token), Map.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        List<Map<String, Object>> items = (List<Map<String, Object>>) r.getBody().get("items");
        assertThat(items).singleElement().satisfies(i -> {
            assertThat(i.get("visitType")).isEqualTo("new");
            assertThat(i.get("n")).isEqualTo(1);
            assertThat(((Number) i.get("medianMin")).doubleValue()).isBetween(14.0, 15.0);
            assertThat(((Number) i.get("p80Min")).doubleValue()).isGreaterThan(((Number) i.get("medianMin")).doubleValue());
            assertThat(i.get("priorSource")).isEqualTo("specialty");
        });
    }

    @Test
    void aForgottenConsultIsCappedFlaggedAndLearnedAsTheCap() {
        setUp();
        UUID forgotten = consult(4 * 3600, "new"); // 4 h against a 15 min prior median: far over 4x
        Map<String, Object> row = stats("new");
        // learned as 4 x 900 s = 3600 s, not 14400 s
        assertThat((Double) row.get("ewma_mu")).isCloseTo(Math.log(900) + 0.1 * (Math.log(3600) - Math.log(900)), within(0.01));
        assertThat(inTenantTx(tenant.id(), () -> jdbc.queryForObject(
                "SELECT capped FROM queue_entry_events WHERE queue_entry_id = ? AND from_status = 'in_consult'", Boolean.class, forgotten))).isTrue();
        UUID normal = consult(700, "new");
        assertThat(inTenantTx(tenant.id(), () -> jdbc.queryForObject(
                "SELECT capped FROM queue_entry_events WHERE queue_entry_id = ? AND from_status = 'in_consult'", Boolean.class, normal))).isFalse();
    }

    @Test
    void newFollowUpAndProcedureVisitsKeepSeparateDistributions() {
        setUp();
        consult(600, "new");
        consult(300, "follow_up");
        consult(1800, "procedure");
        consult(1900, "procedure");
        assertThat(stats("new").get("n")).isEqualTo(1);
        assertThat(stats("follow_up").get("n")).isEqualTo(1);
        assertThat(stats("procedure").get("n")).isEqualTo(2);
        assertThat((Double) stats("follow_up").get("ewma_mu")).isLessThan((Double) stats("procedure").get("ewma_mu"));
    }

    @Test
    void theNightlyRecomputeReplacesOnlineFiguresWithTheRobustMedianAndP80() {
        setUp();
        for (long s : new long[]{300, 360, 420, 480, 540, 600, 660, 720, 780, 840}) {
            consult(s, "new"); // ten observations: median 570 s
        }
        assertThat(nightly.recompute(tenant.id())).isEqualTo(1);
        Map<String, Object> row = stats("new");
        assertThat(row.get("n")).isEqualTo(10);
        assertThat((Double) row.get("mu_log")).isNotNull();
        // blend 10/30 own (570 s) + 20/30 prior (900 s)
        assertThat(((Number) row.get("median_s")).doubleValue()).isCloseTo(10 / 30.0 * 570 + 20 / 30.0 * 900, within(15.0));
        assertThat(nightly.recompute(tenant.id())).isEqualTo(1); // idempotent
        assertThat(stats("new").get("n")).isEqualTo(10);
    }

    @Test
    void ownerAndOwnDoctorMaySeeItAndOthersAndOtherTenantsMayNot() {
        setUp();
        consult(600, "new");
        UUID noReports = seedRole(tenant.id(), "Desk", false, new ModuleGrant("queue", true, true, true, false, false, false, false));
        SeededStaff desk = seedStaff(tenant, noReports, "d" + UUID.randomUUID().toString().substring(0, 6) + "@a.com", "+9196" + (10000000 + (int) (Math.random() * 8999999)), false);
        String deskToken = loginAndGetAccessToken(desk);
        assertThat(exchange("/v1/doctors/" + doctor.id() + "/service-times", HttpMethod.GET, authed(deskToken), Map.class).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(exchange("/v1/doctors/" + desk.id() + "/service-times", HttpMethod.GET, authed(deskToken), Map.class).getStatusCode()).isEqualTo(HttpStatus.OK); // own, empty

        SeededTenant other = seedTenant();
        SeededStaff otherOwner = seedStaff(other, seedFullAccessRole(other.id()), "o" + UUID.randomUUID().toString().substring(0, 6) + "@a.com", "+9196" + (10000000 + (int) (Math.random() * 8999999)), false);
        ResponseEntity<Map> leak = exchange("/v1/doctors/" + doctor.id() + "/service-times", HttpMethod.GET, authed(loginAndGetAccessToken(otherOwner)), Map.class);
        assertThat((List) leak.getBody().get("items")).isEmpty();
        assertThat(inTenantTx(other.id(), () -> jdbc.queryForObject("SELECT count(*) FROM service_time_stats", Integer.class))).isZero();
        assertThat(exchange("/v1/doctors/" + doctor.id() + "/service-times", HttpMethod.GET, new org.springframework.http.HttpEntity<Void>((Void) null), Map.class).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void nightlyRecomputeForAThousandDoctorsFinishesInUnderTwoMinutes() {
        setUp();
        List<UUID> doctors = new ArrayList<>();
        try (Connection su = superuserConnection()) {
            su.setAutoCommit(false);
            try (PreparedStatement ps = su.prepareStatement("INSERT INTO queue_entry_events (tenant_id, queue_entry_id, encounter_id, doctor_id, clinic_date, from_status, to_status, kind, occurred_at, source) VALUES (?,?,?,?,CURRENT_DATE,?,?,'transition',?,'system')")) {
                Instant now = Instant.now();
                for (int d = 0; d < 1000; d++) {
                    UUID doc = UUID.randomUUID();
                    doctors.add(doc);
                    for (int k = 0; k < 5; k++) {
                        UUID entry = UUID.randomUUID();
                        for (int phase = 0; phase < 2; phase++) {
                            ps.setObject(1, tenant.id());
                            ps.setObject(2, entry);
                            ps.setObject(3, entry);
                            ps.setObject(4, doc);
                            ps.setString(5, phase == 0 ? "vitals_done" : "in_consult");
                            ps.setString(6, phase == 0 ? "in_consult" : "checkout_pending");
                            ps.setTimestamp(7, Timestamp.from(now.minusSeconds(3600 - (phase == 0 ? 0 : 300 + 60L * k))));
                            ps.addBatch();
                        }
                    }
                }
                ps.executeBatch();
            }
            su.commit();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        long t0 = System.nanoTime();
        int rows = nightly.recompute(tenant.id());
        long seconds = (System.nanoTime() - t0) / 1_000_000_000L;
        assertThat(rows).isEqualTo(1000);
        assertThat(seconds).as("nightly recompute of 1,000 doctors").isLessThan(120);
        System.out.println("ACC-016 nightly recompute, 1000 doctors: " + seconds + " s");
    }
}
