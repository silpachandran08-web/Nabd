package com.nabd.hms.queue;

import com.nabd.hms.support.ApiTestBase;
import com.nabd.hms.support.MutableClock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.sql.Time;
import java.time.LocalTime;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PLT-028: a walk-in checked in at 23:59:30 clinic time lands on that clinic day's queue (and its evening session),
 * and one at 00:00:30 starts the next day, for India and the Gulf. The clock is frozen, so this never flakes near
 * real midnight. 2026-03-10 is a Tuesday; 00:00:30 Gulf/India is still 2026-03-10 in UTC, so a UTC read would fail.
 */
@Import(ClinicDayCheckInClockApiTest.FrozenClock.class)
class ClinicDayCheckInClockApiTest extends ApiTestBase {

    @Autowired
    MutableClock clock;

    @AfterEach
    void realTime() {
        clock.reset();
    }

    @ParameterizedTest
    @CsvSource({"Asia/Kolkata", "Asia/Dubai", "Asia/Riyadh"})
    void eveningSessionCheckInBelongsToTheSessionsDay(String zone) {
        SeededTenant tenant = seedTenant();
        jdbc.update("UPDATE tenants SET timezone = ? WHERE id = ?", zone, tenant.id());
        UUID roleId = seedFullAccessRole(tenant.id());
        String tag = UUID.randomUUID().toString().substring(0, 6);
        SeededStaff doc = seedStaff(tenant, roleId, "clk" + tag + "@a.com", "+9198" + (10000000 + Math.abs(tag.hashCode() % 89999999)), false);
        // Tuesday (day_of_week 2) evening session, one seat: the second walk-in in the same session must be refused
        inTenantTx(tenant.id(), () -> jdbc.update("INSERT INTO doctor_working_hours (id, tenant_id, doctor_id, day_of_week, start_time, end_time, slot_minutes, max_patients) "
                + "VALUES (?,?,?,?,?,?,?,?)", UUID.randomUUID(), tenant.id(), doc.id(), 2, Time.valueOf(LocalTime.of(18, 0)), Time.valueOf(LocalTime.of(23, 59, 59)), 15, 1));
        String token = loginAndGetAccessToken(doc); // real time, so the JWT is valid for the decoder
        String p1 = registerPatient(token, "Evening Alpha", "1981-02-03", "+9199" + (10000000 + Math.abs((tag + "a").hashCode() % 89999999)));
        String p2 = registerPatient(token, "Midnight Bravo", "1992-05-06", "+9199" + (10000000 + Math.abs((tag + "b").hashCode() % 89999999)));

        clock.setLocal("2026-03-10T23:59:30", zone);
        ResponseEntity<Map> evening = checkIn(token, p1, doc.id());
        assertThat(evening.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(evening.getBody().get("queueDate")).isEqualTo("2026-03-10");
        assertThat(evening.getBody().get("tokenNumber")).isEqualTo(1);

        clock.setLocal("2026-03-10T23:59:45", zone);
        assertThat(checkIn(token, p2, doc.id()).getStatusCode()).as("same evening session is full").isEqualTo(HttpStatus.CONFLICT);

        clock.setLocal("2026-03-11T00:00:30", zone);
        ResponseEntity<Map> next = checkIn(token, p2, doc.id());
        assertThat(next.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(next.getBody().get("queueDate")).isEqualTo("2026-03-11");
        assertThat(next.getBody().get("tokenNumber")).as("token numbering restarts on the new clinic day").isEqualTo(1);
    }

    private String registerPatient(String token, String name, String dob, String phone) {
        ResponseEntity<Map> resp = exchange("/v1/patients", HttpMethod.POST, authedJsonBody(token, Map.of(
                "name", name, "phone", phone, "dob", dob, "gender", "male")), Map.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return (String) resp.getBody().get("id");
    }

    private ResponseEntity<Map> checkIn(String token, String patientId, UUID doctorId) {
        return exchange("/v1/queue/check-in", HttpMethod.POST, authedJsonBody(token, Map.of(
                "patientId", patientId, "doctorId", doctorId.toString())), Map.class);
    }

    @TestConfiguration
    static class FrozenClock {
        @Bean
        @Primary
        MutableClock mutableClock() {
            return new MutableClock();
        }
    }
}
