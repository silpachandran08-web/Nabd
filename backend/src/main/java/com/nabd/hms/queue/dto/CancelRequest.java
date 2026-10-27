package com.nabd.hms.queue.dto;


/** ACC-007: reasonCode from the coded list (GET /v1/reason-codes?kind=cancel) is required (400 otherwise); note only with 'other', at most 200 characters. reason is the pre-ACC-007 free text: deprecated, used as the note when note is absent. */
public record CancelRequest(String reasonCode, String note, @Deprecated String reason) {
}
