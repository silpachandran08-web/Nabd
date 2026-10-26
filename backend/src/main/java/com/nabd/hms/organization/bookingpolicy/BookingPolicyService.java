package com.nabd.hms.organization.bookingpolicy;

import com.nabd.hms.common.ApiException;
import com.nabd.hms.common.AuditService;
import com.nabd.hms.common.ClinicClock;
import com.nabd.hms.common.TenantContext;
import com.nabd.hms.iam.api.ActorDirectory;
import com.nabd.hms.organization.api.BookingRules;
import com.nabd.hms.organization.department.DepartmentWorkflowSync;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * ACC-008 / ACC-013: the booking policy store. A draft is validated on every save; publishing needs a step-up (checked by the
 * controller), turns the draft into the next version, writes an audit row with before and after, and, for the Solo preset,
 * syncs the departments' vitals toggle (DepartmentWorkflowSync). Existing appointments keep the version they were made under.
 */
@Service
@Transactional
class BookingPolicyService implements BookingRules {

    private static final Logger log = LoggerFactory.getLogger(BookingPolicyService.class);

    record Published(int version, Map<String, Object> config, Instant publishedAt, UUID publishedBy, String changeNote) {
    }

    record Draft(Map<String, Object> config, Instant updatedAt) {
    }

    record Current(Published published, Draft draft) {
    }

    record Change(String pointer, Object before, Object after) {
    }

    record VersionItem(int version, Instant publishedAt, UUID publishedBy, String publishedByName, String changeNote, List<Change> diff) {
    }

    record Applied(Draft draft, List<Change> changes, List<DepartmentWorkflowSync.Change> departmentWorkflowChanges) {
    }

    record Page(List<VersionItem> items, String nextCursor) {
    }

    /** shortcut: per-instance L1 only; the Valkey versioned key + published event (T-108) wait for an allocated event type. */
    private record Cached(Integer version, Map<String, Object> config, long loadedAtMs) {
    }

    private final BookingPolicyRepository repo;
    private final TenantContext tenantContext;
    private final AuditService audit;
    private final ActorDirectory actors;
    private final DepartmentWorkflowSync workflowSync;
    private final ObjectMapper json;
    private final ClinicClock clock;
    private final long cacheTtlMs;
    private final ConcurrentHashMap<UUID, Cached> cache = new ConcurrentHashMap<>();

    BookingPolicyService(BookingPolicyRepository repo, TenantContext tenantContext, AuditService audit, ActorDirectory actors,
                         DepartmentWorkflowSync workflowSync, ObjectMapper json, ClinicClock clock,
                         @Value("${app.booking-policy.cache-ms:3000}") long cacheTtlMs) {
        this.repo = repo;
        this.tenantContext = tenantContext;
        this.audit = audit;
        this.actors = actors;
        this.workflowSync = workflowSync;
        this.json = json;
        this.clock = clock;
        this.cacheTtlMs = cacheTtlMs;
    }

    // ---- reads for other modules ----

    @Override
    public Optional<Rule> rule(UUID facilityId, String visitType) {
        Cached c = cached();
        @SuppressWarnings("unchecked")
        Map<String, Object> vt = c.config().get("visitTypes") instanceof Map<?, ?> m ? (Map<String, Object>) m.get(visitType) : null;
        if (vt == null) {
            return Optional.empty();
        }
        return Optional.of(new Rule(c.version() == null ? 0 : c.version(), visitType, (String) vt.get("acceptance"),
                (Integer) vt.get("dailyCapPerDoctor"), (String) vt.get("reviewerRole")));
    }

    @Override
    public Optional<Integer> publishedVersion() {
        return Optional.ofNullable(cached().version());
    }

    private Cached cached() {
        UUID tenantId = tenantContext.current();
        Cached c = cache.get(tenantId);
        if (c != null && System.currentTimeMillis() - c.loadedAtMs() < cacheTtlMs) {
            return c;
        }
        Cached fresh = repo.byStatus(tenantId, "published")
                .map(r -> new Cached(r.version(), read(r.configJson()), System.currentTimeMillis()))
                .orElseGet(() -> new Cached(null, BookingPresets.defaults(), System.currentTimeMillis()));
        cache.put(tenantId, fresh);
        return fresh;
    }

    // ---- API ----

    @Transactional(readOnly = true)
    Current current() {
        UUID tenantId = tenantContext.current();
        Published p = repo.byStatus(tenantId, "published")
                .map(r -> new Published(r.version(), read(r.configJson()), r.publishedAt(), r.publishedBy(), r.changeNote())).orElse(null);
        Draft d = repo.byStatus(tenantId, "draft").map(r -> new Draft(read(r.configJson()), r.updatedAt())).orElse(null);
        return new Current(p, d);
    }

    Draft saveDraft(UUID staffId, Map<String, Object> config) {
        UUID tenantId = tenantContext.current();
        Map<String, Object> clean = finish(BookingPolicyValidator.validate(config));
        repo.lock(tenantId);
        int version = repo.byStatus(tenantId, "draft").map(BookingPolicyRepository.Row::version).orElseGet(() -> repo.nextVersion(tenantId));
        repo.upsertDraft(tenantId, version, write(clean), staffId);
        return repo.byStatus(tenantId, "draft").map(r -> new Draft(read(r.configJson()), r.updatedAt())).orElseThrow();
    }

