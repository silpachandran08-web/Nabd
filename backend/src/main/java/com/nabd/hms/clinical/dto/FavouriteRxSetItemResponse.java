package com.nabd.hms.clinical.dto;

import java.math.BigDecimal;

/** stale (DOC-035, CLN-022): a coded item (drugId) is stale when its drug master product is retired, or when the clinic
 * dispenses it in-house and has none in stock; an uncoded item is stale when the tenant has an active pharmacy item master
 * and neither the drug nor the brand text matches an active item in it, null when there is no master to compare against
 * (nothing is guessed). staleReason: "retired" | "out_of_stock" | "not_in_master", null when not stale.
 * replacement (CLN-022): for a stale coded item, an active product of the same region with exactly the same ingredients
 * (in-house stock first, then same strength and form); shown to the doctor, never applied automatically. */
public record FavouriteRxSetItemResponse(String drugName, String dosage, String frequency, String duration,
                                          String instructions, String brandName, String sigPattern, String route,
                                          BigDecimal quantity, String quantityUnit, Boolean stale,
                                          String drugId, String genericName, CodeSuggestion codeSuggestion,
                                          String staleReason, CodeSuggestion replacement) {

    /** CLN-021: "code this" for an uncoded item: the best drug master match for its text, never applied automatically. */
    @io.swagger.v3.oas.annotations.media.Schema(name = "RxCodeSuggestion")
    public record CodeSuggestion(String drugId, String display) {
    }
}
