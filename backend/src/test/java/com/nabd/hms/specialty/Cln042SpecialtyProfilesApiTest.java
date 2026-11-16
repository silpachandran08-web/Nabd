package com.nabd.hms.specialty;

import com.nabd.hms.common.ModuleGrant;
import com.nabd.hms.platform.entitlements.PlanEntitlements;
import com.nabd.hms.support.ApiTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/** CLN-042: profiles limited by the plan, session claims, read-only after a trial ends, registry-driven config, audit + event. */
@SuppressWarnings({"unchecked", "rawtypes"})
class Cln042SpecialtyProfilesApiTest extends ApiTestBase {

    private static final AtomicInteger SEQ = new AtomicInteger((int) (System.nanoTime() % 400_000) + 100_000);

    @Autowired
    private PlanEntitlements planEntitlements;
    @Autowired
    private SpecialtyService specialtyService;

    private record Clinic(SeededTenant t, SeededStaff owner, String ownerToken, SeededStaff doctor) {
    }

    private String phone() {
        return "+9194" + (10000000 + SEQ.incrementAndGet());
    }

    /** Owner (full access) and a doctor whose role has no specialty grant: specialty permissions can only come from a profile. */
    private Clinic clinic() {
        SeededTenant t = seedTenant();
        SeededStaff owner = seedStaff(t, seedFullAccessRole(t.id()), "o" + SEQ.incrementAndGet() + "@c42.test", phone(), false);
        UUID doctorRole = seedRole(t.id(), "Doctor", false, new ModuleGrant("queue", true, true, true, false, false, false, false),
                new ModuleGrant("clinical", true, true, true, false, false, false, false),
                new ModuleGrant("patients", true, false, false, false, false, false, false));
        SeededStaff doctor = seedStaff(t, doctorRole, "d" + SEQ.incrementAndGet() + "@c42.test", phone(), false);
        return new Clinic(t, owner, loginAndGetAccessToken(owner), doctor);
    }

    private String operator() {
        return platformLoginAndGetAccessToken(seedOperator("sa-" + UUID.randomUUID() + "@nabd.internal", "super_admin", false));
    }

