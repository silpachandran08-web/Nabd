package com.nabd.hms.clinical;

import com.nabd.hms.clinical.dto.EncounterPage;
import com.nabd.hms.clinical.dto.EncounterResponse;
import com.nabd.hms.clinical.dto.PageMeta;
import com.nabd.hms.common.Cursor;
import com.nabd.hms.common.TenantContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static com.nabd.hms.clinical.TimelineModels.EncounterRow;

@Service
public class TimelineService {

    private final TimelineRepository repo;
    private final TenantContext tenantContext;

    TimelineService(TimelineRepository repo, TenantContext tenantContext) {
        this.repo = repo;
        this.tenantContext = tenantContext;
    }

    /** NB-115: "paginates to five years" — a fixed lookback window, cursor-paginated within it. */
    @Transactional
    public EncounterPage get(UUID tenantId, UUID patientId, int limit, String cursor) {
        tenantContext.set(tenantId);
        Instant since = LocalDate.now(ZoneOffset.UTC).minusYears(5).atStartOfDay(ZoneOffset.UTC).toInstant();
        Cursor after = cursor == null ? null : Cursor.decode(cursor);
        List<EncounterRow> rows = repo.findForPatient(tenantId, patientId, since, limit + 1,
                after == null ? null : after.createdAt(), after == null ? null : after.id());

        boolean hasMore = rows.size() > limit;
        List<EncounterRow> page = hasMore ? rows.subList(0, limit) : rows;
        String nextCursor = hasMore
                ? new Cursor(page.get(page.size() - 1).occurredAt(), page.get(page.size() - 1).queueEntryId()).encode()
                : null;

        List<EncounterResponse> data = page.stream()
                .map(e -> new EncounterResponse(e.queueEntryId(), e.occurredAt(), e.doctorId(), e.diagnosis(),
                        e.assessment(), e.medications()))
                .toList();
        return new EncounterPage(data, new PageMeta(nextCursor, limit));
    }
}
