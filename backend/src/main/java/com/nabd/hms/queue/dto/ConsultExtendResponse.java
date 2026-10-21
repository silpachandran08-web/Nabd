package com.nabd.hms.queue.dto;

import java.util.UUID;

public record ConsultExtendResponse(UUID queueEntryId, int extendedBy) {
}
