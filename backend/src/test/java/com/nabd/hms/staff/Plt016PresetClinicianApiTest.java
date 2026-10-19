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
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** PLT-016: doctor pickers follow staff.is_clinician (not role names), reset-to-preset, and the clinician backfill report. */
@SuppressWarnings({"unchecked", "rawtypes"})
class Plt016PresetClinicianApiTest extends ApiTestBase {

    private static final ModuleGrant[] OWNER_PRESET = RolePresets.byCode("owner").orElseThrow().grants().toArray(new ModuleGrant[0]);

    // ── AC2: renaming the Doctor role leaves every doctor picker unchanged ──

    @Test
    void renamingTheDoctorRoleToConsultantLeavesEveryDoctorPickerUnchanged() {
        SeededTenant tenant = seedTenant();
        SeededStaff owner = seedStaff(tenant, seedFullAccessRole(tenant.id()), "o16a@a.com", "+919160000001", false);
        String token = loginAndGetAccessToken(owner);
        UUID doctorRole = seedRole(tenant.id(), "Doctor", false, fullGrant("queue"), fullGrant("clinical"));
        SeededStaff doctor = seedStaff(tenant, doctorRole, "d16a@a.com", "+919160000002", false);
        SeededStaff notClinician = seedStaff(tenant, doctorRole, "d16b@a.com", "+919160000003", false); // role name alone is no signal
        String dentalId = (String) exchange("/v1/departments", HttpMethod.POST, authedJsonBody(token, Map.of(
                "name", "Dental", "active", true)), Map.class).getBody().get("id");
        String generalId = inTenantTx(tenant.id(), () -> jdbc.queryForObject(
                "SELECT id::text FROM departments WHERE tenant_id = ? AND is_default", String.class, tenant.id()));
        exchange("/v1/departments/transfers", HttpMethod.POST, authedJsonBody(token, Map.of(
                "edges", List.of(Map.of("fromDepartmentId", generalId, "toDepartmentId", dentalId)))), List.class);
        inTenantTx(tenant.id(), () -> jdbc.update("UPDATE staff SET department_id = ?::uuid WHERE id IN (?, ?)",
                dentalId, doctor.id(), notClinician.id()));
        assertThat(patch(token, doctor.id(), Map.of("professionalRegistrationNo", "KMC-16001")).getBody().get("clinician")).isEqualTo(true);

        Map<String, List<String>> before = pickers(token, generalId);
        assertThat(before.values()).allSatisfy(ids -> assertThat(ids).containsExactly(doctor.id().toString()));

        ResponseEntity<Map> renamed = exchange("/v1/roles/" + doctorRole, HttpMethod.PATCH, authedJsonBody(token, Map.of(
                "name", "Consultant", "grants", List.of(fullGrant("queue"), fullGrant("clinical")))), Map.class);
        assertThat(renamed.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(renamed.getBody().get("name")).isEqualTo("Consultant");

        assertThat(pickers(token, generalId)).isEqualTo(before);
    }

    /** Calendar columns, queue-assignment roster and the transfer (Rx/consult hand-over) doctor list. */
    private Map<String, List<String>> pickers(String token, String fromDepartmentId) {
        Map<String, List<String>> out = new HashMap<>();
        List<Map<String, Object>> roster = exchange("/v1/staff/roster", HttpMethod.GET, authed(token), List.class).getBody();
        out.put("roster", roster.stream().map(e -> (String) e.get("id")).toList());
        Map<String, Object> day = exchange("/v1/schedule/day?date=" + LocalDate.now().plusDays(2), HttpMethod.GET, authed(token), Map.class).getBody();
        out.put("calendar", ((List<Map<String, Object>>) day.get("doctors")).stream().map(e -> (String) e.get("id")).toList());
        List<Map<String, Object>> targets = exchange("/v1/departments/" + fromDepartmentId + "/transfer-targets", HttpMethod.GET,
                authed(token), List.class).getBody();
        out.put("transfer", ((List<Map<String, Object>>) targets.get(0).get("doctors")).stream().map(e -> (String) e.get("id")).toList());
        return out;
    }

    // ── clinician flag (iam is the single writer) ──

    @Test
    void clinicianNeedsARegistrationNumberAndEveryChangeIsAudited() {
        SeededTenant tenant = seedTenant();
        SeededStaff owner = seedStaff(tenant, seedFullAccessRole(tenant.id()), "o16b@a.com", "+919160000011", false);
        String token = loginAndGetAccessToken(owner);
        SeededStaff nurse = seedStaff(tenant, seedRole(tenant.id(), "Nurse", false, fullGrant("queue")), "n16@a.com", "+919160000012", false);

        ResponseEntity<Map> refused = patch(token, nurse.id(), Map.of("clinician", true));
        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat((String) refused.getBody().get("type")).contains("clinician-needs-registration");
        assertThat(clinician(tenant.id(), nurse.id())).isFalse();

        // registration and an explicit "not a clinician" in one request: the toggle wins
        assertThat(patch(token, nurse.id(), Map.of("professionalRegistrationNo", "NMC 77", "clinician", false)).getBody().get("clinician")).isEqualTo(false);
        ResponseEntity<Map> on = patch(token, nurse.id(), Map.of("clinician", true));
        assertThat(on.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(on.getBody().get("clinician")).isEqualTo(true);
        assertThat(patch(token, nurse.id(), Map.of("clinician", false)).getBody().get("clinician")).isEqualTo(false);
        assertThat(auditCount(tenant.id(), "staff.clinician_set", nurse.id())).isEqualTo(1);
        assertThat(auditCount(tenant.id(), "staff.clinician_cleared", nurse.id())).isEqualTo(1);
    }

    @Test
    void aStaffMemberWithoutStaffEditCannotSetTheFlag() {
        SeededTenant tenant = seedTenant();
        SeededStaff viewer = seedStaff(tenant, seedRole(tenant.id(), "Viewer", false,
                new ModuleGrant("staff", true, false, false, false, false, false, false)), "v16@a.com", "+919160000013", false);
        String token = loginAndGetAccessToken(viewer);
        assertThat(patch(token, viewer.id(), Map.of("clinician", false)).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    // ── AC3: reset to preset ──

    @Test
    void anOwnerCanEditAPresetThenResetItAndTheResetIsAudited() {
        SeededTenant tenant = seedTenant();
        UUID ownerRole = seedRole(tenant.id(), "Owner", true, OWNER_PRESET); // MF-52 owner: no clinical:create/edit/approve
        SeededStaff owner = seedStaff(tenant, ownerRole, "o16c@a.com", "+919160000021", false);
        String token = loginAndGetAccessToken(owner);
        UUID nurseRole = seedPreset(tenant.id(), "nurse");

        // the Owner may edit a preset even where it holds grants the Owner preset itself lacks (clinical:create)
        ResponseEntity<Map> edited = exchange("/v1/roles/" + nurseRole, HttpMethod.PATCH, authedJsonBody(token, Map.of(
                "name", "Nurse", "grants", List.of(fullGrant("queue"), new ModuleGrant("clinical", true, true, false, false, false, false, false)))), Map.class);
        assertThat(edited.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(edited.getBody().get("presetCode")).isEqualTo("nurse");

        ResponseEntity<Map> reset = exchange("/v1/roles/" + nurseRole + "/reset-to-preset", HttpMethod.POST, authedJsonBody(token, Map.of()), Map.class);
        assertThat(reset.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(reset.getBody().get("presetVersion")).isEqualTo(RolePresets.VERSION);
        List<ModuleGrant> grants = objectMapper.convertValue(reset.getBody().get("grants"),
                objectMapper.getTypeFactory().constructCollectionType(List.class, ModuleGrant.class));
        assertThat(grants).containsExactlyElementsOf(RolePresets.byCode("nurse").orElseThrow().grants());
        assertThat(auditCount(tenant.id(), "role.reset_to_preset", nurseRole)).isEqualTo(1);
        String after = inTenantTx(tenant.id(), () -> jdbc.queryForObject(
                "SELECT after::text FROM audit_log WHERE action = 'role.reset_to_preset' AND entity_id = ?", String.class, nurseRole));
        assertThat(after).contains("presetVersion");
    }

    @Test
    void resettingACustomRoleIsRefusedWithNotAPreset() {
        SeededTenant tenant = seedTenant();
        SeededStaff owner = seedStaff(tenant, seedFullAccessRole(tenant.id()), "o16d@a.com", "+919160000031", false);
        String token = loginAndGetAccessToken(owner);
        UUID custom = seedRole(tenant.id(), "Billing Clerk", false, fullGrant("billing"));
        ResponseEntity<Map> resp = exchange("/v1/roles/" + custom + "/reset-to-preset", HttpMethod.POST, authedJsonBody(token, Map.of()), Map.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat((String) resp.getBody().get("type")).contains("not-a-preset");
        assertThat(auditCount(tenant.id(), "role.reset_to_preset", custom)).isZero();
    }

    @Test
    void onlyTheOwnerCanResetAndNeverAnotherTenantsRole() {
        SeededTenant tenant = seedTenant();
        UUID manager = seedRole(tenant.id(), "Manager", false, fullGrant("staff"));
        String managerToken = loginAndGetAccessToken(seedStaff(tenant, manager, "m16@a.com", "+919160000041", false));
        UUID nurseRole = seedPreset(tenant.id(), "nurse");
        assertThat(exchange("/v1/roles/" + nurseRole + "/reset-to-preset", HttpMethod.POST, authedJsonBody(managerToken, Map.of()), Map.class)
                .getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        SeededTenant other = seedTenant();
        String otherOwner = loginAndGetAccessToken(seedStaff(other, seedFullAccessRole(other.id()), "o16e@a.com", "+919160000042", false));
        assertThat(exchange("/v1/roles/" + nurseRole + "/reset-to-preset", HttpMethod.POST, authedJsonBody(otherOwner, Map.of()), Map.class)
                .getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    // ── AC4: backfill of existing tenants and the ambiguous-rows report ──

    @Test
    void backfillFlagsDoctorsAndRegisteredStaffAndReportsTheAmbiguousRows() throws Exception {
        SeededTenant tenant = seedTenant();
        UUID ownerRole = seedFullAccessRole(tenant.id());
        SeededStaff owner = seedStaff(tenant, ownerRole, "o16f@a.com", "+919160000051", false);
        UUID doctorRole = seedRole(tenant.id(), "Senior Doctor", false, fullGrant("queue"));
        UUID receptionRole = seedRole(tenant.id(), "Receptionist", false, fullGrant("queue"));
        SeededStaff both = seedStaff(tenant, doctorRole, "b16@a.com", "+919160000052", false);
        SeededStaff roleOnly = seedStaff(tenant, doctorRole, "r16@a.com", "+919160000053", false);
        SeededStaff regOnly = seedStaff(tenant, receptionRole, "g16@a.com", "+919160000054", false);
        SeededStaff neither = seedStaff(tenant, receptionRole, "x16@a.com", "+919160000055", false);
        inTenantTx(tenant.id(), () -> jdbc.update("UPDATE staff SET professional_registration_no = 'REG-1' WHERE id IN (?, ?)", both.id(), regOnly.id()));

        runV1370Backfill(tenant.id());

        assertThat(clinician(tenant.id(), both.id())).isTrue();
        assertThat(clinician(tenant.id(), roleOnly.id())).isTrue();
        assertThat(clinician(tenant.id(), regOnly.id())).isTrue();
        assertThat(clinician(tenant.id(), neither.id())).isFalse();
        assertThat(clinician(tenant.id(), owner.id())).isFalse();

        String token = loginAndGetAccessToken(owner);
        List<Map<String, Object>> report = exchange("/v1/staff/clinician-review", HttpMethod.GET, authed(token), List.class).getBody();
        Map<Object, Object> reasons = new HashMap<>();
        report.forEach(r -> reasons.put(r.get("staffId"), r.get("reason")));
        assertThat(reasons).containsOnly(
                Map.entry(roleOnly.id().toString(), "doctor_role_without_registration"),
                Map.entry(regOnly.id().toString(), "registration_without_doctor_role"));

        // the owner confirms: the receptionist with a registration number is not a clinician
        assertThat(patch(token, regOnly.id(), Map.of("clinician", false)).getBody().get("clinician")).isEqualTo(false);
        List<Map<String, Object>> remaining = exchange("/v1/staff/clinician-review", HttpMethod.GET, authed(token), List.class).getBody();
        assertThat(remaining).extracting(r -> r.get("staffId")).containsExactly(roleOnly.id().toString());

        // RLS: another tenant's owner sees none of these rows, through the API or SQL
        SeededTenant other = seedTenant();
        String otherToken = loginAndGetAccessToken(seedStaff(other, seedFullAccessRole(other.id()), "o16g@a.com", "+919160000056", false));
        assertThat(exchange("/v1/staff/clinician-review", HttpMethod.GET, authed(otherToken), List.class).getBody()).isEmpty();
        assertThat(inTenantTx(other.id(), () -> jdbc.queryForObject(
                "SELECT count(*) FROM staff_clinician_review WHERE tenant_id = ?", Integer.class, tenant.id()))).isZero();
    }

    /** Runs V1370's own backfill block as nabd_app in this tenant's transaction, so RLS confines it to this tenant. */
    private void runV1370Backfill(UUID tenantId) throws Exception {
        String sql = Files.readString(Path.of("../db/migrations/V1370__role_presets_clinician.sql"));
        String block = sql.substring(sql.indexOf("DO $$"), sql.indexOf("END $$;") + "END $$;".length());
        inTenantTx(tenantId, () -> jdbc.execute(block));
    }

    private UUID seedPreset(UUID tenantId, String code) {
        RolePresets.Preset p = RolePresets.byCode(code).orElseThrow();
        UUID id = UUID.randomUUID();
        String json = toJson(p.grants());
        inTenantTx(tenantId, () -> jdbc.update("INSERT INTO roles (id, tenant_id, name, built_in, grants, preset_code, preset_version) " +
                "VALUES (?,?,?,false,?::jsonb,?,?)", id, tenantId, p.name(), json, p.code(), RolePresets.VERSION));
        return id;
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private ResponseEntity<Map> patch(String token, UUID staffId, Map<String, Object> body) {
        return exchange("/v1/staff/" + staffId, HttpMethod.PATCH, authedJsonBody(token, body), Map.class);
    }

    private boolean clinician(UUID tenantId, UUID staffId) {
        return inTenantTx(tenantId, () -> jdbc.queryForObject("SELECT is_clinician FROM staff WHERE id = ?", Boolean.class, staffId));
    }

    private int auditCount(UUID tenantId, String action, UUID entityId) {
        return inTenantTx(tenantId, () -> jdbc.queryForObject(
                "SELECT count(*) FROM audit_log WHERE action = ? AND entity_id = ?", Integer.class, action, entityId));
    }

    @Test
    void ownerPresetTokenCarriesNoClinicalApprove() {
        SeededTenant tenant = seedTenant();
        SeededStaff owner = seedStaff(tenant, seedRole(tenant.id(), "Owner", true, OWNER_PRESET), "o16h@a.com", "+919160000061", false);
        String token = loginAndGetAccessToken(owner);
        List<String> permissions = (List<String>) decodeClaims(token).get("permissions");
        assertThat(permissions).doesNotContain("clinical:approve").contains("clinical:view", "staff:edit");
        assertThat(GrantsFlattener.flatten(List.of(OWNER_PRESET))).doesNotContain("clinical:approve");
    }

    private Map<String, Object> decodeClaims(String jwt) {
        try {
            byte[] payload = java.util.Base64.getUrlDecoder().decode(jwt.split("\\.")[1]);
            return objectMapper.readValue(payload, Map.class);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
