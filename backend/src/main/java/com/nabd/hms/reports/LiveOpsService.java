package com.nabd.hms.reports;

import com.nabd.hms.common.ApiException;
import com.nabd.hms.common.AuditService;
import com.nabd.hms.common.ClinicClock;
import com.nabd.hms.common.DomainEventType;
import com.nabd.hms.common.EventPublisher;
import com.nabd.hms.common.TenantScope;
import com.nabd.hms.iam.api.ActorDirectory;
import com.nabd.hms.pharmacy.api.DispensingWait;
import com.nabd.hms.queue.api.LiveBoard;
import com.nabd.hms.reports.dto.LiveOpsResponse;
import com.nabd.hms.reports.dto.LiveOpsResponse.LiveAlert;
import com.nabd.hms.reports.dto.LiveOpsResponse.LiveDoctor;
import com.nabd.hms.reports.dto.LiveOpsResponse.LiveStation;
import com.nabd.hms.reports.dto.OpsThresholds;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * ADM-042: the owner's live operations board. The board is read through the owning modules' APIs (queue.api.LiveBoard,
 * pharmacy.api.DispensingWait: a handful of indexed statements on one clinic day) and the thresholds from this module's one table;
 * nothing is cached, so a new threshold applies from the next read or evaluation. evaluate() is the alert pass: an alert that is not
 * yet in the clinic's active set in Valkey ({@code t:{tid}:ops:alerts}, SADD = one raise across pods) publishes
 * reporting.ops_alert.raised.v1, which the sse-fanout consumer pushes on the 'ops' topic; an alert that cleared leaves the set and
 * can be raised again.
 */
@Service
public class LiveOpsService {

    private static final int MAX_REQUESTS = 50;
    private static final Duration ACTIVE_TTL = Duration.ofHours(12);
    /** A prescription left open for longer than this is no longer a patient at the counter (an entry stays valid for days). Rolling, so a wait across midnight still counts. */
    private static final Duration RX_WINDOW = Duration.ofHours(12);

    private final LiveBoard board;
    private final DispensingWait pharmacy;
    private final ActorDirectory actors;
    private final ClinicClock clock;
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final AuditService audit;
    private final EventPublisher events;
    private final TenantScope tenantScope;
    private final ObjectProvider<StringRedisTemplate> valkey;

    LiveOpsService(LiveBoard board, DispensingWait pharmacy, ActorDirectory actors, ClinicClock clock, JdbcTemplate jdbc, ObjectMapper json,
                   AuditService audit, EventPublisher events, TenantScope tenantScope, ObjectProvider<StringRedisTemplate> valkey) {
        this.board = board;
        this.pharmacy = pharmacy;
        this.actors = actors;
        this.clock = clock;
        this.jdbc = jdbc;
        this.json = json;
        this.audit = audit;
        this.events = events;
        this.tenantScope = tenantScope;
        this.valkey = valkey;
    }

