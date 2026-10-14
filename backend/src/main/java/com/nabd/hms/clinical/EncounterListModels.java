package com.nabd.hms.clinical;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** DOC-005: the doctor's own encounter list (read-only). */
public final class EncounterListModels {

    private EncounterListModels() {
    }

    /** One visit. {@code noteState}: none | draft | signed. {@code rxStatus}: none | draft | signed. */
    public record EncounterListItem(UUID queueEntryId, UUID patientId, String patientName, String patientMrn,
                                    LocalDate queueDate, Instant occurredAt, String visitStatus,
                                    String noteState, Instant noteSignedAt, int amendmentCount,
                                    String rxStatus, int rxItemCount,
                                    int documentsDraft, int documentsIssued) {
    }

    public record PageMeta(String nextCursor, int limit) {
    }

    public record EncounterListPage(List<EncounterListItem> data, PageMeta page) {
    }
}
