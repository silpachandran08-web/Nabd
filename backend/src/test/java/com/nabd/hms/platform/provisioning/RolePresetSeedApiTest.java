package com.nabd.hms.platform.provisioning;

import com.nabd.hms.common.GrantsFlattener;
import com.nabd.hms.common.ModuleGrant;
import com.nabd.hms.staff.presets.RolePresets;
import com.nabd.hms.support.ApiTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** PLT-016 (T-090): a newly provisioned tenant has exactly the six presets, with grants equal to the RBAC matrix golden file. */
class RolePresetSeedApiTest extends ApiTestBase {

    @Autowired
    private ProvisioningRepository provisioningRepo;
    @Autowired
    private ProvisioningStepRunner stepRunner;
    private UUID lastJobId;

    @Test
    void provisioningSeedsExactlyTheSixPresetsWithGrantsEqualToTheRbacMatrix() throws Exception {
        UUID tenantId = provisionTenant("preset-seed");
        List<Map<String, Object>> roles = inTenantTx(tenantId, () -> jdbc.queryForList(
                "SELECT name, built_in, preset_code, preset_version, grants::text AS grants FROM roles WHERE tenant_id = ?", tenantId));

        assertThat(roles).extracting(r -> r.get("name")).containsExactlyInAnyOrder(
                "Owner", "Owner-doctor", "Doctor", "Nurse", "Receptionist", "Front-desk + Vitals");
        assertThat(roles).extracting(r -> r.get("preset_code")).containsExactlyInAnyOrder(
                "owner", "owner_doctor", "doctor", "nurse", "receptionist", "frontdesk_vitals");
        assertThat(roles).allMatch(r -> Integer.valueOf(RolePresets.VERSION).equals(r.get("preset_version")));
        assertThat(roles).filteredOn(r -> Boolean.TRUE.equals(r.get("built_in"))).extracting(r -> r.get("name")).containsExactly("Owner");

        Map<String, Map<String, boolean[]>> golden = expectedFromGolden();
        for (Map<String, Object> role : roles) {
            String code = (String) role.get("preset_code");
            List<ModuleGrant> grants = readGrants((String) role.get("grants"));
            Map<String, boolean[]> expected = golden.get(code);
            assertThat(grants).extracting(ModuleGrant::module).as(code).containsExactlyInAnyOrderElementsOf(expected.keySet());
            for (ModuleGrant g : grants) {
                assertThat(flags(g)).as(code + "/" + g.module()).containsExactly(expected.get(g.module()));
            }
        }
    }

    /** MF-52: clinical:approve belongs to Owner-doctor, never to the plain Owner preset. */
    @Test
    void ownerPresetHasNoClinicalApproveButOwnerDoctorDoes() {
        List<String> owner = GrantsFlattener.flatten(RolePresets.byCode("owner").orElseThrow().grants());
        List<String> ownerDoctor = GrantsFlattener.flatten(RolePresets.byCode("owner_doctor").orElseThrow().grants());
        assertThat(owner).doesNotContain("clinical:approve", "clinical:create", "clinical:edit").contains("clinical:view");
        assertThat(ownerDoctor).contains("clinical:approve", "clinical:create", "clinical:edit", "staff:edit", "billing:approve");
        assertThat(GrantsFlattener.flatten(RolePresets.byCode("doctor").orElseThrow().grants())).doesNotContain("clinical:approve");
    }

    @Test
    void reRunningSeedMastersAfterAFailedLaterStepDoesNotDuplicatePresets() {
        UUID tenantId = provisionTenant("preset-rerun");
        ProvisioningModels.Job job = provisioningRepo.findJob(lastJobId).orElseThrow();
        stepRunner.run(job, "seed_masters");
        Integer count = inTenantTx(tenantId, () -> jdbc.queryForObject(
                "SELECT count(*) FROM roles WHERE tenant_id = ? AND preset_code IS NOT NULL", Integer.class, tenantId));
        assertThat(count).isEqualTo(6);
    }

    /** Golden file (matrix + extension rows), then MF-52 on the Owner and Owner-doctor = Owner OR Doctor plus clinical:approve. */
    private Map<String, Map<String, boolean[]>> expectedFromGolden() throws Exception {
        Map<String, Map<String, boolean[]>> out = new HashMap<>();
        for (String line : Files.readAllLines(Path.of("src/test/resources/rbac-matrix-golden.csv"))) {
            if (line.startsWith("#") || line.startsWith("preset,") || line.isBlank()) {
                continue;
            }
            String[] c = line.split(",");
            boolean[] f = new boolean[7];
            for (int i = 0; i < 7; i++) {
                f[i] = c[2 + i].equals("Y") || c[2 + i].equals("P");
            }
            out.computeIfAbsent(c[0], k -> new HashMap<>()).put(c[1], f);
        }
        assertThat(out.values().stream().mapToInt(Map::size).sum()).isEqualTo(65); // 5 roles x (10 matrix + 3 extension)
        out.get("owner").get("clinical")[4] = false; // MF-52
        Map<String, boolean[]> ownerDoctor = new HashMap<>();
        out.get("owner").forEach((module, o) -> {
            boolean[] d = out.get("doctor").get(module);
            boolean[] f = new boolean[7];
            for (int i = 0; i < 7; i++) {
                f[i] = o[i] || d[i];
            }
            if (module.equals("clinical")) {
                f[4] = true;
            }
            ownerDoctor.put(module, f);
        });
        out.put("owner_doctor", ownerDoctor);
        return out;
    }

    private static boolean[] flags(ModuleGrant g) {
        return new boolean[]{g.view(), g.create(), g.edit(), g.delete(), g.approve(), g.refundDiscount(), g.export()};
    }

    private List<ModuleGrant> readGrants(String json) {
        try {
            return objectMapper.readValue(json, objectMapper.getTypeFactory().constructCollectionType(List.class, ModuleGrant.class));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private UUID provisionTenant(String slug) {
        String token = platformLoginAndGetAccessToken(seedOperator("super-" + UUID.randomUUID() + "@nabd.health", "super_admin", false));
        ResponseEntity<Map> created = exchange("/v1/platform/provisioning-jobs", HttpMethod.POST, authedJsonBody(token, Map.of(
                "tenantSlug", slug, "tenantName", "Clinic " + slug, "region", "IN", "ownerEmail", "owner-" + slug + "@nabd.health",
                "ownerName", "Test Owner", "ownerMobile", "+9198765" + Math.abs(slug.hashCode() % 100000),
                "brandName", "Brand " + slug, "path", "self_serve")), Map.class);
        String jobId = (String) created.getBody().get("id");
        lastJobId = UUID.fromString(jobId);
        Map<String, Object> body = null;
        for (int i = 0; i < 6; i++) {
            body = exchange("/v1/platform/provisioning-jobs/" + jobId + "/advance", HttpMethod.POST, authed(token), Map.class).getBody();
        }
        assertThat(body.get("status")).isEqualTo("done");
        return UUID.fromString((String) body.get("createdTenantId"));
    }
}
