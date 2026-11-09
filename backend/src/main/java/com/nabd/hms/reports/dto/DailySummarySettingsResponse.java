package com.nabd.hms.reports.dto;

import java.util.List;
import java.util.UUID;

/**
 * ADM-043: the clinic's daily WhatsApp summary settings. candidates are the active owner-role staff who can be recipients
 * (a recipient without a mobile number on file is listed but never messaged); branches counts the brand's clinics the
 * combined option would cover (1 = this clinic only).
 */
public record DailySummarySettingsResponse(boolean enabled, boolean combined, List<DailySummaryRecipient> recipients, String sendAt,
                                           List<DailySummaryCandidate> candidates, int branches) {
    public record DailySummaryRecipient(UUID staffId) {
    }

    public record DailySummaryCandidate(UUID staffId, String name, boolean hasPhone) {
    }
}
