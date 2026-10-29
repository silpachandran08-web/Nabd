package com.nabd.hms.queue;

import com.nabd.hms.common.ApiException;
import com.nabd.hms.common.AuditService;
import com.nabd.hms.common.Cursor;
import com.nabd.hms.common.TenantContext;
import com.nabd.hms.iam.api.ActorDirectory;
import com.nabd.hms.realtime.RealtimeHub;
import com.nabd.hms.realtime.RealtimeTopic;
import io.swagger.v3.oas.annotations.media.Schema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * ACC-032: the front desk's exceptions inbox. Items are opened by events (DeskExceptionProjector), by the minute sweep for states
 * that only time creates (DeskExceptionSweepJob) and directly by the self check-in; they close on their own when the underlying
 * state changes, by hand, or are snoozed. A row holds ids and codes (summary_params), never free text. The live push uses the
 * realtime 'exceptions' topic directly, after commit: no new event type (the cross-story agreement of Sprint 16).
 */
@Service
public class DeskExceptions {

    public static final String CHECKIN_FAILED = "checkin_failed";
    public static final String UNCONFIRMED = "unconfirmed_booking";
    public static final String REQUEST_SLA = "request_sla";
    public static final String CHECKOUT_UNPAID = "checkout_unpaid";
    public static final String HANDOFF = "handoff";

    private static final Logger log = LoggerFactory.getLogger(DeskExceptions.class);
    private static final Map<String, List<String>> ACTIONS = Map.of(
            CHECKIN_FAILED, List.of("confirm_arrival", "ask_patient", "resolve", "snooze"),
            UNCONFIRMED, List.of("call", "message", "resolve", "snooze"),
            REQUEST_SLA, List.of("review_request", "resolve", "snooze"),
            CHECKOUT_UNPAID, List.of("open_checkout", "resolve", "snooze"),
            HANDOFF, List.of("open_inbox", "resolve", "snooze"));

    @Schema(name = "DeskExceptionItem")
    public record Item(UUID id, String kind, String severity, String refType, UUID refId, String summary, Map<String, Object> params,
                       List<String> actions, java.time.Instant createdAt) {
    }

    @Schema(name = "DeskExceptionPage")
    public record Page(List<Item> items, String nextCursor) {
    }

    @Schema(name = "DeskExceptionActionResponse")
    public record Done(UUID id, String status) {
    }

    private final DeskExceptionRepository repo;
    private final TenantContext tenantContext;
    private final RealtimeHub hub;
    private final ObjectMapper json;
    private final ActorDirectory actors;
    private final AuditService audit;
    private final Clock clock;
    private final int checkoutPendingMinutes;

    DeskExceptions(DeskExceptionRepository repo, TenantContext tenantContext, RealtimeHub hub, ObjectMapper json, ActorDirectory actors,
                   AuditService audit, Clock clock, @Value("${app.desk-exceptions.checkout-pending-minutes:30}") int checkoutPendingMinutes) {
        this.repo = repo;
        this.tenantContext = tenantContext;
        this.hub = hub;
        this.json = json;
        this.actors = actors;
        this.audit = audit;
        this.clock = clock;
        this.checkoutPendingMinutes = checkoutPendingMinutes;
    }

    /** Opens one item (a no-op if that kind+ref is already open). Joins the caller's transaction, which is bound to this tenant. */
    @Transactional
    public void open(UUID tenant, String kind, String severity, String refType, UUID refId, Map<String, Object> params) {
        repo.open(tenant, kind, severity, refType, refId, json.writeValueAsString(params)).ifPresent(id -> push(tenant, id, kind, "open"));
    }

    /** The underlying state changed: close the open items of these kinds for this ref. */
    @Transactional
    public void resolveRef(UUID tenant, UUID refId, String... kinds) {
        repo.resolveByRef(tenant, List.of(kinds), refId).forEach(r -> push(tenant, r.id(), r.kind(), "resolved"));
    }

    @Transactional
    public void onConfirmed(UUID tenant, UUID appointmentId) {
        repo.markConfirmed(tenant, appointmentId);
        resolveRef(tenant, appointmentId, UNCONFIRMED);
    }

