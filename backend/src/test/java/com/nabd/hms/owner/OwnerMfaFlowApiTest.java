package com.nabd.hms.owner;

import com.nabd.hms.common.OpaqueTokens;
import com.nabd.hms.support.ApiTestBase;
import com.nabd.hms.support.TotpTestCodes;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** PLT-005: an owner account passes TOTP (or a recovery code) before any workspace token exists. */
@TestPropertySource(properties = "app.auth.mfa.enforce-privileged=true")
@SuppressWarnings({"rawtypes", "unchecked"})
class OwnerMfaFlowApiTest extends ApiTestBase {

    private final JdbcTemplate su = new JdbcTemplate(superuserDataSource());

    private SeededOwner owner(boolean enrolled) {
        SeededOwner o = seedOwner("own" + UUID.randomUUID().toString().substring(0, 8) + "@a.com");
        if (enrolled) {
            su.update("UPDATE owners SET mfa_enabled = true, mfa_secret_enc = ? WHERE id = ?", cipher.encrypt(TOTP_SECRET), o.id());
        }
        return o;
    }

    private ResponseEntity<Map> login(SeededOwner o) {
        return http.postForEntity(url("/v1/owners/auth/login"), jsonBody(Map.of("email", o.email(), "pin", STAFF_PIN)), Map.class);
    }

    private ResponseEntity<Map> post(String path, Map<String, ?> body) {
        return http.postForEntity(url(path), jsonBody(body), Map.class);
    }

    private ResponseEntity<Map> select(String workspaceToken, UUID clinicId) {
        return exchange("/v1/owners/workspaces/select", HttpMethod.POST, authedJsonBody(workspaceToken, Map.of("clinicId", clinicId.toString())), Map.class);
    }

