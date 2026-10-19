package com.nabd.hms.billing.dto;

import java.util.List;
import java.util.UUID;

public record CheckoutContextResponse(UUID queueEntryId, String patientName, String doctorName, String visitType,
                                       boolean followUpEligible, String currency, List<ChargeResponse> charges,
                                       List<ChargeResponse> pendingProcedures, List<PrescribedItemResponse> prescribedItems,
                                       UUID departmentId, String queueStatus,
                                       List<SuggestedLine> suggestedLines) {
    /** FIN-003: the server's pick for a consultation charge (tier + price the bill would carry). */
    public record SuggestedLine(String chargeCode, String name, String tier, java.math.BigDecimal unitPrice) {
    }
}
