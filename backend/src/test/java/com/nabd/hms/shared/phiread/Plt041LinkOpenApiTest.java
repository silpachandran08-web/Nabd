package com.nabd.hms.shared.phiread;

import com.nabd.hms.support.ApiTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** PLT-041 acceptance: every open attempt of a secure link is a phi_read_log row (outcome, device family, IP), no OTP/UA/content, owner can list them. */
class Plt041LinkOpenApiTest extends ApiTestBase {

    static final String UA = "Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) AppleWebKit/605.1.15 Mobile/15E148 Safari/604.1 SECRET-UA-TAIL";

    @Autowired LinkOpenLog linkOpens;
    @Autowired PhiReadRecorder recorder;

    private static JdbcTemplate su() {
        return new JdbcTemplate(superuserDataSource());
    }

    private UUID seedPatient(UUID tenantId) {
        return inTenantTx(tenantId, () -> jdbc.queryForObject(
                "INSERT INTO patients (tenant_id, name, phone, dob, gender) VALUES (?,?,?,'1990-01-01','other') RETURNING id",
                UUID.class, tenantId, "Link Patient", "+9190" + String.format("%08d", Math.abs(tenantId.hashCode() % 100000000))));
    }

    private void open(UUID tenant, UUID link, UUID patient, LinkOpenLog.Outcome o, String resource, String ip) throws InterruptedException {
        linkOpens.record(new LinkOpenLog.Open(tenant, link, patient, resource, o == LinkOpenLog.Outcome.EXPIRED ? null : UUID.randomUUID(), o, UA, ip, "req-" + link));
        assertThat(recorder.awaitIdle(10_000)).isTrue();
    }

    @Test
    void everyOutcomeWritesARowWithDeviceFamilyAndNoRawUserAgent() throws Exception {
        SeededTenant tenant = seedTenant();
        UUID patient = seedPatient(tenant.id());
        UUID link = UUID.randomUUID();
        open(tenant.id(), link, patient, LinkOpenLog.Outcome.OTP_OK, "rx", "203.0.113.9");
        for (int i = 0; i < 3; i++) {
            open(tenant.id(), link, patient, LinkOpenLog.Outcome.OTP_FAILED, "rx", "203.0.113.9");
        }
        open(tenant.id(), link, patient, LinkOpenLog.Outcome.EXPIRED, "rx", "203.0.113.9");
        open(tenant.id(), link, patient, LinkOpenLog.Outcome.REVOKED, "invoice", "203.0.113.9");

        List<Map<String, Object>> rows = su().queryForList("SELECT * FROM phi_read_log WHERE actor_type = 'patient_link' AND actor_id = ? ORDER BY id", link);
        assertThat(rows).extracting(r -> r.get("outcome")).containsExactly("otp_ok", "otp_failed", "otp_failed", "otp_failed", "expired", "revoked");
        assertThat(rows).allSatisfy(r -> {
            assertThat(r.get("device_family")).isEqualTo("iPhone");
            assertThat(r.get("tenant_id")).isEqualTo(tenant.id());
            assertThat(r.get("patient_id")).isEqualTo(patient);
            assertThat(r.get("read_at")).isNotNull();
            assertThat(r.toString()).doesNotContain("SECRET-UA-TAIL").doesNotContain("Mozilla");
        });
        assertThat(rows.get(4).get("resource_id")).isNull(); // expired: nothing looked up, no PHI
    }

    @Test
    void invalidIpAndResourceAreNotStored() throws Exception {
        SeededTenant tenant = seedTenant();
        UUID patient = seedPatient(tenant.id());
        UUID link = UUID.randomUUID();
        open(tenant.id(), link, patient, LinkOpenLog.Outcome.OTP_OK, "Rx; DROP", "not-an-ip<script>");
        Map<String, Object> r = su().queryForMap("SELECT * FROM phi_read_log WHERE actor_id = ?", link);
        assertThat(r.get("ip")).isNull();
        assertThat(r.get("resource_type")).isEqualTo("unknown");
    }

    @Test
    void openEmitsTheCatalogueEvent() throws Exception {
        SeededTenant tenant = seedTenant();
        UUID patient = seedPatient(tenant.id());
        UUID link = UUID.randomUUID();
        open(tenant.id(), link, patient, LinkOpenLog.Outcome.OTP_FAILED, "report", "203.0.113.9");
        Map<String, Object> ev = su().queryForMap("SELECT payload::text AS p FROM outbox_events WHERE event_type = 'patient.link.opened.v1' AND tenant_id = ?", tenant.id());
        assertThat((String) ev.get("p")).contains(link.toString()).contains("otp_failed").contains("iPhone").doesNotContain("SECRET-UA-TAIL");
    }

    @Test
    void ownerListsOpensOthersAreRefused() throws Exception {
        SeededTenant tenant = seedTenant();
        UUID patient = seedPatient(tenant.id());
        UUID link = UUID.randomUUID();
        open(tenant.id(), link, patient, LinkOpenLog.Outcome.OTP_OK, "rx", "203.0.113.9");
        SeededTenant other = seedTenant();
        open(other.id(), UUID.randomUUID(), seedPatient(other.id()), LinkOpenLog.Outcome.OTP_OK, "rx", "198.51.100.1");

        SeededStaff owner = seedStaff(tenant, seedFullAccessRole(tenant.id()), "o@plt41.com", "+919400004101", false);
        ResponseEntity<Map> ok = exchange("/v1/phi-read/link-opens?patientId=" + patient, HttpMethod.GET, authed(loginAndGetAccessToken(owner)), Map.class);
        assertThat(ok.getStatusCode()).isEqualTo(HttpStatus.OK);
        List<Map> items = (List<Map>) ok.getBody().get("items");
        assertThat(items).hasSize(1);
        assertThat(items.get(0)).containsEntry("outcome", "otp_ok").containsEntry("deviceFamily", "iPhone").containsEntry("linkId", link.toString());
        assertThat(items.get(0).toString()).doesNotContain("SECRET-UA-TAIL");

        UUID reception = seedRole(tenant.id(), "Reception", false, fullGrant("patients"));
        SeededStaff rec = seedStaff(tenant, reception, "r@plt41.com", "+919400004102", false);
        ResponseEntity<Map> denied = exchange("/v1/phi-read/link-opens", HttpMethod.GET, authed(loginAndGetAccessToken(rec)), Map.class);
        assertThat(denied.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        ResponseEntity<Map> bad = exchange("/v1/phi-read/link-opens?limit=0", HttpMethod.GET, authed(loginAndGetAccessToken(owner)), Map.class);
        assertThat(bad.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }
}
