package com.nabd.hms.billing.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;
import java.util.UUID;

/** PHL-018: the in-house Rx lines to pre-tick at checkout (external items are not listed). */
@Schema(name = "CheckoutPrefillResponse")
public record CheckoutPrefillResponse(List<Line> lines) {

    /** @param qty units still to dispense on this prescription line; @param onHand shelf stock now (below qty = shortfall dialog) */
    @Schema(name = "CheckoutPrefillLine")
    public record Line(String chargeCode, String chargeName, int qty, UUID prescriptionItemId, int onHand) {
    }
}
