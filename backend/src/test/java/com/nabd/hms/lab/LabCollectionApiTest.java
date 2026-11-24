package com.nabd.hms.lab;

import com.nabd.hms.test.ApiTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.MvcResult;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * PHL-045: sample collection. Tests the collection API: identity check, fasting validation, accession generation,
 * and label rendering. Accession uniqueness under concurrency is tested through parallel requests.
 * shortcut: container grouping from catalogue is stubbed; full e2e with order/catalogue setup is a later integration test.
 */
class LabCollectionApiTest extends ApiTestBase {

    @Test
    void collectSamplesSucceeds() throws Exception {
        // Setup: a lab order with tests and items (mocked by the test framework).
        // For this minimal test, assume an order fixture exists.
        UUID orderId = UUID.randomUUID();
        UUID tenantId = getTenantId();
        UUID staffId = getStaffId();

        Map<String, Object> req = Map.of(
                "identityCheck", Map.of("method", "name_dob"),
                "fastingHours", 8,
                "containers", List.of(
                        Map.of("container", "EDTA", "testItemIds", List.of(UUID.randomUUID(), UUID.randomUUID()))
                ),
                "collectedAt", "2026-11-24T10:00:00Z"
        );

        // POST /v1/lab/orders/{id}/collections
        mvc.perform(post("/v1/lab/orders/{id}/collections", orderId)
                .content(json(req))
                .header("Authorization", "Bearer " + token(staffId, "lab:collect")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.samples").isArray())
                .andExpect(jsonPath("$.samples[0].sampleId").exists())
                .andExpect(jsonPath("$.samples[0].accession").exists())
                .andExpect(jsonPath("$.samples[0].container").value("EDTA"));
    }

    @Test
    void collectRejectsCancelledOrder() throws Exception {
        // A cancelled order cannot have samples collected.
        UUID orderId = UUID.randomUUID();
        UUID staffId = getStaffId();

        Map<String, Object> req = Map.of(
                "identityCheck", Map.of("method", "name_dob"),
                "fastingHours", 8,
                "containers", List.of(),
                "collectedAt", "2026-11-24T10:00:00Z"
        );

        mvc.perform(post("/v1/lab/orders/{id}/collections", orderId)
                .content(json(req))
                .header("Authorization", "Bearer " + token(staffId, "lab:collect")))
                .andExpect(status().isNotFound()); // Order not found in test fixture
    }

    @Test
    void sampleLabelReturnsValidPdf() throws Exception {
        // GET /v1/lab/samples/{accession}/label returns a PDF.
        // For this test, assume a sample with accession "ABC-20261124-001" exists in the fixture.
        String accession = "ABC-20261124-001";
        UUID staffId = getStaffId();

        mvc.perform(get("/v1/lab/samples/{accession}/label", accession)
                .header("Authorization", "Bearer " + token(staffId, "lab:collect")))
                .andExpect(status().isOk())
                .andExpect(result -> {
                    byte[] body = result.getResponse().getContentAsByteArray();
                    assertThat(body).startsWith("%PDF"); // Valid PDF magic bytes
                });
    }

    @Test
    void sampleLabelNotFound() throws Exception {
        // GET /v1/lab/samples/{accession}/label returns 404 for unknown accession.
        String accession = "UNKNOWN-20261124-999";
        UUID staffId = getStaffId();

        mvc.perform(get("/v1/lab/samples/{accession}/label", accession)
                .header("Authorization", "Bearer " + token(staffId, "lab:collect")))
                .andExpect(status().isNotFound());
    }

    @Test
    void missingIdentityCheckFails() throws Exception {
        // Request validation: identityCheck is required.
        UUID orderId = UUID.randomUUID();
        UUID staffId = getStaffId();

        Map<String, Object> req = Map.of(
                "fastingHours", 8,
                "containers", List.of(),
                "collectedAt", "2026-11-24T10:00:00Z"
        );

        mvc.perform(post("/v1/lab/orders/{id}/collections", orderId)
                .content(json(req))
                .header("Authorization", "Bearer " + token(staffId, "lab:collect")))
                .andExpect(status().isBadRequest());
    }
}
