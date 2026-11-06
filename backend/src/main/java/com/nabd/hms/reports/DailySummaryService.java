package com.nabd.hms.reports;

import com.nabd.hms.common.ApiException;
import com.nabd.hms.common.AuditService;
import com.nabd.hms.common.ClinicClock;
import com.nabd.hms.common.TenantScope;
import com.nabd.hms.iam.api.ActorDirectory;
import com.nabd.hms.organization.api.RegionRules;
import com.nabd.hms.reports.dto.DailySummaryPreviewResponse;
import com.nabd.hms.reports.dto.DailySummarySettingsRequest;
import com.nabd.hms.reports.dto.DailySummarySettingsResponse;
import com.nabd.hms.shared.notify.NotificationPort;
import com.nabd.hms.shared.notify.NotificationPurpose;
import com.nabd.hms.shared.notify.NotificationRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * ADM-043: the owner's daily WhatsApp summary. Built from the OPS-010 rollup of the clinic day plus the live rows the rollup
 * does not carry (average wait, unbilled consultations), sent through the COM-004 notification port as the utility template
 * daily_summary (4 slots: day, figures, attention, link). Due at the clinic's close: the last working-hours end + 30 min
 * (or the owner's HH:mm), or the day close if that comes first; a stale rollup is waited for up to 60 min, then the message
 * goes out marked partial. Counts, amounts and clinic names only: never a patient name, phone or diagnosis.
 */
@Service
public class DailySummaryService {

    static final String OWNER_ROLE = "Owner"; // the built-in role (RefundService.OWNER_ROLE)
    static final Duration AFTER_CLOSE = Duration.ofMinutes(30);
    static final Duration ROLLUP_GRACE = Duration.ofMinutes(60);
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("EEE d MMM yyyy", Locale.ENGLISH);
    private static final Logger log = LoggerFactory.getLogger(DailySummaryService.class);

    /** What the scheduler decided for one clinic: the day to report, the clinics to cover and who gets it. */
    record Plan(LocalDate day, List<DailySummaryRepository.Branch> branches, List<UUID> recipients) {
    }

    private final DailySummaryRepository repo;
    private final ClinicClock clock;
    private final RegionRules regions;
    private final ActorDirectory actors;
    private final NotificationPort notifications;
    private final AuditService auditService;
    private final TenantScope tenantScope;
    private final ObjectMapper json;
    private final String overviewUrl;

    DailySummaryService(DailySummaryRepository repo, ClinicClock clock, RegionRules regions, ActorDirectory actors,
                        NotificationPort notifications, AuditService auditService, TenantScope tenantScope, ObjectMapper json,
                        @Value("${app.frontend-base-url}") String frontendBaseUrl) {
        this.repo = repo;
        this.clock = clock;
        this.regions = regions;
        this.actors = actors;
        this.notifications = notifications;
        this.auditService = auditService;
        this.tenantScope = tenantScope;
        this.json = json;
        this.overviewUrl = frontendBaseUrl.replaceAll("/+$", "") + "/overview";
    }

    // ---- settings (request transaction, tenant bound from the token: PLT-019) ----

    @Transactional(readOnly = true)
    public DailySummarySettingsResponse settings(UUID tenantId, UUID staffId) {
        requireOwner(tenantId, staffId);
        return toResponse(tenantId, repo.settings(tenantId));
    }

    @Transactional
    public DailySummarySettingsResponse update(UUID tenantId, UUID staffId, DailySummarySettingsRequest req) {
        ActorDirectory.Actor actor = requireOwner(tenantId, staffId);
        List<UUID> owners = actors.activeOwners(tenantId).stream().map(ActorDirectory.OwnerContact::id).toList();
        List<UUID> recipients = req.recipients().stream().map(DailySummarySettingsResponse.DailySummaryRecipient::staffId).distinct().toList();
        if (!owners.containsAll(recipients)) { // the summary carries the clinic's money: owners only
            throw new ApiException(HttpStatus.BAD_REQUEST, "recipient-not-owner", "DailySummaryRecipient must be an owner",
                    "Only active staff on the Owner role can receive the daily summary.");
        }
        String sendAt = req.sendAt() == null ? "close" : req.sendAt();
        DailySummaryRepository.Settings before = repo.settings(tenantId);
        repo.upsertSettings(tenantId, req.enabled(), req.combined(), recipients, sendAt, staffId);
        auditService.record(tenantId, "staff", staffId, actor.name(), actor.role(), null, "daily_summary.settings", "daily_summary_settings", tenantId,
                Map.of("enabled", before.enabled(), "combined", before.combined(), "recipients", before.recipients(), "sendAt", before.sendAt()),
                Map.of("enabled", req.enabled(), "combined", req.combined(), "recipients", recipients, "sendAt", sendAt));
        return toResponse(tenantId, repo.settings(tenantId));
    }

    /** Today's message for this clinic alone (the combined message adds the sibling clinics at send time). */
    @Transactional(readOnly = true)
    public DailySummaryPreviewResponse preview(UUID tenantId, UUID staffId) {
        requireOwner(tenantId, staffId);
        LocalDate day = clock.clinicDate(tenantId);
        DailySummaryRepository.Figures f = figures(new DailySummaryRepository.Branch(tenantId, ""), day);
        return new DailySummaryPreviewResponse(day, !f.fresh(), params(day, List.of(f), !f.fresh()));
    }

    private ActorDirectory.Actor requireOwner(UUID tenantId, UUID staffId) {
        ActorDirectory.Actor actor = actors.findActor(tenantId, staffId).orElse(null);
        if (actor == null || !OWNER_ROLE.equals(actor.role())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "owner-required", "Owner only", "The daily summary is for the clinic owner.",
                    Map.of("code", "OWNER_REQUIRED"));
        }
        return actor;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    DailySummarySettingsResponse toResponse(UUID tenantId, DailySummaryRepository.Settings s) {
        List<ActorDirectory.OwnerContact> owners = actors.activeOwners(tenantId);
        Map<UUID, ActorDirectory.Actor> names = actors.findActors(tenantId, owners.stream().map(ActorDirectory.OwnerContact::id).toList());
        List<DailySummarySettingsResponse.DailySummaryCandidate> candidates = owners.stream().map(o -> new DailySummarySettingsResponse.DailySummaryCandidate(o.id(),
                names.containsKey(o.id()) ? names.get(o.id()).name() : "", o.mobilePhone() != null && !o.mobilePhone().isBlank())).toList();
        return new DailySummarySettingsResponse(s.enabled(), s.combined(), s.recipients().stream().map(DailySummarySettingsResponse.DailySummaryRecipient::new).toList(),
                s.sendAt(), candidates, repo.branches(tenantId).size());
    }

    // ---- scheduler path (TenantScope per clinic: a sibling clinic's figures are read in its own transaction). The MANDATORY
    // helpers below run inside the caller's TenantScope/request transaction (self-invocation: the annotation documents the contract). ----

    /** @return true when the clinic's summary went out on this run */
    public boolean sendIfDue(UUID tenantId) {
        Plan plan = tenantScope.run(tenantId, () -> plan(tenantId));
        if (plan == null) {
            return false;
        }
        List<DailySummaryRepository.Figures> figures = new ArrayList<>();
        for (DailySummaryRepository.Branch b : plan.branches()) {
            figures.add(tenantScope.run(b.id(), () -> figures(b, plan.day())));
        }
        return tenantScope.run(tenantId, () -> send(tenantId, plan, figures));
    }

    /** Null when nothing is to be sent now: opted out, nobody to send to, already sent today, not yet due, or waiting for the rollup. */
    @Transactional(propagation = Propagation.MANDATORY)
    Plan plan(UUID tenantId) {
        DailySummaryRepository.Settings s = repo.settings(tenantId);
        LocalDate day = clock.clinicDate(tenantId);
        if (!s.enabled() || s.recipients().isEmpty() || day.equals(s.lastSentDay())) {
            return null;
        }
        Instant now = clock.now();
        Optional<Instant> due = dueAt(tenantId, day, s.sendAt());
        if (due.isEmpty() || now.isBefore(due.get())) {
            return null;
        }
        if (now.isBefore(due.get().plus(ROLLUP_GRACE)) && !figures(new DailySummaryRepository.Branch(tenantId, ""), day).fresh()) {
            return null; // late rollup: wait, up to an hour
        }
        List<DailySummaryRepository.Branch> branches = s.combined() ? repo.branches(tenantId) : List.of(new DailySummaryRepository.Branch(tenantId, ""));
        return new Plan(day, branches, s.recipients());
    }

    /** The earlier of the day close and the configured close ('close' = last working-hours end + 30 min; none = not due). */
    @Transactional(propagation = Propagation.MANDATORY)
    Optional<Instant> dueAt(UUID tenantId, LocalDate day, String sendAt) {
        Optional<Instant> closed = repo.closedAt(tenantId, day);
        Optional<Instant> configured = "close".equals(sendAt)
                ? repo.lastSessionEndSecond(tenantId, day.getDayOfWeek().getValue() % 7).map(sec -> clock.atSecond(tenantId, day, sec).plus(AFTER_CLOSE))
                : Optional.of(clock.atSecond(tenantId, day, LocalTime.parse(sendAt).toSecondOfDay()));
        // shortcut: 'close' with no working hours that weekday never sends; fall back to a tenant-level closing time once setup has one
        if (closed.isPresent() && configured.isPresent()) {
            return Optional.of(closed.get().isBefore(configured.get()) ? closed.get() : configured.get());
        }
        return closed.isPresent() ? closed : configured;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    boolean send(UUID tenantId, Plan plan, List<DailySummaryRepository.Figures> figures) {
        boolean partial = figures.stream().anyMatch(f -> !f.fresh());
        List<String> params = params(plan.day(), figures, partial);
        boolean any = false;
        for (ActorDirectory.OwnerContact o : actors.activeOwners(tenantId)) {
            if (!plan.recipients().contains(o.id()) || o.mobilePhone() == null || o.mobilePhone().isBlank()) {
                continue;
            }
            try {
                notifications.request(new NotificationRequest(NotificationPurpose.DAILY_SUMMARY, "daily_summary", tenantId, null, o.mobilePhone(),
                        "en", params, null, null, null, "daily-summary:" + plan.day() + ":" + tenantId + ":" + o.id()));
                any = true;
            } catch (ApiException e) { // no approved template yet: tried again on the next run
                log.info("daily summary for tenant {} not sent: {}", tenantId, e.getMessage());
            }
        }
        if (any) {
            repo.markSent(tenantId, plan.day());
        }
        return any;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    DailySummaryRepository.Figures figures(DailySummaryRepository.Branch b, LocalDate day) {
        ZoneId zone = clock.zone(b.id());
        String currency = regions.region(Optional.ofNullable(clock.region(b.id())).orElse("IN")).currency();
        return repo.figures(b.id(), b.name(), currency, day, ClinicClock.startOf(day, zone), ClinicClock.startOf(day.plusDays(1), zone));
    }

    // ---- rendering: the four template slots, no newlines (Meta refuses them in a parameter) ----

    List<String> params(LocalDate day, List<DailySummaryRepository.Figures> figures, boolean partial) {
        String currency = figures.get(0).currency();
        String figuresText;
        if (figures.size() == 1) {
            figuresText = line(figures.get(0), true);
        } else {
            BigDecimal[] total = new BigDecimal[] {BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO};
            int visits = 0;
            int noShows = 0;
            StringBuilder sb = new StringBuilder();
            for (DailySummaryRepository.Figures f : figures) {
                sb.append(f.clinicName()).append(": ").append(line(f, false)).append(" | ");
                BigDecimal[] m = byMode(f);
                for (int i = 0; i < 4; i++) total[i] = total[i].add(m[i]);
                visits += f.visits();
                noShows += f.noShows();
            }
            figuresText = sb + "Total: " + visits + " visits, " + money(total[3], currency) + " (cash " + money(total[0], currency)
                    + " / UPI " + money(total[1], currency) + " / card " + money(total[2], currency) + "), " + noShows + " no-shows";
        }
        int unbilled = figures.stream().mapToInt(DailySummaryRepository.Figures::unbilled).sum();
        BigDecimal atRisk = figures.stream().map(DailySummaryRepository.Figures::unbilledAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
        List<String> attention = new ArrayList<>();
        if (unbilled > 0) {
            attention.add(unbilled + (unbilled == 1 ? " consultation done with no bill (" : " consultations done with no bill (") + money(atRisk, currency) + " at risk)");
        }
        if (figures.stream().anyMatch(f -> !f.closed())) {
            attention.add(figures.size() == 1 ? "day not closed yet" : figures.stream().filter(f -> !f.closed()).count() + " of " + figures.size() + " clinics not closed yet");
        }
        return List.of(DAY.format(day) + (partial ? " (partial: some figures may still update)" : ""), figuresText,
                attention.isEmpty() ? "Nothing needs attention" : String.join("; ", attention), overviewUrl);
    }

    private String line(DailySummaryRepository.Figures f, boolean full) {
        BigDecimal[] m = byMode(f);
        String c = f.currency();
        String wait = f.avgWaitMinutes() == null ? "no wait data" : "avg wait " + f.avgWaitMinutes() + " min";
        if (full) {
            return f.visits() + " visits (" + f.completed() + " completed), collected " + money(m[3], c) + ": cash " + money(m[0], c) + " / UPI " + money(m[1], c)
                    + " / card " + money(m[2], c) + ", " + f.noShows() + " no-shows, " + wait;
        }
        return f.visits() + " visits, " + money(m[3], c) + " collected, " + f.noShows() + " no-shows, " + wait;
    }

    /** cash, upi, card, total (total = the rollup's collected, so 'other' methods are inside it). */
    private BigDecimal[] byMode(DailySummaryRepository.Figures f) {
        JsonNode n = json.readTree(f.byMethodJson() == null ? "{}" : f.byMethodJson());
        return new BigDecimal[] {n.path("cash").asDecimal(BigDecimal.ZERO), n.path("upi").asDecimal(BigDecimal.ZERO),
                n.path("card").asDecimal(BigDecimal.ZERO), f.collected()};
    }

    private static String money(BigDecimal amount, String currency) {
        return currency + " " + amount.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }
}
