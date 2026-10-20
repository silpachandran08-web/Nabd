package com.nabd.hms.patient;

import com.nabd.hms.common.AuditService;
import com.nabd.hms.common.ModuleGrant;
import com.nabd.hms.support.ApiTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/** Independent QA (S12 ACC-028): per-country phone validation and E.164 storage on every entry point, data-quality list. */
@SuppressWarnings({"rawtypes", "unchecked"})
class S12QaAcc028PhoneTest extends ApiTestBase {

    private static final AtomicInteger SEQ = new AtomicInteger((int) (System.nanoTime() % 900_000));

    @Autowired AuditService audit;
    @Autowired com.nabd.hms.common.TenantScope tenantScope;

    private static String staffMobile() {
        return "+91944" + String.format("%07d", SEQ.incrementAndGet() % 10_000_000);
    }

    /** Region is immutable after insert (V5 trigger): set it with triggers off, as the engineer's test does. */
    private SeededTenant tenant(String region) {
        SeededTenant t = seedTenant();
        if (!"IN".equals(region)) {
            new JdbcTemplate(superuserDataSource()).execute(
                    "SET session_replication_role = replica; UPDATE tenants SET region = '" + region + "' WHERE id = '" + t.id() + "'");
        }
        return t;
    }

    private String owner(SeededTenant t) {
        return loginAndGetAccessToken(seedStaff(t, seedFullAccessRole(t.id()), "q1acc-" + UUID.randomUUID() + "@a.com", staffMobile(), false));
    }

    private ResponseEntity<Map> register(String token, String phone, String name, boolean confirmed) {
        Map<String, Object> body = new LinkedHashMap<>(Map.of("name", name, "phone", phone, "dob", "1991-04-04", "gender", "male"));
        if (confirmed) body.put("confirmedNotDuplicate", true);
        return exchange("/v1/patients", HttpMethod.POST, authedJsonBody(token, body), Map.class);
    }

    private ResponseEntity<Map> register(String token, String phone) {
        return register(token, phone, "Qa Phone " + SEQ.incrementAndGet(), true);
    }

    private static void assertFieldError(ResponseEntity<Map> r, String field, String typed) {
        assertThat(r.getStatusCode()).as(field + " <- '" + typed + "' body=" + r.getBody()).isEqualTo(HttpStatus.BAD_REQUEST);
        List<Map<String, String>> errors = (List<Map<String, String>>) r.getBody().get("errors");
        assertThat(errors).as("field errors for '" + typed + "'").isNotNull().anySatisfy(e -> assertThat(e.get("field")).isEqualTo(field));
    }

    private String stored(SeededTenant t, Object patientId) {
        return inTenantTx(t.id(), () -> jdbc.queryForObject("SELECT phone FROM patients WHERE id = ?::uuid", String.class, patientId));
    }

    private void assertStored(SeededTenant t, String token, String typed, String e164) {
        ResponseEntity<Map> r = register(token, typed);
        assertThat(r.getStatusCode()).as("'" + typed + "' body=" + r.getBody()).isEqualTo(HttpStatus.CREATED);
        assertThat(r.getBody().get("phone")).as("'" + typed + "' response").isEqualTo(e164);
        assertThat(stored(t, r.getBody().get("id"))).as("'" + typed + "' stored").isEqualTo(e164);
    }

    // AC1: 'abc' -> 400 with a field error on phone; nothing stored; value not echoed; the message names the clinic country.
    @Test
    void abcAndOtherGarbageAreRefusedWithAPhoneFieldErrorAndNoRow() {
        SeededTenant t = tenant("AE");
        String tok = owner(t);
        for (String bad : List.of("abc", "12345", "+", "050 123 45678", "0501234", "+971 50 123", "   ", "05O1234567")) {
            ResponseEntity<Map> r = register(tok, bad);
            assertFieldError(r, "phone", bad);
        }
        ResponseEntity<Map> abc = register(tok, "abc");
        assertThat(abc.getBody().get("code")).isEqualTo("INVALID_PHONE");
        assertThat(abc.getBody().toString()).doesNotContain("abc");
        assertThat(abc.getBody().toString()).as("country hint").contains("AE");
        Integer n = inTenantTx(t.id(), () -> jdbc.queryForObject("SELECT count(*) FROM patients WHERE tenant_id = ?", Integer.class, t.id()));
        assertThat(n).isZero();
    }

