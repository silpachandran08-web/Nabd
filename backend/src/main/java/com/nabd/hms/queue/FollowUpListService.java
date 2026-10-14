package com.nabd.hms.queue;

import com.nabd.hms.common.ApiException;
import com.nabd.hms.common.ClinicClock;
import com.nabd.hms.common.Cursor;
import com.nabd.hms.common.TenantContext;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static com.nabd.hms.queue.FollowUpListModels.FollowUpItem;
import static com.nabd.hms.queue.FollowUpListModels.FollowUpPage;
import static com.nabd.hms.queue.FollowUpListModels.PageMeta;

@Service
public class FollowUpListService {

    static final Set<String> STATUSES = Set.of("scheduled", "cancelled", "completed", "no_show");
    static final int MAX_LIMIT = 100;

    private final FollowUpListRepository repo;
    private final TenantContext tenantContext;
    private final ClinicClock clock;

    FollowUpListService(FollowUpListRepository repo, TenantContext tenantContext, ClinicClock clock) {
        this.repo = repo;
        this.tenantContext = tenantContext;
        this.clock = clock;
    }

    @Transactional
    public FollowUpPage list(UUID tenantId, UUID doctorId, String status, LocalDate from, LocalDate to,
                             int limit, String cursor) {
        if (status != null && !STATUSES.contains(status)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "invalid-status", "Invalid status",
                    "status must be one of scheduled, cancelled, completed, no_show.");
        }
        if (from != null && to != null && from.isAfter(to)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "invalid-range", "Invalid date range",
                    "from must not be after to.");
        }
        int size = Math.max(1, Math.min(limit, MAX_LIMIT));
        tenantContext.set(tenantId);
        var zone = clock.zone(tenantId);
        Instant fromInstant = from == null ? null : from.atStartOfDay(zone).toInstant();
        Instant toExclusive = to == null ? null : to.plusDays(1).atStartOfDay(zone).toInstant();
        Cursor after = cursor == null ? null : Cursor.decode(cursor);
        List<FollowUpItem> rows = repo.find(tenantId, doctorId, status, fromInstant, toExclusive, size + 1,
                after == null ? null : after.createdAt(), after == null ? null : after.id());
        boolean hasMore = rows.size() > size;
        List<FollowUpItem> page = hasMore ? rows.subList(0, size) : rows;
        String next = hasMore
                ? new Cursor(page.get(page.size() - 1).startTime(), page.get(page.size() - 1).appointmentId()).encode()
                : null;
        return new FollowUpPage(page, new PageMeta(next, size));
    }
}
