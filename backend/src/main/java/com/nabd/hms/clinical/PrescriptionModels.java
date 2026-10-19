package com.nabd.hms.clinical;

import java.time.Instant;
import java.util.UUID;

final class PrescriptionModels {

    private PrescriptionModels() {
    }

    /** allergySnapshotJson (DOC-R3): prescriptions.allergy_snapshot as text; null for drafts and pre-V79 signed rows. */
    record PrescriptionRow(UUID id, UUID queueEntryId, UUID patientId, UUID doctorId, String status,
                            Instant createdAt, Instant signedAt, String allergySnapshotJson, UUID signedBy,
                            String signedByRegistrationNo) {
    }

    record PrescriptionItemRow(UUID id, UUID prescriptionId, String drugName, String dosage, String frequency,
                                String duration, String instructions, String allergyOverrideReason, int displayOrder,
                                String overridesJson, String brandName, String sigPattern, String route,
                                java.math.BigDecimal quantity, String quantityUnit) {
    }

    /** DOC-033: tenant-scoped drug name suggestion (see DrugSuggestionResponse). */
    record DrugSuggestionRow(String name, String source, String code, Boolean inStock, Boolean prescriptionOnly) {
    }

    /** For AuditService's actor_name/actor_role snapshot fields (NB-108). */
    record ActorInfo(String name, String role) {
    }

    /** NB-114: a saved prescription set — doctorId null means a clinic default, visible to every doctor. */
    record FavouriteSetRow(UUID id, UUID doctorId, String name, Instant createdAt, boolean shared, Instant updatedAt,
                            Instant lastUsedAt, String ownerName) {
    }

    record FavouriteSetItemRow(UUID id, String drugName, String dosage, String frequency, String duration,
                                String instructions, int displayOrder, String brandName, String sigPattern, String route,
                                java.math.BigDecimal quantity, String quantityUnit) {
    }
}
