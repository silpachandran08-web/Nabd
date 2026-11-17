package com.nabd.hms.patient;

import com.nabd.hms.patient.internal.PhoneReverificationService;
import com.nabd.hms.support.ApiTestBase;
import com.nabd.hms.support.InternalTestTokens;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.JwtEncoder;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** COM-041 (Core side): the Edge's re-verification steps are audited, a lock alerts the desk, a desk number change flags the numbers,
 * and secure links are held while the recipient's number is not verified. */
@SuppressWarnings({"rawtypes", "unchecked"})
class Com041PhoneReverificationApiTest extends ApiTestBase {

    private static final SecureRandom RND = new SecureRandom();
    @Autowired JwtEncoder encoder;
    private final JdbcTemplate su = new JdbcTemplate(superuserDataSource());

    private record Fx(SeededTenant t, String edge, UUID patient, String phone, String hash) {
    }

    private static String phone() {
        return "+9198" + String.format("%08d", RND.nextInt(100_000_000));
    }

    private Fx fx() {
        SeededTenant t = seedTenant();
        String phone = phone();
        UUID p = inTenantTx(t.id(), () -> jdbc.queryForObject("INSERT INTO patients (tenant_id, name, phone, dob, gender) VALUES (?,'Asha Rao',?,'1988-08-14','female') RETURNING id",
                UUID.class, t.id(), phone));
        return new Fx(t, InternalTestTokens.mint(encoder, "patient-access-edge", t.id()), p, phone, PhoneReverificationService.hashOf(phone));
    }

    private String base(Fx f, String hash) {
        return "/internal/v1/tenants/" + f.t().id() + "/households/" + hash + "/reverification";
    }

