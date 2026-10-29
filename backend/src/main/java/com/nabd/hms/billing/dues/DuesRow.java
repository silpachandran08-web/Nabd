package com.nabd.hms.billing.dues;

import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** FIN-014: one patient who owes money. invoiceNumbers lists every open invoice behind totalDue. */
@Schema(name = "DuesRow")
public record DuesRow(UUID patientId, String patientName, BigDecimal totalDue, long oldestDays, String bucket,
                      List<String> invoiceNumbers, Instant lastRemindedAt, Instant oldestAt) {
}
