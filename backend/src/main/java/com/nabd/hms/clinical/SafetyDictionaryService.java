package com.nabd.hms.clinical;

import com.nabd.hms.clinical.dto.SafetyDictionaryBundle;
import com.nabd.hms.common.ApiException;
import com.nabd.hms.common.TenantContext;
import com.nabd.hms.platform.PlatformAudit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * CLN-004: the active dictionary per region, cached as an immutable snapshot in each pod, plus the operator flow
 * upload (draft) -> record CSO approval -> activate / roll back. Every pod polls the active version numbers every
 * {@code app.safety-dictionary.refresh-ms} (default 60 s) and reloads a region whose version changed, so an activation
 * or rollback reaches all pods well inside the 5-minute target without a broadcast channel.
 */
@Service
public class SafetyDictionaryService {

    static final Set<String> REGIONS = Set.of("IN", "SA", "AE", "QA");
    static final String SOURCE_APPROVED = "Nabd CSO dictionary";
    static final String SOURCE_BASELINE = "Nabd baseline dictionary (no CSO approval recorded)";
    private static final Logger log = LoggerFactory.getLogger(SafetyDictionaryService.class);

    private final SafetyDictionaryRepository repo;
    private final PrescriptionRepository prescriptions;
    private final TenantContext tenantContext;
    private final PlatformAudit platformAudit;
    // shortcut: a load racing a refresh can briefly keep the older snapshot; the next poll corrects it (<= refresh-ms)
    private final Map<String, SafetyDictionary> active = new ConcurrentHashMap<>();

    SafetyDictionaryService(SafetyDictionaryRepository repo, PrescriptionRepository prescriptions, TenantContext tenantContext,
                            PlatformAudit platformAudit) {
        this.repo = repo;
        this.prescriptions = prescriptions;
        this.tenantContext = tenantContext;
        this.platformAudit = platformAudit;
    }

    // ── read side ────────────────────────────────────────────────────────

    SafetyDictionary forTenantRegion(String tenantRegion) {
        return current(SafetyDictionary.regionOfTenant(tenantRegion));
    }

    SafetyDictionary current(String region) {
        SafetyDictionary d = active.get(region);
        return d != null ? d : load(region);
    }

    private SafetyDictionary load(String region) {
        SafetyDictionaryRepository.VersionRow v = repo.findActive(region).orElseThrow(() -> new ApiException(HttpStatus.SERVICE_UNAVAILABLE,
                "safety-dictionary-unavailable", "Safety checks unavailable",
                "No active safety dictionary for region " + region + "; prescribing safety checks cannot run."));
        SafetyDictionary d = SafetyDictionary.fromEntries(region, v.version(), v.approvedAt(), v.approvedBy(),
                repo.entries(v.id()), repo.controlledLabel(v));
        active.put(region, d);
        return d;
    }

    /** Per-pod reload: one indexed query; a region is reloaded only when its active version number changed. */
    @Scheduled(fixedDelayString = "${app.safety-dictionary.refresh-ms:60000}", initialDelayString = "${app.safety-dictionary.refresh-ms:60000}")
    public void refresh() {
        Map<String, Integer> now = repo.activeVersions();
        for (Map.Entry<String, SafetyDictionary> e : active.entrySet()) {
            Integer v = now.get(e.getKey());
            if (v == null) {
                active.remove(e.getKey());
            } else if (v != e.getValue().version()) {
                log.info("safety dictionary {} changed v{} -> v{}; reloading", e.getKey(), e.getValue().version(), v);
                load(e.getKey());
            }
        }
    }

    /** GET /v1/clinical/safety-dictionary: what is shown under each warning. No PHI. */
    @Transactional
    public Map<String, Object> activeForTenant(UUID tenantId) {
        tenantContext.set(tenantId);
        SafetyDictionary d = forTenantRegion(prescriptions.findTenantRegion(tenantId).orElse("IN"));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("region", d.region());
        out.put("version", d.version());
        out.put("label", d.label());
        out.put("approvedBy", d.approvedBy());
        out.put("approvedAt", d.approvedAt());
        out.put("source", d.approvedAt() != null ? SOURCE_APPROVED : SOURCE_BASELINE);
        out.put("controlledLabel", d.controlledLabel());
        return out;
    }

