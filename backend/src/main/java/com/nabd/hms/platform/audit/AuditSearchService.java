package com.nabd.hms.platform.audit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nabd.hms.common.ApiException;
import com.nabd.hms.platform.audit.dto.AuditEntryResponse;
import com.nabd.hms.platform.audit.dto.AuditLogPage;
import com.nabd.hms.platform.audit.dto.PageMeta;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static com.nabd.hms.platform.audit.AuditSearchModels.AuditEntry;

@Service
public class AuditSearchService {

    private final AuditSearchRepository repo;
    private final ObjectMapper objectMapper;

    AuditSearchService(AuditSearchRepository repo, ObjectMapper objectMapper) {
        this.repo = repo;
        this.objectMapper = objectMapper;
    }

    public AuditLogPage search(UUID tenantId, String action, String entityType, Instant createdAfter,
                                Instant createdBefore, int limit, String cursor) {
        Long afterId = cursor == null ? null : decodeCursor(cursor);
        List<AuditEntry> rows = repo.search(tenantId, action, entityType, createdAfter, createdBefore,
                afterId, limit + 1);

        boolean hasMore = rows.size() > limit;
        List<AuditEntry> page = hasMore ? rows.subList(0, limit) : rows;
        // audit_log.id is already a bigint IDENTITY column — a total, gap-free order on its own,
        // unlike fleet/tickets' (createdAt, UUID) pairs. No need for that composite cursor shape here.
        String nextCursor = hasMore ? String.valueOf(page.get(page.size() - 1).id()) : null;

        return new AuditLogPage(page.stream().map(this::toResponse).toList(), new PageMeta(nextCursor, limit));
    }

    private long decodeCursor(String cursor) {
        try {
            return Long.parseLong(cursor);
        } catch (NumberFormatException e) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "invalid-cursor", "Invalid cursor",
                    "The pagination cursor is malformed.");
        }
    }

    private AuditEntryResponse toResponse(AuditEntry e) {
        return new AuditEntryResponse(e.id(), e.tenantId(), e.tenantName(), e.tenantSlug(), e.actorType(),
                e.actorId(), e.actorName(), e.actorRole(), e.ipAddress(), e.action(), e.entityType(), e.entityId(),
                parseJson(e.before()), parseJson(e.after()), e.createdAt());
    }

    private JsonNode parseJson(String raw) {
        if (raw == null) {
            return null;
        }
        try {
            return objectMapper.readTree(raw);
        } catch (Exception ex) {
            throw new IllegalStateException("audit_log contains invalid JSON", ex);
        }
    }
}
