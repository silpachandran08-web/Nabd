package com.nabd.hms.organization.setup;

import com.nabd.hms.support.ApiTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** ADM-007: nine resumable steps with server-side drafts (optimistic version), skip/reopen, DPA rules, per-clinic isolation. */
class Adm007SetupWizardApiTest extends ApiTestBase {

    private static final List<String> STEPS = List.of("profile", "preset", "doctors", "prices", "whatsapp", "tax", "dpa", "import", "go_live");

    private String owner(SeededTenant t, String email, String phone) {
        return loginAndGetAccessToken(seedStaff(t, seedFullAccessRole(t.id()), email, phone, false));
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> checklist(String token) {
        return exchange("/v1/setup/checklist", HttpMethod.GET, authed(token), List.class).getBody();
    }

    private Map<String, Object> item(String token, String step) {
        return checklist(token).stream().filter(i -> step.equals(i.get("step"))).findFirst().orElseThrow();
    }

    private ResponseEntity<Void> act(String token, String step, String action) {
        return exchange("/v1/setup/checklist/" + step + "/" + action, HttpMethod.POST, authedJsonBody(token, Map.of()), Void.class);
    }

    private ResponseEntity<Map> draft(String token, String step, Object body) {
        return exchange("/v1/setup/checklist/" + step + "/draft", HttpMethod.PUT, authedJsonBody(token, body), Map.class);
    }

    @Test
    void newTenantListsNineStepsInOrderFirstOneCurrent() {
        SeededTenant t = seedTenant();
        List<Map<String, Object>> items = checklist(owner(t, "w1@a.com", "+919820000001"));
        assertThat(items).extracting(i -> i.get("step")).containsExactlyElementsOf(STEPS);
        assertThat(items).extracting(i -> i.get("status")).containsExactly("current", "pending", "pending", "pending", "pending", "pending", "pending", "pending", "pending");
        assertThat(items.get(0).get("draft")).isEqualTo(Map.of());
        assertThat(items.get(0).get("version")).isEqualTo(0);
    }

    @Test
    void progressIsServerSideSoAnotherDeviceSeesFourDoneAndTheFifthCurrent() {
        SeededTenant t = seedTenant();
        UUID role = seedFullAccessRole(t.id());
        SeededStaff s = seedStaff(t, role, "w2@a.com", "+919820000002", false);
        String laptop = loginAndGetAccessToken(s);
        for (String step : List.of("profile", "preset", "doctors", "prices")) {
            assertThat(act(laptop, step, "complete").getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        }
        String phone = loginAndGetAccessToken(s); // a second session
        List<Map<String, Object>> items = checklist(phone);
        assertThat(items.subList(0, 4)).extracting(i -> i.get("status")).containsOnly("done");
        assertThat(items.get(4)).containsEntry("step", "whatsapp").containsEntry("status", "current");
        assertThat(items.subList(5, 9)).extracting(i -> i.get("status")).containsOnly("pending");
    }

    @Test
    void skippedStepKeepsItsDraftCanBeReopenedAndCompleted() {
        SeededTenant t = seedTenant();
        String token = owner(t, "w3@a.com", "+919820000003");
        assertThat(draft(token, "tax", Map.of("draft", Map.of("taxIdType", "GSTIN", "taxId", "TEMP-1"), "version", 0)).getBody()).containsEntry("version", 1);
        assertThat(act(token, "tax", "skip").getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        Map<String, Object> skipped = item(token, "tax");
        assertThat(skipped).containsEntry("status", "skipped").containsEntry("version", 1);
        assertThat(skipped.get("draft")).isEqualTo(Map.of("taxIdType", "GSTIN", "taxId", "TEMP-1"));
        // reopened later: still there, can be edited and completed
        assertThat(draft(token, "tax", Map.of("draft", Map.of("taxIdType", "GSTIN", "taxId", "TEMP-2"), "version", 1)).getBody()).containsEntry("version", 2);
        assertThat(act(token, "tax", "complete").getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(item(token, "tax")).containsEntry("status", "done");
        assertThat(((Map<?, ?>) item(token, "tax").get("draft")).get("taxId")).isEqualTo("TEMP-2");
        // a skipped step is not the current one: the first pending step is
        assertThat(item(token, "profile")).containsEntry("status", "current");
    }

    @Test
    void dpaCannotBeSkippedAndNeedsAnAcceptanceOnFileToComplete() {
        SeededTenant t = seedTenant();
        String token = owner(t, "w4@a.com", "+919820000004");
        ResponseEntity<Map> skip = exchange("/v1/setup/checklist/dpa/skip", HttpMethod.POST, authedJsonBody(token, Map.of()), Map.class);
        assertThat(skip.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(skip.getBody().get("title")).isEqualTo("STEP_INVALID");
        ResponseEntity<Map> early = exchange("/v1/setup/checklist/dpa/complete", HttpMethod.POST, authedJsonBody(token, Map.of()), Map.class);
        assertThat(early.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(item(token, "dpa")).containsEntry("status", "pending");
        // every other step can be skipped
        for (String step : List.of("profile", "preset", "doctors", "prices", "whatsapp", "tax", "import", "go_live")) {
            assertThat(act(token, step, "skip").getStatusCode()).as(step).isEqualTo(HttpStatus.NO_CONTENT);
        }
        assertThat(item(token, "dpa")).containsEntry("status", "current");
    }

    @Test
    void staleDraftVersionIsRefusedWithVersionConflictAndKeepsTheNewerDraft() {
        SeededTenant t = seedTenant();
        String token = owner(t, "w5@a.com", "+919820000005");
        assertThat(draft(token, "doctors", Map.of("draft", Map.of("n", 1), "version", 0)).getStatusCode()).isEqualTo(HttpStatus.OK);
        ResponseEntity<Map> stale = draft(token, "doctors", Map.of("draft", Map.of("n", 2), "version", 0));
        assertThat(stale.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(stale.getBody().get("title")).isEqualTo("VERSION_CONFLICT");
        assertThat(item(token, "doctors").get("draft")).isEqualTo(Map.of("n", 1));
    }

    @Test
    void draftValidationAndPermissions() {
        SeededTenant t = seedTenant();
        String token = owner(t, "w6@a.com", "+919820000006");
        assertThat(draft(token, "nope", Map.of("draft", Map.of(), "version", 0)).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(draft(token, "welcome", Map.of("draft", Map.of(), "version", 0)).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST); // an old step name
        assertThat(draft(token, "prices", Map.of("draft", List.of(1), "version", 0)).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(draft(token, "prices", Map.of("draft", Map.of("x", "a".repeat(17_000)), "version", 0)).getStatusCode()).isEqualTo(HttpStatus.CONTENT_TOO_LARGE);
        assertThat(draft(token, "prices", Map.of("draft", Map.of(), "version", -1)).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        // boundary: exactly at the limit is accepted
        assertThat(draft(token, "prices", Map.of("draft", Map.of("x", "a".repeat(16_000)), "version", 0)).getStatusCode()).isEqualTo(HttpStatus.OK);

        UUID viewOnly = seedRole(t.id(), "Viewer", false, new com.nabd.hms.common.ModuleGrant("setup", true, false, false, false, false, false, false));
        String viewer = loginAndGetAccessToken(seedStaff(t, viewOnly, "v6@a.com", "+919820000007", false));
        assertThat(checklist(viewer)).hasSize(9);
        assertThat(draft(viewer, "prices", Map.of("draft", Map.of(), "version", 0)).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void draftsAndProgressAreIsolatedPerClinicEvenInOneBrand() {
        SeededOwner o = seedOwner("w7-owner@a.com");
        SeededBrand brand = seedBrand(o, "Brand");
        SeededTenant a = seedClinicInBrand(brand);
        SeededTenant b = seedClinicInBrand(brand);
        String tokenA = owner(a, "w7a@a.com", "+919820000008");
        String tokenB = owner(b, "w7b@a.com", "+919820000009");
        draft(tokenA, "profile", Map.of("draft", Map.of("name", "Clinic A only"), "version", 0));
        act(tokenA, "profile", "complete");
        assertThat(item(tokenB, "profile")).containsEntry("status", "current").containsEntry("version", 0);
        assertThat(item(tokenB, "profile").get("draft")).isEqualTo(Map.of());
        // the table's own policies: forced RLS and no sibling widening
        Integer widened = jdbc.queryForObject("SELECT count(*) FROM pg_policies WHERE tablename = 'clinic_setup_progress' AND (qual ILIKE '%visible_tenant_ids%' OR qual ILIKE '%accessible_tenant_ids%')", Integer.class);
        assertThat(widened).isZero();
        assertThat(inTenantTx(b.id(), () -> jdbc.queryForObject("SELECT count(*) FROM clinic_setup_progress WHERE tenant_id = ?", Integer.class, a.id()))).isZero();
    }

    @Test
    void completingGoLiveStillMarksSetupCompleted() {
        SeededTenant t = seedTenant();
        String token = owner(t, "w8@a.com", "+919820000010");
        assertThat(act(token, "go_live", "complete").getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(exchange("/v1/setup/profile", HttpMethod.GET, authed(token), Map.class).getBody().get("setupCompletedAt")).isNotNull();
    }
}
