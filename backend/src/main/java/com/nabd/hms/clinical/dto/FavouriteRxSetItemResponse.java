package com.nabd.hms.clinical.dto;

import java.math.BigDecimal;

/** stale (DOC-035): true when the tenant has an active pharmacy item master and neither the drug nor the brand text matches
 * an active item in it; null when there is no master to compare against (nothing is guessed). */
public record FavouriteRxSetItemResponse(String drugName, String dosage, String frequency, String duration,
                                          String instructions, String brandName, String sigPattern, String route,
                                          BigDecimal quantity, String quantityUnit, Boolean stale,
                                          String drugId, String genericName, CodeSuggestion codeSuggestion) {

    /** CLN-021: "code this" for an uncoded item: the best drug master match for its text, never applied automatically. */
    @io.swagger.v3.oas.annotations.media.Schema(name = "RxCodeSuggestion")
    public record CodeSuggestion(String drugId, String display) {
    }
}