    private String state(Fx f, String hash) {
        ResponseEntity<Map> r = exchange(base(f, hash), HttpMethod.GET, new HttpEntity<>(bearer(f.edge())), Map.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        return (String) r.getBody().get("state");
    }

    private HttpStatus report(Fx f, Map<String, Object> step) {
        return HttpStatus.valueOf(exchange(base(f, f.hash()), HttpMethod.POST, authedJsonBody(f.edge(), step), Map.class).getStatusCode().value());
    }

    private boolean check(Fx f, String dob) {
        ResponseEntity<Map> r = exchange(base(f, f.hash()) + "/check", HttpMethod.POST, authedJsonBody(f.edge(), Map.of("dob", dob)), Map.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        return (Boolean) r.getBody().get("match");
    }

    private static HttpHeaders bearer(String token) {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(token);
        return h;
    }

    private int audits(Fx f, String action) {
        return su.queryForObject("SELECT count(*) FROM audit_log WHERE tenant_id = ? AND action = ?", Integer.class, f.t().id(), action);
    }

    @Test
    void everyEdgeStepIsAuditedThe3rdFailureLocksAndAlertsTheDeskAndSuccessClearsWithMethodAndTime() {
        Fx f = fx();
        assertThat(state(f, f.hash())).isEqualTo("verified");
        assertThat(report(f, Map.of("step", "flagged", "reason", "changed_number"))).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(state(f, f.hash())).isEqualTo("needs_reverify");
        assertThat(audits(f, "phone.reverify.flagged")).isEqualTo(1);

        assertThat(check(f, "1970-01-01")).isFalse();
        assertThat(check(f, "1988-08-14")).isTrue();
        assertThat(audits(f, "phone.reverify.second_factor")).isEqualTo(2);
        assertThat(report(f, Map.of("step", "failed", "attempt", 1))).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(report(f, Map.of("step", "failed", "attempt", 2))).isEqualTo(HttpStatus.NO_CONTENT);
        Instant until = Instant.now().plus(24, ChronoUnit.HOURS);
        assertThat(report(f, Map.of("step", "locked", "attempt", 3, "lockedUntil", until.toString()))).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(state(f, f.hash())).isEqualTo("locked");
        assertThat(su.queryForObject("SELECT count(*) FROM patient_desk_tasks WHERE tenant_id = ? AND kind = 'phone_reverify_locked' AND patient_id = ? AND status = 'open'",
                Integer.class, f.t().id(), f.patient())).isEqualTo(1);
        assertThat(audits(f, "phone.reverify.locked")).isEqualTo(1);
        assertThat(check(f, "1988-08-14")).as("no second factor is accepted while locked").isFalse();

        assertThat(report(f, Map.of("step", "verified", "method", "otp+dob"))).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(state(f, f.hash())).isEqualTo("verified");
        Map<String, Object> v = su.queryForMap("SELECT after::text AS after, created_at FROM audit_log WHERE tenant_id = ? AND action = 'phone.reverify.verified'", f.t().id());
        assertThat((String) v.get("after")).contains("\"method\": \"otp+dob\"");
        assertThat(v.get("created_at")).isNotNull();
        String all = su.queryForObject("SELECT string_agg(coalesce(after::text, ''), ' ') FROM audit_log WHERE tenant_id = ? AND action LIKE 'phone.reverify.%'", String.class, f.t().id());
        assertThat(all).doesNotContain("1988").doesNotContain(f.phone().substring(1)).doesNotContain(f.hash());
    }

    @Test
    void incompleteStepsBadHashesAndOtherClinicsAreRefused() {
        Fx f = fx();
        assertThat(report(f, Map.of("step", "flagged"))).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(report(f, Map.of("step", "locked", "attempt", 3))).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(report(f, Map.of("step", "erase"))).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(exchange(base(f, "not-a-hash"), HttpMethod.GET, new HttpEntity<>(bearer(f.edge())), Map.class).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        Fx other = fx();
        assertThat(exchange(base(f, f.hash()), HttpMethod.GET, new HttpEntity<>(bearer(other.edge())), Map.class).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(exchange(base(f, f.hash()), HttpMethod.GET, HttpEntity.EMPTY, Map.class).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        // tenant isolation of the table itself
        report(f, Map.of("step", "flagged", "reason", "silence"));
        assertThat(inTenantTx(other.t().id(), () -> jdbc.queryForObject("SELECT count(*) FROM phone_reverifications", Integer.class))).isZero();
    }

    @Test
    void aDeskNumberChangeFlagsTheOldAndNewNumberAndSecureLinksWaitForReverification() throws Exception {
        Fx f = fx();
        String staff = loginAndGetAccessToken(seedStaff(f.t(), seedFullAccessRole(f.t().id()), "c41" + UUID.randomUUID().toString().substring(0, 6) + "@a.com", phone(), false));
        String newPhone = phone();
        ResponseEntity<Map> r = exchange("/v1/patients/" + f.patient(), HttpMethod.PATCH, authedJsonBody(staff, Map.of("phone", newPhone)), Map.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        String newHash = PhoneReverificationService.hashOf(newPhone);
        assertThat(state(f, f.hash())).isEqualTo("needs_reverify");
        assertThat(state(f, newHash)).isEqualTo("needs_reverify");
        assertThat(su.queryForObject("SELECT actor_type FROM audit_log WHERE tenant_id = ? AND action = 'phone.reverify.flagged' AND entity_id = ?", String.class,
                f.t().id(), f.patient())).isEqualTo("staff");

        String token = link(f);
        ResponseEntity<Map> held = exchange("/v1/public/links/" + token + "/otp", HttpMethod.POST, new HttpEntity<>(json()), Map.class);
        assertThat(held.getStatusCode()).isEqualTo(HttpStatus.LOCKED);
        assertThat(String.valueOf(held.getBody().get("type"))).endsWith("phone-reverify-required");

        Fx onNew = new Fx(f.t(), f.edge(), f.patient(), newPhone, newHash);
        assertThat(report(onNew, Map.of("step", "verified", "method", "otp+dob"))).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(exchange("/v1/public/links/" + token + "/otp", HttpMethod.POST, new HttpEntity<>(json()), Map.class).getStatusCode())
                .isEqualTo(HttpStatus.ACCEPTED);
    }

    /** A document link for the patient inserted directly, with a token we know (IDC-015 never returns it). */
    private String link(Fx f) throws Exception {
        UUID file = UUID.randomUUID();
        inTenantTx(f.t().id(), () -> jdbc.update("INSERT INTO file_objects (id, tenant_id, purpose, storage_key, declared_type, detected_type, size_bytes, status, patient_id) "
                + "VALUES (?,?,'outside_report',?,'application/pdf','application/pdf',1000,'available',?)", file, f.t().id(), "tenant/" + f.t().id() + "/" + file, f.patient()));
        byte[] raw = new byte[48];
        RND.nextBytes(raw);
        ByteBuffer.wrap(raw).putLong(f.t().id().getMostSignificantBits()).putLong(f.t().id().getLeastSignificantBits());
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8)));
        su.update("INSERT INTO secure_links (tenant_id, token_hash, document_type, document_id, patient_id, recipient_patient_id, expires_at, created_by) "
                + "VALUES (?,?,'report',?,?,?, now() + interval '7 days', gen_random_uuid())", f.t().id(), hash, file, f.patient(), f.patient());
        return token;
    }

    private static HttpHeaders json() {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        h.set("User-Agent", "com041-test/1.0");
        return h;
    }
}
