package com.nabd.hms.specialty;

import com.nabd.hms.common.ApiException;
import com.nabd.hms.common.AuditService;
import com.nabd.hms.common.DomainEventType;
import com.nabd.hms.common.EventPublisher;
import com.nabd.hms.iam.api.ActorDirectory;
import com.nabd.hms.organization.api.Entitlements;
import io.swagger.v3.oas.annotations.media.Schema;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * CLN-042: doctor specialty profiles. The registry (specialty_profiles) is Nabd-defined; which profiles a clinic may use is
 * its plan entitlement (organization.api.Entitlements, code = config.module, e.g. specialty_derm; a plan listing no specialty
 * keeps them all). The owner assigns a primary and optional secondary profile (staff:edit); the access token then carries
 * specialty=&lt;primary&gt; and &lt;module&gt;:view|edit (TokenService). When the entitlement lapses (trial end) history stays
 * readable and writes under /v1/specialty/&lt;x&gt;/** get 403 ENTITLEMENT_EXPIRED (SpecialtyWriteGuard).
 */
@Service
public class SpecialtyService {

    static final long TTL_MILLIS = 60_000;

    record Profile(String code, Map<String, String> name, Map<String, Object> config, int configVersion) {
        String module() {
            return (String) config.get("module");
        }
    }

    @Schema(name = "SpecialtyProfileItem")
    public record ProfileItem(@Schema(requiredMode = Schema.RequiredMode.REQUIRED) String code,
                              @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Map<String, String> name,
                              @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean entitled,
                              @Schema(requiredMode = Schema.RequiredMode.REQUIRED) int configVersion) {
    }

    @Schema(name = "SpecialtyProfileList")
    public record ProfileList(@Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<ProfileItem> items) {
    }

    @Schema(name = "StaffSpecialtyProfiles")
    public record Assignment(@Schema(nullable = true) String primary, @Schema(nullable = true) String secondary) {
    }

    /** The caller's effective profile: primary null = none assigned. writable false = the plan no longer includes it. */
    @Schema(name = "MySpecialtyProfile")
    public record Me(@Schema(nullable = true) String primary, @Schema(nullable = true) String secondary,
                     @Schema(nullable = true) Map<String, String> name, @Schema(nullable = true) Integer configVersion,
                     @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean writable,
                     @Schema(nullable = true, description = "widgets[], quickActions[], fieldSets[], templatePack") Map<String, Object> config) {
    }

    private record Snapshot(List<Profile> profiles, long loadedAt) {
    }

    private final SpecialtyRepository repo;
    private final Entitlements entitlements;
    private final ActorDirectory actors;
    private final AuditService audit;
    private final EventPublisher events;
    private final ObjectMapper json;
    private final Clock clock;
    private volatile Snapshot cache;

    SpecialtyService(SpecialtyRepository repo, Entitlements entitlements, ActorDirectory actors, AuditService audit,
                     EventPublisher events, ObjectMapper json, Clock clock) {
        this.repo = repo;
        this.entitlements = entitlements;
        this.actors = actors;
        this.audit = audit;
        this.events = events;
        this.json = json;
        this.clock = clock;
    }

    /** The registry, cached 60 s (it changes only by migration, which bumps config_version). No RLS (reference data). */
    @Transactional(readOnly = true)
    List<Profile> registry() {
        Snapshot s = cache;
        if (s != null && clock.millis() - s.loadedAt() < TTL_MILLIS) return s.profiles();
        List<Profile> loaded = repo.profiles().stream().map(r -> new Profile(r.code(),
                json.readValue(r.nameJson(), new TypeReference<Map<String, String>>() { }),
                json.readValue(r.configJson(), new TypeReference<Map<String, Object>>() { }), r.configVersion())).toList();
        cache = new Snapshot(loaded, clock.millis());
        return loaded;
    }

    /** Next read reloads the registry (tests that add a profile the way a migration would). */
    void evictRegistry() {
        cache = null;
    }

