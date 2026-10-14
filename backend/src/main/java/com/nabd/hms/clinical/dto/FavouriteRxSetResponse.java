package com.nabd.hms.clinical.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** doctorId null means this set is a clinic standard, visible to every doctor (NB-114).
 * DOC-035: scope is relative to the caller ("mine" | "shared" | "standard"); shared tells whether an owned set is visible to
 * the clinic; staleCheckAvailable false means the tenant has no active pharmacy items, so staleItemCount is 0 and unknown,
 * not "all good"; canEdit says whether the caller may PATCH/DELETE it; lastUsedAt is the last time it was applied. */
public record FavouriteRxSetResponse(UUID id, UUID doctorId, String name, Instant createdAt,
                                      List<FavouriteRxSetItemResponse> items, String scope, boolean shared,
                                      String ownerName, Instant updatedAt, Instant lastUsedAt,
                                      boolean staleCheckAvailable, int staleItemCount, boolean canEdit) {
}