    @Transactional
    public Page list(String kind, String cursor, int limit) {
        if (kind != null && !ACTIONS.containsKey(kind)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "validation-failed", "Validation failed", "Unknown exception kind.");
        }
        Cursor c = cursor == null || cursor.isBlank() ? null : Cursor.decode(cursor);
        int n = Math.max(1, Math.min(limit, 100));
        List<DeskExceptionRepository.Row> rows = repo.listOpen(tenantContext.current(), kind, c == null ? null : c.createdAt(), c == null ? null : c.id(), n + 1);
        List<Item> items = rows.stream().limit(n).map(this::item).toList();
        DeskExceptionRepository.Row last = rows.size() > n ? rows.get(n - 1) : null;
        return new Page(items, last == null ? null : new Cursor(last.createdAt(), last.id()).encode());
    }

    @Transactional
    public Done act(UUID staffId, UUID id, String action, Integer snoozeMinutes) {
        if (!"resolve".equals(action) && !"snooze".equals(action)) {
            throw new ApiException(HttpStatus.NOT_FOUND, "not-found", "Not found", "The requested resource was not found.");
        }
        UUID tenant = tenantContext.current();
        var row = repo.find(tenant, id).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "not-found", "Not found", "The requested resource was not found."));
        int minutes = snoozeMinutes == null ? 30 : snoozeMinutes;
        if ("snooze".equals(action) && (minutes < 1 || minutes > 480)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "validation-failed", "Validation failed", "snoozeMinutes must be from 1 to 480.");
        }
        boolean done = "resolve".equals(action) ? repo.resolve(tenant, id, staffId) : repo.snooze(tenant, id, clock.instant().plus(Duration.ofMinutes(minutes)));
        if (!done) {
            throw new ApiException(HttpStatus.CONFLICT, "already-resolved", "Already resolved", "Someone already resolved this item.",
                    Map.of("code", "ALREADY_RESOLVED"));
        }
        var actor = actors.findActor(tenant, staffId).orElseThrow();
        audit.record(tenant, "staff", staffId, actor.name(), actor.role(), null, "desk_exception." + action, "desk_exception", id, null, Map.of("kind", row.kind()));
        String status = "resolve".equals(action) ? "resolved" : "snoozed";
        push(tenant, id, row.kind(), status);
        return new Done(id, status);
    }

    /** The minute sweep for one tenant (bound by the caller): what only time creates, then the auto-close backstop. */
    @Transactional
    public int sweep() {
        UUID tenant = tenantContext.current();
        int opened = repo.openUnconfirmed(tenant) + repo.openRequestsNearSla(tenant) + repo.openCheckoutPending(tenant, checkoutPendingMinutes);
        var closed = repo.resolveSettled(tenant);
        closed.forEach(r -> push(tenant, r.id(), r.kind(), "resolved"));
        if (opened > 0) {
            push(tenant, null, "*", "open");
        }
        return opened + closed.size();
    }

    private Item item(DeskExceptionRepository.Row r) {
        @SuppressWarnings("unchecked")
        Map<String, Object> params = json.readValue(r.params(), Map.class);
        return new Item(r.id(), r.kind(), r.severity(), r.refType(), r.refId(), r.kind(), params, ACTIONS.get(r.kind()), r.createdAt());
    }

    /** Delta on the exceptions topic once the transaction commits; clients re-read the list. A push failure never fails the write. */
    private void push(UUID tenant, UUID id, String kind, String status) {
        ObjectNode d = json.createObjectNode().put("type", "desk.exception.changed").put("id", id == null ? "sweep" : id.toString())
                .put("v", clock.millis()).put("topic", RealtimeTopic.EXCEPTIONS.wire());
        d.set("fields", json.createObjectNode().put("exceptionId", id == null ? "" : id.toString()).put("kind", kind).put("status", status));
        String delta = d.toString();
        Runnable send = () -> {
            try {
                hub.publish(tenant, RealtimeTopic.EXCEPTIONS, delta);
            } catch (RuntimeException e) {
                log.warn("exceptions push failed for tenant {}; clients refresh on reconnect", tenant);
            }
        };
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    send.run();
                }
            });
        } else {
            send.run();
        }
    }
}
