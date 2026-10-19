package com.nabd.hms.platform.audit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nabd.hms.common.ApiException;
import com.nabd.hms.common.AuditService;
import com.nabd.hms.platform.audit.dto.AuditEntryResponse;
import com.nabd.hms.platform.audit.dto.AuditLogPage;
import com.nabd.hms.platform.audit.dto.PageMeta;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static com.nabd.hms.platform.audit.AuditSearchModels.AuditEntry;
import static com.nabd.hms.platform.audit.AuditSearchModels.AuditFilter;
import static com.nabd.hms.platform.audit.AuditSearchModels.OperatorInfo;

@Service
public class AuditSearchService {

    private static final Logger log = LoggerFactory.getLogger(AuditSearchService.class);

    /** PLT-028: hard upper bound on one CSV export. Beyond it the export is refused (narrow the filters)
     * rather than silently truncated — a partial compliance export that looks complete is worse than none. */
    static final int EXPORT_MAX_ROWS = 10_000;

    private static final Set<String> ACTOR_TYPES = Set.of("staff", "operator", "system");

    private static final String CSV_HEADER = "id,createdAt,tenantId,tenantName,tenantSlug,actorType,actorId,"
            + "actorName,actorRole,ipAddress,action,entityType,entityId,before,after\n";

    private final AuditSearchRepository repo;
    private final ObjectMapper objectMapper;
    private final AuditService auditService;

    AuditSearchService(AuditSearchRepository repo, ObjectMapper objectMapper, AuditService auditService) {
        this.repo = repo;
        this.objectMapper = objectMapper;
        this.auditService = auditService;
    }

    /** PLT-056: every search is clamped (default 50, 1..100), written to master.operator_audit_log and, with a
     * tenant filter, to that tenant's audit_log as operator.audit_search. before/after only for compliance_dpo
     * with includeValues and a reason; everyone else gets them as null. */
    @Transactional
    public AuditLogPage search(AuditFilter filter, Integer rawLimit, String cursor, ValueAccess values,
                               UUID operatorId, String ipAddress) {
        int limit = clampLimit(rawLimit);
        AuditFilter f = normalize(filter);
        OperatorInfo operator = operator(operatorId);
        boolean withValues = values.check(operator);
        Long afterId = cursor == null ? null : decodeCursor(cursor);
        List<AuditEntry> rows = repo.search(f, afterId, limit + 1);

        boolean hasMore = rows.size() > limit;
        List<AuditEntry> page = hasMore ? rows.subList(0, limit) : rows;
        // audit_log.id is already a bigint IDENTITY column — a total, gap-free order on its own,
        // unlike fleet/tickets' (createdAt, UUID) pairs. No need for that composite cursor shape here.
        String nextCursor = hasMore ? String.valueOf(page.get(page.size() - 1).id()) : null;

        recordOperatorAccess("audit_log.search", f, page.size(), values, withValues, operatorId, operator, ipAddress);
        return new AuditLogPage(page.stream().map(e -> toResponse(e, withValues)).toList(), new PageMeta(nextCursor, limit));
    }

