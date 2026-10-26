package com.nabd.hms.queue;

import com.nabd.hms.common.ApiException;
import com.nabd.hms.common.AuditService;
import com.nabd.hms.common.ClinicClock;
import com.nabd.hms.common.OpaqueTokens;
import com.nabd.hms.common.TenantContext;
import com.nabd.hms.common.TenantScope;
import com.nabd.hms.iam.api.ActorDirectory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * ACC-023: pairing codes (6 digits, 10 min, one use), device tokens (opaque, SHA-256 at rest, never in a URL) and the tokens-only lobby
 * projection. The projection never reads a patient name or phone: the board queries it uses return them, this class drops them.
 */
@Service
public class DisplayService {

    static final Duration CODE_TTL = Duration.ofMinutes(10);
    static final Duration LOCK_FOR = Duration.ofMinutes(15);
    static final int MAX_WRONG_CODES = 5;
    private static final SecureRandom RANDOM = new SecureRandom();

    public record Scope(List<UUID> doctorIds, UUID departmentId) {
    }

    /** The only thing a TV is ever sent about a doctor: name, tokens, a delay notice shaped by the doctor's display mode. */
    public record LobbyDoctor(String name, Integer nowServing, List<Integer> next, Integer delayMinutes) {
    }

    public record Projection(long v, List<String> languages, List<LobbyDoctor> doctors) {
    }

    /** A paired TV, as the hub holds it. */
    record Device(UUID tenantId, UUID deviceId, Scope scope) {
    }

    sealed interface PairResult {
        record Paired(String deviceToken, Scope scope) implements PairResult {
        }

        record Invalid() implements PairResult {
        }

        record Locked(long retryAfterSeconds) implements PairResult {
        }
    }

    private final DisplayRepository repo;
    private final QueueBoardRepository board;
    private final EtaDisplayModes modes;
    private final TenantScope tenants;
    private final TenantContext tenantContext;
    private final ClinicClock clock;
    private final AuditService audit;
    private final ActorDirectory actors;
    private final ObjectMapper json;

    DisplayService(DisplayRepository repo, QueueBoardRepository board, EtaDisplayModes modes, TenantScope tenants, TenantContext tenantContext,
                   ClinicClock clock, AuditService audit, ActorDirectory actors, ObjectMapper json) {
        this.repo = repo;
        this.board = board;
        this.modes = modes;
        this.tenants = tenants;
        this.tenantContext = tenantContext;
        this.clock = clock;
        this.audit = audit;
        this.actors = actors;
        this.json = json;
    }

    // ---- staff side -------------------------------------------------------------------------------------------------------------------

    public record CreatedCode(String code, Instant expiresAt, String clinic) {
    }

    @Transactional
    public CreatedCode createCode(UUID staffId, Scope scope, String label) {
        UUID tenantId = tenantContext.current();
        Instant now = clock.now();
        String code;
        String hash;
        do { // a code is unique among this clinic's live codes
            code = String.format("%06d", RANDOM.nextInt(1_000_000));
            hash = codeHash(tenantId, code);
        } while (repo.activeCodeExists(tenantId, hash, now));
        Instant expires = now.plus(CODE_TTL);
        repo.insertCode(tenantId, hash, toJson(scope), label, expires, staffId);
        var actor = actors.findActor(tenantId, staffId).orElseThrow();
        audit.record(tenantId, "staff", staffId, actor.name(), actor.role(), null, "display.pairing_code_created", "display_device", null,
                null, Map.of("label", label));
        return new CreatedCode(code, expires, repo.slugOf(tenantId));
    }

    @Transactional
    public List<DisplayRepository.Device> devices() {
        return repo.devices(tenantContext.current());
    }

    Scope scopeOf(DisplayRepository.Device d) {
        return fromJson(d.scope());
    }

