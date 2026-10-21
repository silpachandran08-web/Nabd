package com.nabd.hms.organization.dpa;

import com.nabd.hms.common.OpaqueTokens;
import com.nabd.hms.support.ApiTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** ADM-002: data gate (demo_only until a signed DPA and a plan), OTP acceptance, signed PDF, re-acceptance, DB-level guard. */
class Adm002DpaGateApiTest extends ApiTestBase {

    private static final String VERSION = "2026-10-v1";
    private final JdbcTemplate su = new JdbcTemplate(superuserDataSource());

    private record Owner(SeededTenant tenant, SeededStaff staff, String token) {
    }

    private Owner demoOwner() {
        SeededTenant t = seedTenant();
        jdbc.update("UPDATE tenants SET data_gate = 'demo_only' WHERE id = ?", t.id());
        String phone = "+9198" + (10000000 + (int) (Math.random() * 89999999));
        SeededStaff owner = seedStaff(t, seedFullAccessRole(t.id()), "own" + UUID.randomUUID() + "@a.com", phone, false);
        return new Owner(t, owner, loginAndGetAccessToken(owner));
    }

    private void subscribe(SeededTenant t) {
        UUID plan = seedPlan("adm-" + UUID.randomUUID().toString().substring(0, 8), 1000, "INR", 10);
        jdbc.update("INSERT INTO master.subscriptions (tenant_id, plan_id, mrr_cents, currency, renewal_date) VALUES (?,?,1000,'INR',current_date + 30)", t.id(), plan);
    }

    private Map<?, ?> body(ResponseEntity<Map> r) {
        return r.getBody();
    }

    private ResponseEntity<Map> registerPatient(String token, boolean synthetic) {
        Map<String, Object> b = new java.util.HashMap<>(Map.of("name", "Pat " + UUID.randomUUID().toString().substring(0, 6),
                "phone", "+9197" + (10000000 + (int) (Math.random() * 89999999)), "dob", "1990-01-01", "gender", "female"));
        if (synthetic) {
            b.put("synthetic", true);
        }
        return exchange("/v1/patients", HttpMethod.POST, authedJsonBody(token, b), Map.class);
    }