    /** Fills the draft from a preset and says what it changed against the previous draft (or the published/default settings). */
    Applied applyPreset(UUID staffId, String preset) {
        Map<String, Object> def = BookingPresets.forName(preset);
        if (def == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "unknown-preset", "Unknown preset",
                    "This preset is not available.", Map.of("code", "UNKNOWN_PRESET"));
        }
        UUID tenantId = tenantContext.current();
        Current cur = current();
        Map<String, Object> before = cur.draft() != null ? cur.draft().config()
                : cur.published() != null ? cur.published().config() : BookingPresets.defaults();
        Draft draft = saveDraft(staffId, def);
        boolean vitals = (Boolean) ((Map<?, ?>) draft.config().get("workflow")).get("vitalsEnabled");
        return new Applied(draft, diff(before, draft.config()), workflowSync.preview(vitals));
    }

    Map<String, Object> publish(UUID staffId, String changeNote) {
        UUID tenantId = tenantContext.current();
        repo.lock(tenantId);
        BookingPolicyRepository.Row draft = repo.byStatus(tenantId, "draft").orElseThrow(() ->
                new ApiException(HttpStatus.CONFLICT, "no-draft", "No draft to publish", "Save a draft first.", Map.of("code", "NO_DRAFT")));
        Optional<BookingPolicyRepository.Row> old = repo.byStatus(tenantId, "published");
        Map<String, Object> config = read(draft.configJson());
        repo.supersedePublished(tenantId);
        repo.publishDraft(tenantId, staffId, changeNote);
        List<DepartmentWorkflowSync.Change> wf = List.of();
        if ("solo".equals(config.get("preset"))) {
            wf = workflowSync.apply((Boolean) ((Map<?, ?>) config.get("workflow")).get("vitalsEnabled"));
        }
        Map<String, Object> before = old.map(r -> Map.<String, Object>of("version", r.version(), "config", read(r.configJson()))).orElse(null);
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("version", draft.version());
        after.put("config", config);
        after.put("changeNote", changeNote);
        after.put("departmentsChanged", wf.stream().map(c -> c.departmentId().toString()).toList());
        ActorDirectory.Actor actor = actors.findActor(tenantId, staffId).orElseThrow(() ->
                new ApiException(HttpStatus.NOT_FOUND, "not-found", "Not found", "The requested resource was not found."));
        audit.record(tenantId, "staff", staffId, actor.name(), actor.role(), null, "scheduling.booking_policy.published",
                "booking_policy", draft.id(), before, after);
        cache.remove(tenantId); // this instance sees it at once; the others within the cache TTL
        log.info("booking policy v{} published for tenant {} by {}", draft.version(), tenantId, staffId);
        return Map.of("version", draft.version(), "effectiveAt", clock.now());
    }

    @Transactional(readOnly = true)
    Page versions(String cursor, int limit) {
        UUID tenantId = tenantContext.current();
        Integer before = null;
        try {
            before = cursor == null || cursor.isBlank() ? null : Integer.valueOf(cursor);
        } catch (NumberFormatException e) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "bad-cursor", "Invalid cursor", "The cursor is not valid.");
        }
        List<BookingPolicyRepository.Row> rows = repo.history(tenantId, before, limit + 1);
        boolean more = rows.size() > limit;
        List<VersionItem> items = new ArrayList<>();
        for (int i = 0; i < Math.min(limit, rows.size()); i++) {
            BookingPolicyRepository.Row r = rows.get(i);
            Map<String, Object> prev = i + 1 < rows.size() ? read(rows.get(i + 1).configJson()) : Map.of();
            String name = r.publishedBy() == null ? null : actors.findActor(tenantId, r.publishedBy()).map(ActorDirectory.Actor::name).orElse(null);
            items.add(new VersionItem(r.version(), r.publishedAt(), r.publishedBy(), name, r.changeNote(), diff(prev, read(r.configJson()))));
        }
        return new Page(items, more ? String.valueOf(items.get(items.size() - 1).version()) : null);
    }

    // ---- helpers ----

    /** Marks the clinic 'customised' when a preset's settings were edited afterwards (server-owned, never trusted from the client). */
    private Map<String, Object> finish(Map<String, Object> valid) {
        Map<String, Object> out = new LinkedHashMap<>(valid);
        Map<String, Object> def = BookingPresets.forName((String) valid.get("preset"));
        out.put("presetCustomised", def != null && !withoutLabel(def).equals(withoutLabel(valid)));
        return out;
    }

    private static Map<String, Object> withoutLabel(Map<String, Object> c) {
        Map<String, Object> m = new LinkedHashMap<>(c);
        m.remove("presetCustomised");
        return m;
    }

    static List<Change> diff(Map<String, Object> from, Map<String, Object> to) {
        Map<String, Object> a = flatten(from);
        Map<String, Object> b = flatten(to);
        Set<String> keys = new TreeSet<>(a.keySet());
        keys.addAll(b.keySet());
        List<Change> out = new ArrayList<>();
        for (String k : new LinkedHashSet<>(keys)) {
            if (!java.util.Objects.equals(a.get(k), b.get(k))) {
                out.add(new Change(k, a.get(k), b.get(k)));
            }
        }
        return out;
    }

    private static Map<String, Object> flatten(Map<String, Object> m) {
        Map<String, Object> out = new LinkedHashMap<>();
        walk("", m, out);
        return out;
    }

    private static void walk(String path, Object v, Map<String, Object> out) {
        if (v instanceof Map<?, ?> m) {
            m.forEach((k, x) -> walk(path + "/" + k, x, out));
        } else {
            out.put(path, v);
        }
    }

    private Map<String, Object> read(String text) {
        try {
            return json.readValue(text, new TypeReference<LinkedHashMap<String, Object>>() {
            });
        } catch (JacksonException e) {
            throw new IllegalStateException("malformed booking policy JSON", e);
        }
    }

    private String write(Object v) {
        try {
            return json.writeValueAsString(v);
        } catch (JacksonException e) {
            throw new IllegalStateException("failed to serialize booking policy", e);
        }
    }
}
