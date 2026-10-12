package com.nabd.hms.clinical.dto;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/** One row of the append-only allergy history. before/after are the register fields at that moment. */
public record AllergyEventResponse(UUID id, UUID allergyId, String event, String substance, Map<String, Object> before,
                                    Map<String, Object> after, String reason, UUID actorId, String actorName,
                                    Instant occurredAt) {
}
