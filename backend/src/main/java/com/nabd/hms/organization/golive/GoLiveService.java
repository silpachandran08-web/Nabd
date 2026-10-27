package com.nabd.hms.organization.golive;

import com.nabd.hms.common.ApiException;
import com.nabd.hms.common.AuditService;
import com.nabd.hms.common.DomainEventType;
import com.nabd.hms.common.EventPublisher;
import com.nabd.hms.common.TenantContext;
import com.nabd.hms.common.TenantLifecyclePort;
import com.nabd.hms.iam.api.ActorDirectory;
import com.nabd.hms.organization.api.GoLiveCheck;
import com.nabd.hms.organization.golive.dto.GoLiveChecksResponse;
import com.nabd.hms.organization.golive.dto.GoLiveResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * ADM-011: go-live check list (blockers vs good to have, evaluated server-side from the checks every module registers) and the
 * go-live transition. A blocker is something that stops patients being served; everything else is listed, never enforced.
 */
@Service
public class GoLiveService {

    private final List<GoLiveCheck> checks;
    private final JdbcTemplate jdbc;
    private final TenantContext tenantContext;
    private final ActorDirectory actors;
    private final TenantLifecyclePort lifecycle;
    private final AuditService audit;
    private final EventPublisher events;
    private final String bookingBaseUrl;

    GoLiveService(List<GoLiveCheck> checks, JdbcTemplate jdbc, TenantContext tenantContext, ActorDirectory actors, TenantLifecyclePort lifecycle,
                  AuditService audit, EventPublisher events, @Value("${app.booking.base-url:}") String bookingBaseUrl) {
        this.checks = checks.stream().sorted(Comparator.comparingInt(GoLiveCheck::order)).toList();
        this.jdbc = jdbc;
        this.tenantContext = tenantContext;
        this.actors = actors;
        this.lifecycle = lifecycle;
        this.audit = audit;
        this.events = events;
        this.bookingBaseUrl = bookingBaseUrl;
    }

    @Transactional
    public GoLiveChecksResponse checks() {
        var blockers = new java.util.ArrayList<GoLiveChecksResponse.Blocker>();
        var nice = new java.util.ArrayList<GoLiveChecksResponse.NiceToHave>();
        for (GoLiveCheck c : checks) {
            GoLiveCheck.Result r = c.evaluate();
            if (r == null) {
                continue;
            }
            if (c.blocker()) {
                blockers.add(new GoLiveChecksResponse.Blocker(r.code(), r.ok(), r.fixUrl()));
            } else {
                nice.add(new GoLiveChecksResponse.NiceToHave(r.code(), r.ok(), r.note()));
            }
        }
        return new GoLiveChecksResponse(blockers, nice);
    }

    @Transactional
    public GoLiveResponse goLive(UUID staffId, String ip) {
        UUID tenantId = tenantContext.current();
        ActorDirectory.Actor owner = actors.findActor(tenantId, staffId).filter(a -> "Owner".equals(a.role()))
                .orElseThrow(() -> new ApiException(HttpStatus.FORBIDDEN, "NOT_OWNER", "Owner only", "Only the clinic owner can take the clinic live."));
        String status = jdbc.queryForObject("SELECT status FROM tenants WHERE id = ?", String.class, tenantId);
        Instant liveAt = liveAt();
        if (liveAt != null && "active".equals(status)) { // a repeat: same answer, nothing written
            return response(liveAt);
        }
        List<String> open = checks().blockers().stream().filter(b -> !b.ok()).map(GoLiveChecksResponse.Blocker::code).toList();
        if (!open.isEmpty()) {
            throw new ApiException(HttpStatus.CONFLICT, "BLOCKERS_OPEN", "Not ready to go live", "Fix the open items first.", Map.of("code", "BLOCKERS_OPEN", "blockers", open));
        }
        if ("provisioning".equals(status)) {
            lifecycle.transitionBy("trialing", staffId, "go live");
            status = "trialing";
        }
        if (!"active".equals(status)) {
            lifecycle.transitionBy("active", staffId, "owner went live");
        }
        lifecycle.markLive(staffId);
        jdbc.update("INSERT INTO clinic_setup_progress (tenant_id, step, status, done_at) VALUES (?, 'go_live', 'done', now()) " +
                "ON CONFLICT (tenant_id, step) DO UPDATE SET status = 'done', done_at = now(), skipped_at = NULL, updated_at = now()", tenantId);
        liveAt = liveAt();
        audit.record(tenantId, "staff", staffId, owner.name(), owner.role(), ip, "setup.went_live", "tenant", tenantId, null, Map.of("liveAt", liveAt.toString()));
        events.publish(tenantId, DomainEventType.ORGANIZATION_CONFIG_CHANGED, tenantId, Map.of("configKey", "tenant.went_live", "status", "updated"));
        return response(liveAt);
    }

    /** The printable poster of a live clinic (404 before go-live: there is no link to print yet). */
    @Transactional
    public byte[] poster() {
        UUID tenantId = tenantContext.current();
        Instant liveAt = liveAt();
        if (liveAt == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "not-live", "Not live yet", "The booking poster is available once the clinic is live.");
        }
        String name = jdbc.queryForObject("SELECT name FROM tenants WHERE id = ?", String.class, tenantId);
        String url = bookingUrl();
        return PosterPdf.render(name, url, QrCode.encode(url));
    }

    private GoLiveResponse response(Instant liveAt) {
        return new GoLiveResponse(liveAt, bookingUrl(), "/v1/setup/go-live/poster");
    }

    /** shortcut: the public booking page itself belongs to the patient edge; its address is app.booking.base-url + /b/{slug}. */
    private String bookingUrl() {
        return bookingBaseUrl + "/b/" + jdbc.queryForObject("SELECT slug FROM tenants WHERE id = ?", String.class, tenantContext.current());
    }

    private Instant liveAt() {
        Timestamp t = jdbc.queryForObject("SELECT setup_completed_at FROM tenants WHERE id = ?", Timestamp.class, tenantContext.current());
        return t == null ? null : t.toInstant();
    }
}
