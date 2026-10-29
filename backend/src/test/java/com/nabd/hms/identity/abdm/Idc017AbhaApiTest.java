package com.nabd.hms.identity.abdm;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.nabd.hms.common.ApiException;
import com.nabd.hms.common.ModuleGrant;
import com.nabd.hms.identity.integration.AbhaGateway;
import com.nabd.hms.support.ApiTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/** IDC-017: ABHA verify / create at registration against a fake ABHA gateway (the real ABDM sandbox is Needs external action). */
@Import(Idc017AbhaApiTest.Fake.class)
class Idc017AbhaApiTest extends ApiTestBase {

    static final class FakeAbha implements AbhaGateway {
        final List<String> calls = new CopyOnWriteArrayList<>();
        final AtomicBoolean down = new AtomicBoolean();
        final AtomicReference<Profile> next = new AtomicReference<>();
        final AtomicReference<String> lastIdentifier = new AtomicReference<>();

        private void check() {
            if (down.get()) {
                throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "abdm-unavailable", "ABDM unavailable", "down");
            }
        }

        @Override public String loginOtp(String id) { calls.add("loginOtp"); check(); return "txn-login"; }

        @Override public Profile loginConfirm(String txn, String otp) {
            calls.add("loginConfirm");
            check();
            if (!"123456".equals(otp)) {
                throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "abha-invalid", "ABHA refused", "wrong otp");
            }
            return next.get();
        }

        @Override public String enrolOtp(EnrolMode mode, String identifier) { calls.add("enrolOtp:" + mode); lastIdentifier.set(identifier); check(); return "txn-enrol"; }

        @Override public Profile enrolConfirm(EnrolMode mode, String txn, String otp, String mobile) { calls.add("enrolConfirm"); check(); return next.get(); }
    }

    static final FakeAbha ABHA = new FakeAbha();

    @TestConfiguration
    static class Fake {
        @Bean @Primary AbhaGateway fakeAbhaGateway() { return ABHA; }
    }

    private static final String NUMBER = "91-1234-5678-9012";

    @BeforeEach
    void reset() {
        ABHA.calls.clear();
        ABHA.down.set(false);
        ABHA.next.set(new AbhaGateway.Profile("91123456789012", "asha.rao@abdm", "Asha Rao", "1990-01-01", "F", "9198xxxxxx01"));
    }

    private record Clinic(SeededTenant tenant, String token) {
    }

    private Clinic clinic(boolean abdmOn) {
        SeededTenant t = seedTenant();
        SeededStaff s = seedStaff(t, seedFullAccessRole(t.id()), "abha" + t.slug() + "@a.com", "+9195" + (10000000 + (int) (Math.random() * 89999999)), false);
        if (abdmOn) {
            inTenantTx(t.id(), () -> jdbc.update("INSERT INTO tenant_integrations_abdm (tenant_id, enabled, hfr_id, verified_at) VALUES (?, true, 'HFR-OK-1', now())", t.id()));
        }
        return new Clinic(t, loginAndGetAccessToken(s));
    }

    private String patient(Clinic c, String name) {
        ResponseEntity<Map> r = exchange("/v1/patients", HttpMethod.POST, authedJsonBody(c.token(), Map.of("name", name, "phone", "+9198" + (10000000 + (int) (Math.random() * 89999999)),
                "dob", "1990-01-01", "gender", "female", "confirmedNotDuplicate", true)), Map.class);
        assertThat(r.getStatusCode()).as(String.valueOf(r.getBody())).isEqualTo(HttpStatus.CREATED);
        return (String) r.getBody().get("id");
    }

    private ResponseEntity<Map> verify(Clinic c, String patientId, Map<String, Object> body) {
        return exchange("/v1/patients/" + patientId + "/abha/verify", HttpMethod.POST, authedJsonBody(c.token(), body), Map.class);
    }

    private ResponseEntity<Map> enrol(Clinic c, Map<String, Object> body) {
        return exchange("/v1/abha/enrol", HttpMethod.POST, authedJsonBody(c.token(), body), Map.class);
    }

    private JdbcTemplate su() {
        return new JdbcTemplate(superuserDataSource());
    }

    @Test
    void numberAndOtpVerifyStoresEncryptedWithBlindIndexAndAuditsWithoutTheNumber() {
        Clinic c = clinic(true);
        assertThat(exchange("/v1/abha/status", HttpMethod.GET, authed(c.token()), Map.class).getBody()).containsEntry("enabled", true);
        String p = patient(c, "Asha Rao");
        long t0 = System.nanoTime();
        ResponseEntity<Map> init = verify(c, p, Map.of("step", "init", "abhaNumberOrAddress", NUMBER));
        assertThat(init.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(init.getBody()).containsEntry("txnId", "txn-login").doesNotContainKey("verified");
        ResponseEntity<Map> done = verify(c, p, Map.of("step", "confirm", "txnId", "txn-login", "otp", "123456"));
        assertThat((System.nanoTime() - t0) / 1_000_000_000.0).as("two calls through the API + gateway round trip stay far below the 20 s desk budget").isLessThan(20.0);
        assertThat(done.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(done.getBody()).containsEntry("verified", true).containsEntry("abhaLast4", "9012").containsEntry("abhaAddress", "asha.rao@abdm");

        List<Map<String, Object>> rows = su().queryForList("SELECT id_type, blind_index, value_enc, last4, verified_via FROM patient_identifiers WHERE tenant_id = ? ORDER BY id_type", c.tenant().id());
        assertThat(rows).hasSize(2);
        assertThat(rows.stream().map(r -> r.get("id_type")).toList()).containsExactly("ABHA_ADDRESS", "ABHA_NUMBER");
        for (Map<String, Object> r : rows) {
            assertThat((String) r.get("blind_index")).matches("[0-9a-f]{64}");
            assertThat(new String((byte[]) r.get("value_enc"), java.nio.charset.StandardCharsets.ISO_8859_1)).doesNotContain("91123456789012").doesNotContain("asha.rao");
            assertThat(r.get("verified_via")).isEqualTo("abdm_mobile_otp");
        }
        String audit = su().queryForObject("SELECT string_agg(after::text, ' ') FROM audit_log WHERE tenant_id = ? AND action = 'identity.abha_verified'", String.class, c.tenant().id());
        assertThat(audit).contains("9012").doesNotContain("91123456789012").doesNotContain("9112-3456");
    }

    @Test
    void wrongOtpIs422_badFormatIs422_gatewayDownIs503_nothingIsStored() {
        Clinic c = clinic(true);
        String p = patient(c, "Bina Shah");
        assertThat(verify(c, p, Map.of("step", "init", "abhaNumberOrAddress", "12345")).getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(ABHA.calls).as("a malformed id never reaches ABDM").isEmpty();
        assertThat(verify(c, p, Map.of("step", "confirm", "txnId", "t", "otp", "000000")).getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(verify(c, p, Map.of("step", "confirm", "txnId", "t", "otp", "12")).getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        ABHA.down.set(true);
        assertThat(verify(c, p, Map.of("step", "init", "abhaNumberOrAddress", NUMBER)).getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(su().queryForObject("SELECT count(*) FROM patient_identifiers WHERE tenant_id = ?", Integer.class, c.tenant().id())).isZero();
        assertThat(verify(c, UUID.randomUUID().toString(), Map.of("step", "init", "abhaNumberOrAddress", NUMBER)).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void declinedOrSwitchedOffMeansNoAbdmCallAndRegistrationIsUnaffected() {
        Clinic off = clinic(false);
        String p = patient(off, "No Abha"); // registration completes without any ABHA step
        assertThat(ABHA.calls).isEmpty();
        assertThat(exchange("/v1/abha/status", HttpMethod.GET, authed(off.token()), Map.class).getBody()).containsEntry("enabled", false);
        ResponseEntity<Map> r = verify(off, p, Map.of("step", "init", "abhaNumberOrAddress", NUMBER));
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(String.valueOf(r.getBody().get("type"))).endsWith("abdm-disabled");
        assertThat(enrol(off, Map.of("step", "init", "mode", "mobile", "mobile", "+919800000001")).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(ABHA.calls).isEmpty();
    }

    @Test
    void abhaLinkedToAnotherPatientIs409WithTheExistingPatientForTheMergeFlow_atInitAndAtConfirm() {
        Clinic c = clinic(true);
        String a = patient(c, "Asha Rao");
        String b = patient(c, "Asha R");
        verify(c, a, Map.of("step", "init", "abhaNumberOrAddress", NUMBER));
        assertThat(verify(c, a, Map.of("step", "confirm", "txnId", "txn-login", "otp", "123456")).getStatusCode()).isEqualTo(HttpStatus.OK);
        ABHA.calls.clear();

        ResponseEntity<Map> init = verify(c, b, Map.of("step", "init", "abhaNumberOrAddress", NUMBER));
        assertThat(init.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(String.valueOf(init.getBody().get("type"))).endsWith("abha-linked-elsewhere");
        @SuppressWarnings("unchecked") Map<String, Object> existing = (Map<String, Object>) init.getBody().get("existingPatient");
        assertThat(existing).containsEntry("id", a).containsEntry("name", "Asha Rao");
        assertThat(ABHA.calls).as("no OTP is spent on an id that is already linked").isEmpty();

        assertThat(verify(c, b, Map.of("step", "confirm", "txnId", "txn-login", "otp", "123456")).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(su().queryForObject("SELECT count(*) FROM patient_identifiers WHERE tenant_id = ? AND patient_id = ?::uuid", Integer.class, c.tenant().id(), b)).isZero();
        // re-verifying the owner is fine
        assertThat(verify(c, a, Map.of("step", "confirm", "txnId", "txn-login", "otp", "123456")).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(su().queryForObject("SELECT count(*) FROM patient_identifiers WHERE tenant_id = ?", Integer.class, c.tenant().id())).isEqualTo(2);
    }

    @Test
    void sameAbhaInAnotherClinicIsAllowed_andRlsHidesIdentifiersAcrossTenants() {
        Clinic c1 = clinic(true);
        Clinic c2 = clinic(true);
        String p1 = patient(c1, "Asha Rao");
        String p2 = patient(c2, "Asha Rao");
        for (Map.Entry<Clinic, String> e : Map.of(c1, p1, c2, p2).entrySet()) {
            verify(e.getKey(), e.getValue(), Map.of("step", "init", "abhaNumberOrAddress", NUMBER));
            assertThat(verify(e.getKey(), e.getValue(), Map.of("step", "confirm", "txnId", "txn-login", "otp", "123456")).getStatusCode()).isEqualTo(HttpStatus.OK);
        }
        assertThat(inTenantTx(c1.tenant().id(), () -> jdbc.queryForObject("SELECT count(*) FROM patient_identifiers", Integer.class))).isEqualTo(2);
        assertThat(inTenantTx(c2.tenant().id(), () -> jdbc.queryForObject("SELECT count(*) FROM patient_identifiers WHERE tenant_id = ?", Integer.class, c1.tenant().id()))).isZero();
        assertThat(su().queryForList("SELECT DISTINCT blind_index FROM patient_identifiers WHERE id_type = 'ABHA_NUMBER'", String.class)).as("the index is keyed per tenant").hasSize(2);
        assertThat(verify(c1, p2, Map.of("step", "init", "abhaNumberOrAddress", NUMBER)).getStatusCode()).as("another clinic's patient").isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void aStaffMemberWithoutPatientEditCannotUseIt() {
        Clinic c = clinic(true);
        String p = patient(c, "Cara");
        UUID viewOnly = seedRole(c.tenant().id(), "viewer " + UUID.randomUUID().toString().substring(0, 6), false, new ModuleGrant("patients", true, false, false, false, false, false, false));
        SeededStaff v = seedStaff(c.tenant(), viewOnly, "v" + c.tenant().slug() + "@a.com", "+9195" + (10000000 + (int) (Math.random() * 89999999)), false);
        Clinic viewer = new Clinic(c.tenant(), loginAndGetAccessToken(v));
        assertThat(verify(viewer, p, Map.of("step", "init", "abhaNumberOrAddress", NUMBER)).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(enrol(viewer, Map.of("step", "init", "mode", "mobile", "mobile", "+919800000001")).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void createWithAadhaarOtpLinksTheNewAbhaAndTheAadhaarNumberIsNeverWrittenAnywhere() {
        Clinic c = clinic(true);
        String p = patient(c, "Dev Nair");
        String aadhaar = "234567890123";
        Logger root = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
        ch.qos.logback.classic.Level old = root.getLevel();
        ListAppender<ILoggingEvent> logs = new ListAppender<>();
        logs.start();
        root.setLevel(ch.qos.logback.classic.Level.DEBUG);
        root.addAppender(logs);
        try {
            ResponseEntity<Map> init = enrol(c, Map.of("step", "init", "mode", "aadhaar", "aadhaarNumber", aadhaar));
            assertThat(init.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(init.getBody()).containsEntry("txnId", "txn-enrol");
            assertThat(ABHA.lastIdentifier.get()).as("the gateway (and only it) received the number").isEqualTo(aadhaar);
            ResponseEntity<Map> done = enrol(c, Map.of("step", "confirm", "mode", "aadhaar", "txnId", "txn-enrol", "otp", "123456", "mobile", "+919800000002", "patientId", p));
            assertThat(done.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(done.getBody()).containsEntry("verified", true).containsEntry("abhaLast4", "9012");
            assertThat(enrol(c, Map.of("step", "init", "mode", "aadhaar", "aadhaarNumber", "12345")).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        } finally {
            root.detachAppender(logs);
            root.setLevel(old);
        }
        // server threads only: the test client's own DEBUG logging echoes the request it builds, which is not application output
        String allLogs = String.join("\n", logs.list.stream().filter(e -> e.getThreadName().contains("-exec-")).map(ILoggingEvent::getFormattedMessage).toList());
        assertThat(allLogs).as("log scan: no Aadhaar in any log line").doesNotContain(aadhaar);
        assertThat(su().queryForObject("SELECT verified_via FROM patient_identifiers WHERE tenant_id = ? AND id_type = 'ABHA_NUMBER'", String.class, c.tenant().id())).isEqualTo("abdm_enrol");
        // database scan: no table of the public schema holds the number
        for (String table : su().queryForList("SELECT tablename FROM pg_tables WHERE schemaname = 'public'", String.class)) {
            Integer hits = su().queryForObject("SELECT count(*) FROM \"" + table + "\" t WHERE t::text LIKE ?", Integer.class, "%" + aadhaar + "%");
            assertThat(hits).as("table " + table).isZero();
        }
        // enrol without a patient creates an ABHA and links nobody
        Clinic c2 = clinic(true);
        enrol(c2, Map.of("step", "init", "mode", "mobile", "mobile", "+919800000003"));
        ResponseEntity<Map> free = enrol(c2, Map.of("step", "confirm", "mode", "mobile", "txnId", "txn-enrol", "otp", "123456"));
        assertThat(free.getBody()).containsEntry("verified", true).containsEntry("abhaLast4", "9012");
        assertThat(su().queryForObject("SELECT count(*) FROM patient_identifiers WHERE tenant_id = ?", Integer.class, c2.tenant().id())).isZero();
    }
}
