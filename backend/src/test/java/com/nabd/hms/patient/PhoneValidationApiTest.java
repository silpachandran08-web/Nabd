package com.nabd.hms.patient;

import com.nabd.hms.common.AuditService;
import com.nabd.hms.common.ModuleGrant;
import com.nabd.hms.support.ApiTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/** ACC-028: phones validated per clinic country, stored E.164; invalid stored numbers listed for correction. */
@SuppressWarnings({"rawtypes", "unchecked"})
class PhoneValidationApiTest extends ApiTestBase {

    private static final AtomicInteger SEQ = new AtomicInteger((int) (System.nanoTime() % 1_000_000));

    @Autowired AuditService audit;
    @Autowired com.nabd.hms.common.TenantScope tenantScope;

    private static String staffMobile() {
        return "+91945" + String.format("%07d", SEQ.incrementAndGet() % 10_000_000);
    }

    /** seedTenant() with a chosen region. Region is immutable after insert (V5 trigger), so the test sets it with triggers off. */
    private SeededTenant tenant(String region) {
        SeededTenant t = seedTenant();
        if (!"IN".equals(region)) {
            new org.springframework.jdbc.core.JdbcTemplate(superuserDataSource()).execute(
                    "SET session_replication_role = replica; UPDATE tenants SET region = '" + region + "' WHERE id = '" + t.id() + "'");
        }
        return t;
    }

    private String owner(SeededTenant t) {
        return loginAndGetAccessToken(seedStaff(t, seedFullAccessRole(t.id()), "acc28-" + UUID.randomUUID() + "@a.com", staffMobile(), false));
    }

    private ResponseEntity<Map> register(String token, String phone) {
        return exchange("/v1/patients", HttpMethod.POST, authedJsonBody(token, Map.of("name", "Acc Phone " + SEQ.incrementAndGet(),
                "phone", phone, "dob", "1990-01-01", "gender", "female", "confirmedNotDuplicate", true)), Map.class);
    }

    private static void assertPhoneFieldError(ResponseEntity<Map> r, String field) {
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat((String) r.getBody().get("type")).endsWith("validation-failed");
        assertThat(r.getBody().get("code")).isEqualTo("INVALID_PHONE");
        List<Map<String, String>> errors = (List<Map<String, String>>) r.getBody().get("errors");
        assertThat(errors).singleElement().satisfies(e -> assertThat(e.get("field")).isEqualTo(field));
    }

    @Test
    void abcIsA400WithAFieldErrorOnPhone_andNothingIsStored() {
        SeededTenant t = tenant("IN");
        String tok = owner(t);
        ResponseEntity<Map> r = register(tok, "abc");
        assertPhoneFieldError(r, "phone");
        assertThat(r.getBody().toString()).doesNotContain("abc");
        assertPhoneFieldError(register(tok, "12345"), "phone");
        Integer n = inTenantTx(t.id(), () -> jdbc.queryForObject("SELECT count(*) FROM patients WHERE tenant_id = ?", Integer.class, t.id()));
        assertThat(n).isZero();
    }

    @Test
    void localNumbersAreStoredInE164PerClinicCountry() {
        Map<String, String[]> matrix = Map.of(
                "AE", new String[]{"050 123 4567", "+971501234567"},
                "QA", new String[]{"3312 3456", "+97433123456"},
                "IN", new String[]{"098450 22110", "+919845022110"},
                "KSA", new String[]{"0501234567", "+966501234567"});
        matrix.forEach((region, c) -> {
            SeededTenant t = tenant(region);
            ResponseEntity<Map> r = register(owner(t), c[0]);
            assertThat(r.getStatusCode()).as(region).isEqualTo(HttpStatus.CREATED);
            assertThat(r.getBody().get("phone")).as(region).isEqualTo(c[1]);
            String stored = inTenantTx(t.id(), () -> jdbc.queryForObject("SELECT phone FROM patients WHERE id = ?::uuid", String.class, r.getBody().get("id")));
            assertThat(stored).as(region + " stored").isEqualTo(c[1]);
        });
    }

