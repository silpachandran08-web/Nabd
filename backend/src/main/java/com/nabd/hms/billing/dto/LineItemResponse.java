package com.nabd.hms.billing.dto;

import java.math.BigDecimal;
import java.util.List;

public record LineItemResponse(String chargeCode, String chargeName, String category, int quantity,
                                BigDecimal unitPrice, BigDecimal taxRatePercent, BigDecimal lineTotal,
                                BigDecimal listUnitPrice, String tier, String overrideReason,
                                Long taxableMinor, List<TaxPart> taxes,
                                java.util.UUID id) { // id: the line a FIN-013 refund request names
    /** FIN-008: one stored tax component of the line (CGST, SGST, IGST, VAT); empty for an exempt line or a Bill of Supply. */
    public record TaxPart(String type, BigDecimal ratePercent, long amountMinor) {
    }
}