    @Test
    void unenrolledOwnerIsRoutedIntoEnrolmentAndOnlyThenGetsAWorkspaceToken() {
        SeededOwner o = owner(false);
        SeededTenant clinic = seedClinicInBrand(seedBrand(o, "Brand"));

        ResponseEntity<Map> r = login(o);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(r.getBody()).containsKey("setupToken").doesNotContainKeys("pendingToken", "workspaceToken", "challengeId");
        String setup = (String) r.getBody().get("setupToken");
        assertThat(exchange("/v1/owners/me/workspaces", HttpMethod.GET, authed(setup), Map.class).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(select(setup, clinic.id()).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        // an owner setup token never enrols a clinic staff member
        assertThat(post("/v1/auth/mfa/enroll", Map.of("setupToken", setup)).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);

        ResponseEntity<Map> enroll = post("/v1/owners/auth/mfa/enroll", Map.of("setupToken", setup));
        assertThat(enroll.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat((String) enroll.getBody().get("otpauthUri")).startsWith("otpauth://totp/");
        byte[] secret = TotpTestCodes.base32((String) enroll.getBody().get("secretBase32"));

        assertThat(post("/v1/owners/auth/mfa/confirm", Map.of("setupToken", setup, "code", "000000")).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        ResponseEntity<Map> confirm = post("/v1/owners/auth/mfa/confirm", Map.of("setupToken", setup, "code", TotpTestCodes.code(secret, 0)));
        assertThat(confirm.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat((List<String>) confirm.getBody().get("recoveryCodes")).hasSize(8);
        String ws = (String) confirm.getBody().get("workspaceToken");
        assertThat(exchange("/v1/owners/me/workspaces", HttpMethod.GET, authed(ws), Map.class).getStatusCode()).isEqualTo(HttpStatus.OK);
        ResponseEntity<Map> sel = select(ws, clinic.id());
        assertThat(sel.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(sel.getBody()).containsKey("accessToken");

        // next sign-in is challenged, not set up again
        assertThat(login(o).getBody()).containsKey("challengeId").doesNotContainKey("setupToken");
        assertThat(su.queryForObject("SELECT mfa_enabled FROM owners WHERE id = ?", Boolean.class, o.id())).isTrue();
    }

    @Test
    void enrolledOwnerNeedsTheTotpBeforeWorkspaceSelect() {
        SeededOwner o = owner(true);
        SeededTenant clinic = seedClinicInBrand(seedBrand(o, "Brand"));

        ResponseEntity<Map> r = login(o);
        assertThat(r.getBody()).containsKey("challengeId").doesNotContainKeys("pendingToken", "workspaceToken");
        String challenge = (String) r.getBody().get("challengeId");
        assertThat(select(challenge, clinic.id()).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        // the owner challenge is not a staff challenge
        assertThat(post("/v1/auth/mfa/verify", Map.of("challengeId", challenge, "code", currentTotpCode())).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);

        ResponseEntity<Map> bad = post("/v1/owners/auth/mfa/verify", Map.of("challengeToken", challenge, "code", "000000"));
        assertThat(bad.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat((String) bad.getBody().get("type")).endsWith("mfa-failed");

        String code = totpCodeAtOffset(0);
        ResponseEntity<Map> ok = post("/v1/owners/auth/mfa/verify", Map.of("challengeToken", challenge, "code", code));
        assertThat(ok.getStatusCode()).isEqualTo(HttpStatus.OK);
        String ws = (String) ok.getBody().get("workspaceToken");
        assertThat(select(ws, clinic.id()).getStatusCode()).isEqualTo(HttpStatus.OK);

        // replay of the same time-step is refused
        assertThat(post("/v1/owners/auth/mfa/verify", Map.of("challengeToken", challenge, "code", code)).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void repeatedWrongCodesLockTheOwnerWith423() {
        SeededOwner o = owner(true);
        String challenge = (String) login(o).getBody().get("challengeId");
        for (int i = 0; i < 5; i++) {
            assertThat(post("/v1/owners/auth/mfa/verify", Map.of("challengeToken", challenge, "code", "000000")).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        }
        assertThat(post("/v1/owners/auth/mfa/verify", Map.of("challengeToken", challenge, "code", totpCodeAtOffset(0))).getStatusCode().value()).isEqualTo(423);
    }

    @Test
    void recoveryCodeIsSingleUseAndReturnsAWorkspaceToken() {
        SeededOwner o = owner(false);
        String setup = (String) login(o).getBody().get("setupToken");
        byte[] secret = TotpTestCodes.base32((String) post("/v1/owners/auth/mfa/enroll", Map.of("setupToken", setup)).getBody().get("secretBase32"));
        List<String> codes = (List<String>) post("/v1/owners/auth/mfa/confirm", Map.of("setupToken", setup, "code", TotpTestCodes.code(secret, 0)))
                .getBody().get("recoveryCodes");

        String challenge = (String) login(o).getBody().get("challengeId");
        ResponseEntity<Map> rec = post("/v1/owners/auth/mfa/recovery-verify", Map.of("challengeToken", challenge, "recoveryCode", codes.get(0).toLowerCase()));
        assertThat(rec.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(rec.getBody().get("remainingRecoveryCodes")).isEqualTo(7);
        assertThat(exchange("/v1/owners/me/workspaces", HttpMethod.GET, authed((String) rec.getBody().get("workspaceToken")), Map.class).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(post("/v1/owners/auth/mfa/recovery-verify", Map.of("challengeToken", challenge, "recoveryCode", codes.get(0))).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        // only hashes are stored
        assertThat(su.queryForObject("SELECT count(*) FROM owner_mfa_recovery_codes WHERE owner_id = ? AND code_hash = ?", Integer.class,
                o.id(), OpaqueTokens.sha256Hex(codes.get(1)))).isEqualTo(1);
        assertThat(su.queryForObject("SELECT count(*) FROM owner_mfa_recovery_codes WHERE code_hash = ?", Integer.class, codes.get(1))).isZero();
    }

    @Test
    void ownerInviteAcceptGoesStraightIntoEnrolment() {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO owners (id, name, email, status) VALUES (?, 'New Owner', ?, 'active')", id, "inv" + id.toString().substring(0, 6) + "@a.com");
        String raw = OpaqueTokens.generate();
        su.update("UPDATE owners SET invite_token_hash = ?, invite_expires_at = now() + interval '1 day' WHERE id = ?", OpaqueTokens.sha256Hex(raw), id);
        ResponseEntity<Map> r = post("/v1/owners/invitations/" + raw + "/accept", Map.of("pin", STAFF_PIN));
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(r.getBody()).containsKey("setupToken").doesNotContainKey("pendingToken");
    }

    @Test
    void theAppRoleCannotReadOwnerSecretsOrRecoveryCodes() {
        assertThat(su.queryForObject("SELECT has_table_privilege('nabd_app', 'owner_mfa_recovery_codes', 'SELECT')", Boolean.class)).isFalse();
        assertThat(su.queryForObject("SELECT has_table_privilege('nabd_app', 'owner_mfa_recovery_codes', 'INSERT')", Boolean.class)).isFalse();
        assertThat(su.queryForObject("SELECT has_column_privilege('nabd_app', 'owners', 'mfa_secret_enc', 'SELECT')", Boolean.class)).isFalse();
        assertThat(su.queryForObject("SELECT has_column_privilege('nabd_app', 'owners', 'mfa_pending_secret_enc', 'SELECT')", Boolean.class)).isFalse();
        assertThat(su.queryForObject("SELECT relforcerowsecurity FROM pg_class WHERE oid = 'owner_mfa_recovery_codes'::regclass", Boolean.class)).isTrue();
    }
}
