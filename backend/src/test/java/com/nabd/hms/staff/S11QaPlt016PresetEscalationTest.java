package com.nabd.hms.staff;

import com.nabd.hms.common.GrantsFlattener;
import com.nabd.hms.common.ModuleGrant;
import com.nabd.hms.staff.presets.RolePresets;
import com.nabd.hms.support.ApiTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/** Independent QA (S11 PLT-016): preset bundles, MF-52 Owner without clinical writes, reset-to-preset, clinician pickers, no escalation. */
@SuppressWarnings({"rawtypes", "unchecked"})
class S11QaPlt016PresetEscalationTest extends ApiTestBase {

    private static final AtomicInteger SEQ = new AtomicInteger();

    private String phone() {
        return "+9194" + (10_000_000 + SEQ.incrementAndGet());
    }

    private SeededStaff staff(SeededTenant t, UUID role) {
        return seedStaff(t, role, "q3p" + UUID.randomUUID() + "@a.com", phone(), false);
    }

    /** A tenant seeded with all six presets as provisioning would (built-in Owner with the MF-52 grants). */
    private Map<String, UUID> presets(UUID tenantId) {
        Map<String, UUID> out = new HashMap<>();
        for (RolePresets.Preset p : RolePresets.ALL) {
            UUID id = UUID.randomUUID();
            String json = json(p.grants());
            inTenantTx(tenantId, () -> jdbc.update("INSERT INTO roles (id, tenant_id, name, built_in, grants, preset_code, preset_version) " +
                    "VALUES (?,?,?,?,?::jsonb,?,?)", id, tenantId, p.name(), p.builtIn(), json, p.code(), RolePresets.VERSION));
            out.put(p.code(), id);
        }
        return out;
    }

