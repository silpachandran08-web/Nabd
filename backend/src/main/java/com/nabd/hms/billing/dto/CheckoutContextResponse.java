package com.nabd.hms.billing.dto;

import java.util.List;
import java.util.UUID;

public record CheckoutContextResponse(UUID queueEntryId, String patientName, String doctorName, String visitType,
                                       boolean followUpEligible, String currency, List<ChargeResponse> charges,
                                       List<ChargeResponse> pendingProcedures, List<PrescribedItemResponse> prescribedItems) {
}