    @Transactional(readOnly = true)
    public LiveOpsResponse live(UUID tenantId, UUID facility) {
        if (facility != null && !facility.equals(tenantId)) {
            throw ApiException.notFound(); // a session works in one facility and cannot read another one
        }
        return snapshot(tenantId);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    LiveOpsResponse snapshot(UUID tenantId) {
        Instant now = clock.now();
        OpsThresholds t = thresholds(tenantId);
        List<LiveBoard.Doctor> doctors = board.doctors(tenantId, now);
        Map<UUID, String> names = new HashMap<>();
        actors.findActors(tenantId, doctors.stream().map(LiveBoard.Doctor::id).toList()).forEach((id, a) -> names.put(id, a.name()));
        DispensingWait.Open rx = pharmacy.openSince(tenantId, now.minus(RX_WINDOW));
        List<LiveBoard.OpenRequest> requests = board.openRequests(tenantId, MAX_REQUESTS);
        List<LiveDoctor> rows = doctors.stream()
                .map(d -> new LiveDoctor(d.id(), names.get(d.id()), d.status(), d.waiting(), d.booked(), d.delayMin(), OpsAlerts.minutes(d.waitingSince(), now), d.told()))
                .sorted(Comparator.comparing(LiveDoctor::delayMin).reversed().thenComparing(d -> d.name() == null ? "" : d.name()).thenComparing(LiveDoctor::id))
                .toList();
        return new LiveOpsResponse(now, t, rows, new LiveStation(rx.open(), OpsAlerts.minutes(rx.oldestSince(), now)),
                new LiveStation(requests.size(), requests.isEmpty() ? null : OpsAlerts.minutes(requests.get(0).since(), now)), null,
                OpsAlerts.evaluate(t, doctors, names, rx, requests, now));
    }

    /** The clinic's thresholds; a missing row or field falls back to the product default. */
    @Transactional(propagation = Propagation.MANDATORY)
    OpsThresholds thresholds(UUID tenantId) {
        OpsThresholds d = OpsThresholds.DEFAULTS;
        return jdbc.query("SELECT thresholds::text FROM ops_alert_thresholds WHERE tenant_id = ?", (rs, i) -> {
            JsonNode n = json.readTree(rs.getString(1));
            return new OpsThresholds(n.path("pharmacyWaitMin").asInt(d.pharmacyWaitMin()), n.path("labTatBreach").asBoolean(d.labTatBreach()),
                    n.path("requestWaitMin").asInt(d.requestWaitMin()), n.path("doctorLateMin").asInt(d.doctorLateMin()));
        }, tenantId).stream().findFirst().orElse(d);
    }

    @Transactional
    public OpsThresholds updateThresholds(UUID tenantId, UUID staffId, String ip, OpsThresholds req) {
        ActorDirectory.Actor actor = actors.findActor(tenantId, staffId).orElseThrow(ApiException::notFound);
        OpsThresholds before = thresholds(tenantId);
        jdbc.update("INSERT INTO ops_alert_thresholds (tenant_id, thresholds) VALUES (?, ?::jsonb) "
                + "ON CONFLICT (tenant_id) DO UPDATE SET thresholds = EXCLUDED.thresholds, updated_at = now()", tenantId, json.writeValueAsString(req));
        audit.record(tenantId, "staff", staffId, actor.name(), actor.role(), ip, "ops_alert_thresholds.updated", "ops_alert_thresholds", tenantId, before, req);
        return req;
    }

    /**
     * One alert pass for a clinic that has a live board open. @return how many alerts were raised (0 without Valkey: nothing can be
     * pushed then, and the board still reads live on request).
     */
    public int evaluate(UUID tenantId) {
        StringRedisTemplate v = valkey.getIfAvailable();
        if (v == null) {
            return 0;
        }
        String key = "t:" + tenantId + ":ops:alerts";
        List<String> added = new ArrayList<>();
        try {
            return tenantScope.run(tenantId, () -> {
                List<LiveAlert> alerts = snapshot(tenantId).alerts();
                Set<String> current = alerts.stream().map(OpsAlerts::key).collect(Collectors.toSet());
                Set<String> active = v.opsForSet().members(key);
                if (active != null) {
                    active.stream().filter(k -> !current.contains(k)).forEach(k -> v.opsForSet().remove(key, k)); // cleared: may be raised again
                }
                for (LiveAlert a : alerts) {
                    if (Long.valueOf(1).equals(v.opsForSet().add(key, OpsAlerts.key(a)))) { // of two pods on the same clinic only one adds it
                        added.add(OpsAlerts.key(a));
                        events.publish(tenantId, DomainEventType.REPORTING_OPS_ALERT_RAISED, a.doctorId() == null ? tenantId : a.doctorId(),
                                Map.of("code", a.code(), "severity", a.severity()));
                    }
                }
                v.expire(key, ACTIVE_TTL);
                return added.size();
            });
        } catch (RuntimeException e) {
            if (!added.isEmpty()) {
                v.opsForSet().remove(key, added.toArray()); // the events rolled back with the transaction: raise them on the next pass
            }
            throw e;
        }
    }
}
