package com.nabd.hms.patient;

import com.nabd.hms.common.ClinicClock;
import com.nabd.hms.patient.api.ConsentRegistry;
import com.nabd.hms.patient.api.ConsentRegistry.Purpose;
import com.nabd.hms.patient.api.PatientRefs;
import com.nabd.hms.support.ApiTestBase;
import com.nabd.hms.support.InternalTestTokens;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.jwt.JwtEncoder;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** IDC-010: the Edge creates a family member for a caregiver (minor, adult with a phone, adult without a phone, second guardian). */
class Idc010ChatMemberApiTest extends ApiTestBase {

    @Autowired JwtEncoder encoder;
    @Autowired PatientRefs refs;
    @Autowired ConsentRegistry registry;
    @Autowired ClinicClock clock;

    private record Setup(SeededTenant t, String token, String caregiverRef, UUID caregiverId) {
    }

    private Setup setup(String phone) {
        SeededTenant t = seedTenant();
        inTenantTx(t.id(), () -> jdbc.update("INSERT INTO privacy_notices (tenant_id, version, language, template_key, body_md, mandatory_hash, effective_from) "
                + "VALUES (?,'v1','en','IN-DPDP-1','body','h1', now() - interval '1 day')", t.id()));
        UUID cg = UUID.randomUUID();
        inTenantTx(t.id(), () -> jdbc.update("INSERT INTO patients (id, tenant_id, name, phone, dob, gender) VALUES (?,?,'Meera Mother',?,'1988-02-03','female')", cg, t.id(), phone));
        String ref = inTenantTx(t.id(), () -> refs.refFor(cg));
        return new Setup(t, InternalTestTokens.mint(encoder, "patient-access-edge", t.id()), ref, cg);
    }

    private String years(int n) {
        return LocalDate.now().minusYears(n).toString(); // far from midnight boundaries for the ages used (6, 35, 74)
    }

    private Map<String, Object> minorBody(String name, String challenge) {
        Map<String, Object> m = new HashMap<>();
        m.put("relation", "child");
        m.put("name", name);
        m.put("dob", years(6));
        m.put("guardianConsent", Map.of("otpChallengeId", challenge, "noticeVersion", "v1"));
        return m;
    }

    private ResponseEntity<Map> post(Setup s, Object body) {
        return exchange("/internal/v1/tenants/" + s.t().id() + "/patients/" + s.caregiverRef() + "/members", HttpMethod.POST, authedJsonBody(s.token(), body), Map.class);
    }

    private int count(UUID tenant, String sql, Object... args) {
        return inTenantTx(tenant, () -> jdbc.queryForObject(sql, Integer.class, args));
    }

