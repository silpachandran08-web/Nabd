package com.nabd.hms.platform.entitlements;

import com.nabd.hms.common.ApiException;
import com.nabd.hms.organization.api.Entitlements;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * PLT-059: the {@link Entitlements} implementation. One snapshot per clinic, cached 60 s (the "effective within 60 s"
 * guarantee on every replica) and evicted at once on the replica that made a change. Reads only master tables (no RLS),
 * so it needs no tenant transaction. Override expiry is evaluated at check time, so it is exact, not 60 s late.
 */
@Component
public class PlanEntitlements implements Entitlements {

    /** ADM-034's closed module list. */
    public static final List<String> MODULES = List.of("queue_eta", "whatsapp_booking", "emr_erx", "billing",
            "pharmacy_external", "pharmacy_inhouse", "lab", "packages", "branded_site", "hr", "insurance", "ipd",
            "patient_app", "ai_safety");
    /** Switchable features: the modules plus the kill-only WhatsApp bot. */
    public static final Set<String> FEATURES;
    static {
        Set<String> f = new HashSet<>(MODULES);
        f.add("whatsapp_bot");
        FEATURES = Set.copyOf(f);
    }

    static final long TTL_MILLIS = 60_000;

    record Ovr(String state, Instant expiresAt) {
    }

    /** modules == null: plan without entitlement rows (legacy) or no subscription = unrestricted. */
    record Snapshot(Set<String> modules, Integer seats, Integer branches, Map<String, Ovr> overrides,
                    Set<String> globalKills, Instant loadedAt) {
    }

    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final Map<UUID, Snapshot> cache = new ConcurrentHashMap<>();

    PlanEntitlements(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @Override
    public boolean has(String module) {
        return state(currentTenant(), module) == State.ENABLED;
    }

    @Override
    public boolean killed(String feature) {
        return state(currentTenant(), feature) == State.KILLED;
    }

    @Override
    public int seatLimit() {
        Integer s = snapshot(currentTenant()).seats();
        return s == null ? Integer.MAX_VALUE : s;
    }

    @Override
    public int branchLimit() {
        Integer b = snapshot(currentTenant()).branches();
        return b == null ? Integer.MAX_VALUE : b;
    }

    @Override
    public java.util.Optional<String> planUnlocking(String module) {
        return jdbc.queryForList("SELECT p.name FROM master.plans p JOIN master.plan_entitlements e " +
                "ON e.plan_id = p.id AND e.plan_version = p.version WHERE p.active AND e.module_code = ? " +
                "ORDER BY p.monthly_price_cents, p.name LIMIT 1", String.class, module).stream().findFirst();
    }

    @Override
    public Map<String, Boolean> modules() {
        UUID t = currentTenant();
        Map<String, Boolean> out = new LinkedHashMap<>();
        MODULES.forEach(m -> out.put(m, state(t, m) == State.ENABLED));
        out.put("whatsapp_bot", state(t, "whatsapp_bot") == State.ENABLED);
        return out;
    }

    enum State { ENABLED, NOT_IN_PLAN, KILLED }

    State state(UUID tenant, String feature) {
        Snapshot s = snapshot(tenant);
        if (s.globalKills().contains(feature)) return State.KILLED;
        Ovr o = s.overrides().get(feature);
        if (o != null && (o.expiresAt() == null || o.expiresAt().isAfter(clock.instant()))) {
            switch (o.state()) {
                case "kill": return State.KILLED;
                case "off": return State.NOT_IN_PLAN;
                default: return State.ENABLED;
            }
        }
        // whatsapp_bot is not a plan module: it is on unless killed
        if (s.modules() == null || "whatsapp_bot".equals(feature)) return State.ENABLED;
        return s.modules().contains(feature) ? State.ENABLED : State.NOT_IN_PLAN;
    }

    /** Throws the refusal the interceptor and services use. */
    void require(UUID tenant, String module) {
        switch (state(tenant, module)) {
            case KILLED -> throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "feature-disabled", "FEATURE_DISABLED",
                    "This feature is switched off for your clinic right now. Please contact Nabd support.");
            case NOT_IN_PLAN -> throw new ApiException(HttpStatus.FORBIDDEN, "module-not-in-plan", "MODULE_NOT_IN_PLAN",
                    "Your plan does not include this module. Existing data stays readable; ask the owner to upgrade.");
            default -> { }
        }
    }

    public void evict(UUID tenant) {
        cache.remove(tenant);
    }

    public void evictAll() {
        cache.clear();
    }

    Snapshot snapshot(UUID tenant) {
        Snapshot s = cache.get(tenant);
        if (s != null && clock.millis() - s.loadedAt().toEpochMilli() < TTL_MILLIS) return s;
        s = load(tenant);
        cache.put(tenant, s);
        return s;
    }

    private Snapshot load(UUID tenant) {
        Set<String> modules = null;
        Integer seats = null, branches = null;
        List<Map<String, Object>> sub = jdbc.queryForList(
                "SELECT s.plan_id, s.plan_version, COALESCE(pv.seat_limit, p.seat_limit) AS seats, " +
                        "COALESCE(pv.branch_limit, p.branch_limit) AS branches " +
                        "FROM master.subscriptions s JOIN master.plans p ON p.id = s.plan_id " +
                        "LEFT JOIN master.plan_versions pv ON pv.plan_id = s.plan_id AND pv.version = s.plan_version " +
                        "WHERE s.tenant_id = ?", tenant);
        if (!sub.isEmpty()) {
            Map<String, Object> r = sub.get(0);
            seats = (Integer) r.get("seats");
            branches = (Integer) r.get("branches");
            List<String> codes = jdbc.queryForList("SELECT module_code FROM master.plan_entitlements WHERE plan_id = ? AND plan_version = ?",
                    String.class, r.get("plan_id"), r.get("plan_version"));
            if (!codes.isEmpty()) modules = Set.copyOf(codes);
        }
        Map<String, Ovr> overrides = new HashMap<>();
        jdbc.query("SELECT feature_code, state, expires_at FROM master.tenant_feature_overrides WHERE tenant_id = ?",
                rs -> {
                    Timestamp e = rs.getTimestamp("expires_at");
                    overrides.put(rs.getString("feature_code"), new Ovr(rs.getString("state"), e == null ? null : e.toInstant()));
                }, tenant);
        Set<String> kills = Set.copyOf(jdbc.queryForList("SELECT feature_code FROM master.kill_switches WHERE active", String.class));
        return new Snapshot(modules, seats, branches, overrides, kills, clock.instant());
    }

    static UUID currentTenant() {
        if (SecurityContextHolder.getContext().getAuthentication() instanceof JwtAuthenticationToken t
                && t.getToken().getClaimAsString("tenantId") != null) {
            return UUID.fromString(t.getToken().getClaimAsString("tenantId"));
        }
        throw new ApiException(HttpStatus.FORBIDDEN, "clinic-session-required", "Clinic session required",
                "Entitlements are answered for a clinic session.");
    }
}
