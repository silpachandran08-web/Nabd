package com.nabd.hms.platform.activation;

import com.nabd.hms.common.ApiException;
import com.nabd.hms.common.OutboxEvent;
import com.nabd.hms.common.TenantScope;
import com.nabd.hms.iam.api.ActorDirectory;
import com.nabd.hms.organization.api.SetupProgress;
import com.nabd.hms.platform.ticket.TicketService;
import com.nabd.hms.shared.notify.NotificationPort;
import com.nabd.hms.shared.notify.NotificationPurpose;
import com.nabd.hms.shared.notify.NotificationRequest;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

/**
 * ADM-005 (SCS-03): where self-serve clinics stop in the setup wizard. The funnel table is built from the wizard's
 * organization.setup_step.changed.v1 events (idempotent on the outbox id); the operator sees counts per step and the median
 * time to go-live per sign-up week, and the clinics stuck on a step. The hourly job nudges the owner on WhatsApp after 48 h on
 * one step, at most once per clinic and step in 7 days (quiet hours and the owner's opt-out apply in messaging); the second
 * failure of the import or WhatsApp step opens a support ticket with the clinic id, the step and the error code only.
 */
@Service
public class ActivationService {

    private static final Logger log = LoggerFactory.getLogger(ActivationService.class);
    static final Duration STUCK_AFTER = Duration.ofHours(48);
    static final Duration RENUDGE_AFTER = Duration.ofDays(7);
    /** Steps whose repeated failure means the clinic needs a person (SCS-03). */
    static final Set<String> TICKET_STEPS = Set.of("import", "whatsapp");
    static final int TICKET_AFTER_FAILURES = 2;
    private static final Set<String> STATUSES = Set.of("reached", "done", "skipped", "failed");
    private static final Map<String, String> STEP_LABELS = Map.of("profile", "Clinic profile", "preset", "Clinic type", "doctors", "Doctors",
            "prices", "Prices", "whatsapp", "WhatsApp number", "tax", "Tax", "dpa", "Data processing agreement", "import", "Patient import",
            "go_live", "Go live");

    public record StepCount(String step, long reached, long completed) {
    }

    public record CohortView(LocalDate week, long signups, List<StepCount> steps, Double medianHoursToLive) {
    }

    public record Funnel(List<CohortView> cohorts) {
    }

    public record StuckItem(UUID tenantId, String step, Instant since, int nudgesSent) {
    }

    public record StuckPage(List<StuckItem> items, String nextCursor) {
    }

    private final ActivationRepository repo;
    private final TicketService tickets;
    private final TenantScope tenantScope;
    private final ActorDirectory actors;
    private final NotificationPort notifications;
    private final ObjectMapper json;
    private final Clock clock;
    private final String helpLink;

    ActivationService(ActivationRepository repo, TicketService tickets, TenantScope tenantScope, ActorDirectory actors,
                      NotificationPort notifications, ObjectMapper json, Clock clock,
                      @Value("${app.frontend-base-url:http://localhost:3000}") String frontendBaseUrl) {
        this.repo = repo;
        this.tickets = tickets;
        this.tenantScope = tenantScope;
        this.actors = actors;
        this.notifications = notifications;
        this.json = json;
        this.clock = clock;
        this.helpLink = frontendBaseUrl.replaceAll("/+$", "") + "/setup/wizard";
    }

    // ---- event consumer ------------------------------------------------------------------------------------------------

    /** One wizard step event, in the outbox handler's transaction (bound to the event's clinic). */
    @Transactional(propagation = Propagation.MANDATORY)
    public void apply(OutboxEvent e) {
        JsonNode p = json.readTree(e.payloadJson());
        String step = p.path("step").asString(), status = p.path("status").asString();
        String code = p.hasNonNull("errorCode") ? p.path("errorCode").asString() : null;
        if (!SetupProgress.STEPS.contains(step) || !STATUSES.contains(status) || (code != null && !code.matches("^[A-Z0-9_]{1,40}$"))) {
            log.warn("activation event {} ignored: unknown step or status", e.id());
            return;
        }
        repo.apply(e.tenantId(), step, status, code, e.id()).ifPresent(a -> {
            if ("failed".equals(status) && TICKET_STEPS.contains(step) && a.attempts() == TICKET_AFTER_FAILURES && a.ticketId() == null) {
                // ids and codes only: the clinic's name is joined from tenants by the console, never copied in
                var t = tickets.raiseFromSystem(e.tenantId(), "Setup help needed: " + STEP_LABELS.get(step) + " failed twice",
                        "Tenant " + e.tenantId() + ", step " + step + ", error code " + (code == null ? "none" : code)
                                + ". Offer a human-assisted setup.", "high");
                repo.setTicket(e.tenantId(), step, t.id());
                log.info("activation ticket {} opened for tenant {} step {}", t.id(), e.tenantId(), step);
            }
        });
    }

    // ---- operator reads --------------------------------------------------------------------------------------------------