    @Test
    void minorGetsGuardianConsentWithOtpEvidenceAndAppearsInTheHouseholdPickerAtOnce() throws Exception {
        Setup s = setup("+919800001001");
        ResponseEntity<Map> r = post(s, minorBody("Aarav Kumar", "chal-0001-abcd"));
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(r.getBody()).containsEntry("proxyStatus", "guardian_consented");
        String memberRef = (String) r.getBody().get("memberRef");
        UUID child = inTenantTx(s.t().id(), () -> refs.patientIdFor(memberRef)).orElseThrow();
        Map<String, Object> c = inTenantTx(s.t().id(), () -> jdbc.queryForMap("SELECT channel, notice_version, guardian_patient_id, evidence->>'otpChallengeId' AS otp, evidence->>'consentBy' AS by "
                + "FROM consents WHERE patient_id = ? AND consent_type = 'data_processing'", child));
        assertThat(c).containsEntry("channel", "WHATSAPP").containsEntry("notice_version", "v1").containsEntry("guardian_patient_id", s.caregiverId())
                .containsEntry("otp", "chal-0001-abcd").containsEntry("by", "guardian");
        assertThat(count(s.t().id(), "SELECT count(*) FROM patients WHERE id = ? AND guardian_id = ?", child, s.caregiverId())).isEqualTo(1);
        assertThat(inTenantTx(s.t().id(), () -> registry.isGranted(child, Purpose.DATA_PROCESSING))).isTrue();
        List<PatientRefs.HouseholdMember> hh = inTenantTx(s.t().id(), () -> refs.household(sha("919800001001")));
        assertThat(hh).extracting(PatientRefs.HouseholdMember::patientRef).contains(memberRef);
        assertThat(count(s.t().id(), "SELECT count(*) FROM audit_log WHERE action = 'patient.member_added'")).isEqualTo(1);
        // replay of the same step: same member, 200, nothing stored twice
        ResponseEntity<Map> again = post(s, minorBody("aarav kumar", "chal-0001-abcd"));
        assertThat(again.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(again.getBody().get("memberRef")).isEqualTo(memberRef);
        assertThat(count(s.t().id(), "SELECT count(*) FROM patients WHERE guardian_id = ?", s.caregiverId())).isEqualTo(1);
    }

    @Test
    void minorWithoutGuardianConsentIs422_staleNoticeIs409_futureDobIs400_nothingIsStored() throws Exception {
        Setup s = setup("+919800001002");
        Map<String, Object> none = minorBody("Isha Kumar", "chal-0002-abcd");
        none.remove("guardianConsent");
        ResponseEntity<Map> r = post(s, none);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(String.valueOf(r.getBody().get("type"))).endsWith("guardian-required");
        Map<String, Object> stale = minorBody("Isha Kumar", "chal-0002-abcd");
        stale.put("guardianConsent", Map.of("otpChallengeId", "chal-0002-abcd", "noticeVersion", "v0"));
        assertThat(post(s, stale).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        Map<String, Object> future = minorBody("Isha Kumar", "chal-0002-abcd");
        future.put("dob", LocalDate.now().plusYears(1).toString());
        assertThat(post(s, future).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        Map<String, Object> badRel = minorBody("Isha Kumar", "chal-0002-abcd");
        badRel.put("relation", "cousin");
        assertThat(post(s, badRel).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(count(s.t().id(), "SELECT count(*) FROM patients WHERE id <> ?", s.caregiverId())).isZero();
    }

    @Test
    void adultWithOwnPhoneIsCreatedWithoutConsentAndListedAwaitingConsent() throws Exception {
        Setup s = setup("+919800001003");
        Map<String, Object> b = new HashMap<>(Map.of("relation", "other", "name", "Ravi Sharma", "dob", years(35), "phone", "+919800001999"));
        ResponseEntity<Map> r = post(s, b);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(r.getBody()).containsEntry("proxyStatus", "awaiting_consent");
        UUID member = inTenantTx(s.t().id(), () -> refs.patientIdFor((String) r.getBody().get("memberRef"))).orElseThrow();
        assertThat(count(s.t().id(), "SELECT count(*) FROM consents WHERE patient_id = ?", member)).as("no consent is invented for an adult").isZero();
        assertThat(count(s.t().id(), "SELECT count(*) FROM patients WHERE id = ? AND phone = '+919800001999'", member)).isEqualTo(1);
        assertThat(count(s.t().id(), "SELECT count(*) FROM patient_desk_tasks")).isZero();
        ResponseEntity<Map> list = exchange("/internal/v1/tenants/" + s.t().id() + "/patients/" + s.caregiverRef() + "/members", HttpMethod.GET, authed(s.token()), Map.class);
        assertThat(list.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat((List<Map<String, Object>>) list.getBody().get("members")).singleElement()
                .satisfies(m -> assertThat(m).containsEntry("proxyStatus", "awaiting_consent").containsEntry("firstName", "Ravi").containsEntry("proxyScope", "NONE"));
        Map<String, Object> badPhone = new HashMap<>(b);
        badPhone.put("name", "Ravi Two");
        badPhone.put("phone", "12");
        assertThat(post(s, badPhone).getStatusCode().is4xxClientError()).isTrue();
    }

    @Test
    void adultWithoutPhoneIsBookOnlyWithADeskTaskAndAppearsInThePicker() throws Exception {
        Setup s = setup("+919800001004");
        ResponseEntity<Map> r = post(s, Map.of("relation", "parent", "name", "Lakshmi Devi", "dob", years(74)));
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(r.getBody()).containsEntry("proxyStatus", "pending_desk_confirmation");
        String ref = (String) r.getBody().get("memberRef");
        UUID member = inTenantTx(s.t().id(), () -> refs.patientIdFor(ref)).orElseThrow();
        assertThat(count(s.t().id(), "SELECT count(*) FROM chat_added_members WHERE member_patient_id = ? AND proxy_scope = 'BOOK_ONLY'", member)).isEqualTo(1);
        assertThat(count(s.t().id(), "SELECT count(*) FROM patient_desk_tasks WHERE patient_id = ? AND kind = 'confirm_assisted_consent' AND status = 'open'", member)).isEqualTo(1);
        assertThat(inTenantTx(s.t().id(), () -> refs.household(sha("919800001004")))).extracting(PatientRefs.HouseholdMember::patientRef).contains(ref);
        assertThat(inTenantTx(s.t().id(), () -> registry.isGranted(member, Purpose.DATA_PROCESSING))).as("consent still to be confirmed in person").isFalse();
    }

    @Test
    void secondGuardianGetsAConsentRowOfTheirOwn() throws Exception {
        Setup mother = setup("+919800001005");
        String childRef = (String) post(mother, minorBody("Nila Kumar", "chal-0005-aaaa")).getBody().get("memberRef");
        UUID childId = inTenantTx(mother.t().id(), () -> refs.patientIdFor(childRef)).orElseThrow();
        UUID father = UUID.randomUUID();
        inTenantTx(mother.t().id(), () -> jdbc.update("INSERT INTO patients (id, tenant_id, name, phone, dob, gender) VALUES (?,?,'Kiran Father','+919800001006','1985-05-05','male')", father, mother.t().id()));
        String fatherRef = inTenantTx(mother.t().id(), () -> refs.refFor(father));
        Setup dad = new Setup(mother.t(), mother.token(), fatherRef, father);
        Map<String, Object> b = new HashMap<>(Map.of("relation", "child", "name", "xx", "dob", years(6), "memberRef", childRef,
                "guardianConsent", Map.of("otpChallengeId", "chal-0005-bbbb", "noticeVersion", "v1")));
        ResponseEntity<Map> r = post(dad, b);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(count(mother.t().id(), "SELECT count(*) FROM consents WHERE patient_id = ? AND consent_type = 'data_processing' AND guardian_patient_id = ?", childId, mother.caregiverId())).isEqualTo(1);
        assertThat(count(mother.t().id(), "SELECT count(*) FROM consents WHERE patient_id = ? AND consent_type = 'data_processing' AND guardian_patient_id = ?", childId, father)).isEqualTo(1);
        assertThat(post(dad, b).getStatusCode()).as("replay adds no third row").isEqualTo(HttpStatus.OK);
        assertThat(count(mother.t().id(), "SELECT count(*) FROM consents WHERE patient_id = ? AND consent_type = 'data_processing'", childId)).isEqualTo(2);
        // an adult cannot be given a second guardian
        String adultRef = (String) post(mother, Map.of("relation", "parent", "name", "Old Parent", "dob", years(74))).getBody().get("memberRef");
        Map<String, Object> toAdult = new HashMap<>(b);
        toAdult.put("memberRef", adultRef);
        assertThat(post(dad, toAdult).getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
    }

    @Test
    void otherClinicStaffTokenAndUnknownCaregiverAreRefused_rlsIsolatesTheNewTables() throws Exception {
        Setup a = setup("+919800001007");
        SeededTenant b = seedTenant();
        assertThat(exchange("/internal/v1/tenants/" + b.id() + "/patients/" + a.caregiverRef() + "/members", HttpMethod.POST,
                authedJsonBody(a.token(), minorBody("Zed Kumar", "chal-0007-aaaa")), Map.class).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        Setup noSuch = new Setup(a.t(), a.token(), "no-such-reference", a.caregiverId());
        assertThat(post(noSuch, minorBody("Zed Kumar", "chal-0007-aaaa")).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        SeededStaff owner = seedStaff(a.t(), seedFullAccessRole(a.t().id()), "idc010@a.com", "+919800001008", false);
        assertThat(post(new Setup(a.t(), loginAndGetAccessToken(owner), a.caregiverRef(), a.caregiverId()), minorBody("Zed Kumar", "chal-0007-aaaa")).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        post(a, Map.of("relation", "parent", "name", "Pat Parent", "dob", years(74)));
        assertThat(count(b.id(), "SELECT count(*) FROM chat_added_members")).isZero();
        assertThat(count(b.id(), "SELECT count(*) FROM patient_desk_tasks")).isZero();
        assertThat(count(a.t().id(), "SELECT count(*) FROM chat_added_members")).isEqualTo(1);
    }

    private static String sha(String digits) {
        try {
            return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(digits.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
