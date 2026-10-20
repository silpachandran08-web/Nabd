package com.nabd.hms.billing.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** FIN-005: every close and reopen in a date range, newest first. Cash fields are null on a reopen; note is the
 * close note or the reopen reason. nextCursor is null on the last page. */
public record DayCloseHistoryResponse(List<Item> items, String nextCursor) {
    public record Item(UUID id, LocalDate date, String action, BigDecimal expectedCash, BigDecimal countedCash,
                       BigDecimal variance, String note, String actorName, Instant at) {
    }
}