    /** includeValues + reason as sent; check() enforces the DPO-only, reason-required rule. */
    public record ValueAccess(boolean include, String reason) {
        boolean check(OperatorInfo operator) {
            if (!include) return false;
            if (!"compliance_dpo".equals(operator.role())) {
                throw new ApiException(HttpStatus.FORBIDDEN, "values-not-permitted", "Values not permitted",
                        "Only the compliance DPO may see before/after values.", Map.of("code", "VALUES_NOT_PERMITTED"));
            }
            if (reason == null || reason.isBlank() || reason.length() > 500) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "reason-required", "Reason required",
                        "State a reason (1-500 characters) to see before/after values.", Map.of("code", "REASON_REQUIRED"));
            }
            return true;
        }
    }

    static final int SEARCH_DEFAULT_LIMIT = 50;

    static int clampLimit(Integer raw) {
        if (raw == null) return SEARCH_DEFAULT_LIMIT;
        if (raw < 1 || raw > com.nabd.hms.common.PageRequest.MAX) {
            // PLT-027 validation shape (field-named), keeping PLT-056's code
            String msg = "must be between 1 and " + com.nabd.hms.common.PageRequest.MAX;
            throw new ApiException(HttpStatus.BAD_REQUEST, "validation", "Validation failed", "limit: " + msg,
                    Map.of("errors", java.util.List.of(Map.of("field", "limit", "message", msg)), "code", "LIMIT_OUT_OF_RANGE"));
        }
        return raw;
    }

    private void recordOperatorAccess(String action, AuditFilter f, int rowCount, ValueAccess values,
                                      boolean withValues, UUID operatorId, OperatorInfo operator, String ipAddress) {
        Map<String, Object> params = auditedFilters(f);
        params.put("includeValues", withValues);
        String reason = withValues ? values.reason().trim() : null;
        try {
            repo.insertOperatorAudit(operatorId, action, f.tenantId(), objectMapper.writeValueAsString(params),
                    rowCount, reason, ipAddress);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
        if (f.tenantId() != null && repo.tenantExists(f.tenantId())) {
            Map<String, Object> after = new LinkedHashMap<>(params);
            after.put("operatorAction", action);
            after.put("rowCount", rowCount);
            auditService.record(f.tenantId(), "operator", operatorId, operator.name(), operator.role(), ipAddress,
                    "operator.audit_search", "audit_log", null, null, after);
        }
    }

    private OperatorInfo operator(UUID operatorId) {
        return repo.findOperatorInfo(operatorId).orElseThrow(() -> new ApiException(
                HttpStatus.NOT_FOUND, "not-found", "Not found", "The requested resource was not found."));
    }

    /**
     * PLT-028: server-side CSV of exactly what the view would page through for the same filters — the same
     * columns the view returns (before/after as their stored JSON text), nothing more.
     *
     * <p>Audited as {@code audit_log.export}. audit_log rows are per tenant (tenant_id NOT NULL, per-tenant hash
     * chain) and there is no platform-level ledger, so the event is written once into the chain of every tenant
     * whose rows the file contains, with that tenant's row count and the export-wide total: each tenant's own
     * trail shows who exported its records. An export that matched no rows discloses nothing and has no tenant
     * chain to land in; it is logged only (see the PLT-028 report note on a platform-level ledger).
     */
    @Transactional
    public String exportCsv(AuditFilter filter, ValueAccess values, UUID operatorId, String ipAddress) {
        AuditFilter f = normalize(filter);
        OperatorInfo operator = operator(operatorId);
        boolean withValues = values.check(operator);
        List<AuditEntry> rows = repo.search(f, null, EXPORT_MAX_ROWS + 1);
        if (rows.size() > EXPORT_MAX_ROWS) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "export-too-large", "Export too large",
                    "More than " + EXPORT_MAX_ROWS + " audit entries match these filters. Narrow the date range "
                            + "or add a tenant, action or actor filter, then export again.");
        }

        StringBuilder sb = new StringBuilder(CSV_HEADER);
        Map<UUID, Integer> rowsPerTenant = new LinkedHashMap<>();
        for (AuditEntry e : rows) {
            rowsPerTenant.merge(e.tenantId(), 1, Integer::sum);
            sb.append(e.id()).append(',')
                    .append(e.createdAt()).append(',')
                    .append(e.tenantId() == null ? "" : e.tenantId()).append(',')
                    .append(csvField(e.tenantName())).append(',')
                    .append(csvField(e.tenantSlug())).append(',')
                    .append(csvField(e.actorType())).append(',')
                    .append(e.actorId() == null ? "" : e.actorId()).append(',')
                    .append(csvField(e.actorName())).append(',')
                    .append(csvField(e.actorRole())).append(',')
                    .append(csvField(e.ipAddress())).append(',')
                    .append(csvField(e.action())).append(',')
                    .append(csvField(e.entityType())).append(',')
                    .append(e.entityId() == null ? "" : e.entityId()).append(',')
                    .append(withValues ? csvField(e.before()) : "").append(',')
                    .append(withValues ? csvField(e.after()) : "").append('\n');
        }

        Map<String, Object> filters = auditedFilters(f);
        recordOperatorAccess("audit_log.export", f, rows.size(), values, withValues, operatorId, operator, ipAddress);
        long tenantCount = rowsPerTenant.keySet().stream().filter(java.util.Objects::nonNull).count();
        if (rowsPerTenant.isEmpty()) {
            rowsPerTenant.put(null, 0); // nothing disclosed, but the export itself still lands in the platform chain
        }
        rowsPerTenant.forEach((tenantId, count) -> {
            Map<String, Object> after = new LinkedHashMap<>();
            after.put("format", "csv");
            after.put("filters", filters);
            after.put("rowCount", count);
            after.put("totalRowCount", rows.size());
            after.put("tenantCount", tenantCount);
            if (tenantId == null) { // platform-scope rows in the file, or an empty export (V126 platform chain)
                auditService.recordPlatform("operator", operatorId, operator.name(), operator.role(), ipAddress,
                        "audit_log.export", "audit_log", null, null, after);
            } else {
                auditService.record(tenantId, "operator", operatorId, operator.name(), operator.role(), ipAddress,
                        "audit_log.export", "audit_log", null, null, after);
            }
        });
        log.info("audit log export by operator {} ({}): {} rows across {} tenants",
                operatorId, operator.role(), rows.size(), rowsPerTenant.size());
        return sb.toString();
    }

    /** Blank strings become "no filter"; actorType is a closed set, so an unknown value is a 400, not an empty page. */
    private AuditFilter normalize(AuditFilter f) {
        String actorType = blankToNull(f.actorType());
        if (actorType != null && !ACTOR_TYPES.contains(actorType)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "invalid-actor-type", "Invalid actor type",
                    "actorType must be one of staff, operator, system.");
        }
        if (f.createdAfter() != null && f.createdBefore() != null && f.createdAfter().isAfter(f.createdBefore())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "invalid-date-range", "Invalid date range",
                    "createdAfter must not be later than createdBefore.");
        }
        return new AuditFilter(f.tenantId(), blankToNull(f.action()), blankToNull(f.entityType()),
                f.createdAfter(), f.createdBefore(), f.actorId(), actorType, blankToNull(f.actorRole()),
                blankToNull(f.actorName()));
    }

    /** The filters as recorded in the export's own audit event. The actor-name search text is recorded only as
     * present/absent: AuditService's PHI policy keeps direct identifiers (names) out of before/after. */
    private Map<String, Object> auditedFilters(AuditFilter f) {
        Map<String, Object> m = new LinkedHashMap<>();
        if (f.tenantId() != null) m.put("tenantId", f.tenantId().toString());
        if (f.action() != null) m.put("action", f.action());
        if (f.entityType() != null) m.put("entityType", f.entityType());
        if (f.createdAfter() != null) m.put("createdAfter", f.createdAfter().toString());
        if (f.createdBefore() != null) m.put("createdBefore", f.createdBefore().toString());
        if (f.actorId() != null) m.put("actorId", f.actorId().toString());
        if (f.actorType() != null) m.put("actorType", f.actorType());
        if (f.actorRole() != null) m.put("actorRole", f.actorRole());
        if (f.actorName() != null) m.put("actorNameFilter", true);
        return m;
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    /** Quoted, quotes doubled (same as ReportsService.csvField), plus a leading apostrophe on values a
     * spreadsheet would otherwise evaluate as a formula (CSV injection via e.g. a staff name "=HYPERLINK(...)"). */
    static String csvField(String value) {
        if (value == null) {
            return "";
        }
        String v = value;
        if (!v.isEmpty() && "=+-@\t\r".indexOf(v.charAt(0)) >= 0) {
            v = "'" + v;
        }
        return "\"" + v.replace("\"", "\"\"") + "\"";
    }

    private long decodeCursor(String cursor) {
        try {
            return Long.parseLong(cursor);
        } catch (NumberFormatException e) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "invalid-cursor", "Invalid cursor",
                    "The pagination cursor is malformed.");
        }
    }

    private AuditEntryResponse toResponse(AuditEntry e, boolean withValues) {
        return new AuditEntryResponse(e.id(), e.tenantId(), e.tenantName(), e.tenantSlug(), e.actorType(),
                e.actorId(), e.actorName(), e.actorRole(), e.ipAddress(), e.action(), e.entityType(), e.entityId(),
                withValues ? parseJson(e.before()) : null, withValues ? parseJson(e.after()) : null, e.createdAt());
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
