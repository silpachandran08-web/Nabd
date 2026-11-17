package com.nabd.hms.auth;

import com.nabd.hms.common.ModuleGrant;
import com.nabd.hms.support.ApiTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/** S23 QA (t1) PLT-013: review replay and race, device label boundaries, and self-review. */
@SuppressWarnings({"unchecked", "rawtypes"})
class S23QaPlt013BreakGlassReviewApiTest extends ApiTestBase {

    private static final AtomicInteger SEQ = new AtomicInteger((int) (System.nanoTime() % 300_000) + 200_000);

    private record Clinic(SeededTenant t, SeededStaff owner, String ownerToken, UUID doctorRole) {
    }

    private static String phone() {
        return "+9188" + String.format("%08d", SEQ.incrementAndGet());
    }

    private Clinic clinic() {
        SeededTenant t = seedTenant();
        UUID ownerRole = seedRole(t.id(), "Owner", true, new ModuleGrant("staff", true, true, true, true, true, true, true),
                new ModuleGrant("compliance", true, false, false, true, true, false, true),
                new ModuleGrant("clinical", true, false, false, false, false, false, false, true));
        SeededStaff owner = seedStaff(t, ownerRole, "q13o" + SEQ.incrementAndGet() + "@q.com", phone(), false);
        return new Clinic(t, owner, loginAndGetAccessToken(owner), seedBreakGlassDoctorRole(t.id()));
    }

    private UUID endedGrant(Clinic c, String doctorToken) {
        ResponseEntity<Map> r = activateBreakGlass(doctorToken, "Collapsed at reception, unknown history", null);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        UUID id = UUID.fromString((String) r.getBody().get("grantId"));
        assertThat(exchange("/v1/auth/break-glass/" + id + "/deactivate", HttpMethod.POST, authedJsonBody(c.ownerToken(), Map.of()), Void.class)
                .getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        return id;
    }

    private String doctor(Clinic c) {
        return loginAndGetAccessToken(seedStaff(c.t(), c.doctorRole(), "q13d" + SEQ.incrementAndGet() + "@q.com", phone(), false));
    }

    private ResponseEntity<Map> review(String token, UUID grantId, String key, Map<String, Object> body) {
        return exchange("/v1/break-glass/grants/" + grantId + "/review", HttpMethod.POST, authedJsonBodyWithHeader(token, "Idempotency-Key", key, body), Map.class);
    }

    private int reviewedAudits(Clinic c, UUID grantId) {
        return inTenantTx(c.t().id(), () -> jdbc.queryForObject(
                "SELECT count(*) FROM audit_log WHERE tenant_id = ? AND action = 'auth.break_glass_reviewed' AND entity_id = ?", Integer.class, c.t().id(), grantId));
    }

    // P13-A01: a client retry with the same Idempotency-Key replays the outcome instead of failing
    @Test
    void p13a01_sameKeyRetryIsAReplay() {
        Clinic c = clinic();
        UUID g = endedGrant(c, doctor(c));
        String key = UUID.randomUUID().toString();
        ResponseEntity<Map> a = review(c.ownerToken(), g, key, Map.of("outcome", "justified"));
        ResponseEntity<Map> b = review(c.ownerToken(), g, key, Map.of("outcome", "justified"));
        System.out.println("P13-A01 first=" + a.getStatusCode() + " retry=" + b.getStatusCode() + " replayed=" + b.getHeaders().getFirst("Idempotent-Replayed"));
        assertThat(a.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(b.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(b.getBody()).isEqualTo(a.getBody());
        assertThat(reviewedAudits(c, g)).isEqualTo(1);
    }

    // P13-A02: two reviewers at once: exactly one wins
    @Test
    void p13a02_parallelReviewsOneWins() {
        Clinic c = clinic();
        UUID g = endedGrant(c, doctor(c));
        CompletableFuture<ResponseEntity<Map>> x = CompletableFuture.supplyAsync(() -> review(c.ownerToken(), g, UUID.randomUUID().toString(), Map.of("outcome", "justified")));
        CompletableFuture<ResponseEntity<Map>> y = CompletableFuture.supplyAsync(() -> review(c.ownerToken(), g, UUID.randomUUID().toString(),
                Map.of("outcome", "not_justified", "note", "Parallel QA review")));
        List<Integer> codes = new ArrayList<>(List.of(x.join().getStatusCode().value(), y.join().getStatusCode().value()));
        System.out.println("P13-A02 parallel=" + codes);
        assertThat(codes).containsExactlyInAnyOrder(200, 409);
        assertThat(reviewedAudits(c, g)).isEqualTo(1);
    }

    private String loginWithLabel(SeededStaff s, String label) {
        HttpEntity<String> body = jsonBody(Map.of("tenantSlug", s.slug(), "email", s.email(), "pin", STAFF_PIN));
        HttpHeaders h = new HttpHeaders();
        h.addAll(body.getHeaders());
        h.set("X-Device-Label", label);
        ResponseEntity<Map> r = exchange("/v1/auth/login", HttpMethod.POST, new HttpEntity<>(body.getBody(), h), Map.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        return inTenantTx(s.tenantId(), () -> jdbc.queryForObject(
                "SELECT device_label FROM sessions WHERE staff_id = ? ORDER BY created_at DESC LIMIT 1", String.class, s.id()));
    }

    // P13-A03: device label boundaries (64 kept, 65 cut, blank -> placeholder, tab collapsed)
    @Test
    void p13a03_deviceLabelBoundaries() {
        Clinic c = clinic();
        SeededStaff s = seedStaff(c.t(), c.doctorRole(), "q13l" + SEQ.incrementAndGet() + "@q.com", phone(), false);
        String l64 = "D".repeat(64);
        String r64 = loginWithLabel(s, l64);
        String r65 = loginWithLabel(s, "E".repeat(65));
        String blank = loginWithLabel(s, " \t ");
        String tab = loginWithLabel(s, "Ward\tPC");
        System.out.println("P13-A03 64=" + r64.length() + " 65=" + r65.length() + " blank='" + blank + "' tab='" + tab + "'");
        assertThat(r64).isEqualTo(l64);
        assertThat(r65).isEqualTo("E".repeat(64));
        assertThat(blank).isEqualTo("Unknown device");
        assertThat(tab).isEqualTo("Ward PC");
    }

    // P13-A04: a compliance approver (not the owner) who used break-glass reviews their own use
    @Test
    void p13a04_aNonOwnerApproverReviewingTheirOwnUse() {
        Clinic c = clinic();
        UUID role = seedRole(c.t().id(), "Lead doctor", false, new ModuleGrant("queue", true, true, false, false, false, false, false),
                new ModuleGrant("clinical", true, false, false, false, false, false, false, true),
                new ModuleGrant("compliance", true, false, false, false, true, false, false));
        String lead = loginAndGetAccessToken(seedStaff(c.t(), role, "q13x" + SEQ.incrementAndGet() + "@q.com", phone(), false));
        UUID g = endedGrant(c, lead);
        ResponseEntity<Map> r = review(lead, g, UUID.randomUUID().toString(), Map.of("outcome", "justified"));
        System.out.println("P13-A04 self-review by non-owner approver=" + r.getStatusCode() + " " + r.getBody());
        // recorded as an observation: the spec does not forbid it; e9 allows the owner to self-review by design
    }
}