    // AC2-AC5: the four story examples exactly, response and DB row.
    @Test
    void storyExamplesAreStoredInE164() {
        SeededTenant ae = tenant("AE");
        assertStored(ae, owner(ae), "050 123 4567", "+971501234567");
        SeededTenant qa = tenant("QA");
        assertStored(qa, owner(qa), "3312 3456", "+97433123456");
        SeededTenant in = tenant("IN");
        assertStored(in, owner(in), "098450 22110", "+919845022110");
        SeededTenant sa = tenant("KSA");
        assertStored(sa, owner(sa), "0501234567", "+966501234567");
    }

    // Extra: per country, the local form without trunk 0, with +CC, with 00CC, with punctuation; and the +CC (0) trunk form.
    @Test
    void eachCountryAcceptsPlusAndDoubleZeroAndTrunkVariants() {
        Map<String, List<String[]>> matrix = new LinkedHashMap<>();
        matrix.put("AE", List.of(new String[]{"501234567", "+971501234567"}, new String[]{"+971 50 123 4567", "+971501234567"},
                new String[]{"00971501234567", "+971501234567"}, new String[]{"(050) 123-4567", "+971501234567"}));
        matrix.put("QA", List.of(new String[]{"33123456", "+97433123456"}, new String[]{"+974 3312 3456", "+97433123456"},
                new String[]{"00974 3312 3456", "+97433123456"}));
        matrix.put("IN", List.of(new String[]{"98450 22110", "+919845022110"}, new String[]{"+91 98450 22110", "+919845022110"},
                new String[]{"0091 98450 22110", "+919845022110"}, new String[]{"09845022110", "+919845022110"}));
        matrix.put("KSA", List.of(new String[]{"501234567", "+966501234567"}, new String[]{"+966 50 123 4567", "+966501234567"},
                new String[]{"00966501234567", "+966501234567"}, new String[]{"٠٥٠١٢٣٤٥٦٧", "+966501234567"}));
        matrix.forEach((region, cases) -> {
            SeededTenant t = tenant(region);
            String tok = owner(t);
            cases.forEach(c -> assertStored(t, tok, c[0], c[1]));
            // a trunk 0 typed after the country code must never be stored as part of the number
            String trunk = switch (region) {
                case "AE" -> "+971 050 123 4567";
                case "QA" -> "+974 3312 3456";
                case "IN" -> "+91 098450 22110";
                default -> "+966 050 123 4567";
            };
            ResponseEntity<Map> r = register(tok, trunk);
            if (r.getStatusCode() == HttpStatus.CREATED) {
                assertThat((String) r.getBody().get("phone")).as(region + " " + trunk).doesNotContain("+9710").doesNotContain("+910").doesNotContain("+9660");
            } else {
                assertFieldError(r, "phone", trunk);
            }
        });
    }

    // Extra: a local number of another market is refused in this clinic; any valid foreign number with + is accepted.
    @Test
    void localFormsAreReadInTheClinicCountryOnly() {
        SeededTenant in = tenant("IN");
        String tokIn = owner(in);
        assertFieldError(register(tokIn, "3312 3456"), "phone", "3312 3456");       // Qatar local in an India clinic
        assertStored(in, tokIn, "+974 3312 3456", "+97433123456");
        SeededTenant sa = tenant("KSA");
        String tokSa = owner(sa);
        assertFieldError(register(tokSa, "98450 22110"), "phone", "98450 22110");   // India local in a KSA clinic
        assertStored(sa, tokSa, "+971 50 123 4567", "+971501234567");
    }

