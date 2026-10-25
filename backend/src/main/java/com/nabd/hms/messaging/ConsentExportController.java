package com.nabd.hms.messaging;

import com.nabd.hms.common.ApiException;
import com.nabd.hms.common.TenantScope;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * COM-008: paged channel-consent snapshot for the Comms Gateway's nightly reconciliation. Same internal identity as the
 * COM-004 claim check (shortcut: shared service token until PLT-009's aud=internal service identity replaces it).
 * version = outbox id of the patient's latest consent.changed.v1 event for the purpose (0 when none): the same number
 * space as the event's data.version, so the snapshot's version gate compares like with like.
 */
@RestController
class ConsentExportController {

    record Item(UUID patientId, String purpose, boolean granted, long version, Instant at) {}

    record Page(List<Item> items, String nextCursor) {}

    private static final int PAGE = 500;

    private final JdbcTemplate jdbc;
    private final TenantScope tenantScope;
    private final byte[] token;

    ConsentExportController(JdbcTemplate jdbc, TenantScope tenantScope, @Value("${app.internal.comms-token:}") String token) {
        this.jdbc = jdbc;
        this.tenantScope = tenantScope;
        this.token = token.getBytes(StandardCharsets.UTF_8);
    }

    @GetMapping("/internal/v1/consents:export")
    Page export(@RequestParam UUID tenantId, @RequestParam(required = false) Instant since, @RequestParam(required = false) String cursor,
                @RequestHeader(value = "X-Internal-Token", required = false) String presented) {
        if (token.length == 0 || presented == null || !MessageDigest.isEqual(token, presented.getBytes(StandardCharsets.UTF_8))) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "unauthorized", "Unauthorized", "Internal service identity required.");
        }
        UUID afterPatient = null;
        String afterPurpose = "";
        if (cursor != null) {
            try {
                String[] c = cursor.split(":", 2);
                afterPatient = UUID.fromString(c[0]);
                afterPurpose = c[1];
            } catch (RuntimeException e) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "invalid-cursor", "Invalid cursor", "cursor is not one issued by this endpoint.");
            }
        }
        final UUID fromPatient = afterPatient;
        final String fromPurpose = afterPurpose;
        List<Item> rows = tenantScope.run(tenantId, () -> jdbc.query("""
                SELECT * FROM (
                  SELECT DISTINCT ON (c.patient_id, c.consent_type) c.patient_id, c.consent_type, c.withdrawn_at IS NULL AS granted,
                         COALESCE(c.withdrawn_at, c.granted_at) AS at,
                         COALESCE((SELECT max(o.id) FROM outbox_events o WHERE o.aggregate_id = c.patient_id
                                     AND o.ce_type = 'consent.changed.v1' AND o.payload->>'consentType' = c.consent_type), 0) AS version
                  FROM consents c
                  WHERE c.consent_type IN ('messaging', 'marketing')
                  ORDER BY c.patient_id, c.consent_type, c.created_at DESC, c.id DESC
                ) l
                WHERE (?::timestamptz IS NULL OR l.at >= ?::timestamptz) AND (l.patient_id, l.consent_type) > (?::uuid, ?)
                ORDER BY l.patient_id, l.consent_type LIMIT ?
                """, (rs, i) -> new Item(rs.getObject(1, UUID.class), rs.getString(2), rs.getBoolean(3),
                        rs.getLong(5), rs.getTimestamp(4).toInstant()),
                since == null ? null : java.sql.Timestamp.from(since), since == null ? null : java.sql.Timestamp.from(since),
                fromPatient == null ? "00000000-0000-0000-0000-000000000000" : fromPatient.toString(), fromPurpose, PAGE + 1));
        boolean more = rows.size() > PAGE;
        List<Item> items = more ? rows.subList(0, PAGE) : rows;
        Item last = items.isEmpty() ? null : items.get(items.size() - 1);
        return new Page(items, more ? last.patientId() + ":" + last.purpose() : null);
    }
}
