package com.nabd.hms.clinical.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * A completed visit, or (ADM-013, only with include=imported) a read-only past visit from the clinic's old software:
 * kind 'imported_history', documentId instead of queueEntryId, the old doctor's name instead of doctorId, bill = the old
 * bill number and amount as text. kind is null for a visit.
 */
public record EncounterResponse(UUID queueEntryId, Instant occurredAt, UUID doctorId, String diagnosis,
                                 String assessment, String medications, String kind, UUID documentId, String doctorName, String bill) {
}