    // Extra: duplicates are caught across typing styles (the reason E.164 exists).
    @Test
    void theSameNumberTypedDifferentlyIsFlaggedAsAPossibleDuplicate() {
        SeededTenant t = tenant("IN");
        String tok = owner(t);
        assertThat(register(tok, "098450 22110", "Ravi Duplicate", true).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        ResponseEntity<Map> dup = register(tok, "+91 98450-22110", "Ravi Duplicate", false);
        assertThat(dup.getStatusCode()).as(String.valueOf(dup.getBody())).isEqualTo(HttpStatus.CONFLICT);
    }

    // Extra: patient edit validates and normalises with the clinic country.
    @Test
    void patientEditValidatesAndNormalises() {
        SeededTenant t = tenant("QA");
        String tok = owner(t);
        String id = (String) register(tok, "3312 3456").getBody().get("id");
        assertFieldError(exchange("/v1/patients/" + id, HttpMethod.PATCH, authedJsonBody(tok, Map.of("phone", "abc")), Map.class), "phone", "abc");
        assertThat(stored(t, id)).isEqualTo("+97433123456");
        ResponseEntity<Map> ok = exchange("/v1/patients/" + id, HttpMethod.PATCH, authedJsonBody(tok, Map.of("phone", "5512 3456")), Map.class);
        assertThat(ok.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(stored(t, id)).isEqualTo("+97455123456");
        ResponseEntity<Map> foreign = exchange("/v1/patients/" + id, HttpMethod.PATCH, authedJsonBody(tok, Map.of("phone", "+966 50 123 4567")), Map.class);
        assertThat(foreign.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(stored(t, id)).isEqualTo("+966501234567");
    }

    // Extra: guardian on file is a patient record, so a child registered with a guardian still gets a validated phone.
    @Test
    void childWithGuardianStillHasThePhoneValidated() {
        SeededTenant t = tenant("AE");
        String tok = owner(t);
        String guardian = (String) register(tok, "050 123 4567").getBody().get("id");
        Map<String, Object> child = new LinkedHashMap<>(Map.of("name", "Child Qa", "phone", "abc", "dob", "2019-01-01", "gender", "female",
                "guardianId", guardian, "confirmedNotDuplicate", true, "guardianConsent", Map.of("confirmed", true, "channel", "in_person")));
        assertFieldError(exchange("/v1/patients", HttpMethod.POST, authedJsonBody(tok, child), Map.class), "phone", "abc");
        child.put("phone", "050 123 4567");
        ResponseEntity<Map> ok = exchange("/v1/patients", HttpMethod.POST, authedJsonBody(tok, child), Map.class);
        assertThat(ok.getStatusCode()).as(String.valueOf(ok.getBody())).isEqualTo(HttpStatus.CREATED);
        assertThat(stored(t, ok.getBody().get("id"))).isEqualTo("+971501234567");
    }

    // Extra: staff invite per country.
    @Test
    void staffInviteValidatesPerClinicCountry() {
        SeededTenant t = tenant("QA");
        String tok = owner(t);
        UUID role = seedRole(t.id(), "Desk", false, new ModuleGrant("patients", true, true, false, false, false, false, false));
        java.util.function.Function<String, ResponseEntity<Map>> invite = m -> exchange("/v1/staff", HttpMethod.POST, authedJsonBody(tok,
                Map.of("email", "q1inv-" + UUID.randomUUID() + "@a.com", "name", "Invitee", "mobilePhone", m, "roleId", role.toString())), Map.class);
        for (String bad : List.of("abc", "3312 345", "050 123 4567")) assertFieldError(invite.apply(bad), "mobilePhone", bad);
        ResponseEntity<Map> ok = invite.apply("3312 3456");
        assertThat(ok.getStatusCode().is2xxSuccessful()).as(String.valueOf(ok.getBody())).isTrue();
        assertThat(((Map) ok.getBody().get("staff")).get("mobilePhone")).isEqualTo("+97433123456");
        ResponseEntity<Map> plus = invite.apply("+91 98450 22111");
        assertThat(((Map) plus.getBody().get("staff")).get("mobilePhone")).isEqualTo("+919845022111");
    }

    // Extra: provisioning owner mobile is validated at the request (KSA trunk 0, IN trunk 0, garbage).
    @Test
    void provisioningOwnerMobileIsValidatedAndStoredE164() {
        String op = platformLoginAndGetAccessToken(seedOperator("q1prov-" + UUID.randomUUID() + "@nabd.health", "super_admin", false));
        JdbcTemplate su = new JdbcTemplate(superuserDataSource());
        java.util.function.BiFunction<String, String, ResponseEntity<Map>> job = (region, mobile) -> {
            String slug = "q1p" + Math.abs(UUID.randomUUID().hashCode());
            return exchange("/v1/platform/provisioning-jobs", HttpMethod.POST, authedJsonBody(op, Map.of("tenantSlug", slug,
                    "tenantName", "Qa Clinic " + slug, "region", region, "ownerEmail", "o-" + slug + "@nabd.health", "ownerName", "Qa Owner",
                    "ownerMobile", mobile, "brandName", "Qa Brand " + slug, "path", "self_serve")), Map.class);
        };
        int before = su.queryForObject("SELECT count(*) FROM master.provisioning_jobs", Integer.class);
        assertFieldError(job.apply("IN", "abc"), "ownerMobile", "abc");
        assertFieldError(job.apply("KSA", "98450 22110"), "ownerMobile", "98450 22110");
        assertThat(su.queryForObject("SELECT count(*) FROM master.provisioning_jobs", Integer.class)).as("no job for a refused mobile").isEqualTo(before);

        ResponseEntity<Map> ksa = job.apply("KSA", "0501234567");
        assertThat(ksa.getStatusCode().is2xxSuccessful()).as(String.valueOf(ksa.getBody())).isTrue();
        assertThat(su.queryForObject("SELECT owner_mobile FROM master.provisioning_jobs WHERE id = ?::uuid", String.class, ksa.getBody().get("id")))
                .isEqualTo("+966501234567");
        ResponseEntity<Map> in = job.apply("IN", "098450 22110");
        assertThat(su.queryForObject("SELECT owner_mobile FROM master.provisioning_jobs WHERE id = ?::uuid", String.class, in.getBody().get("id")))
                .isEqualTo("+919845022110");
    }

    private UUID insertRaw(SeededTenant t, String name, String phone, boolean flagged) {
        return inTenantTx(t.id(), () -> jdbc.queryForObject(
                "INSERT INTO patients (tenant_id, name, phone, dob, gender, phone_invalid) VALUES (?,?,?,'1975-05-05','female',?) RETURNING id",
                UUID.class, t.id(), name, phone, flagged));
    }

    private ResponseEntity<Map> dq(String token, String query) {
        return exchange("/v1/patients/data-quality" + query, HttpMethod.GET, authed(token), Map.class);
    }

    // AC6: flagged numbers are listed for correction, tenant-scoped, gated, never deleted; the renormalise run flags legacy rows.
    @Test
    void dataQualityListsFlaggedNumbersPerTenantAndIsGated() {
        SeededTenant a = tenant("AE");
        SeededTenant b = tenant("AE");
        String tokA = owner(a);
        String tokB = owner(b);
        UUID legacyBad = insertRaw(a, "Legacy Bad", "050-12", false);   // legacy row before the job runs
        UUID legacyOk = insertRaw(a, "Legacy Ok", "0501112233", false);
        UUID bFlag = insertRaw(b, "Other Tenant", "xyz", true);
        assertStored(a, tokA, "050 123 4567", "+971501234567");

        PhoneRenormaliseJob job = new PhoneRenormaliseJob(jdbc, new TransactionTemplate(txManager), tenantScope, audit);
        job.run(a.id(), "AE");

        List<Map<String, Object>> items = (List<Map<String, Object>>) dq(tokA, "?issue=phone").getBody().get("items");
        assertThat(items).extracting(i -> i.get("patientId")).containsExactly(legacyBad.toString());
        assertThat(items.get(0)).containsEntry("rawPhone", "050-12").containsEntry("displayName", "Legacy Bad");
        assertThat(items.get(0).keySet()).containsExactlyInAnyOrder("patientId", "displayName", "rawPhone");
        assertThat(inTenantTx(a.id(), () -> jdbc.queryForObject("SELECT phone FROM patients WHERE id = ?", String.class, legacyOk))).isEqualTo("+971501112233");
        assertThat(inTenantTx(a.id(), () -> jdbc.queryForObject("SELECT count(*) FROM patients WHERE id = ?", Integer.class, legacyBad))).isEqualTo(1);

        // tenant isolation both ways
        assertThat(dq(tokA, "?issue=phone").getBody().toString()).doesNotContain(bFlag.toString()).doesNotContain("xyz");
        List<Map<String, Object>> bItems = (List<Map<String, Object>>) dq(tokB, "?issue=phone").getBody().get("items");
        assertThat(bItems).extracting(i -> i.get("patientId")).containsExactly(bFlag.toString());

        // gates: no token 401, patients:view only 403, no patients grant 403, missing issue 400
        assertThat(http.exchange(url("/v1/patients/data-quality?issue=phone"), HttpMethod.GET, null, Map.class).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        UUID viewRole = seedRole(a.id(), "Viewer", false, new ModuleGrant("patients", true, false, false, false, false, false, false));
        String viewer = loginAndGetAccessToken(seedStaff(a, viewRole, "q1v-" + UUID.randomUUID() + "@a.com", staffMobile(), false));
        assertThat(dq(viewer, "?issue=phone").getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        UUID billRole = seedRole(a.id(), "Cashier", false, fullGrant("billing"));
        String cashier = loginAndGetAccessToken(seedStaff(a, billRole, "q1c-" + UUID.randomUUID() + "@a.com", staffMobile(), false));
        assertThat(dq(cashier, "?issue=phone").getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(dq(tokA, "").getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(dq(tokA, "?issue=phone&limit=0").getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        // correcting the number takes it off the list
        assertThat(exchange("/v1/patients/" + legacyBad, HttpMethod.PATCH, authedJsonBody(tokA, Map.of("phone", "052 765 4321")), Map.class)
                .getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat((List<?>) dq(tokA, "?issue=phone").getBody().get("items")).isEmpty();
    }

    // AC6 paging boundary: exactly limit rows -> no next cursor; limit+1 rows -> one more page with the rest; no overlap.
    @Test
    void dataQualityPagesWithoutOverlapOrLoss() {
        SeededTenant t = tenant("IN");
        String tok = owner(t);
        for (int i = 0; i < 5; i++) insertRaw(t, "Bad " + i, "bad" + i, true);
        java.util.Set<Object> seen = new java.util.HashSet<>();
        String cursor = null;
        int pages = 0;
        do {
            ResponseEntity<Map> p = dq(tok, "?issue=phone&limit=2" + (cursor == null ? "" : "&cursor=" + cursor));
            assertThat(p.getStatusCode()).isEqualTo(HttpStatus.OK);
            for (Map<String, Object> i : (List<Map<String, Object>>) p.getBody().get("items")) assertThat(seen.add(i.get("patientId"))).isTrue();
            cursor = (String) p.getBody().get("nextCursor");
            pages++;
        } while (cursor != null && pages < 10);
        assertThat(seen).hasSize(5);
        assertThat(pages).isEqualTo(3);
        ResponseEntity<Map> exact = dq(tok, "?issue=phone&limit=5");
        assertThat((List<?>) exact.getBody().get("items")).hasSize(5);
        assertThat(exact.getBody().get("nextCursor")).isNull();
    }
}
