package com.nabd.hms.specialty;

import com.nabd.hms.common.ModuleGrant;
import com.nabd.hms.support.ApiTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * S23 QA (t1) CLN-042: a specialty profile mints specialty_<x>:view|edit into the token. The PLT-014 rule elsewhere is "you cannot
 * assign permissions you do not hold yourself" (GRANT_EXCEEDS_CALLER); these cases check whether the profile PUT is a way round it.
 */
@SuppressWarnings({"unchecked", "rawtypes"})
class S23QaCln042SpecialtyGrantApiTest extends ApiTestBase {

    private static final AtomicInteger SEQ = new AtomicInteger((int) (System.nanoTime() % 300_000) + 500_000);

    private static String phone() {
        return "+9189" + String.format("%08d", SEQ.incrementAndGet());
    }

    private List<String> permissions(String token) {
        try {
            Map<String, Object> c = objectMapper.readValue(Base64.getUrlDecoder().decode(token.split("\\.")[1]), Map.class);
            return (List<String>) c.get("permissions");
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private ResponseEntity<Map> assign(String token, UUID staffId, String primary) {
        return exchange("/v1/staff/" + staffId + "/specialty-profiles", HttpMethod.PUT, authedJsonBody(token, Map.of("primary", primary)), Map.class);
    }

    private String patient(String token) {
        return (String) exchange("/v1/patients", HttpMethod.POST, authedJsonBody(token, Map.of("name", "Q42 Patient", "phone", phone(),
                "dob", "1990-01-01", "gender", "female")), Map.class).getBody().get("id");
    }

    // C42-A01: an admin with staff:edit only (no clinical, no specialty grant) gives themselves the dental profile
    @Test
    void c42a01_staffEditAdminCannotGiveThemselvesClinicalSpecialtyPermissions() {
        SeededTenant t = seedTenant();
        String owner = loginAndGetAccessToken(seedStaff(t, seedFullAccessRole(t.id()), "q42o" + SEQ.incrementAndGet() + "@q.com", phone(), false));
        UUID adminRole = seedRole(t.id(), "Admin", false, new ModuleGrant("staff", true, true, true, false, false, false, false));
        SeededStaff admin = seedStaff(t, adminRole, "q42a" + SEQ.incrementAndGet() + "@q.com", phone(), false);
        String adminToken = loginAndGetAccessToken(admin);
        assertThat(permissions(adminToken)).noneMatch(p -> p.startsWith("specialty_") || p.startsWith("clinical:"));
        String patientId = patient(owner);

        ResponseEntity<Map> self = assign(adminToken, admin.id(), "DENT");
        String again = loginAndGetAccessToken(admin);
        List<String> after = permissions(again);
        ResponseEntity<Map> write = exchange("/v1/specialty/dental/patients/" + patientId + "/chart/36", HttpMethod.PATCH,
                authedJsonBody(again, Map.of("status", "missing", "note", "qa")), Map.class);
        ResponseEntity<String> read = exchange("/v1/specialty/dental/patients/" + patientId + "/chart", HttpMethod.GET, authed(again), String.class);
        System.out.println("C42-A01 self-assign=" + self.getStatusCode() + " " + self.getBody() + " newPerms(specialty)="
                + after.stream().filter(p -> p.startsWith("specialty_")).toList() + " dentalWrite=" + write.getStatusCode() + " dentalRead=" + read.getStatusCode());
        assertThat(self.getStatusCode()).as("assigning a profile whose permissions the caller does not hold").isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(after).doesNotContain("specialty_dental:edit", "specialty_dental:view");
        assertThat(write.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    // C42-A02: the owner gives a receptionist (no clinical grant at all) the dental profile
    @Test
    void c42a02_aNonClinicianWithAProfileGetsNoClinicalChartAccess() {
        SeededTenant t = seedTenant();
        String owner = loginAndGetAccessToken(seedStaff(t, seedFullAccessRole(t.id()), "q42p" + SEQ.incrementAndGet() + "@q.com", phone(), false));
        UUID recRole = seedRole(t.id(), "Receptionist", false, new ModuleGrant("queue", true, true, true, false, false, false, false),
                new ModuleGrant("patients", true, true, true, false, false, false, false));
        SeededStaff rec = seedStaff(t, recRole, "q42r" + SEQ.incrementAndGet() + "@q.com", phone(), false);
        String patientId = patient(owner);
        assertThat(exchange("/v1/specialty/dental/patients/" + patientId + "/chart/11", HttpMethod.PATCH,
                authedJsonBody(owner, Map.of("status", "filled")), Map.class).getStatusCode()).isEqualTo(HttpStatus.OK);

        ResponseEntity<Map> put = assign(owner, rec.id(), "DENT");
        String recToken = loginAndGetAccessToken(rec);
        ResponseEntity<String> read = exchange("/v1/specialty/dental/patients/" + patientId + "/chart", HttpMethod.GET, authed(recToken), String.class);
        ResponseEntity<Map> write = exchange("/v1/specialty/dental/patients/" + patientId + "/chart/11", HttpMethod.PATCH,
                authedJsonBody(recToken, Map.of("status", "missing")), Map.class);
        System.out.println("C42-A02 assign to receptionist=" + put.getStatusCode() + " perms=" + permissions(recToken).stream().filter(p -> p.startsWith("specialty_")).toList()
                + " dentalRead=" + read.getStatusCode() + " dentalWrite=" + write.getStatusCode());
        assertThat(read.getStatusCode().is2xxSuccessful() || write.getStatusCode().is2xxSuccessful())
                .as("a receptionist must not read or write a dental chart because of a specialty profile").isFalse();
        // fix: the receiver must be a clinician
        assertThat(put.getStatusCode().value()).isEqualTo(422);
        assertThat(put.getBody()).containsEntry("code", "NOT_CLINICIAN");
    }

    // fix T1-BUG-2: only the Owner assigns (a clinician admin with staff:edit and clinical:edit cannot, not even to a doctor), the owner
    // needs to be a clinician to take a profile, and the minted permissions never exceed the holder's clinical grants or outlive the flag
    @Test
    void c42a03_onlyTheOwnerAssigns_toClinicians_andTokenPermissionsFollowTheClinicalGrants() {
        SeededTenant t = seedTenant();
        SeededStaff ownerStaff = seedStaff(t, seedFullAccessRole(t.id()), "q42q" + SEQ.incrementAndGet() + "@q.com", phone(), false);
        String owner = loginAndGetAccessToken(ownerStaff);
        UUID adminRole = seedRole(t.id(), "Clinical admin", false, new ModuleGrant("staff", true, true, true, false, false, false, false),
                new ModuleGrant("clinical", true, true, true, false, false, false, false));
        SeededStaff admin = seedStaff(t, adminRole, "q42c" + SEQ.incrementAndGet() + "@q.com", phone(), false);
        UUID viewerRole = seedRole(t.id(), "Visiting doctor", false, new ModuleGrant("clinical", true, false, false, false, false, false, false));
        SeededStaff viewer = seedStaff(t, viewerRole, "q42v" + SEQ.incrementAndGet() + "@q.com", phone(), false);
        for (UUID id : List.of(admin.id(), viewer.id())) {
            inTenantTx(t.id(), () -> jdbc.update("UPDATE staff SET is_clinician = true WHERE id = ?", id));
        }
        String adminToken = loginAndGetAccessToken(admin);

        ResponseEntity<Map> bySelf = assign(adminToken, admin.id(), "DENT");
        ResponseEntity<Map> byAdmin = assign(adminToken, viewer.id(), "DENT");
        assertThat(bySelf.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(bySelf.getBody()).containsEntry("code", "OWNER_ONLY");
        assertThat(byAdmin.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(inTenantTx(t.id(), () -> jdbc.queryForObject("SELECT count(*) FROM staff_specialty_profiles", Integer.class))).isZero();

        // the owner is not a clinician here: no profile for themselves either
        assertThat(assign(owner, ownerStaff.id(), "DENT").getBody()).containsEntry("code", "NOT_CLINICIAN");

        // a clinician whose role reads clinical records only gets the profile's view, not edit
        assertThat(assign(owner, viewer.id(), "DENT").getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(permissions(loginAndGetAccessToken(viewer))).contains("specialty_dental:view").doesNotContain("specialty_dental:edit");
        assertThat(assign(owner, admin.id(), "DENT").getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(permissions(loginAndGetAccessToken(admin))).contains("specialty_dental:view", "specialty_dental:edit");

        // no longer a clinician: the stored profile grants nothing at the next sign-in
        inTenantTx(t.id(), () -> jdbc.update("UPDATE staff SET is_clinician = false WHERE id = ?", admin.id()));
        assertThat(permissions(loginAndGetAccessToken(admin))).noneMatch(p -> p.startsWith("specialty_"));
    }
}
