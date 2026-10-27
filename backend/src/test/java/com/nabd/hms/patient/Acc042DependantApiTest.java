package com.nabd.hms.patient;

import com.nabd.hms.common.ClinicClock;
import com.nabd.hms.support.ApiTestBase;
import com.nabd.hms.support.InternalTestTokens;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.JwtEncoder;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** ACC-042 (household list, add a child from chat) and the ACC-040 booking profile the WhatsApp flow reads. */
class Acc042DependantApiTest extends ApiTestBase {

    @Autowired JwtEncoder encoder;
    @Autowired ClinicClock clinicClock;

    private record Clinic(SeededTenant tenant, SeededStaff doctor, String staffToken, String edgeToken, String phone, String hash) {
        String base() {
            return "/internal/v1/tenants/" + tenant.id();
        }
    }

    private static String sha256(String phone) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(phone.replaceAll("\\D", "").getBytes(StandardCharsets.UTF_8)));
    }

    private Clinic clinic(String tag) throws Exception {
        SeededTenant t = seedTenant();
        SeededStaff doctor = seedStaff(t, seedFullAccessRole(t.id()), tag + "@a.com", "+9195" + String.format("%08d", Math.abs(tag.hashCode()) % 100000000), false);
        String token = loginAndGetAccessToken(doctor);
        String phone = "+9198" + String.format("%08d", Math.abs(tag.hashCode()) % 100000000);
        assertThat(exchange("/v1/patients", HttpMethod.POST, authedJsonBody(token, Map.of("name", "Asha Rao", "phone", phone, "dob", "1988-02-03", "gender", "female")), Map.class)
                .getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return new Clinic(t, doctor, token, InternalTestTokens.mint(encoder, "patient-access-edge", t.id()), phone, sha256(phone));
    }

    private ResponseEntity<Map> addChild(Clinic c, String hash, String first, String dob, String sex) {
        return exchange(c.base() + "/households/" + hash + "/dependants", HttpMethod.POST,
                authedJsonBody(c.edgeToken(), Map.of("firstName", first, "dob", dob, "sex", sex)), Map.class);
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> members(Clinic c) {
        ResponseEntity<Map> r = exchange(c.base() + "/households/" + c.hash(), HttpMethod.GET, authed(c.edgeToken()), Map.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        return (List<Map<String, Object>>) r.getBody().get("members");
    }

    private JdbcTemplate su() {
        return new JdbcTemplate(superuserDataSource());
    }

    @Test
    void addingAChildCreatesAMinorUnderTheGuardianWithGuardianAccessConsentAndHouseholdListsIt() throws Exception {
        Clinic c = clinic("acc042-add");
        String dob = clinicClock.today(c.tenant().id()).minusYears(6).toString();
        ResponseEntity<Map> r = addChild(c, c.hash(), "Aarav", dob, "male");
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String ref = (String) r.getBody().get("patientRef");
        assertThat(members(c)).extracting(m -> m.get("firstName")).containsExactlyInAnyOrder("Asha", "Aarav");
        Map<String, Object> child = members(c).stream().filter(m -> "Aarav".equals(m.get("firstName"))).findFirst().orElseThrow();
        assertThat(child).containsEntry("patientRef", ref).containsEntry("relation", "dependent").containsEntry("age", "5-11");
        Map<String, Object> row = su().queryForMap("""
                SELECT p.name, p.guardian_id IS NOT NULL AS has_guardian,
                       (SELECT count(*) FROM consents x WHERE x.patient_id = p.id AND x.consent_type = 'guardian_access' AND x.withdrawn_at IS NULL) AS consents
                FROM patients p JOIN internal_patient_refs r ON r.patient_id = p.id WHERE r.patient_ref = ?""", ref);
        assertThat(row.get("name")).isEqualTo("Aarav Rao");
        assertThat(su().queryForObject("SELECT phone FROM patients WHERE tenant_id = ? AND name = 'Aarav Rao'", String.class, c.tenant().id()))
                .as("reminders for the child go to the guardian's number").isEqualTo(c.phone());
        assertThat(row.get("has_guardian")).isEqualTo(true);
        assertThat(((Number) row.get("consents")).intValue()).isEqualTo(1);
        assertThat(su().queryForObject("SELECT count(*) FROM audit_log WHERE tenant_id = ? AND action = 'patient.dependant_added_via_edge'", Integer.class, c.tenant().id())).isEqualTo(1);
    }

    @Test
    void anAdultDobIsRefusedNotAMinorAndAnUnknownPhoneIs404() throws Exception {
        Clinic c = clinic("acc042-refuse");
        ResponseEntity<Map> adult = addChild(c, c.hash(), "Old", clinicClock.today(c.tenant().id()).minusYears(18).toString(), "male");
        assertThat(adult.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(adult.getBody()).containsEntry("code", "NOT_A_MINOR");
        assertThat(addChild(c, sha256("+919000000000"), "Kid", "2024-01-01", "male").getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(addChild(c, c.hash(), "Kid", "2999-01-01", "male").getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(addChild(c, c.hash(), "Kid", "2024-01-01", "x").getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(members(c)).hasSize(1);
    }

    @Test
    void theSameChildTwiceIs409PossibleDuplicateWithTheExistingRef() throws Exception {
        Clinic c = clinic("acc042-dup");
        String dob = clinicClock.today(c.tenant().id()).minusYears(4).toString();
        String ref = (String) addChild(c, c.hash(), "Meera", dob, "female").getBody().get("patientRef");
        ResponseEntity<Map> again = addChild(c, c.hash(), "meera", dob, "female");
        assertThat(again.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(again.getBody()).containsEntry("code", "POSSIBLE_DUPLICATE").containsEntry("patientRef", ref);
        assertThat(members(c)).hasSize(2);
    }

    @Test
    void aChildWhoTurned18DropsOffAndARevokedGuardianLinkDropsTheChildImmediately() throws Exception {
        Clinic c = clinic("acc042-age");
        LocalDate today = clinicClock.today(c.tenant().id());
        addChild(c, c.hash(), "Teen", today.minusYears(10).toString(), "male");
        addChild(c, c.hash(), "Tiny", today.minusYears(2).toString(), "female");
        assertThat(members(c)).hasSize(3);
        su().update("UPDATE patients SET dob = ? WHERE tenant_id = ? AND name = 'Teen Rao'", java.sql.Date.valueOf(today.minusYears(18)), c.tenant().id());
        assertThat(members(c)).extracting(m -> m.get("firstName")).containsExactlyInAnyOrder("Asha", "Tiny");
        su().update("UPDATE patients SET guardian_id = NULL WHERE tenant_id = ? AND name = 'Tiny Rao'", c.tenant().id());
        assertThat(members(c)).extracting(m -> m.get("firstName")).containsExactly("Asha");
    }

    @Test
    void anotherClinicsEdgeIdentityIsForbiddenAndTheReplayWithTheSameKeyCreatesOneChild() throws Exception {
        Clinic a = clinic("acc042-ta"), b = clinic("acc042-tb");
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(a.edgeToken());
        h.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);
        h.add("Idempotency-Key", UUID.randomUUID().toString());
        String body = "{\"firstName\":\"Zed\",\"dob\":\"" + clinicClock.today(a.tenant().id()).minusYears(3) + "\",\"sex\":\"male\"}";
        assertThat(http.exchange(url(b.base() + "/households/" + b.hash() + "/dependants"), HttpMethod.POST, new HttpEntity<>(body, h), String.class).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        HttpHeaders k = new HttpHeaders();
        k.addAll(h);
        k.set("Idempotency-Key", UUID.randomUUID().toString());
        for (int i = 0; i < 2; i++) {
            assertThat(http.exchange(url(a.base() + "/households/" + a.hash() + "/dependants"), HttpMethod.POST, new HttpEntity<>(body, k), String.class).getStatusCode())
                    .isEqualTo(HttpStatus.CREATED);
        }
        assertThat(members(a)).hasSize(2);
        assertThat(members(b)).hasSize(1);
    }

    @Test
    void bookingProfileListsTheClinicNameZoneAndBookableDoctorsOfThatClinicOnly() throws Exception {
        Clinic a = clinic("acc040-pa"), b = clinic("acc040-pb");
        assertThat(exchange("/v1/doctors/" + a.doctor().id() + "/working-hours", HttpMethod.POST, authedJsonBody(a.staffToken(), Map.of(
                "dayOfWeek", 1, "startTime", "09:00:00", "endTime", "12:00:00", "slotMinutes", 15)), Map.class).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        ResponseEntity<Map> r = exchange(a.base() + "/booking-profile", HttpMethod.GET, authed(a.edgeToken()), Map.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(r.getBody().get("timeZone")).isEqualTo(clinicClock.zone(a.tenant().id()).getId());
        assertThat(r.getBody().get("clinicName")).isNotNull();
        assertThat((List<Map<String, Object>>) r.getBody().get("doctors")).extracting(d -> d.get("doctorId")).containsExactly(a.doctor().id().toString());
        assertThat(exchange(b.base() + "/booking-profile", HttpMethod.GET, authed(a.edgeToken()), Map.class).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat((List<?>) exchange(b.base() + "/booking-profile", HttpMethod.GET, authed(b.edgeToken()), Map.class).getBody().get("doctors")).isEmpty();
    }
}
