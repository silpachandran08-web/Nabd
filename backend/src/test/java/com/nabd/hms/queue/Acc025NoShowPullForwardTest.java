package com.nabd.hms.queue;

import com.nabd.hms.common.ClinicClock;
import com.nabd.hms.support.ApiTestBase;
import com.nabd.hms.support.ValkeyTestSupport;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** ACC-025: marking a no-show recomputes the ETAs of the patients behind within 5 s (they move up). Real clock, real Valkey projection. */
@SuppressWarnings({"rawtypes", "unchecked"})
class Acc025NoShowPullForwardTest extends ApiTestBase {

    static final ValkeyTestSupport VALKEY = ValkeyTestSupport.start();

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("app.valkey.url", VALKEY::url);
    }

    @AfterAll
    static void stop() {
        VALKEY.close();
    }

    @Autowired StringRedisTemplate valkey;
    @Autowired ClinicClock clock;

    /** A zone whose local time is about 12:00 now, so "now - 20 min" and "now + 5 min" are always on today's clinic date. */
    private static String noonZone() {
        int h = 12 - ZonedDateTime.now(ZoneOffset.UTC).getHour();
        if (h > 12) h -= 24;
        return h == 0 ? "Etc/GMT" : "Etc/GMT" + (h > 0 ? "-" : "+") + Math.abs(h);
    }

    private UUID appt(SeededTenant t, SeededStaff doctor, String patientId, Instant start) {
        UUID id = UUID.randomUUID();
        try (Connection su = superuserConnection();
             PreparedStatement ps = su.prepareStatement("INSERT INTO appointments (id, tenant_id, patient_id, doctor_id, start_time, end_time, status) VALUES (?,?,?,?,?,?, 'scheduled')")) {
            ps.setObject(1, id);
            ps.setObject(2, t.id());
            ps.setObject(3, UUID.fromString(patientId));
            ps.setObject(4, doctor.id());
            ps.setTimestamp(5, Timestamp.from(start));
            ps.setTimestamp(6, Timestamp.from(start.plusSeconds(900)));
            ps.executeUpdate();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        return id;
    }

    private String patient(String token, String name, int n) {
        ResponseEntity<Map> p = exchange("/v1/patients", HttpMethod.POST, authedJsonBody(token, Map.of("name", name + " " + UUID.randomUUID().toString().substring(0, 6),
                "phone", "+91975" + (1000000 + n), "dob", "1985-02-0" + (1 + n), "gender", "male")), Map.class);
        assertThat(p.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return (String) p.getBody().get("id");
    }

    private static int position(Object field) {
        return field == null ? -1 : Integer.parseInt(field.toString().split("\\|")[0]);
    }

    @Test
    void ac5_theQueueBehindAMarkedNoShowIsRecomputedWithinFiveSeconds() throws Exception {
        SeededTenant tenant = seedTenant();
        jdbc.update("UPDATE tenants SET timezone = ? WHERE id = ?", noonZone(), tenant.id());
        SeededStaff doctor = seedStaff(tenant, seedFullAccessRole(tenant.id()), "pf" + UUID.randomUUID().toString().substring(0, 6) + "@a.com", "+9196" + (1000000 + (int) (Math.random() * 8999999)), false);
        String token = loginAndGetAccessToken(doctor);
        Instant now = Instant.now();
        UUID a = appt(tenant, doctor, patient(token, "Noshow Alpha", 1), now.minus(Duration.ofMinutes(20)));
        UUID c = appt(tenant, doctor, patient(token, "Behind Gamma", 2), now.plus(Duration.ofMinutes(5)));
        ResponseEntity<Map> in = exchange("/v1/queue/check-in", HttpMethod.POST, authedJsonBody(token, Map.of(
                "patientId", patient(token, "Waiting Beta", 3), "doctorId", doctor.id().toString())), Map.class);
        assertThat(in.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        String key = EtaProjection.hashKey(tenant.id(), doctor.id(), clock.today(tenant.id()));
        Map<Object, Object> before = Map.of();
        long end = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (System.nanoTime() < end && !(before.containsKey("a:" + a) && before.containsKey("a:" + c))) {
            before = valkey.opsForHash().entries(key);
            Thread.sleep(50);
        }
        assertThat(before).containsKeys("a:" + a, "a:" + c);
        int positionBefore = position(before.get("a:" + c));
        Thread.sleep(600); // the check-in burst's debounced recompute is done

        long t0 = System.nanoTime();
        assertThat(exchange("/v1/appointments/" + a + "/no-show", HttpMethod.POST, authedJsonBody(token, Map.of()), Map.class).getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<Object, Object> after = before;
        while (System.nanoTime() - t0 < 5_000_000_000L) {
            after = valkey.opsForHash().entries(key);
            if (!after.containsKey("a:" + a) && position(after.get("a:" + c)) < positionBefore) {
                break;
            }
            Thread.sleep(50);
        }
        long ms = (System.nanoTime() - t0) / 1_000_000;
        System.out.println("ACC-025 no-show to recomputed ETAs: " + ms + " ms");
        assertThat(after).as("the no-show is gone from the projection").doesNotContainKey("a:" + a);
        assertThat(position(after.get("a:" + c))).as("the patient behind moved up").isLessThan(positionBefore);
        assertThat(ms).isLessThan(5000);
        assertThat(Long.parseLong(after.get("a:" + c).toString().split("\\|")[1])).as("and is expected earlier").isLessThanOrEqualTo(Long.parseLong(before.get("a:" + c).toString().split("\\|")[1]));
    }
}
