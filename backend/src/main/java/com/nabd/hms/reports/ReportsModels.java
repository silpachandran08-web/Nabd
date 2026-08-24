package com.nabd.hms.reports;

import java.math.BigDecimal;
import java.time.Instant;
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

    /** NB-233: a checked-out, billed=true procedure whose charge never landed on that visit's invoice. */
    record LeakageRow(UUID procedureOrderId, UUID queueEntryId, String patientName, String chargeCode,
                       String chargeName, BigDecimal amount, Instant completedAt) {
    }

    /** NB-236: per-doctor delay stats; sameDayRepeatDays counts distinct days with 2+ announcements. */
    record DoctorPunctualityRow(UUID doctorId, String doctorName, long delayCount, double avgDelayMinutes,
                                 long sameDayRepeatDays) {
    }

    /** For AuditService's actor_name/actor_role snapshot fields, same pattern as
     * PrescriptionRepository/DentalRepository.findActorInfo. */
    record ActorInfo(String name, String role) {
    }
}
