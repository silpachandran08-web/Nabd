package com.nabd.hms.queue.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** ACC-024: the ladder sends {@code minutes} (15/30/45/60 or 1..240); {@code delayMinutes} is the original name and still works. */
public record DelayAnnounceRequest(@com.fasterxml.jackson.annotation.JsonAlias("minutes") @NotNull @Min(1) @Max(value = 240, message = "must be at most 240") Integer delayMinutes,
                                   @Size(max = 200) String reason, @Size(max = 40) String reasonCode, Boolean broadcast) {

    public DelayAnnounceRequest(Integer delayMinutes, String reason) {
        this(delayMinutes, reason, null, null);
    }
}
