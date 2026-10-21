package com.nabd.hms.clinical;

import com.nabd.hms.support.ApiTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** CLN-018: attribution endpoint and the "no bulk KB content" surface. */
class DrugKnowledgeBaseApiTest extends ApiTestBase {

    @Test
    void attributionNeedsBothNameAndValidMonthElseNothingIsConfigured() {
        assertThat(new DrugKnowledgeBaseController("Example KB", "2026-10").get())
                .isEqualTo(new DrugKnowledgeBaseController.Source(true, "Example KB", "2026-10"));
        assertThat(new DrugKnowledgeBaseController(" Example KB ", " 2026-01 ").get().source()).isEqualTo("Example KB");
        for (String[] bad : new String[][]{{"", "2026-10"}, {"  ", "2026-10"}, {"X", ""}, {"X", "2026-13"}, {"X", "2026-00"}, {"X", "Oct 2026"}}) {
            assertThat(new DrugKnowledgeBaseController(bad[0], bad[1]).get().configured()).as(String.join("/", bad)).isFalse();
        }
    }

    @Test
    void endpointIsAuthenticatedAndNotConfiguredByDefaultAndCarriesNoKbContent() {
        SeededTenant tenant = seedTenant();
        UUID roleId = seedFullAccessRole(tenant.id());
        String token = loginAndGetAccessToken(seedStaff(tenant, roleId, "kb18@a.com", "+919800018001", false));

        ResponseEntity<Map> anon = exchange("/v1/clinical/drug-knowledge-base", HttpMethod.GET, null, Map.class);
        assertThat(anon.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);

        ResponseEntity<Map> ok = exchange("/v1/clinical/drug-knowledge-base", HttpMethod.GET, authed(token), Map.class);
        assertThat(ok.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(ok.getBody()).containsEntry("configured", false);
        assertThat(ok.getBody().keySet()).isSubsetOf("configured", "source", "dataMonth");
    }

    @Test
    void typeaheadIsTheOnlyDrugListAndIsCappedAtTwentyWhateverTheClientAsks() {
        SeededTenant tenant = seedTenant();
        UUID roleId = seedFullAccessRole(tenant.id());
        String token = loginAndGetAccessToken(seedStaff(tenant, roleId, "kb18b@a.com", "+919800018002", false));
        exchange("/v1/pharmacy/settings", HttpMethod.PATCH, authedJsonBody(token, Map.of("mode", "hybrid", "reason", "Test setup")), Map.class);
        for (int i = 0; i < 25; i++) {
            exchange("/v1/pharmacy/items", HttpMethod.POST, authedJsonBody(token, Map.of(
                    "name", "Kbtest drug " + (char) ('a' + i), "isRx", true, "price", 1.5, "stockQty", 1)), Map.class);
        }
        for (String limit : new String[]{"1000", "21", "20"}) {
            ResponseEntity<List> hits = http.exchange(java.net.URI.create(url("/v1/clinical/drugs/search?q=kbtest&limit=" + limit)),
                    HttpMethod.GET, authed(token), List.class);
            assertThat(hits.getBody()).as("limit=" + limit).hasSize(20);
        }
        ResponseEntity<List> one = http.exchange(java.net.URI.create(url("/v1/clinical/drugs/search?q=k")), HttpMethod.GET, authed(token), List.class);
        assertThat(one.getBody()).as("one character returns nothing").isEmpty();
    }
}