    @Transactional(readOnly = true)
    public Funnel funnel(LocalDate from, LocalDate to, String market, String specialty) {
        LocalDate end = to == null ? LocalDate.now(clock) : to;
        LocalDate start = from == null ? end.minusWeeks(12) : from;
        if (start.isAfter(end)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "validation", "Validation failed", "from must not be after to.");
        }
        // platform-wide cohorts: the platform clock's calendar (UTC in production), not any one clinic's
        Instant a = start.atStartOfDay(clock.getZone()).toInstant(), b = end.plusDays(1).atStartOfDay(clock.getZone()).toInstant();
        String m = blankToNull(market), s = blankToNull(specialty);
        Map<LocalDate, Map<String, ActivationRepository.FunnelRow>> byWeek = new TreeMap<>();
        for (var r : repo.funnel(a, b, m, s)) byWeek.computeIfAbsent(r.week(), w -> new TreeMap<>()).put(r.step(), r);
        List<CohortView> out = new ArrayList<>();
        for (var c : repo.cohorts(a, b, m, s)) {
            var rows = byWeek.getOrDefault(c.week(), Map.of());
            List<StepCount> steps = SetupProgress.STEPS.stream().map(st -> {
                var r = rows.get(st);
                return new StepCount(st, r == null ? 0 : r.reached(), r == null ? 0 : r.completed());
            }).toList();
            out.add(new CohortView(c.week(), c.signups(), steps, c.medianHoursToLive() == null ? null : Math.round(c.medianHoursToLive() * 10) / 10.0));
        }
        out.sort(Comparator.comparing(CohortView::week).reversed());
        return new Funnel(out);
    }

    @Transactional(readOnly = true)
    public StuckPage stuck(int hours, String cursor, int limit) {
        if (hours < 1 || hours > 24 * 90) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "validation", "Validation failed", "hours must be between 1 and 2160.");
        }
        int n = Math.max(1, Math.min(limit, 100));
        Instant afterSince = null;
        UUID afterTenant = null;
        String afterStep = null;
        if (cursor != null && !cursor.isBlank()) {
            try {
                String[] c = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8).split("\\|", 3);
                afterSince = Instant.parse(c[0]);
                afterTenant = UUID.fromString(c[1]);
                afterStep = c[2];
            } catch (RuntimeException e) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "validation", "Validation failed", "Invalid cursor.");
            }
        }
        List<ActivationRepository.Stuck> rows = repo.stuck(clock.instant().minus(Duration.ofHours(hours)), afterSince, afterTenant, afterStep, n + 1);
        boolean more = rows.size() > n;
        List<ActivationRepository.Stuck> page = more ? rows.subList(0, n) : rows;
        String next = null;
        if (more) {
            var last = page.get(n - 1);
            next = Base64.getUrlEncoder().withoutPadding().encodeToString((last.since() + "|" + last.tenantId() + "|" + last.step()).getBytes(StandardCharsets.UTF_8));
        }
        return new StuckPage(page.stream().map(r -> new StuckItem(r.tenantId(), r.step(), r.since(), r.nudgesSent())).toList(), next);
    }

    // ---- nudge job ---------------------------------------------------------------------------------------------------------

    @Scheduled(cron = "${app.activation.nudge-cron:0 20 * * * *}")
    @SchedulerLock(name = "adm-activation-nudge", lockAtMostFor = "PT20M")
    public void scheduled() {
        runNudges();
    }

    /** @return nudges sent. One clinic failing never stops the others; a claim whose send failed is put back. Logs ids only. */
    public int runNudges() {
        Instant now = clock.instant();
        int sent = 0;
        for (var c : repo.claimNudges(now.minus(STUCK_AFTER), now.minus(RENUDGE_AFTER), 200)) {
            boolean ok = false;
            try {
                ok = tenantScope.run(c.tenantId(), () -> nudge(c));
            } catch (RuntimeException e) {
                log.warn("setup nudge for tenant {} step {} failed: {}", c.tenantId(), c.step(), e.getClass().getSimpleName());
            }
            if (ok) {
                sent++;
            } else {
                putBack(c);
            }
        }
        return sent;
    }

    @Transactional(propagation = Propagation.MANDATORY) // private: no proxy; marks that it runs inside TenantScope.run
    private boolean nudge(ActivationRepository.Claim c) {
        if (!notifications.sendable(NotificationPurpose.SETUP_NUDGE, "en")) { // no approved template yet: the claim goes back, the next run retries
            log.info("setup nudge for tenant {} not sent: no approved template", c.tenantId());
            return false;
        }
        int n = repo.nudgesSent(c.tenantId(), c.step());
        boolean any = false;
        for (ActorDirectory.OwnerContact o : actors.activeOwners(c.tenantId())) {
            if (o.mobilePhone() == null || o.mobilePhone().isBlank()) continue;
            notifications.request(new NotificationRequest(NotificationPurpose.SETUP_NUDGE, "tenant", c.tenantId(), null, o.mobilePhone(), "en",
                    List.of(STEP_LABELS.get(c.step()), helpLink), null, null, null,
                    "setup-nudge:" + c.tenantId() + ":" + c.step() + ":" + n + ":" + o.id()));
            any = true;
        }
        return any;
    }

    private void putBack(ActivationRepository.Claim c) {
        try {
            tenantScope.run(c.tenantId(), () -> repo.putBack(c.tenantId(), c.step(), c.previousNudgedAt()));
        } catch (RuntimeException e) {
            log.warn("setup nudge claim for tenant {} step {} not put back: {}", c.tenantId(), c.step(), e.getClass().getSimpleName());
        }
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
