package com.nabd.hms.queue;

import com.nabd.hms.common.ApiException;
import com.nabd.hms.common.AuditService;
import com.nabd.hms.common.ClinicClock;
import com.nabd.hms.common.TenantContext;
import com.nabd.hms.iam.api.ActorDirectory;
import com.nabd.hms.queue.api.ReasonCodes;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * ACC-007: platform catalogue per market (reason_code_catalogue) + tenant additions/hides (tenant_reason_codes).
 * shortcut: read straight from the tables on every call (a few dozen rows); move to the class C config cache when it shows up in a profile.
 */
@Service
@Transactional
class ReasonCodeService implements ReasonCodes {

    static final Set<String> KINDS = Set.of("cancel", "no_show", "reschedule", "override", "refusal", "discount"); // FIN-018: discount = billing price changes
    private static final Set<String> LANGS = Set.of("en", "hi", "kn", "te", "ar");
    private static final int NOTE_MAX = 200;

    private final JdbcTemplate jdbc;
    private final TenantContext tenantContext;
    private final ClinicClock clock;
    private final AuditService audit;
    private final ActorDirectory actors;
    private final ObjectMapper json;

    ReasonCodeService(JdbcTemplate jdbc, TenantContext tenantContext, ClinicClock clock, AuditService audit, ActorDirectory actors,
                      ObjectMapper json) {
        this.jdbc = jdbc;
        this.tenantContext = tenantContext;
        this.clock = clock;
        this.audit = audit;
        this.actors = actors;
        this.json = json;
    }

    @Override
    public List<Item> list(String kind, String locale) {
        if (!KINDS.contains(kind)) {
            throw bad("INVALID_KIND", "kind must be one of cancel, no_show, reschedule, override, refusal, discount.");
        }
        UUID tenant = tenantContext.current();
        String lang = locale == null ? "en" : locale.toLowerCase().split("[-_]")[0];
        if (!LANGS.contains(lang)) {
            lang = "en";
        }
        return jdbc.query("""
                SELECT code, COALESCE(labels->>?::text, labels->>'en') AS label, requires_note FROM (
                  SELECT c.code, c.labels, c.requires_note, c.sort FROM reason_code_catalogue c
                  WHERE c.kind = ? AND c.market = ?
                    AND NOT EXISTS (SELECT 1 FROM tenant_reason_codes h WHERE h.tenant_id = ? AND h.kind = c.kind AND h.code = c.code AND h.hidden)
                  UNION ALL
                  SELECT t.code, t.labels, false, 1000 FROM tenant_reason_codes t WHERE t.tenant_id = ? AND t.kind = ? AND NOT t.hidden
                ) x ORDER BY sort, code""",
                (rs, i) -> new Item(rs.getString("code"), rs.getString("label"), rs.getBoolean("requires_note")),
                lang, kind, clock.region(tenant), tenant, tenant, kind);
    }

    @Override
    public String require(String kind, String code, String note) {
        Item item = find(kind, code);
        String n = note == null || note.isBlank() ? null : note.trim();
        if (item.requiresNote() && n == null) {
            throw bad("NOTE_REQUIRED", "A note is required with the reason '" + item.code() + "'.");
        }
        if (!item.requiresNote() && n != null) {
            throw bad("NOTE_NOT_ALLOWED", "A free-text note is allowed only with the reason 'other'.");
        }
        if (n != null && n.length() > NOTE_MAX) {
            throw bad("NOTE_TOO_LONG", "The note may be at most " + NOTE_MAX + " characters.");
        }
        return n;
    }

    @Override
    public void requireKnown(String kind, String code) {
        find(kind, code);
    }

    private Item find(String kind, String code) {
        if (code == null || code.isBlank()) {
            throw bad("REASON_REQUIRED", "reasonCode is required.");
        }
        return list(kind, "en").stream().filter(i -> i.code().equals(code)).findFirst()
                .orElseThrow(() -> bad("REASON_INVALID", "reasonCode is not on the " + kind + " reason list."));
    }

    /** POST /v1/reason-codes: the tenant adds an entry; platform codes (and the tenant's own) cannot be redefined. */
    Map<String, Object> add(UUID staffId, String kind, String code, Map<String, String> labels) {
        if (!KINDS.contains(kind)) {
            throw bad("INVALID_KIND", "kind must be one of cancel, no_show, reschedule, override, refusal, discount.");
        }
        if (code == null || !code.matches("[a-z0-9_]{3,40}")) {
            throw bad("INVALID_CODE", "code must be 3 to 40 characters: lower-case letters, digits and _.");
        }
        if (labels == null || labels.get("en") == null || labels.get("en").isBlank()
                || !LANGS.containsAll(labels.keySet()) || labels.values().stream().anyMatch(v -> v == null || v.isBlank() || v.length() > 80)) {
            throw bad("INVALID_LABELS", "labels needs an English label; other keys are hi, kn, te, ar; each label is at most 80 characters.");
        }
        UUID tenant = tenantContext.current();
        boolean exists = Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM reason_code_catalogue WHERE kind = ? AND code = ? AND market = ?) "
                        + "OR EXISTS (SELECT 1 FROM tenant_reason_codes WHERE tenant_id = ? AND kind = ? AND code = ?)",
                Boolean.class, kind, code, clock.region(tenant), tenant, kind, code));
        if (exists) {
            throw new ApiException(HttpStatus.CONFLICT, "code-exists", "Reason code exists", "That code is already on the " + kind + " list.",
                    Map.of("code", "CODE_EXISTS"));
        }
        UUID id = com.nabd.hms.shared.UuidV7.next();
        jdbc.update("INSERT INTO tenant_reason_codes (id, tenant_id, kind, code, labels) VALUES (?,?,?,?,?::jsonb)", id, tenant, kind, code, json.writeValueAsString(labels));
        var actor = actors.findActor(tenant, staffId).orElseThrow();
        audit.record(tenant, "staff", staffId, actor.name(), actor.role(), null, "reason_code.added", "tenant_reason_code", id,
                null, Map.of("kind", kind, "code", code));
        return Map.of("code", code);
    }

    private static ApiException bad(String code, String detail) {
        return new ApiException(HttpStatus.BAD_REQUEST, "invalid-reason", "Invalid reason", detail, Map.of("code", code));
    }
}
