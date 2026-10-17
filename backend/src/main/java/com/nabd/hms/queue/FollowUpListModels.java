package com.nabd.hms.queue;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** DOC-006: the doctor's own follow-up appointments (appointments.is_follow_up = true). Read-only. */
public final class FollowUpListModels {

    private FollowUpListModels() {
    }

    public record FollowUpItem(UUID appointmentId, UUID patientId, String patientName, String patientMrn,
                               Instant startTime, String status, String cancelReason) {
    }

    public record PageMeta(String nextCursor, int limit) {
    }

    public record FollowUpPage(List<FollowUpItem> data, PageMeta page) {
    }
}