    private String json(Object v) {
        try {
            return objectMapper.writeValueAsString(v);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private List<String> permissions(String token) {
        try {
            Map c = objectMapper.readValue(java.util.Base64.getUrlDecoder().decode(token.split("\\.")[1]), Map.class);
            return (List<String>) c.get("permissions");
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static Map<String, Object> grant(String module, boolean v, boolean c, boolean e, boolean d, boolean a, boolean r, boolean x) {
        Map<String, Object> m = new HashMap<>();
        m.put("module", module);
        m.put("view", v); m.put("create", c); m.put("edit", e); m.put("delete", d); m.put("approve", a); m.put("refundDiscount", r); m.put("export", x);
        return m;
    }

    private ResponseEntity<Map> createRole(String token, String name, List<Map<String, Object>> grants) {
        return exchange("/v1/roles", HttpMethod.POST, authedJsonBody(token, Map.of("name", name, "grants", grants, "mfaRequired", true)), Map.class);
    }

    private ResponseEntity<Map> patchRole(String token, UUID id, String name, List<Map<String, Object>> grants) {
        return exchange("/v1/roles/" + id, HttpMethod.PATCH, authedJsonBody(token, Map.of("name", name, "grants", grants, "mfaRequired", true)), Map.class);
    }

    private ResponseEntity<Map> patchStaff(String token, UUID id, Map<String, Object> body) {
        return exchange("/v1/staff/" + id, HttpMethod.PATCH, authedJsonBody(token, body), Map.class);
    }

    private String grantsOf(UUID tenantId, UUID roleId) {
        return inTenantTx(tenantId, () -> jdbc.queryForObject("SELECT grants::text FROM roles WHERE id = ?", String.class, roleId));
    }

    private UUID roleOf(UUID tenantId, UUID staffId) {
        return inTenantTx(tenantId, () -> jdbc.queryForObject("SELECT role_id FROM staff WHERE id = ?", UUID.class, staffId));
    }

    private static String type(ResponseEntity<Map> r) {
        return String.valueOf(r.getBody().get("type"));
    }

    /** Manager: staff view/create/edit and patients view only. No clinical, no billing refund. */
    private UUID managerRole(UUID tenantId) {
        return seedRole(tenantId, "Mgr" + UUID.randomUUID().toString().substring(0, 5), false,
                new ModuleGrant("staff", true, true, true, false, false, false, false),
                new ModuleGrant("patients", true, false, false, false, false, false, false));
    }

    // ---- AC1: provisioning seeds exactly six presets ----
    @Test
    void ac1_realProvisioningJob_seedsExactlySixPresets_matchingTheCatalogue() {
        UUID tenantId = provisionTenant("q3p" + UUID.randomUUID().toString().substring(0, 8));
        List<Map<String, Object>> roles = inTenantTx(tenantId, () -> jdbc.queryForList(
                "SELECT name, built_in, preset_code, grants::text AS grants FROM roles WHERE tenant_id = ?", tenantId));
        assertThat(roles).hasSize(6);
        assertThat(roles).extracting(r -> r.get("name")).containsExactlyInAnyOrder(
                "Owner", "Owner-doctor", "Doctor", "Nurse", "Receptionist", "Front-desk + Vitals");
        for (Map<String, Object> r : roles) {
            RolePresets.Preset p = RolePresets.byCode((String) r.get("preset_code")).orElseThrow();
            List<ModuleGrant> seeded = readGrants((String) r.get("grants"));
            assertThat(GrantsFlattener.flatten(seeded)).as(p.code()).containsExactlyInAnyOrderElementsOf(GrantsFlattener.flatten(p.grants()));
        }
    }

    // ---- AC5 + task (c): a new clinic's Owner has no clinical create/edit/approve, enforced end to end ----
    @Test
    void ac5_newClinicOwner_tokenAndApiRefuseClinicalCreateEditApprove_butViewWorks() {
        UUID tenantId = provisionTenant("q3o" + UUID.randomUUID().toString().substring(0, 8));
        SeededTenant t = tenantRef(tenantId, jdbc.queryForObject("SELECT slug FROM tenants WHERE id = ?", String.class, tenantId));
        UUID ownerRole = inTenantTx(tenantId, () -> jdbc.queryForObject("SELECT id FROM roles WHERE preset_code = 'owner'", UUID.class));
        UUID doctorRole = inTenantTx(tenantId, () -> jdbc.queryForObject("SELECT id FROM roles WHERE preset_code = 'doctor'", UUID.class));
        String owner = loginAndGetAccessToken(staff(t, ownerRole));
        SeededStaff doc = com.nabd.hms.support.ClinicianFixture.licensed(staff(t, doctorRole));
        String docTok = loginAndGetAccessToken(doc);

        assertThat(permissions(owner)).contains("clinical:view").doesNotContain("clinical:create", "clinical:edit", "clinical:approve");
        assertThat(permissions(docTok)).contains("clinical:edit").doesNotContain("clinical:approve");

        ResponseEntity<Map> reg = exchange("/v1/patients", HttpMethod.POST, authedJsonBody(owner, Map.of(
                "name", "Qaowner Pia", "phone", phone(), "dob", "1990-01-01", "gender", "female", "synthetic", true)), Map.class); // ADM-002: demo_only gate
        String patient = (String) reg.getBody().get("id");
        ResponseEntity<Map> checkIn = exchange("/v1/queue/check-in", HttpMethod.POST, authedJsonBody(owner, Map.of(
                "patientId", patient, "doctorId", doc.id().toString())), Map.class);
        assertThat(checkIn.getStatusCode().is2xxSuccessful()).as(String.valueOf(checkIn.getBody())).isTrue();
        String q = (String) checkIn.getBody().get("id");

        assertThat(exchange("/v1/clinical/notes/" + q, HttpMethod.PATCH, authedJsonBody(owner, Map.of("assessment", "owner wrote this")), Map.class)
                .getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(exchange("/v1/clinical/patients/" + patient + "/allergies", HttpMethod.POST, authedJsonBody(owner, Map.of(
                "substance", "Peanut", "severity", "mild")), Map.class).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(exchange("/v1/clinical/vitals/" + q, HttpMethod.PATCH, authedJsonBody(owner, Map.of("pulseBpm", 70)), Map.class)
                .getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(exchange("/v1/clinical/notes/" + q + "/sign", HttpMethod.POST, authed(owner), Map.class)
                .getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(exchange("/v1/clinical/prescriptions/" + q + "/sign", HttpMethod.POST, authed(owner), Map.class)
                .getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        // the doctor can write it, and the owner can still read it (clinical:view)
        assertThat(exchange("/v1/clinical/notes/" + q, HttpMethod.PATCH, authedJsonBody(docTok, Map.of("assessment", "doctor wrote this")), Map.class)
                .getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(exchange("/v1/clinical/notes/" + q, HttpMethod.GET, authed(owner), Map.class).getBody().get("assessment")).isEqualTo("doctor wrote this");
    }

    // ---- task (c): the Owner may build a role with grants it lacks; that never lets a non-Owner escalate ----
    @Test
    void ownerCreatesRoleWithClinicalApproveItLacks_nonOwnerCannotAssignInviteEditCreateDelegateOrResetToReachIt() {
        SeededTenant t = seedTenant();
        Map<String, UUID> p = presets(t.id());
        String owner = loginAndGetAccessToken(staff(t, p.get("owner")));
        List<Map<String, Object>> approverGrants = List.of(grant("clinical", true, true, true, false, true, false, false),
                grant("patients", true, false, false, false, false, false, false));
        ResponseEntity<Map> created = createRole(owner, "Clinical approver", approverGrants);
        assertThat(created.getStatusCode()).as(String.valueOf(created.getBody())).isEqualTo(HttpStatus.CREATED);
        UUID approver = UUID.fromString((String) created.getBody().get("id"));

        UUID mgrRole = managerRole(t.id());
        SeededStaff mgr = staff(t, mgrRole);
        SeededStaff colleague = staff(t, p.get("receptionist"));
        String mgrTok = loginAndGetAccessToken(mgr);
        String approverGrantsBefore = grantsOf(t.id(), approver);

        ResponseEntity<Map> assignColleague = patchStaff(mgrTok, colleague.id(), Map.of("roleId", approver.toString()));
        assertThat(assignColleague.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(type(assignColleague)).endsWith("grant-exceeds-caller");
        assertThat(patchStaff(mgrTok, mgr.id(), Map.of("roleId", approver.toString())).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(patchStaff(mgrTok, mgr.id(), Map.of("roleId", p.get("owner_doctor").toString())).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(exchange("/v1/staff", HttpMethod.POST, authedJsonBody(mgrTok, Map.of("email", "q3inv" + UUID.randomUUID() + "@a.com",
                "name", "Invitee", "mobilePhone", phone(), "roleId", approver.toString())), Map.class).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        // edit the Owner-made role while keeping clinical:approve, or edit own role to add it
        ResponseEntity<Map> editApprover = patchRole(mgrTok, approver, "Renamed approver", approverGrants);
        assertThat(editApprover.getStatusCode().value()).as(String.valueOf(editApprover.getBody())).isIn(400, 403);
        ResponseEntity<Map> editOwn = patchRole(mgrTok, mgrRole, "Mgr", List.of(grant("staff", true, true, true, false, false, false, false),
                grant("patients", true, false, false, false, false, false, false), grant("clinical", true, true, true, false, true, false, false)));
        assertThat(editOwn.getStatusCode().value()).isIn(400, 403);
        assertThat(createRole(mgrTok, "Mgr approver", approverGrants).getStatusCode().value()).isIn(400, 403);
        ResponseEntity<Map> delegate = exchange("/v1/roles/delegations", HttpMethod.POST, authedJsonBody(mgrTok, Map.of(
                "staffId", colleague.id().toString(), "delegatedRoleId", approver.toString(), "reason", "cover",
                "expiresAt", Instant.now().plusSeconds(3600).toString())), Map.class);
        assertThat(delegate.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        ResponseEntity<Map> reset = exchange("/v1/roles/" + p.get("owner_doctor") + "/reset-to-preset", HttpMethod.POST, authed(mgrTok), Map.class);
        assertThat(reset.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        assertThat(roleOf(t.id(), colleague.id())).isEqualTo(p.get("receptionist"));
        assertThat(roleOf(t.id(), mgr.id())).isEqualTo(mgrRole);
        assertThat(grantsOf(t.id(), approver)).isEqualTo(approverGrantsBefore);
        assertThat(GrantsFlattener.flatten(readGrants(grantsOf(t.id(), mgrRole)))).doesNotContain("clinical:approve");
        assertThat(permissions(loginAndGetAccessToken(mgr))).doesNotContain("clinical:approve", "clinical:edit");
    }

    @Test
    void ownerExemptionIsOnlyTheBuiltInOwner_anOwnerDoctorHolderOrACustomRoleNamedOwnerIsNotExempt() {
        SeededTenant t = seedTenant();
        Map<String, UUID> p = presets(t.id());
        String ownerDoctor = loginAndGetAccessToken(staff(t, p.get("owner_doctor")));
        // Owner-doctor = Owner OR Doctor: neither has reports:create
        assertThat(permissions(ownerDoctor)).doesNotContain("reports:create");
        ResponseEntity<Map> r = createRole(ownerDoctor, "Report writer", List.of(grant("reports", true, true, false, false, false, false, false)));
        assertThat(r.getStatusCode().value()).as(String.valueOf(r.getBody())).isIn(400, 403);
        assertThat(exchange("/v1/roles/" + p.get("nurse") + "/reset-to-preset", HttpMethod.POST, authed(ownerDoctor), Map.class)
                .getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        // a custom (not built-in) role that happens to be named Owner gives no exemption
        SeededTenant t2 = seedTenant(); // no built-in Owner here, so a custom role may take the name
        UUID fakeOwner = seedRole(t2.id(), "Owner", false, new ModuleGrant("staff", true, true, true, false, false, false, false));
        String fake = loginAndGetAccessToken(staff(t2, fakeOwner));
        assertThat(createRole(fake, "Escalated", List.of(grant("billing", true, true, true, true, true, true, true))).getStatusCode().value()).isIn(400, 403);
        assertThat(inTenantTx(t.id(), () -> jdbc.queryForObject("SELECT count(*) FROM roles WHERE name IN ('Escalated','Report writer')", Integer.class))).isZero();
        assertThat(inTenantTx(t2.id(), () -> jdbc.queryForObject("SELECT count(*) FROM roles WHERE name = 'Escalated'", Integer.class))).isZero();
    }

    /** A delegated (loaned) grant must not be turned into a permanent one by editing one's own permanent role. */
    @Test
    void nonOwnerOnLoan_cannotBakeTheLoanedGrantIntoTheirPermanentRole() {
        SeededTenant t = seedTenant();
        Map<String, UUID> p = presets(t.id());
        SeededStaff ownerStaff = staff(t, p.get("owner"));
        String owner = loginAndGetAccessToken(ownerStaff);
        UUID mgrRole = managerRole(t.id());
        SeededStaff mgr = staff(t, mgrRole);
        UUID refunder = seedRole(t.id(), "Refunder", false, new ModuleGrant("billing", true, true, true, false, false, true, false));
        ResponseEntity<Map> d = exchange("/v1/roles/delegations", HttpMethod.POST, authedJsonBody(owner, Map.of(
                "staffId", mgr.id().toString(), "delegatedRoleId", refunder.toString(), "reason", "leave cover",
                "expiresAt", Instant.now().plusSeconds(3600).toString())), Map.class);
        assertThat(d.getStatusCode()).as(String.valueOf(d.getBody())).isEqualTo(HttpStatus.CREATED);
        String mgrTok = loginAndGetAccessToken(mgr);
        assertThat(permissions(mgrTok)).contains("billing:refundDiscount");

        ResponseEntity<Map> bake = patchRole(mgrTok, mgrRole, "Mgr", List.of(grant("staff", true, true, true, false, false, false, false),
                grant("patients", true, false, false, false, false, false, false), grant("billing", true, true, true, false, false, true, false)));
        assertThat(bake.getStatusCode().value()).as("PATCH own permanent role with loaned billing:refundDiscount -> " + bake.getStatusCode()
                + " " + bake.getBody()).isIn(400, 403);
        assertThat(GrantsFlattener.flatten(readGrants(grantsOf(t.id(), mgrRole)))).doesNotContain("billing:refundDiscount");
    }

    // ---- AC3: edit a preset then reset ----
    @Test
    void ac3_ownerEditsNursePreset_resetRestoresCatalogueGrants_auditedOnce_customRole409_otherTenant404() {
        SeededTenant t = seedTenant(), other = seedTenant();
        Map<String, UUID> p = presets(t.id());
        Map<String, UUID> po = presets(other.id());
        String owner = loginAndGetAccessToken(staff(t, p.get("owner")));
        UUID nurse = p.get("nurse");
        assertThat(patchRole(owner, nurse, "Senior nurse", List.of(grant("queue", true, false, false, false, false, false, false)))
                .getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(GrantsFlattener.flatten(readGrants(grantsOf(t.id(), nurse)))).containsExactly("queue:view");

        ResponseEntity<Map> reset = exchange("/v1/roles/" + nurse + "/reset-to-preset", HttpMethod.POST, authed(owner), Map.class);
        assertThat(reset.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(reset.getBody().get("presetCode")).isEqualTo("nurse");
        assertThat(reset.getBody().get("presetVersion")).isEqualTo(RolePresets.VERSION);
        assertThat(GrantsFlattener.flatten(readGrants(grantsOf(t.id(), nurse))))
                .containsExactlyInAnyOrderElementsOf(GrantsFlattener.flatten(RolePresets.byCode("nurse").orElseThrow().grants()));
        assertThat(inTenantTx(t.id(), () -> jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE action = 'role.reset_to_preset' AND entity_id = ?",
                Integer.class, nurse))).isEqualTo(1);

        UUID custom = seedRole(t.id(), "Custom", false, new ModuleGrant("queue", true, false, false, false, false, false, false));
        ResponseEntity<Map> notPreset = exchange("/v1/roles/" + custom + "/reset-to-preset", HttpMethod.POST, authed(owner), Map.class);
        assertThat(notPreset.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(type(notPreset)).endsWith("not-a-preset");
        assertThat(exchange("/v1/roles/" + po.get("nurse") + "/reset-to-preset", HttpMethod.POST, authed(owner), Map.class)
                .getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(exchange("/v1/roles/" + nurse + "/reset-to-preset", HttpMethod.POST, jsonBody(Map.of()), Map.class)
                .getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    // ---- AC2: pickers follow is_clinician, not role names ----
    @Test
    void ac2_renameDoctorPresetToConsultant_rosterUnchanged_andRenamingAnotherRoleDoctorAddsNobody() {
        SeededTenant t = seedTenant();
        Map<String, UUID> p = presets(t.id());
        String owner = loginAndGetAccessToken(staff(t, p.get("owner")));
        SeededStaff doc = staff(t, p.get("doctor"));
        SeededStaff rec = staff(t, p.get("receptionist"));
        assertThat(patchStaff(owner, doc.id(), Map.of("professionalRegistrationNo", "QA3-REG-1")).getStatusCode()).isEqualTo(HttpStatus.OK);
        List<String> before = roster(owner);
        assertThat(before).contains(doc.id().toString()).doesNotContain(rec.id().toString());

        List<Map<String, Object>> docGrants = (List<Map<String, Object>>) (List) readGrants(grantsOf(t.id(), p.get("doctor"))).stream()
                .map(g -> objectMapper.convertValue(g, Map.class)).toList();
        assertThat(patchRole(owner, p.get("doctor"), "Consultant", docGrants).getStatusCode()).isEqualTo(HttpStatus.OK);
        List<Map<String, Object>> recGrants = (List<Map<String, Object>>) (List) readGrants(grantsOf(t.id(), p.get("receptionist"))).stream()
                .map(g -> objectMapper.convertValue(g, Map.class)).toList();
        assertThat(patchRole(owner, p.get("receptionist"), "Doctor", recGrants).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(roster(owner)).containsExactlyInAnyOrderElementsOf(before);
    }

    private List<String> roster(String token) {
        List<Map<String, Object>> r = exchange("/v1/staff/roster", HttpMethod.GET, authed(token), List.class).getBody();
        List<String> ids = new ArrayList<>();
        r.forEach(e -> ids.add((String) e.get("id")));
        return ids;
    }

    // ---- AC4: backfill ----
    @Test
    void ac4_backfill_doctorRoleAndRegistrationIsClinician_ambiguousRowsListed_tenantScoped() throws Exception {
        SeededTenant t = seedTenant(), other = seedTenant();
        UUID ownerRole = seedFullAccessRole(t.id());
        SeededStaff owner = staff(t, ownerRole);
        UUID drRole = seedRole(t.id(), "Visiting doctor", false, new ModuleGrant("queue", true, false, false, false, false, false, false));
        UUID nurseRole = seedRole(t.id(), "Nurse", false, new ModuleGrant("queue", true, false, false, false, false, false, false));
        SeededStaff both = staff(t, drRole), roleOnly = staff(t, drRole), regOnly = staff(t, nurseRole), neither = staff(t, nurseRole);
        inTenantTx(t.id(), () -> jdbc.update("UPDATE staff SET professional_registration_no = 'QA3-R' WHERE id IN (?, ?)", both.id(), regOnly.id()));
        SeededStaff otherDoc = staff(other, seedRole(other.id(), "Doctor", false, new ModuleGrant("queue", true, false, false, false, false, false, false)));

        String sql = Files.readString(Path.of("../db/migrations/V1370__role_presets_clinician.sql"));
        String block = sql.substring(sql.indexOf("DO $$"), sql.indexOf("END $$;") + "END $$;".length());
        inTenantTx(t.id(), () -> jdbc.execute(block));

        java.util.function.Function<UUID, Boolean> flag = id -> inTenantTx(t.id(), () -> jdbc.queryForObject("SELECT is_clinician FROM staff WHERE id = ?", Boolean.class, id));
        assertThat(flag.apply(both.id())).isTrue();
        assertThat(flag.apply(neither.id())).isFalse();
        assertThat(flag.apply(owner.id())).isFalse();
        List<Map<String, Object>> report = exchange("/v1/staff/clinician-review", HttpMethod.GET, authed(loginAndGetAccessToken(owner)), List.class).getBody();
        Map<String, Object> reasons = new HashMap<>();
        report.forEach(r -> reasons.put((String) r.get("staffId"), r.get("reason")));
        assertThat(reasons).containsOnlyKeys(roleOnly.id().toString(), regOnly.id().toString());
        assertThat(reasons.get(regOnly.id().toString())).isEqualTo("registration_without_doctor_role");
        assertThat(reasons.get(roleOnly.id().toString())).isEqualTo("doctor_role_without_registration");
        assertThat(inTenantTx(other.id(), () -> jdbc.queryForObject("SELECT is_clinician FROM staff WHERE id = ?", Boolean.class, otherDoc.id())))
                .as("backfill run under tenant t never touched another tenant").isFalse();
    }

    /** SeededTenant's constructor is protected (other package); the provisioned tenant is not made by seedTenant(). */
    private static SeededTenant tenantRef(UUID id, String slug) {
        try {
            var c = SeededTenant.class.getDeclaredConstructor(UUID.class, String.class);
            c.setAccessible(true);
            return c.newInstance(id, slug);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private List<ModuleGrant> readGrants(String json) {
        try {
            return objectMapper.readValue(json, objectMapper.getTypeFactory().constructCollectionType(List.class, ModuleGrant.class));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private UUID provisionTenant(String slug) {
        String token = platformLoginAndGetAccessToken(seedOperator("q3op-" + UUID.randomUUID() + "@nabd.health", "super_admin", false));
        ResponseEntity<Map> created = exchange("/v1/platform/provisioning-jobs", HttpMethod.POST, authedJsonBody(token, Map.of(
                "tenantSlug", slug, "tenantName", "Clinic " + slug, "region", "IN", "ownerEmail", "owner-" + slug + "@nabd.health",
                "ownerName", "QA Owner", "ownerMobile", phone(), "brandName", "Brand " + slug, "path", "self_serve")), Map.class);
        assertThat(created.getStatusCode().is2xxSuccessful()).as(String.valueOf(created.getBody())).isTrue();
        String jobId = (String) created.getBody().get("id");
        Map<String, Object> body = null;
        for (int i = 0; i < 6; i++) {
            body = exchange("/v1/platform/provisioning-jobs/" + jobId + "/advance", HttpMethod.POST, authed(token), Map.class).getBody();
        }
        assertThat(body.get("status")).isEqualTo("done");
        return UUID.fromString((String) body.get("createdTenantId"));
    }
}
