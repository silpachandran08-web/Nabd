package com.nabd.hms.reports;

import java.math.BigDecimal;
import java.util.UUID;

final class ReportsModels {

    private ReportsModels() {
    }

    record SourceCount(String source, long visitCount) {
    }

    record StaffCollectionRow(UUID staffId, String staffName, BigDecimal collected, long paymentCount) {
    }

    record NoShowRiskRow(UUID patientId, String patientName, long priorNoShowCount) {
    }

    /** For AuditService's actor_name/actor_role snapshot fields, same pattern as
     * PrescriptionRepository/DentalRepository.findActorInfo. */
    record ActorInfo(String name, String role) {
    }
}
