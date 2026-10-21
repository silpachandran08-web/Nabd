package com.nabd.hms.shared.notify;

import java.util.UUID;

/** @param status REQUESTED (event emitted), BLOCKED (no consent: row kept, nothing sent), or the existing row's status
 *               when {@code duplicate} (same dedupeKey asked before). */
public record NotificationOutcome(UUID requestId, String status, boolean duplicate) {
}
