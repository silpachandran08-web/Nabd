package com.nabd.hms.queue;

import com.nabd.hms.support.ApiTestBase;
import com.nabd.hms.support.MutableClock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.sql.Time;
import java.time.LocalTime;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Independent QA (S11) for PLT-028 AC2 (frozen clock at 23:59:30 clinic time) and the FEF-011 null-timezone check:
 * what clinic day the backend uses when tenants.timezone is null. Shares the engineer's frozen-clock context.
 */
@Import(ClinicDayCheckInClockApiTest.FrozenClock.class)
class S11QaClinicClockTest extends ApiTestBase {

    @Autowired MutableClock clock;

    @AfterEach
    void realTime() {
        clock.reset();
    }

    /** region is immutable after provisioning (V5 trigger): flipped as superuser with triggers off, as SafetyDictionaryApiTest does. */
    private static void forceRegion(java.util.UUID tenantId, String region) {
        try (java.sql.Connection c = superuserConnection(); java.sql.Statement st = c.createStatement()) {
            st.execute("SET session_replication_role = replica");
            st.execute("UPDATE tenants SET region = '" + region + "' WHERE id = '" + tenantId + "'");
        } catch (java.sql.SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    private record Clinic(SeededTenant t, SeededStaff doc, String token) { }

    private Clinic clinic(String zone, String region) {
        SeededTenant t = seedTenant();
        jdbc.update("UPDATE tenants SET timezone = ? WHERE id = ?", zone, t.id());
        if (!"IN".equals(region)) forceRegion(t.id(), region);
        String tag = UUID.randomUUID().toString().substring(0, 6);
        SeededStaff doc = seedStaff(t, seedFullAccessRole(t.id()), "q4clk" + tag + "@a.com", "+9196" + (10000000 + Math.abs(tag.hashCode() % 89999999)), false);
        // Tuesday evening session 18:00-23:59:59 and Wednesday morning, so both clinic days have a session
        inTenantTx(t.id(), () -> {
            jdbc.update("INSERT INTO doctor_working_hours (id, tenant_id, doctor_id, day_of_week, start_time, end_time, slot_minutes, max_patients) VALUES (?,?,?,?,?,?,?,?)",
                    UUID.randomUUID(), t.id(), doc.id(), 2, Time.valueOf(LocalTime.of(18, 0)), Time.valueOf(LocalTime.of(23, 59, 59)), 15, 20);
            jdbc.update("INSERT INTO doctor_working_hours (id, tenant_id, doctor_id, day_of_week, start_time, end_time, slot_minutes, max_patients) VALUES (?,?,?,?,?,?,?,?)",
                    UUID.randomUUID(), t.id(), doc.id(), 3, Time.valueOf(LocalTime.of(0, 0)), Time.valueOf(LocalTime.of(11, 0)), 15, 20);
        });
        return new Clinic(t, doc, loginAndGetAccessToken(doc)); // login at real time: the JWT decoder uses the system clock
    }

    private String patient(Clinic c, String name) {
        String tag = UUID.randomUUID().toString().substring(0, 6);
        ResponseEntity<Map> r = exchange("/v1/patients", HttpMethod.POST, authedJsonBody(c.token, Map.of(
                "name", name + " " + tag.replaceAll("[0-9]", "x"), "phone", "+9193" + (10000000 + Math.abs(tag.hashCode() % 89999999)),
                "dob", "1984-04-04", "gender", "female")), Map.class);
        assertThat(r.getStatusCode()).as(String.valueOf(r.getBody())).isEqualTo(HttpStatus.CREATED);
        return (String) r.getBody().get("id");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> scheduleToday(Clinic c) {
        ResponseEntity<Map> r = exchange("/v1/schedule/day", HttpMethod.GET, authed(c.token), Map.class);
        assertThat(r.getStatusCode()).as(String.valueOf(r.getBody())).isEqualTo(HttpStatus.OK);
        return r.getBody();
    }

    // PLT-028 AC2: frozen at 23:59:30 clinic time an evening walk-in belongs to the session's day; 40 s later the
    // clinic's "today" has moved on while UTC has not (Gulf and India are ahead of UTC)
    @ParameterizedTest
    @CsvSource({"Asia/Dubai,IN", "Asia/Riyadh,KSA", "Asia/Kolkata,IN"})
    void eveningCheckInAt235930IsTheSessionsDayAndTodayRollsAtClinicMidnight(String zone, String region) {
        Clinic c = clinic(zone, region);
        String p = patient(c, "Late Evening");

        clock.setLocal("2026-03-10T23:59:30", zone);
        ResponseEntity<Map> in = exchange("/v1/queue/check-in", HttpMethod.POST, authedJsonBody(c.token, Map.of(
                "patientId", p, "doctorId", c.doc.id().toString())), Map.class);
        assertThat(in.getStatusCode()).as(String.valueOf(in.getBody())).isEqualTo(HttpStatus.CREATED);
        assertThat(in.getBody().get("queueDate")).isEqualTo("2026-03-10");
        Map<String, Object> before = scheduleToday(c);
        assertThat(before).containsEntry("date", "2026-03-10").containsEntry("timezone", zone).containsEntry("today", true);

        clock.setLocal("2026-03-11T00:00:10", zone);
        assertThat(scheduleToday(c)).containsEntry("date", "2026-03-11").containsEntry("today", true);
    }

    // FEF-011 (c): tenants.timezone NULL (the column is nullable, V17). ClinicClock's contract is "a bad or missing value
    // degrades to UTC", which already disagrees with the UI's region default. Found instead: zone() throws an NPE
    // (List.of(null).stream().findFirst()), so every clinic-day endpoint answers 500 for such a tenant.
    @Test
    @Tag("known-defect-PLT-028")
    void nullTimezoneTenantGetsAClinicDayInsteadOfA500() {
        Clinic c = clinic(null, "KSA");
        clock.set(java.time.Instant.parse("2026-03-10T22:30:00Z"));
        Map<String, Object> day = scheduleToday(c);
        assertThat(day.get("timezone")).isIn("UTC", "Asia/Riyadh");
    }

    // FEF-011 (c): the raw null is what the UI sees from /setup/profile, so it applies the region default
    @Test
    void setupProfileReportsTheNullTimezoneAndTheRegion() {
        Clinic c = clinic(null, "KSA");
        ResponseEntity<Map> profile = exchange("/v1/setup/profile", HttpMethod.GET, authed(c.token), Map.class);
        assertThat(profile.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(profile.getBody()).containsKey("timezone");
        assertThat(profile.getBody().get("timezone")).isNull();
        assertThat(profile.getBody().get("region")).isEqualTo("KSA");
    }

    // PLT-028 AC4 companion: an unusable legacy value also degrades to UTC (never a 500)
    @Test
    void unusableTimezoneDegradesToUtc() {
        Clinic c = clinic("IST", "IN");
        clock.set(java.time.Instant.parse("2026-03-10T20:00:00Z"));
        assertThat(scheduleToday(c)).containsEntry("timezone", "UTC").containsEntry("date", "2026-03-10");
    }
}