    private void subscribe(String op, UUID tenant, List<String> modules) {
        ResponseEntity<Map> p = exchange("/v1/platform/plans", HttpMethod.POST, authedJsonBody(op, Map.of(
                "code", "c42-" + UUID.randomUUID(), "name", "P", "monthlyPriceCents", 100000, "currency", "INR",
                "active", true, "modules", modules, "limits", Map.of("seats", 20, "branches", 2))), Map.class);
        assertThat(p.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        ResponseEntity<Map> s = exchange("/v1/platform/billing/subscriptions/" + tenant, HttpMethod.POST, authedJsonBody(op,
                Map.of("planId", p.getBody().get("id"), "mrrCents", 100000, "renewalDate", LocalDate.now().plusDays(30).toString())), Map.class);
        assertThat(s.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    private ResponseEntity<Map> assign(String token, UUID staffId, String primary, String secondary) {
        Map<String, Object> body = new HashMap<>();
        body.put("primary", primary);
        if (secondary != null) body.put("secondary", secondary);
        return exchange("/v1/staff/" + staffId + "/specialty-profiles", HttpMethod.PUT, authedJsonBody(token, body), Map.class);
    }

    private Map<String, Boolean> entitled(String token) {
        Map<String, Object> body = exchange("/v1/specialty/profiles", HttpMethod.GET, authed(token), Map.class).getBody();
        return ((List<Map<String, Object>>) body.get("items")).stream()
                .collect(Collectors.toMap(m -> (String) m.get("code"), m -> (Boolean) m.get("entitled")));
    }

    private Map<String, Object> claims(String token) throws Exception {
        return objectMapper.readValue(Base64.getUrlDecoder().decode(token.split("\\.")[1]), Map.class);
    }

    private String patient(String token) {
        return (String) exchange("/v1/patients", HttpMethod.POST, authedJsonBody(token, Map.of(
                "name", "C42 Patient", "phone", phone(), "dob", "1990-01-01", "gender", "female")), Map.class).getBody().get("id");
    }

    @Test
    void ac1_onlyTheProfilesThePlanUnlocksCanBeChosen() {
        Clinic c = clinic();
        String op = operator();
        subscribe(op, c.t().id(), List.of("billing", "specialty_derm", "specialty_gp"));

        Map<String, Boolean> e = entitled(c.ownerToken());
        assertThat(e).hasSize(9).containsEntry("DERM", true).containsEntry("GP", true);
        assertThat(e.entrySet().stream().filter(Map.Entry::getValue).map(Map.Entry::getKey)).containsExactlyInAnyOrder("DERM", "GP");

        ResponseEntity<Map> refused = assign(c.ownerToken(), c.doctor().id(), "DENT", null);
        assertThat(refused.getStatusCode().value()).isEqualTo(422);
        assertThat(refused.getBody().get("code")).isEqualTo("NOT_ENTITLED");
        assertThat(assign(c.ownerToken(), c.doctor().id(), "DERM", "PED").getBody().get("code")).isEqualTo("NOT_ENTITLED");
        assertThat(assign(c.ownerToken(), c.doctor().id(), "XYZ", null).getBody().get("code")).isEqualTo("UNKNOWN_PROFILE");
        assertThat(assign(c.ownerToken(), c.doctor().id(), "DERM", "DERM").getBody().get("code")).isEqualTo("SAME_PROFILE");
        assertThat(assign(c.ownerToken(), c.doctor().id(), "derm", null).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        ResponseEntity<Map> ok = assign(c.ownerToken(), c.doctor().id(), "DERM", "GP");
        assertThat(ok.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(ok.getBody()).containsEntry("primary", "DERM").containsEntry("secondary", "GP");
        assertThat(exchange("/v1/staff/" + c.doctor().id() + "/specialty-profiles", HttpMethod.GET, authed(c.ownerToken()), Map.class).getBody())
                .containsEntry("primary", "DERM").containsEntry("secondary", "GP");

        // the doctor (no staff:edit) cannot assign, nor list the registry (staff:view); another clinic's staff is 404
        String doctorToken = loginAndGetAccessToken(c.doctor());
        assertThat(assign(doctorToken, c.doctor().id(), "GP", null).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(exchange("/v1/specialty/profiles", HttpMethod.GET, authed(doctorToken), Map.class).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        Clinic other = clinic();
        assertThat(assign(c.ownerToken(), other.doctor().id(), "GP", null).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(inTenantTx(other.t().id(), () -> jdbc.queryForObject("SELECT count(*) FROM staff_specialty_profiles", Integer.class))).isZero();

        // a clinic without a subscription (or on a plan listing no specialty) keeps every profile
        assertThat(entitled(other.ownerToken()).values()).containsOnly(true);
    }

    @Test
    void ac2_theSessionCarriesThePrimaryProfileAndItsPermissions() throws Exception {
        Clinic c = clinic();
        assertThat(claims(loginAndGetAccessToken(c.doctor()))).doesNotContainKey("specialty");
        Map<String, Object> none = exchange("/v1/specialty/me", HttpMethod.GET, authed(loginAndGetAccessToken(c.doctor())), Map.class).getBody();
        assertThat(none).containsEntry("primary", null).containsEntry("config", null);

        assertThat(assign(c.ownerToken(), c.doctor().id(), "DERM", "GP").getStatusCode()).isEqualTo(HttpStatus.OK);
        String token = loginAndGetAccessToken(c.doctor());
        Map<String, Object> claims = claims(token);
        assertThat(claims).containsEntry("specialty", "DERM");
        assertThat((List<String>) claims.get("permissions"))
                .contains("specialty_derm:view", "specialty_derm:edit", "specialty_gp:view", "specialty_gp:edit")
                .doesNotContain("specialty_dental:view");

        Map<String, Object> me = exchange("/v1/specialty/me", HttpMethod.GET, authed(token), Map.class).getBody();
        assertThat(me).containsEntry("primary", "DERM").containsEntry("secondary", "GP").containsEntry("writable", true);
        Map<String, Object> config = (Map<String, Object>) me.get("config");
        assertThat(config).containsKeys("widgets", "quickActions", "fieldSets", "templatePack").containsEntry("module", "specialty_derm");
        assertThat(((Map<String, String>) me.get("name")).get("en")).isEqualTo("Dermatology & cosmetology");
    }

    @Test
    void ac3_afterTheTrialEndsHistoryIsReadableButWritesAre403EntitlementExpired() {
        Clinic c = clinic();
        String op = operator();
        subscribe(op, c.t().id(), List.of("billing", "specialty_gp"));
        Map<String, Object> trial = Map.of("state", "on", "reason", "DENT trial", "expiresAt", Instant.now().plusSeconds(3600).toString());
        assertThat(exchange("/v1/platform/tenants/" + c.t().id() + "/features/specialty_dental", HttpMethod.PUT, authedJsonBody(op, trial), Map.class)
                .getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(assign(c.ownerToken(), c.doctor().id(), "DENT", null).getStatusCode()).isEqualTo(HttpStatus.OK);

        String doctor = loginAndGetAccessToken(c.doctor()); // specialty_dental:edit comes from the DENT profile only
        String patientId = patient(c.ownerToken());
        String tooth = "/v1/specialty/dental/patients/" + patientId + "/chart/36";
        assertThat(exchange(tooth, HttpMethod.PATCH, authedJsonBody(doctor, Map.of("status", "filled")), Map.class).getStatusCode()).isEqualTo(HttpStatus.OK);

        // the trial ends
        new JdbcTemplate(superuserDataSource()).update("UPDATE master.tenant_feature_overrides SET expires_at = now() - interval '1 minute' " +
                "WHERE tenant_id = ? AND feature_code = 'specialty_dental'", c.t().id());
        planEntitlements.evict(c.t().id());

        ResponseEntity<Map> refused = exchange(tooth, HttpMethod.PATCH, authedJsonBody(doctor, Map.of("status", "crown")), Map.class);
        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(refused.getBody()).containsEntry("code", "ENTITLEMENT_EXPIRED");
        assertThat(exchange("/v1/specialty/dental/patients/" + patientId + "/chart/supernumerary", HttpMethod.POST,
                authedJsonBody(doctor, Map.of("nearToothNumber", 11)), Map.class).getBody()).containsEntry("code", "ENTITLEMENT_EXPIRED");
        ResponseEntity<List> chart = exchange("/v1/specialty/dental/patients/" + patientId + "/chart", HttpMethod.GET, authed(doctor), List.class);
        assertThat(chart.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(((Map<String, Object>) chart.getBody().get(0))).containsEntry("status", "filled");

        Map<String, Object> me = exchange("/v1/specialty/me", HttpMethod.GET, authed(doctor), Map.class).getBody();
        assertThat(me).containsEntry("primary", "DENT").containsEntry("writable", false);
        assertThat(entitled(c.ownerToken())).containsEntry("DENT", false).containsEntry("GP", true);
        assertThat(assign(c.ownerToken(), c.doctor().id(), "DENT", "GP").getBody().get("code")).isEqualTo("NOT_ENTITLED");
    }

    @Test
    void ac4_aNewProfileCodeIsServedFromTheRegistryWithoutCodeChanges() {
        JdbcTemplate su = new JdbcTemplate(superuserDataSource());
        su.update("INSERT INTO specialty_profiles (code, name, config) VALUES ('CLNTST', '{\"en\":\"Test specialty\"}', " +
                "'{\"module\":\"specialty_clntst\",\"widgets\":[{\"id\":\"brandNewWidget\",\"label\":{\"en\":\"New widget\"}}]," +
                "\"quickActions\":[{\"id\":\"consult\",\"href\":\"/consult\",\"label\":{\"en\":\"Start consult\"}}],\"fieldSets\":[],\"templatePack\":null}')");
        try {
            specialtyService.evictRegistry();
            Clinic c = clinic();
            assertThat(entitled(c.ownerToken())).containsEntry("CLNTST", true);
            assertThat(assign(c.ownerToken(), c.doctor().id(), "CLNTST", null).getStatusCode()).isEqualTo(HttpStatus.OK);
            Map<String, Object> me = exchange("/v1/specialty/me", HttpMethod.GET, authed(loginAndGetAccessToken(c.doctor())), Map.class).getBody();
            assertThat(me).containsEntry("primary", "CLNTST");
            assertThat((List<Map<String, Object>>) ((Map<String, Object>) me.get("config")).get("widgets"))
                    .extracting(w -> w.get("id")).containsExactly("brandNewWidget");
            inTenantTx(c.t().id(), () -> jdbc.update("DELETE FROM staff_specialty_profiles WHERE tenant_id = ?", c.t().id()));
        } finally {
            su.update("DELETE FROM specialty_profiles WHERE code = 'CLNTST'");
            specialtyService.evictRegistry();
        }
        // the config schema is enforced when a profile is imported
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> su.update("INSERT INTO specialty_profiles (code, name, config) VALUES " +
                "('BAD', '{\"en\":\"Bad\"}', '{\"module\":\"derm\",\"widgets\":[],\"quickActions\":[],\"fieldSets\":[],\"templatePack\":null}')"))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }

    @Test
    void ac5_aProfileChangeIsAuditedWithActorDoctorAndBeforeAfter_andPublished() {
        Clinic c = clinic();
        assertThat(assign(c.ownerToken(), c.doctor().id(), "GP", null).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(assign(c.ownerToken(), c.doctor().id(), "DERM", "GP").getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(assign(c.ownerToken(), c.doctor().id(), "DERM", "GP").getStatusCode()).isEqualTo(HttpStatus.OK); // repeat: no-op

        List<Map<String, Object>> rows = inTenantTx(c.t().id(), () -> jdbc.queryForList(
                "SELECT actor_id, entity_id, before::text AS b, after::text AS a FROM audit_log WHERE action = 'specialty.profile_assigned' ORDER BY id"));
        assertThat(rows).hasSize(2);
        Map<String, Object> last = rows.get(1);
        assertThat(last.get("actor_id")).isEqualTo(c.owner().id());
        assertThat(last.get("entity_id")).isEqualTo(c.doctor().id());
        assertThat((String) last.get("b")).contains("\"primary\": \"GP\"").contains("\"secondary\": null");
        assertThat((String) last.get("a")).contains("\"primary\": \"DERM\"").contains("\"secondary\": \"GP\"");

        List<String> events = inTenantTx(c.t().id(), () -> jdbc.queryForList(
                "SELECT payload::text FROM outbox_events WHERE event_type = 'specialty.profile.assigned.v1' AND aggregate_id = ? ORDER BY id",
                String.class, c.doctor().id()));
        assertThat(events).hasSize(2);
        assertThat(events.get(1)).contains("\"primary\": \"DERM\"").contains("\"secondary\": \"GP\"").contains(c.t().id().toString());
    }
}