    @Transactional
    public void unpair(UUID staffId, UUID deviceId) {
        UUID tenantId = tenantContext.current();
        var device = repo.device(tenantId, deviceId).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "not-found", "Not found", "No such display."));
        if (repo.revoke(tenantId, deviceId, clock.now()) == 1) {
            var actor = actors.findActor(tenantId, staffId).orElseThrow();
            audit.record(tenantId, "staff", staffId, actor.name(), actor.role(), null, "display.device_unpaired", "display_device", deviceId,
                    Map.of("label", device.label()), null);
        }
    }

    // ---- the TV's side ----------------------------------------------------------------------------------------------------------------

    /** Not transactional on purpose: the wrong-code counter must commit even though the caller then answers 400. */
    PairResult pair(String clinicSlug, String code) {
        UUID tenantId = repo.tenantBySlug(clinicSlug == null ? "" : clinicSlug.trim()).orElse(null);
        if (tenantId == null || code == null || !code.matches("\\d{6}")) {
            return new PairResult.Invalid(); // same answer as a wrong code: nothing to enumerate
        }
        return tenants.run(tenantId, () -> {
            Instant now = clock.now();
            var locked = repo.lockedUntil(tenantId).filter(now::isBefore);
            if (locked.isPresent()) {
                return new PairResult.Locked(Math.max(1, Duration.between(now, locked.get()).toSeconds()));
            }
            var pairing = repo.consumeCode(tenantId, codeHash(tenantId, code), now);
            if (pairing.isEmpty()) {
                repo.recordFailure(tenantId, MAX_WRONG_CODES, now.plus(LOCK_FOR));
                return new PairResult.Invalid();
            }
            repo.clearFailures(tenantId);
            UUID deviceId = UUID.randomUUID();
            String secret = OpaqueTokens.generate();
            repo.insertDevice(tenantId, deviceId, pairing.get().label(), pairing.get().scope(), OpaqueTokens.sha256Hex(secret), pairing.get().createdBy(), now);
            return new PairResult.Paired(tenantId + "." + deviceId + "." + secret, fromJson(pairing.get().scope()));
        });
    }

    /** token = tenantId.deviceId.secret. 401 DEVICE_REVOKED for an unpaired TV, 401 INVALID_DEVICE for anything else. */
    Device authenticate(String token) {
        String[] parts = token == null ? new String[0] : token.split("\\.", 3);
        UUID tenantId;
        UUID deviceId;
        try {
            tenantId = UUID.fromString(parts[0]);
            deviceId = UUID.fromString(parts[1]);
        } catch (RuntimeException e) {
            throw invalid("INVALID_DEVICE");
        }
        String secret = parts[2];
        return tenants.run(tenantId, () -> {
            var device = repo.device(tenantId, deviceId).orElseThrow(() -> invalid("INVALID_DEVICE"));
            if (!MessageDigest.isEqual(OpaqueTokens.sha256Hex(secret).getBytes(), device.tokenHash().getBytes())) {
                throw invalid("INVALID_DEVICE");
            }
            if (device.revokedAt() != null) {
                throw invalid("DEVICE_REVOKED");
            }
            return new Device(tenantId, deviceId, fromJson(device.scope()));
        });
    }

    /** Runs in the tenant's transaction: which of these devices are still paired (and note that they were seen). */
    @org.springframework.transaction.annotation.Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
    List<UUID> stillPaired(List<UUID> deviceIds, boolean touch) {
        UUID tenantId = tenantContext.current();
        List<UUID> live = repo.live(tenantId, deviceIds);
        if (touch && !live.isEmpty()) {
            repo.touch(tenantId, live, clock.now());
        }
        return live;
    }

    /** The doctors in this scope for today as a lobby sees them. Runs in the tenant's transaction. */
    @org.springframework.transaction.annotation.Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
    List<LobbyDoctor> lobby(Scope scope, Map<String, String> modeCache) {
        UUID tenantId = tenantContext.current();
        LocalDate day = clock.today(tenantId);
        var zone = clock.zone(tenantId);
        var delays = board.activeDelays(tenantId, day);
        var entries = board.entries(tenantId, day, scope.departmentId());
        List<LobbyDoctor> out = new ArrayList<>();
        for (var d : board.doctors(tenantId, day, scope.departmentId())) {
            if (!scope.doctorIds().isEmpty() && !scope.doctorIds().contains(d.id())) {
                continue;
            }
            Integer nowServing = null;
            List<Integer> next = new ArrayList<>();
            for (var e : entries) {
                if (!e.doctorId().equals(d.id())) {
                    continue;
                }
                if ("in_consult".equals(e.status())) {
                    nowServing = nowServing == null ? e.token() : Math.min(nowServing, e.token());
                } else if (!"checkout_pending".equals(e.status()) && next.size() < 3) {
                    next.add(e.token());
                }
            }
            String mode = modeCache.computeIfAbsent(d.id().toString(), k -> modes.resolve(d.id()).mode());
            Integer delay = PatientEtaView.shape(mode, new PatientEtaView.Input(null, null, null, delays.get(d.id())), zone).delayMinutes();
            out.add(new LobbyDoctor(d.name(), nowServing, next, delay));
        }
        return out;
    }

    @org.springframework.transaction.annotation.Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
    List<String> languages() {
        return repo.languages(tenantContext.current());
    }

    // ---- helpers ----------------------------------------------------------------------------------------------------------------------

    private static String codeHash(UUID tenantId, String code) {
        return OpaqueTokens.sha256Hex(tenantId + ":" + code);
    }

    private ApiException invalid(String code) {
        return new ApiException(HttpStatus.UNAUTHORIZED, code.toLowerCase().replace('_', '-'), "Display not paired", "This screen is not paired.", Map.of("code", code));
    }

    private String toJson(Scope scope) {
        return json.writeValueAsString(scope == null ? new Scope(List.of(), null) : scope);
    }

    private Scope fromJson(String s) {
        Scope v = json.readValue(s, Scope.class);
        return new Scope(v.doctorIds() == null ? List.of() : v.doctorIds(), v.departmentId());
    }
}
