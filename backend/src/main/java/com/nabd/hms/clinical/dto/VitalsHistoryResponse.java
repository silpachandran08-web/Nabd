package com.nabd.hms.clinical.dto;

import java.util.List;

/** NUR-022: items are the same shape as VitalsResponse (queueEntryId, recordedAt, bpSystolic, ... bloodSugar, painScore). */
public record VitalsHistoryResponse(List<VitalsResponse> items) {
}