    @Test
    void aForeignNumberWithPlusIsAccepted_aLocalNumberOfAnotherCountryIsNot() {
        SeededTenant t = tenant("QA");
        String tok = owner(t);
        assertThat(register(tok, "+91 98450 22110").getBody().get("phone")).isEqualTo("+919845022110");
        assertPhoneFieldError(register(tok, "050 123 4567"), "phone"); // UAE local format in a Qatar clinic
    }

    @Test
    void patchValidatesPhoneAndACorrectionClearsTheFlag() {
        SeededTenant t = tenant("AE");
        String tok = owner(t);
        String id = (String) register(tok, "050 123 4567").getBody().get("id");
        inTenantTx(t.id(), () -> jdbc.update("UPDATE patients SET phone = 'abc', phone_invalid = true WHERE id = ?::uuid", id));

        assertPhoneFieldError(exchange("/v1/patients/" + id, HttpMethod.PATCH, authedJsonBody(tok, Map.of("phone", "12")), Map.class), "phone");
        ResponseEntity<Map> ok = exchange("/v1/patients/" + id, HttpMethod.PATCH, authedJsonBody(tok, Map.of("phone", "0521234567")), Map.class);
        assertThat(ok.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(ok.getBody().get("phone")).isEqualTo("+971521234567");
        Boolean invalid = inTenantTx(t.id(), () -> jdbc.queryForObject("SELECT phone_invalid FROM patients WHERE id = ?::uuid", Boolean.class, id));
        assertThat(invalid).isFalse();
    }

    private UUID insertRaw(SeededTenant t, String name, String phone) {
        return inTenantTx(t.id(), () -> jdbc.queryForObject(
                "INSERT INTO patients (tenant_id, name, phone, dob, gender) VALUES (?,?,?,'1980-02-02','male') RETURNING id", UUID.class, t.id(), name, phone));
    }

    @Test
    void renormaliseJobRewritesValidAndFlagsInvalid_dataQualityListsThemPagedAndGated() {
        SeededTenant t = tenant("KSA");
        String tok = owner(t);
        UUID legacyValid = insertRaw(t, "Legacy Valid", "0512345678");
        UUID bad1 = insertRaw(t, "Bad One", "abc");
        UUID bad2 = insertRaw(t, "Bad Two", "12345");
        SeededTenant other = tenant("KSA");
        UUID otherBad = insertRaw(other, "Other Bad", "zzz");

        PhoneRenormaliseJob job = new PhoneRenormaliseJob(jdbc, new TransactionTemplate(txManager), tenantScope, audit);
        assertThat(job.run(t.id(), "KSA")).containsExactly(1, 2);
        assertThat(job.run(t.id(), "KSA")).as("re-runnable, nothing left to do").containsExactly(0, 0);
        job.run(other.id(), "KSA");
        Map<String, Object> legacy = inTenantTx(t.id(), () -> jdbc.queryForMap("SELECT phone, phone_invalid FROM patients WHERE id = ?", legacyValid));
        assertThat(legacy).containsEntry("phone", "+966512345678").containsEntry("phone_invalid", false);
        String kept = inTenantTx(t.id(), () -> jdbc.queryForObject("SELECT phone FROM patients WHERE id = ?", String.class, bad1));
        assertThat(kept).as("raw value kept, never deleted").isEqualTo("abc");

        ResponseEntity<Map> all = exchange("/v1/patients/data-quality?issue=phone", HttpMethod.GET, authed(tok), Map.class);
        assertThat(all.getStatusCode()).isEqualTo(HttpStatus.OK);
        List<Map<String, Object>> items = (List<Map<String, Object>>) all.getBody().get("items");
        assertThat(items).extracting(i -> i.get("patientId")).containsExactlyInAnyOrder(bad1.toString(), bad2.toString());
        assertThat(items).extracting(i -> i.get("rawPhone")).containsExactlyInAnyOrder("abc", "12345");
        assertThat(items).extracting(i -> i.get("displayName")).containsExactlyInAnyOrder("Bad One", "Bad Two");
        assertThat(all.getBody().toString()).doesNotContain(otherBad.toString()).doesNotContain("zzz"); // tenant isolation
        assertThat(all.getBody().get("nextCursor")).isNull();

        ResponseEntity<Map> p1 = exchange("/v1/patients/data-quality?issue=phone&limit=1", HttpMethod.GET, authed(tok), Map.class);
        String cursor = (String) p1.getBody().get("nextCursor");
        assertThat(cursor).isNotNull();
        ResponseEntity<Map> p2 = exchange("/v1/patients/data-quality?issue=phone&limit=1&cursor=" + cursor, HttpMethod.GET, authed(tok), Map.class);
        assertThat((List<?>) p2.getBody().get("items")).hasSize(1);
        assertThat(p2.getBody().get("nextCursor")).isNull();
        assertThat(((Map) ((List) p1.getBody().get("items")).get(0)).get("patientId"))
                .isNotEqualTo(((Map) ((List) p2.getBody().get("items")).get(0)).get("patientId"));

        assertThat(exchange("/v1/patients/data-quality?issue=email", HttpMethod.GET, authed(tok), Map.class).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(exchange("/v1/patients/data-quality?issue=phone&cursor=nope", HttpMethod.GET, authed(tok), Map.class).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(exchange("/v1/patients/data-quality?issue=phone&limit=101", HttpMethod.GET, authed(tok), Map.class).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        // patients:view without patients:edit -> 403
        UUID viewOnly = seedRole(t.id(), "Viewer", false, new ModuleGrant("patients", true, false, false, false, false, false, false));
        String viewer = loginAndGetAccessToken(seedStaff(t, viewOnly, "acc28v-" + UUID.randomUUID() + "@a.com", staffMobile(), false));
        assertThat(exchange("/v1/patients/data-quality?issue=phone", HttpMethod.GET, authed(viewer), Map.class).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        // fixing a number takes it off the list
        exchange("/v1/patients/" + bad1, HttpMethod.PATCH, authedJsonBody(tok, Map.of("phone", "0551234567")), Map.class);
        List<Map<String, Object>> after = (List<Map<String, Object>>) exchange("/v1/patients/data-quality?issue=phone", HttpMethod.GET, authed(tok), Map.class).getBody().get("items");
        assertThat(after).extracting(i -> i.get("patientId")).containsExactly(bad2.toString());
    }

    @Test
    void staffInviteValidatesAndStoresE164() {
        SeededTenant t = tenant("AE");
        String tok = owner(t);
        UUID role = seedRole(t.id(), "Desk", false, new ModuleGrant("patients", true, true, false, false, false, false, false));
        ResponseEntity<Map> bad = exchange("/v1/staff", HttpMethod.POST, authedJsonBody(tok, Map.of("email", "inv-" + UUID.randomUUID() + "@a.com",
                "name", "Invitee", "mobilePhone", "abc", "roleId", role.toString())), Map.class);
        assertPhoneFieldError(bad, "mobilePhone");
        ResponseEntity<Map> ok = exchange("/v1/staff", HttpMethod.POST, authedJsonBody(tok, Map.of("email", "inv-" + UUID.randomUUID() + "@a.com",
                "name", "Invitee", "mobilePhone", "050 765 4321", "roleId", role.toString())), Map.class);
        assertThat(ok.getStatusCode().is2xxSuccessful()).as(String.valueOf(ok.getBody())).isTrue();
        assertThat(((Map) ok.getBody().get("staff")).get("mobilePhone")).isEqualTo("+971507654321");
    }
}
