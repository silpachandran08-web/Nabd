package com.nabd.hms.clinical;

import com.nabd.hms.common.ApiException;
import com.nabd.hms.common.Cursor;
import com.nabd.hms.common.TenantContext;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static com.nabd.hms.clinical.EncounterListModels.EncounterListItem;
import static com.nabd.hms.clinical.EncounterListModels.EncounterListPage;
import static com.nabd.hms.clinical.EncounterListModels.PageMeta;

@Service
public class EncounterListService {

    /** none = no note yet, draft/signed = the note's own status, unsigned = none or draft. */
    static final Set<String> STATES = Set.of("none", "draft", "signed", "unsigned");
    static final int MAX_LIMIT = 100;

    private final EncounterListRepository repo;
    private final TenantContext tenantContext;

    EncounterListService(EncounterListRepository repo, TenantContext tenantContext) {
        this.repo = repo;
        this.tenantContext = tenantContext;
    }

    @Transactional
    public EncounterListPage list(ReadScope scope, String state, LocalDate from, LocalDate to,
                                  int limit, String cursor) {
        UUID tenantId = scope.tenantId();
        if (state != null && !STATES.contains(state)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "invalid-state", "Invalid state",
                    "state must be one of none, draft, signed, unsigned.");
        }
        if (from != null && to != null && from.isAfter(to)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "invalid-range", "Invalid date range",
                    "from must not be after to.");
        }
        int size = Math.max(1, Math.min(limit, MAX_LIMIT));
        tenantContext.set(tenantId);
        Cursor after = cursor == null ? null : Cursor.decode(cursor);
        List<EncounterListItem> rows = repo.find(scope, state, from, to, size + 1,
                after == null ? null : after.createdAt(), after == null ? null : after.id());
        boolean hasMore = rows.size() > size;
        List<EncounterListItem> page = hasMore ? rows.subList(0, size) : rows;
        String next = hasMore
                ? new Cursor(page.get(page.size() - 1).occurredAt(), page.get(page.size() - 1).queueEntryId()).encode()
                : null;
        return new EncounterListPage(page, new PageMeta(next, size));
    }
}
