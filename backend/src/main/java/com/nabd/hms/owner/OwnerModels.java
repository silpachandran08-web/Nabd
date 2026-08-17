package com.nabd.hms.owner;

import java.util.List;
import java.util.UUID;

/** Row shapes for the Owner/Brand/Clinic hierarchy — plain records, matching AuthModels' style. */
final class OwnerModels {
    private OwnerModels() {
    }

    record Owner(UUID id, String name, String email, String pinHash, String status) {
    }

    record ClinicSummary(UUID id, String name, String slug, String region, String status) {
    }

    record BrandWorkspace(UUID id, String name, String status, List<ClinicSummary> clinics) {
    }
}
