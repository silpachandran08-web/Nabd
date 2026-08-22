package com.nabd.hms.clinical.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** doctorId null means this set is a clinic default, visible to every doctor (NB-114). */
public record FavouriteRxSetResponse(UUID id, UUID doctorId, String name, Instant createdAt,
                                      List<FavouriteRxSetItemResponse> items) {
}
