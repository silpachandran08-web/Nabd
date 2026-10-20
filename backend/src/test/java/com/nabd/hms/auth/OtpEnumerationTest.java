package com.nabd.hms.auth;

import com.nabd.hms.support.ApiTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** PLT-006: the OTP request never reveals whether a clinic or number exists: same status, body and (p95) timing. */
@SuppressWarnings({"rawtypes", "unchecked"})
class OtpEnumerationTest extends ApiTestBase {

    private ResponseEntity<String> request(String slug, String phone) {
        return http.postForEntity(url("/v1/auth/otp/request"), jsonBody(Map.of("tenantSlug", slug, "mobilePhone", phone)), String.class);
    }

    @Test
    void knownUnknownSuspendedAndLimitedAllAnswerTheSame() {
        SeededTenant t = seedTenant();
        String known = "+919944400001";
        seedStaff(t, seedRole(t.id(), "Desk", false), "enum@a.com", known, false);
        SeededTenant suspended = seedTenant();
        jdbc.update("UPDATE tenants SET status = 'suspended' WHERE id = ?", suspended.id());

        List<ResponseEntity<String>> all = List.of(request(t.slug(), known), request(t.slug(), known) /* resend-gap limited */,
                request(t.slug(), "+919944400999"), request("no-such-clinic", known), request(suspended.slug(), known));
        String body = all.get(0).getBody();
        assertThat(body).contains("\"status\":\"sent_if_eligible\"").contains("\"resendAfterSeconds\":60");
        for (ResponseEntity<String> r : all) {
            assertThat(r.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
            assertThat(r.getBody()).isEqualTo(body);
        }
    }

    @Test
    void malformedPhoneIsTheOnly400() {
        SeededTenant t = seedTenant();
        for (String bad : List.of("abc", "12", "+91 98765 43210 12345 678", "98765-4321x")) {
            assertThat(request(t.slug(), bad).getStatusCode()).as(bad).isEqualTo(HttpStatus.BAD_REQUEST);
        }
        assertThat(request(t.slug(), "98123 45671").getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
    }

    @Test
    void p95LatencyForKnownAndUnknownNumbersDiffersByLessThan50ms() {
        SeededTenant t = seedTenant();
        UUID role = seedRole(t.id(), "Desk", false);
        int n = 40;
        List<String> known = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            String phone = "+91993" + String.format("%07d", i);
            seedStaff(t, role, "lat" + i + "@a.com", phone, false);
            known.add(phone);
        }
        for (int i = 0; i < 5; i++) { // warm-up
            request(t.slug(), "+91992" + String.format("%07d", 900 + i));
        }
        List<Long> k = new ArrayList<>();
        List<Long> u = new ArrayList<>();
        for (int i = 0; i < n; i++) { // interleaved so drift hits both equally
            long s0 = System.nanoTime();
            request(t.slug(), known.get(i));
            k.add(System.nanoTime() - s0);
            long s1 = System.nanoTime();
            request(t.slug(), "+91992" + String.format("%07d", i));
            u.add(System.nanoTime() - s1);
        }
        long p95k = p95(k) / 1_000_000;
        long p95u = p95(u) / 1_000_000;
        System.out.printf("PLT-006 p95 known=%dms unknown=%dms%n", p95k, p95u);
        assertThat(Math.abs(p95k - p95u)).isLessThan(50);
    }

    private static long p95(List<Long> xs) {
        List<Long> s = new ArrayList<>(xs);
        Collections.sort(s);
        return s.get((int) Math.ceil(0.95 * s.size()) - 1);
    }
}
