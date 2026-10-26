package com.nabd.hms.queue.dto;

import java.util.UUID;

/** expectedHeadId: the entry the caller's screen shows as next; a different head is a 409 STALE_HEAD. */
public record CallNextRequest(UUID expectedHeadId) {
}
