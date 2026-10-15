package com.nabd.hms.reports.dto;

import java.math.BigDecimal;
import java.util.UUID;

/** OWN-025: handledCount = completed visits as doctor + distinct visits billed; discountCount = invoices
 * with a discount raised; overrideCount = prescription safety overrides/acknowledgements (audit_log). */
public record StaffPerformanceResponse(UUID staffId, String staffName, BigDecimal collected, long paymentCount,
                                       long handledCount, long discountCount, long overrideCount) {
}