    // ── operator side (platform audit chain) ─────────────────────────────

    @Transactional
    public List<Map<String, Object>> versions(String region) {
        return repo.list(checkRegion(region)).stream().map(SafetyDictionaryService::view).toList();
    }

    @Transactional
    public Map<String, Object> upload(String region, SafetyDictionaryBundle bundle, UUID operatorId, String ip) {
        checkRegion(region);
        List<String> errors = SafetyDictionaryBundles.validate(bundle);
        if (!errors.isEmpty()) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "BUNDLE_INVALID", "Dictionary bundle invalid",
                    errors.size() + " problem(s) in the bundle; nothing was imported.",
                    Map.of("errors", errors.size() > 200 ? errors.subList(0, 200) : errors));
        }
        List<SafetyDictionary.Entry> entries = SafetyDictionaryBundles.toEntries(bundle);
        SafetyDictionaryRepository.ImportResult r = repo.importVersion(region, entries,
                Map.of("controlled", SafetyDictionaryBundles.labels(bundle)), "operator:" + operatorId);
        Map<String, Object> diff = repo.findActive(region)
                .map(a -> diff(region + " v" + a.version(), repo.entries(a.id()), entries))
                .orElse(Map.of("comparedTo", "none"));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("versionId", r.versionId());
        out.put("region", region);
        out.put("version", r.version());
        out.put("status", r.status());
        out.put("checksum", r.checksum());
        out.put("created", r.created());
        out.put("diffSummary", diff);
        if (r.created()) {
            platformAudit.record(null, operatorId, ip, "safety_dictionary.uploaded", "safety_dictionary_version", r.versionId(),
                    Map.of("region", region, "version", r.version(), "checksum", r.checksum(), "entries", entries.size(),
                            "added", diff.getOrDefault("added", Map.of()), "removed", diff.getOrDefault("removed", Map.of())));
        }
        return out;
    }

    @Transactional
    public Map<String, Object> approve(String region, UUID versionId, String csoApprovalRef, String approvedBy, UUID operatorId, String ip) {
        SafetyDictionaryRepository.VersionRow v = find(region, versionId);
        if (v.csoApprovalRef() != null || !repo.approve(versionId, csoApprovalRef.trim(), approvedBy.trim())) {
            throw new ApiException(HttpStatus.CONFLICT, "ALREADY_APPROVED", "Already approved",
                    "This dictionary version already has a CSO approval record.");
        }
        platformAudit.record(null, operatorId, ip, "safety_dictionary.approved", "safety_dictionary_version", versionId,
                Map.of("region", v.region(), "version", v.version(), "csoApprovalRef", csoApprovalRef.trim(), "approvedBy", approvedBy.trim()));
        return view(find(region, versionId));
    }

    /** Activate an approved version, or roll back to an earlier approved one: same operation. */
    @Transactional
    public Map<String, Object> activate(String region, UUID versionId, String csoApprovalRef, String reason, UUID operatorId, String ip) {
        SafetyDictionaryRepository.VersionRow v = find(region, versionId);
        if ("active".equals(v.status())) {
            throw alreadyActive();
        }
        if (v.csoApprovalRef() == null || !v.csoApprovalRef().equals(csoApprovalRef.trim())) {
            throw notApproved();
        }
        SafetyDictionaryRepository.ActivateResult r = repo.activate(versionId);
        switch (r.outcome()) {
            case "activated" -> { }
            case "already_active" -> throw alreadyActive();
            case "not_approved" -> throw notApproved();
            default -> throw new ApiException(HttpStatus.NOT_FOUND, "not-found", "Not found", "Dictionary version not found.");
        }
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("region", r.region());
        after.put("activeVersion", r.version());
        after.put("previousVersion", r.previousVersion());
        after.put("checksum", v.checksum());
        after.put("csoApprovalRef", v.csoApprovalRef());
        after.put("reason", reason.trim());
        platformAudit.record(null, operatorId, ip, "safety_dictionary.activated", "safety_dictionary_version", versionId,
                r.previousVersion() == null ? null : Map.of("activeVersion", r.previousVersion()), after);
        // this pod drops its snapshot once the switch is committed; other pods pick it up on their next poll
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                active.remove(r.region());
            }
        });
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("region", r.region());
        out.put("activeVersion", r.version());
        out.put("previousVersion", r.previousVersion());
        return out;
    }

    // ── helpers ──────────────────────────────────────────────────────────

    private SafetyDictionaryRepository.VersionRow find(String region, UUID versionId) {
        return repo.find(checkRegion(region), versionId).orElseThrow(() ->
                new ApiException(HttpStatus.NOT_FOUND, "not-found", "Not found", "Dictionary version not found in region " + region + "."));
    }

    private static String checkRegion(String region) {
        if (!REGIONS.contains(region)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "validation", "Unknown region", "Region must be one of IN, SA, AE, QA.");
        }
        return region;
    }

    private static ApiException notApproved() {
        return new ApiException(HttpStatus.CONFLICT, "NOT_APPROVED", "No CSO approval",
                "This dictionary version has no matching CSO approval record and cannot be activated.");
    }

    private static ApiException alreadyActive() {
        return new ApiException(HttpStatus.CONFLICT, "ALREADY_ACTIVE", "Already active", "This dictionary version is already active.");
    }

    private static Map<String, Object> view(SafetyDictionaryRepository.VersionRow v) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("versionId", v.id());
        m.put("region", v.region());
        m.put("version", v.version());
        m.put("status", v.status());
        m.put("checksum", v.checksum());
        m.put("csoApprovalRef", v.csoApprovalRef());
        m.put("approvedBy", v.approvedBy());
        m.put("approvedAt", v.approvedAt());
        m.put("activatedAt", v.activatedAt());
        m.put("createdAt", v.createdAt());
        return m;
    }

    private static String key(SafetyDictionary.Entry e) {
        return e.kind() + "|" + (e.classId() == null ? "" : e.classId()) + "|" + e.matchKind() + "|" + e.value() + "|"
                + (e.lang() == null ? "" : e.lang()) + "|" + String.join(",", e.crossReactsTo() == null ? List.of() : e.crossReactsTo());
    }

    /** Counts per kind of entries added and removed against the active version, plus up to 200 of each for review. */
    static Map<String, Object> diff(String comparedTo, List<SafetyDictionary.Entry> before, List<SafetyDictionary.Entry> after) {
        Set<String> b = new HashSet<>();
        before.forEach(e -> b.add(key(e)));
        Set<String> a = new HashSet<>();
        after.forEach(e -> a.add(key(e)));
        Map<String, Integer> added = new TreeMap<>();
        Map<String, Integer> removed = new TreeMap<>();
        List<String> addedList = new ArrayList<>();
        List<String> removedList = new ArrayList<>();
        for (SafetyDictionary.Entry e : after) {
            if (!b.contains(key(e))) {
                added.merge(e.kind(), 1, Integer::sum);
                if (addedList.size() < 200) {
                    addedList.add(key(e));
                }
            }
        }
        for (SafetyDictionary.Entry e : before) {
            if (!a.contains(key(e))) {
                removed.merge(e.kind(), 1, Integer::sum);
                if (removedList.size() < 200) {
                    removedList.add(key(e));
                }
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("comparedTo", comparedTo);
        out.put("added", added);
        out.put("removed", removed);
        out.put("addedEntries", addedList);
        out.put("removedEntries", removedList);
        return out;
    }
}