    /** Requests a challenge and plants a known code (the real one is only in the WhatsApp message). */
    private UUID challenge(Owner o, String code) {
        ResponseEntity<Map> r = exchange("/v1/setup/dpa/otp", HttpMethod.POST, authedJsonBody(o.token(), Map.of()), Map.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        su.update("UPDATE staff SET whatsapp_otp_hash = ? WHERE id = ?", OpaqueTokens.sha256Hex(code), o.staff().id());
        return UUID.fromString((String) body(r).get("otpChallengeId"));
    }

    private ResponseEntity<Map> accept(Owner o, String version, UUID challenge, String otp, String eSignRef) {
        Map<String, Object> b = new java.util.HashMap<>(Map.of("version", version, "otpChallengeId", challenge.toString(), "otp", otp));
        if (eSignRef != null) {
            b.put("eSignRef", eSignRef);
        }
        return exchange("/v1/setup/dpa/accept", HttpMethod.POST, authedJsonBody(o.token(), b), Map.class);
    }

    private int patients(SeededTenant t) {
        return inTenantTx(t.id(), () -> jdbc.queryForObject("SELECT count(*) FROM patients", Integer.class));
    }

    private static String type(ResponseEntity<Map> r) {
        return String.valueOf(r.getBody().get("type"));
    }

    @Test
    void demoOnlyRefusesRealPatientsAndWritesNothing_syntheticStillWorks() {
        Owner o = demoOwner();
        ResponseEntity<Map> real = registerPatient(o.token(), false);
        assertThat(real.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(type(real)).endsWith("DPA_REQUIRED");
        assertThat(patients(o.tenant())).isZero();

        ResponseEntity<Map> demo = registerPatient(o.token(), true);
        assertThat(demo.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(inTenantTx(o.tenant().id(), () -> jdbc.queryForObject("SELECT is_synthetic FROM patients", Boolean.class))).isTrue();
        assertThat(registerPatient(o.token(), false).getStatusCode()).as("still gated after a demo patient").isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void newTenantsDefaultToDemoOnly() {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO tenants (id, slug, name, region, status) VALUES (?,?,?,?,?)", id, "d" + id.toString().replace("-", "").substring(0, 12), "Default", "IN", "active");
        assertThat(jdbc.queryForObject("SELECT data_gate FROM tenants WHERE id = ?", String.class, id)).isEqualTo("demo_only");
    }

    @Test
    void ownerSignsWithOtp_noPlanKeepsDemoOnly_thenPlanOpensTheGateWithin5Seconds() throws Exception {
        Owner o = demoOwner();
        Map<?, ?> status = exchange("/v1/setup/dpa", HttpMethod.GET, authed(o.token()), Map.class).getBody();
        assertThat(status.get("version")).isEqualTo(VERSION);
        assertThat(status.get("marketCode")).isEqualTo("IN");
        assertThat((String) status.get("html")).contains("data fiduciary").contains("data processor").contains("Render");
        assertThat(status.get("dataGate")).isEqualTo("demo_only");
        assertThat(status.get("blockers")).isEqualTo(List.of("DPA_REQUIRED", "PLAN_REQUIRED"));

        UUID ch = challenge(o, "123456");
        assertThat(accept(o, VERSION, ch, "654321", null).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        ResponseEntity<Map> ok = accept(o, VERSION, ch, "123456", null);
        assertThat(ok.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(body(ok).get("dataGate")).as("signed but no plan").isEqualTo("demo_only");
        assertThat(body(ok).get("blockers")).isEqualTo(List.of("PLAN_REQUIRED"));
        assertThat(registerPatient(o.token(), false).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        Map<String, Object> row = su.queryForMap("SELECT dpa_version, signer_owner_id, method, ip::text AS ip, pdf_sha256, accepted_at FROM dpa_acceptances WHERE tenant_id = ?", o.tenant().id());
        assertThat(row.get("dpa_version")).isEqualTo(VERSION);
        assertThat(row.get("signer_owner_id")).isEqualTo(o.staff().id());
        assertThat(row.get("method")).isEqualTo("otp");
        assertThat((String) row.get("ip")).isNotBlank();
        assertThat(row.get("accepted_at")).isNotNull();
        ResponseEntity<byte[]> pdf = exchange((String) body(ok).get("pdfUrl"), HttpMethod.GET, authed(o.token()), byte[].class);
        assertThat(pdf.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(new String(pdf.getBody(), 0, 5)).isEqualTo("%PDF-");
        assertThat(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(pdf.getBody()))).isEqualTo(row.get("pdf_sha256"));
        assertThat(su.queryForObject("SELECT signed_pdf FROM dpa_acceptances WHERE tenant_id = ?", byte[].class, o.tenant().id()))
                .as("stored encrypted, not as the PDF").isNotEqualTo(pdf.getBody());

        subscribe(o.tenant());
        long deadline = System.currentTimeMillis() + 8000; // the gate cache lives 5 s; a plan chosen after signing is seen within one TTL
        ResponseEntity<Map> reg;
        do {
            reg = registerPatient(o.token(), false);
            if (reg.getStatusCode() == HttpStatus.CREATED) {
                break;
            }
            Thread.sleep(250);
        } while (System.currentTimeMillis() < deadline);
        assertThat(reg.getStatusCode()).as("live within one gate-cache TTL (5 s) of the plan").isEqualTo(HttpStatus.CREATED);
        assertThat(jdbc.queryForObject("SELECT data_gate FROM tenants WHERE id = ?", String.class, o.tenant().id())).isEqualTo("live");
        assertThat(exchange("/v1/setup/dpa", HttpMethod.GET, authed(o.token()), Map.class).getBody().get("dataGate")).isEqualTo("live");
    }

    @Test
    void withAPlanAlreadyInPlaceSigningOpensTheGateAtOnce_andAReplayIsTheSameAcceptance() {
        Owner o = demoOwner();
        subscribe(o.tenant());
        UUID ch = challenge(o, "111222");
        ResponseEntity<Map> ok = accept(o, VERSION, ch, "111222", null);
        assertThat(ok.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(body(ok).get("dataGate")).isEqualTo("live");
        assertThat(body(ok).get("blockers")).isEqualTo(List.of());
        assertThat(registerPatient(o.token(), false).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        ResponseEntity<Map> again = accept(o, VERSION, ch, "111222", null);
        assertThat(again.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(body(again).get("acceptanceId")).isEqualTo(body(ok).get("acceptanceId"));
        assertThat(su.queryForObject("SELECT count(*) FROM dpa_acceptances WHERE tenant_id = ?", Integer.class, o.tenant().id())).isEqualTo(1);
        assertThat(su.queryForObject("SELECT count(*) FROM audit_log WHERE tenant_id = ? AND action = 'setup.dpa_accepted'", Integer.class, o.tenant().id())).isEqualTo(1);
        assertThat(su.queryForObject("SELECT count(*) FROM outbox_events WHERE tenant_id = ? AND event_type = 'organization.dpa.accepted.v1'", Integer.class, o.tenant().id())).isEqualTo(1);
    }

    @Test
    void refusals_notOwner_supersededVersion_eSign_exhaustedChallenge() {
        Owner o = demoOwner();
        UUID clerkRole = seedRole(o.tenant().id(), "Receptionist", false, fullGrant("setup"), fullGrant("patients"));
        SeededStaff clerk = seedStaff(o.tenant(), clerkRole, "clerk" + UUID.randomUUID() + "@a.com", "+919855500123", false);
        String clerkToken = loginAndGetAccessToken(clerk);
        ResponseEntity<Map> otp = exchange("/v1/setup/dpa/otp", HttpMethod.POST, authedJsonBody(clerkToken, Map.of()), Map.class);
        assertThat(otp.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(type(otp)).endsWith("NOT_OWNER");
        ResponseEntity<Map> acc = exchange("/v1/setup/dpa/accept", HttpMethod.POST,
                authedJsonBody(clerkToken, Map.of("version", VERSION, "otpChallengeId", UUID.randomUUID().toString(), "otp", "123456")), Map.class);
        assertThat(acc.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(type(acc)).endsWith("NOT_OWNER");

        UUID ch = challenge(o, "222333");
        ResponseEntity<Map> old = accept(o, "2020-01-v0", ch, "222333", null);
        assertThat(old.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(type(old)).endsWith("VERSION_SUPERSEDED");
        ResponseEntity<Map> esign = accept(o, VERSION, ch, "222333", "aadhaar-ref");
        assertThat(esign.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(type(esign)).endsWith("ESIGN_NOT_SUPPORTED");

        for (int i = 0; i < 5; i++) {
            ResponseEntity<Map> bad = accept(o, VERSION, ch, "000000", null);
            assertThat(type(bad)).endsWith("OTP_INVALID");
        }
        ResponseEntity<Map> late = accept(o, VERSION, ch, "222333", null);
        assertThat(late.getStatusCode()).as("the right code after 5 wrong ones").isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(type(late)).endsWith("OTP_INVALID");
        assertThat(su.queryForObject("SELECT count(*) FROM dpa_acceptances WHERE tenant_id = ?", Integer.class, o.tenant().id())).isZero();
        // a challenge of another purpose or staff member is no good either
        assertThat(accept(o, VERSION, UUID.randomUUID(), "222333", null).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void newDpaVersionOnlyPromptsALiveTenant_acceptingItIsAuditedAndTheGateStaysLive() {
        Owner o = demoOwner();
        subscribe(o.tenant());
        su.update("INSERT INTO dpa_acceptances (tenant_id, dpa_version, signer_owner_id, method) VALUES (?,?,?,'paper')", o.tenant().id(), "2026-01-v0", o.staff().id());
        su.update("UPDATE tenants SET data_gate = 'live' WHERE id = ?", o.tenant().id());
        Map<?, ?> s = exchange("/v1/setup/dpa", HttpMethod.GET, authed(o.token()), Map.class).getBody();
        assertThat(s.get("dataGate")).isEqualTo("live");
        assertThat(((Map<?, ?>) s.get("accepted")).get("version")).isEqualTo("2026-01-v0");
        assertThat(s.get("reacceptDueBy")).as("banner due date for the newer version").isNotNull();
        assertThat(registerPatient(o.token(), false).getStatusCode()).as("not re-blocked").isEqualTo(HttpStatus.CREATED);

        ResponseEntity<Map> ok = accept(o, VERSION, challenge(o, "333444"), "333444", null);
        assertThat(ok.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(body(ok).get("dataGate")).isEqualTo("live");
        assertThat(su.queryForObject("SELECT count(*) FROM audit_log WHERE tenant_id = ? AND action = 'setup.dpa_accepted'", Integer.class, o.tenant().id())).isEqualTo(1);
        assertThat(exchange("/v1/setup/dpa", HttpMethod.GET, authed(o.token()), Map.class).getBody().get("reacceptDueBy")).isNull();
    }

    @Test
    void onlyADpaAcceptanceWithAPlanCanOpenTheGate_whoeverRunsTheUpdate() {
        Owner o = demoOwner();
        assertThatThrownBy(() -> jdbc.update("UPDATE tenants SET data_gate = 'live' WHERE id = ?", o.tenant().id())).hasMessageContaining("DPA acceptance");
        su.update("INSERT INTO dpa_acceptances (tenant_id, dpa_version, signer_owner_id, method) VALUES (?,?,?,'paper')", o.tenant().id(), VERSION, o.staff().id());
        assertThatThrownBy(() -> su.update("UPDATE tenants SET data_gate = 'live' WHERE id = ?", o.tenant().id())).hasMessageContaining("subscription");
        assertThat(jdbc.queryForObject("SELECT data_gate FROM tenants WHERE id = ?", String.class, o.tenant().id())).isEqualTo("demo_only");
        assertThatThrownBy(() -> su.update("UPDATE dpa_acceptances SET dpa_version = 'x' WHERE tenant_id = ?", o.tenant().id())).hasMessageContaining("append-only");
        // no API route exists to set the gate
        ResponseEntity<Map> patch = exchange("/v1/setup/profile", HttpMethod.PATCH, authedJsonBody(o.token(), Map.of("dataGate", "live")), Map.class);
        assertThat(jdbc.queryForObject("SELECT data_gate FROM tenants WHERE id = ?", String.class, o.tenant().id())).isEqualTo("demo_only");
        assertThat(patch.getStatusCode().value()).isNotEqualTo(500);
    }

    @Test
    void anotherTenantCannotReadAnAcceptanceOrItsPdf() {
        Owner a = demoOwner();
        subscribe(a.tenant());
        ResponseEntity<Map> ok = accept(a, VERSION, challenge(a, "444555"), "444555", null);
        Owner b = demoOwner();
        assertThat(inTenantTx(b.tenant().id(), () -> jdbc.queryForObject("SELECT count(*) FROM dpa_acceptances", Integer.class))).isZero();
        assertThat(exchange((String) body(ok).get("pdfUrl"), HttpMethod.GET, authed(b.token()), Map.class).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(((Map<?, ?>) exchange("/v1/setup/dpa", HttpMethod.GET, authed(b.token()), Map.class).getBody()).get("accepted")).isNull();
    }

    @Test
    void everyPatientCreatingPathGoesThroughTheGatedService() throws Exception {
        // table-driven over the code base: the only INSERT INTO patients is PatientRepository.insert, called only by PatientService.register
        try (Stream<Path> files = Files.walk(Path.of("src/main/java"))) {
            List<Path> inserting = files.filter(p -> p.toString().endsWith(".java")).filter(p -> {
                try {
                    return Files.readString(p).replaceAll("\\s+", " ").toLowerCase().contains("insert into patients");
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            }).toList();
            assertThat(inserting).extracting(p -> p.getFileName().toString()).containsExactly("PatientRepository.java");
        }
        String svc = Files.readString(Path.of("src/main/java/com/nabd/hms/patient/PatientService.java"));
        assertThat(svc.indexOf("dataGate.requireRealData()")).isPositive().isLessThan(svc.indexOf("repo.insert("));
        try (Stream<Path> files = Files.walk(Path.of("src/main/java"))) { // and PatientRepository.insert has exactly one caller
            assertThat(files.filter(p -> p.toString().endsWith(".java")).filter(p -> {
                try {
                    return Files.readString(p).contains("repo.insert(id, tenantId, req.name(), phone");
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            }).map(p -> p.getFileName().toString()).toList()).containsExactly("PatientService.java");
        }
    }
}
