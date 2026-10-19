package com.nabd.hms.queue.dto;

import jakarta.validation.constraints.NotBlank;

/** expectedStatus is the status the caller's screen shows; null only for server-internal callers that just read the row. */
public record QueueStatusUpdateRequest(@NotBlank String status, String expectedStatus) {
    public QueueStatusUpdateRequest(String status) {
        this(status, null);
    }
}