    Optional<Profile> profile(String code) {
        return registry().stream().filter(p -> p.code().equals(code)).findFirst();
    }

    /** True when the module is a registered specialty profile's module. */
    boolean isProfileModule(String module) {
        return registry().stream().anyMatch(p -> p.module().equals(module));
    }

    boolean entitled(String module) {
        return entitlements.has(module);
    }

    @Transactional(readOnly = true)
    public ProfileList profiles() {
        return new ProfileList(registry().stream()
                .map(p -> new ProfileItem(p.code(), p.name(), entitled(p.module()), p.configVersion())).toList());
    }

    @Transactional(readOnly = true)
    public Assignment assignment(UUID tenantId, UUID staffId) {
        actors.findActor(tenantId, staffId).orElseThrow(ApiException::notFound);
        return read(tenantId, staffId);
    }

    @Transactional
    public Assignment assign(UUID tenantId, UUID callerId, UUID staffId, String primary, String secondary, String ip) {
        actors.findActor(tenantId, staffId).orElseThrow(ApiException::notFound);
        if (primary.equals(secondary)) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "same-profile", "SAME_PROFILE",
                    "The secondary profile must differ from the primary one.", Map.of("code", "SAME_PROFILE"));
        }
        for (String code : secondary == null ? List.of(primary) : List.of(primary, secondary)) {
            Profile p = profile(code).orElseThrow(() -> new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "unknown-profile",
                    "UNKNOWN_PROFILE", "No specialty profile has this code.", Map.of("code", "UNKNOWN_PROFILE")));
            if (!entitled(p.module())) {
                throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "not-entitled", "NOT_ENTITLED",
                        "Your clinic's plan does not include the " + p.name().get("en") + " profile. Ask Nabd to add it to your plan.",
                        Map.of("code", "NOT_ENTITLED", "profile", code));
            }
        }
        repo.lock(staffId);
        Assignment before = read(tenantId, staffId);
        Assignment after = new Assignment(primary, secondary);
        if (before.equals(after)) return after; // idempotent repeat: no audit, no event
        repo.replace(tenantId, staffId, primary, secondary, callerId);
        ActorDirectory.Actor actor = actors.findActor(tenantId, callerId).orElseThrow(ApiException::notFound);
        audit.record(tenantId, "staff", callerId, actor.name(), actor.role(), ip, "specialty.profile_assigned",
                "staff_specialty_profiles", staffId, snapshot(staffId, before), snapshot(staffId, after));
        Map<String, Object> data = new HashMap<>();
        data.put("staffId", staffId);
        data.put("primary", primary);
        data.put("secondary", secondary);
        events.publish(tenantId, DomainEventType.SPECIALTY_PROFILE_ASSIGNED, staffId, data);
        return after;
    }

    @Transactional(readOnly = true)
    public Me me(UUID tenantId, UUID staffId) {
        Assignment a = read(tenantId, staffId);
        Profile p = a.primary() == null ? null : profile(a.primary()).orElse(null);
        if (p == null) return new Me(null, null, null, null, false, null);
        return new Me(a.primary(), a.secondary(), p.name(), p.configVersion(), entitled(p.module()), p.config());
    }

    @Transactional(readOnly = true) // private: no proxy; every caller is transactional (PLT-019 rule)
    private Assignment read(UUID tenantId, UUID staffId) {
        String primary = null, secondary = null;
        for (SpecialtyRepository.AssignmentRow r : repo.assignment(tenantId, staffId)) {
            if (r.primary()) primary = r.code();
            else secondary = r.code();
        }
        return new Assignment(primary, secondary);
    }

    private static Map<String, Object> snapshot(UUID staffId, Assignment a) {
        Map<String, Object> m = new HashMap<>();
        m.put("staffId", staffId);
        m.put("primary", a.primary());
        m.put("secondary", a.secondary());
        return m;
    }
}
