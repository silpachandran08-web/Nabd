package com.nabd.hms.clinical.dto;

import java.math.BigDecimal;

/** stale (DOC-035): true when the tenant has an active pharmacy item master and neither the drug nor the brand text matches
 * an active item in it; null when there is no master to compare against (nothing is guessed). */
public record FavouriteRxSetItemResponse(String drugName, String dosage, String frequency, String duration,
                                          String instructions, String brandName, String sigPattern, String route,
                                          BigDecimal quantity, String quantityUnit, Boolean stale) {
}
