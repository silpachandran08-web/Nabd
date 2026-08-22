package com.nabd.hms.clinical.dto;

import java.time.Instant;
import java.util.UUID;

public record NoteAmendmentResponse(UUID id, UUID amendedBy, String reason, String subjective, String objective,
                                     String assessment, String plan, String diagnosis, Instant createdAt) {
}
