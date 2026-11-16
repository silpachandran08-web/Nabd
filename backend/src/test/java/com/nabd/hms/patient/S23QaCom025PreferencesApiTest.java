package com.nabd.hms.patient;

import com.nabd.hms.support.ApiTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/** S23 QA (t1) COM-025: caregiver refs from another clinic or made up, bad channel, and the pending-proxy rule. */
@SuppressWarnings({"unchecked", "rawtypes"})
class S23QaCom025PreferencesApiTest extends ApiTestBase {

    private static final AtomicInteger SEQ = new AtomicInteger((int) (System.nanoTime() % 300_000) + 800_000);

    private static String phone() {
        return "+9187" + String.format("%08d", SEQ.incrementAndGet());
    }

    private record Clinic(SeededTenant t, String owner) {
    }

    private Clinic clinic() {
        SeededTenant t = seedTenant();
        return new Clinic(t, loginAndGetAccessToken(seedStaff(t, seedFullAccessRole(t.id()), "q25" + SEQ.incrementAndGet() + "@q.com", phone(), false)));
    }

    private UUID patient(SeededTenant t, String name, String dob) {
        UUID id = inTenantTx(t.id(), () -> jdbc.queryForObject("INSERT INTO patients (tenant_id, name, phone, dob, gender) VALUES (?,?,?,?::date,'male') RETURNING id",
                UUID.class, t.id(), name, phone(), dob));
        inTenantTx(t.id(), () -> jdbc.update("INSERT INTO consents (tenant_id, patient_id, consent_type) VALUES (?,?,'data_processing'),(?,?,'messaging')", t.id(), id, t.id(), id));
        return id;
    }

    private static Map<String, Object> prefs(String channel, List<Map<String, Object>> caregivers) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("language", null);
        m.put("channel", channel);
        m.put("categories", Map.of());
        m.put("caregivers", caregivers);
        return m;
    }

    private ResponseEntity<Map> put(Clinic c, UUID patient, Map<String, Object> body) {
        return exchange("/v1/patients/" + patient + "/notification-preferences", HttpMethod.PUT, authedJsonBody(c.owner(), body), Map.class);
    }

    // C25-A01 + A02: a caregiver from another clinic or a made-up id is 422 (never 500, never saved); a bad channel is 400
    @Test
    void c25a01_foreignOrUnknownCaregiverIs422_badChannel400() {
        Clinic a = clinic();
        Clinic b = clinic();
        UUID subject = patient(a.t(), "Qa Subject", "1950-01-01");
        UUID foreign = patient(b.t(), "Qa Foreign", "1980-01-01");
        ResponseEntity<Map> f = put(a, subject, prefs("whatsapp", List.of(Map.of("personRef", foreign.toString(), "categories", List.of("careReminders")))));
        ResponseEntity<Map> u = put(a, subject, prefs("whatsapp", List.of(Map.of("personRef", UUID.randomUUID().toString(), "categories", List.of("recalls")))));
        ResponseEntity<Map> ch = put(a, subject, prefs("fax", List.of()));
        System.out.println("C25-A01 foreign=" + f.getStatusCode() + " " + f.getBody() + " unknown=" + u.getStatusCode() + " channel fax=" + ch.getStatusCode());
        assertThat(f.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(f.getBody()).containsEntry("code", "CAREGIVER_NOT_AUTHORISED");
        assertThat(u.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(ch.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(inTenantTx(a.t().id(), () -> jdbc.queryForObject("SELECT count(*) FROM patient_notification_preferences WHERE patient_id = ?", Integer.class, subject))).isZero();
    }

    // C25-A03: spec says "active proxy link"; a pending (not yet accepted) proxy is recorded here
    @Test
    void c25a03_aPendingProxyIsOfferedAsCaregiver_observation() {
        Clinic c = clinic();
        UUID father = patient(c.t(), "Qa Father", "1948-01-01");
        UUID son = patient(c.t(), "Qa Son", "1979-01-01");
        inTenantTx(c.t().id(), () -> jdbc.update("INSERT INTO patient_proxies (tenant_id, proxy_patient_id, subject_patient_id, relation, scope, status) "
                + "VALUES (?,?,?,'son','BOOK_ONLY','pending')", c.t().id(), son, father));
        Map<String, Object> view = exchange("/v1/patients/" + father + "/notification-preferences", HttpMethod.GET, authed(c.owner()), Map.class).getBody();
        ResponseEntity<Map> add = put(c, father, prefs("whatsapp", List.of(Map.of("personRef", son.toString(), "categories", List.of("careReminders")))));
        System.out.println("C25-A03 pending proxy eligible=" + view.get("eligibleCaregivers") + " add=" + add.getStatusCode());
    }
}
