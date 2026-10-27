package com.nabd.hms.clinical.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * CLN-008: everything the solo doctor's phone needs before the consult opens, in one read.
 * safety.allergyState is "recorded", "none_documented" or "not_assessed" (never read "not_assessed" as "no allergies");
 * safety.pregnancy.status is "not_documented" when nobody recorded it. lastVisits holds at most 3 completed visits,
 * activeMeds the medicines on the patient's last 3 signed prescriptions (de-duplicated, at most 20 lines).
 */
public record VisitSummaryResponse(VisitPatient patient, VisitSafety safety, List<VisitCondition> conditions,
                                    List<EncounterResponse> lastVisits, List<VisitMedicine> activeMeds,
                                    VitalsResponse vitals, VisitIntake intake, int attachmentsCount) {

    @Schema(name = "VisitSummaryPatient")
    public record VisitPatient(UUID id, UUID queueEntryId, String name, String ageText, String sex, String mrn, String queueStatus) {
    }

    @Schema(name = "VisitSummarySafety")
    public record VisitSafety(String allergyState, List<VisitAllergy> allergies, VisitPregnancy pregnancy) {
    }

    @Schema(name = "VisitSummaryAllergy")
    public record VisitAllergy(String substance, String severity, String reaction) {
    }

    @Schema(name = "VisitSummaryPregnancy")
    public record VisitPregnancy(String status, LocalDate expectedDeliveryDate) {
    }

    @Schema(name = "VisitSummaryCondition")
    public record VisitCondition(String condition, LocalDate reviewDueDate) {
    }

    @Schema(name = "VisitSummaryMedicine")
    public record VisitMedicine(String drugName, String dosage, String frequency, String duration) {
    }

    @Schema(name = "VisitSummaryIntake")
    public record VisitIntake(String presentingComplaint, String paymentMode) {
    }
}
