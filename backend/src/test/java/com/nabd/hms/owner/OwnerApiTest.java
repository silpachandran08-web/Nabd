package com.nabd.hms.owner;

import com.nabd.hms.support.ApiTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class OwnerApiTest extends ApiTestBase {

    // ---- login ----

    @Test
    void loginWithCorrectPinSucceeds() {
        SeededOwner owner = seedOwner("owner1@a.com");

        ResponseEntity<Map> resp = http.postForEntity(url("/v1/owners/auth/login"), jsonBody(Map.of(
                "email", owner.email(), "pin", STAFF_PIN)), Map.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).containsKeys("pendingToken", "expiresIn");
    }

    @Test
    void loginWithWrongPinFails() {
        SeededOwner owner = seedOwner("owner2@a.com");

        ResponseEntity<Map> resp = http.postForEntity(url("/v1/owners/auth/login"), jsonBody(Map.of(
                "email", owner.email(), "pin", "9999")), Map.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void loginWithUnknownEmailFails() {
        ResponseEntity<Map> resp = http.postForEntity(url("/v1/owners/auth/login"), jsonBody(Map.of(
                "email", "nobody@a.com", "pin", "1234")), Map.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void repeatedFailuresLockAccount() {
        SeededOwner owner = seedOwner("owner3@a.com");

        for (int i = 0; i < 5; i++) {
            http.postForEntity(url("/v1/owners/auth/login"), jsonBody(Map.of(
                    "email", owner.email(), "pin", "0000")), Map.class);
        }
        ResponseEntity<Map> locked = http.postForEntity(url("/v1/owners/auth/login"), jsonBody(Map.of(
                "email", owner.email(), "pin", "0000")), Map.class);
        assertThat(locked.getStatusCode()).isEqualTo(HttpStatus.LOCKED);

        // regression: a still-locked hit must not renew the window (same fix as staff login this session)
        Integer attemptsAfterFirstLock = jdbc.queryForObject(
                "SELECT COUNT(*) FROM login_attempts WHERE owner_id = ?", Integer.class, owner.id());
        http.postForEntity(url("/v1/owners/auth/login"), jsonBody(Map.of(
                "email", owner.email(), "pin", STAFF_PIN)), Map.class);
        Integer attemptsAfterHammering = jdbc.queryForObject(
                "SELECT COUNT(*) FROM login_attempts WHERE owner_id = ?", Integer.class, owner.id());
        assertThat(attemptsAfterHammering).isEqualTo(attemptsAfterFirstLock);
    }

    // ---- workspaces ----

    @Test
    void listWorkspacesReturnsBrandsAndClinics() {
        SeededOwner owner = seedOwner("owner4@a.com");
        SeededBrand brand = seedBrand(owner, "Brand One");
        SeededTenant clinic = seedClinicInBrand(brand);
        String pending = ownerLoginPendingToken(owner);

        ResponseEntity<Map> resp = exchange("/v1/owners/me/workspaces", HttpMethod.GET, authed(pending), Map.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        List<Map<String, Object>> brands = (List<Map<String, Object>>) resp.getBody().get("brands");
        assertThat(brands).hasSize(1);
        assertThat(brands.get(0).get("name")).isEqualTo("Brand One");
        List<Map<String, Object>> clinics = (List<Map<String, Object>>) brands.get(0).get("clinics");
        assertThat(clinics).hasSize(1);
        assertThat(clinics.get(0).get("id")).isEqualTo(clinic.id().toString());
    }

    @Test
    void listWorkspacesIsEmptyForOwnerWithNoBrands() {
        SeededOwner owner = seedOwner("owner5@a.com");
        String pending = ownerLoginPendingToken(owner);

        ResponseEntity<Map> resp = exchange("/v1/owners/me/workspaces", HttpMethod.GET, authed(pending), Map.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat((List<?>) resp.getBody().get("brands")).isEmpty();
    }

    @Test
    void workspaceListingDoesNotLeakAcrossOwners() {
        SeededOwner ownerA = seedOwner("ownerA@a.com");
        SeededOwner ownerB = seedOwner("ownerB@a.com");
        seedClinicInBrand(seedBrand(ownerA, "A's Brand"));
        seedClinicInBrand(seedBrand(ownerB, "B's Brand"));

        String pendingA = ownerLoginPendingToken(ownerA);
        ResponseEntity<Map> resp = exchange("/v1/owners/me/workspaces", HttpMethod.GET, authed(pendingA), Map.class);
        List<Map<String, Object>> brands = (List<Map<String, Object>>) resp.getBody().get("brands");

        assertThat(brands).hasSize(1);
        assertThat(brands.get(0).get("name")).isEqualTo("A's Brand");
    }

    @Test
    void workspaceEndpointsRejectMissingOrWrongPurposeToken() {
        SeededOwner owner = seedOwner("owner6@a.com");
        seedClinicInBrand(seedBrand(owner, "Brand"));

        ResponseEntity<Map> noAuth = http.getForEntity(url("/v1/owners/me/workspaces"), Map.class);
        assertThat(noAuth.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);

        // a completely unrelated, garbage bearer token should also be rejected, not just "no token"
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth("not-a-real-jwt");
        ResponseEntity<Map> garbage = http.exchange(url("/v1/owners/me/workspaces"), HttpMethod.GET,
                new HttpEntity<>(headers), Map.class);
        assertThat(garbage.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    // ---- workspace selection ----

    @Test
    void selectingOwnedClinicMintsAWorkingToken() {
        SeededOwner owner = seedOwner("owner7@a.com");
        SeededTenant clinic = seedClinicInBrand(seedBrand(owner, "Brand"));
        String pending = ownerLoginPendingToken(owner);

        ResponseEntity<Map> resp = exchange("/v1/owners/workspaces/select", HttpMethod.POST,
                authedJsonBody(pending, Map.of("clinicId", clinic.id().toString())), Map.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        String accessToken = (String) resp.getBody().get("accessToken");
        assertThat(accessToken).isNotBlank();

        // the minted token must actually work against a normal clinic endpoint, with full permissions
        ResponseEntity<Map> staffList = exchange("/v1/staff", HttpMethod.GET, authed(accessToken), Map.class);
        assertThat(staffList.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void selectingUnownedClinicIsRejected() {
        SeededOwner ownerA = seedOwner("ownerA2@a.com");
        SeededOwner ownerB = seedOwner("ownerB2@a.com");
        SeededTenant clinicB = seedClinicInBrand(seedBrand(ownerB, "B's Brand"));
        String pendingA = ownerLoginPendingToken(ownerA);

        ResponseEntity<Map> resp = exchange("/v1/owners/workspaces/select", HttpMethod.POST,
                authedJsonBody(pendingA, Map.of("clinicId", clinicB.id().toString())), Map.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void selectingUnknownClinicIsRejected() {
        SeededOwner owner = seedOwner("owner8@a.com");
        seedClinicInBrand(seedBrand(owner, "Brand"));
        String pending = ownerLoginPendingToken(owner);

        ResponseEntity<Map> resp = exchange("/v1/owners/workspaces/select", HttpMethod.POST,
                authedJsonBody(pending, Map.of("clinicId", UUID.randomUUID().toString())), Map.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void selectingWorkspaceWithANormalStaffTokenIsRejected() {
        SeededOwner owner = seedOwner("owner9@a.com");
        SeededTenant clinic = seedClinicInBrand(seedBrand(owner, "Brand")); // already seeds a built-in role
        UUID roleId = seedRole(clinic.id(), "Doctor", false, fullGrant("patients"), fullGrant("queue"));
        SeededStaff staff = seedStaff(clinic, roleId, "doctor@a.com", "+919900000001", false);
        String staffToken = loginAndGetAccessToken(staff);

        // a real, validly-signed clinic access token has the wrong "purpose" — must not work here
        ResponseEntity<Map> resp = exchange("/v1/owners/workspaces/select", HttpMethod.POST,
                authedJsonBody(staffToken, Map.of("clinicId", clinic.id().toString())), Map.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void selectingSameClinicTwiceReusesTheSameShadowStaffRow() {
        SeededOwner owner = seedOwner("owner10@a.com");
        SeededTenant clinic = seedClinicInBrand(seedBrand(owner, "Brand"));

        String pending1 = ownerLoginPendingToken(owner);
        exchange("/v1/owners/workspaces/select", HttpMethod.POST,
                authedJsonBody(pending1, Map.of("clinicId", clinic.id().toString())), Map.class);
        String pending2 = ownerLoginPendingToken(owner);
        exchange("/v1/owners/workspaces/select", HttpMethod.POST,
                authedJsonBody(pending2, Map.of("clinicId", clinic.id().toString())), Map.class);

        Integer shadowStaffCount = inTenantTx(clinic.id(), () -> jdbc.queryForObject(
                "SELECT COUNT(*) FROM staff WHERE tenant_id = ? AND owner_id = ?", Integer.class,
                clinic.id(), owner.id()));
        assertThat(shadowStaffCount).isEqualTo(1);
    }
}
