package com.nabd.hms.billing.dto;

import java.math.BigDecimal;
import java.util.UUID;

/** PHL-001: prescriptionItemId + remainingQty let checkout link an is_rx line to this signed line (null quantity = remaining 0). */
public record PrescribedItemResponse(UUID prescriptionItemId, String drugName, String dosage, String frequency, String duration,
                                     String instructions, BigDecimal quantity, BigDecimal remainingQty) {
}
