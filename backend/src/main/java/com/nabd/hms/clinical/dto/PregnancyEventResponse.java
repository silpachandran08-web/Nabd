package com.nabd.hms.clinical.dto;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/** One append-only history row: the status before (null for the first statement) and after, with who and when. */
public record PregnancyEventResponse(UUID id, Map<String, Object> before, Map<String, Object> after, UUID actorId,
                                      String actorName, Instant occurredAt) {
}
