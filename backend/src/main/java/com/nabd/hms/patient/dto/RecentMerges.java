package com.nabd.hms.patient.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** ACC-030: merges that can still be undone (not reversed, younger than 30 days). */
public record RecentMerges(List<Item> items) {
    public record Item(UUID mergeId, Instant mergedAt, Instant undoUntil, DuplicateSuggestionPage.Brief duplicate, DuplicateSuggestionPage.Brief survivor) {
    }
}
