package com.nabd.hms.platform.subscriptions;

import com.nabd.hms.common.ApiException;
import com.nabd.hms.common.AuditService;
import com.nabd.hms.common.ClinicClock;
import com.nabd.hms.common.StepUpVerifier;
import com.nabd.hms.common.TenantContext;
import com.nabd.hms.common.TenantScope;
import com.nabd.hms.iam.api.ActorDirectory;
import com.nabd.hms.organization.api.SetupProgress;
import com.nabd.hms.platform.PlatformAudit;
import com.nabd.hms.platform.ticket.TicketPhiRedactor;
import com.nabd.hms.platform.ticket.TicketService;
import com.nabd.hms.shared.notify.NotificationPort;
import com.nabd.hms.shared.notify.NotificationPurpose;
import com.nabd.hms.shared.notify.NotificationRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * ADM-006 (SCS-04, CEO-21): the owner books Nabd's paid one-time white-glove setup from the wizard. A booking is an engagement on
 * the existing tenant: the fee comes from the clinic's plan in the catalogue (per market) and is waived on an annual plan
 * (reason annual_plan); a due fee becomes a 'due' Nabd invoice that the subscription billing gateway collects (external: the
 * payment link stays null until it is connected). Owner only, step-up bound to "white-glove:book" and used once, one open
 * booking per clinic. The booking opens a system support ticket: the onboarding operator names it to ask the owner for a
 * support-access grant (PLT-057) and has no other way into the clinic. Closing the engagement marks the covered wizard steps
 * done and sends the owner a WhatsApp summary.
 */
@Service
public class WhiteGloveService {

    private static final Logger log = LoggerFactory.getLogger(WhiteGloveService.class);
    public static final String STEP_UP_TARGET = "white-glove:book";
    static final Duration MAX_AHEAD = Duration.ofDays(90);
    private static final Map<String, Set<String>> NEXT = Map.of(
            "requested", Set.of("scheduled", "cancelled"),
            "scheduled", Set.of("in_progress", "cancelled"),
            "in_progress", Set.of("done", "cancelled"));
    /** Steps Nabd can set up for the clinic; the DPA and go-live stay the owner's own. */
    static final List<String> COVERABLE = SetupProgress.STEPS.stream().filter(s -> !"dpa".equals(s) && !"go_live".equals(s)).toList();
    private static final Map<String, String> LABELS = Map.of("profile", "clinic profile", "preset", "clinic type", "doctors", "doctors and timings",
            "prices", "prices", "whatsapp", "WhatsApp number", "tax", "tax registration", "import", "patient import");

    public record Fee(long amountMinor, String currency, String status) {
    }

    public record Booked(UUID engagementId, Fee fee, String waiverReason, String paymentLink) {
    }

    public record Offer(long feeMinor, String currency, String feeStatus, String waiverReason) {
    }

    public record Engagement(UUID id, String status, Fee fee, String waiverReason, List<Instant> preferredSlots, Instant slotAt,
                             List<String> coveredSteps, Instant createdAt, Instant closedAt) {
    }

    public record OwnerView(Offer offer, Engagement engagement) {
    }

    public record QueueItem(UUID id, UUID tenantId, String tenantName, String status, Fee fee, String waiverReason, List<Instant> preferredSlots,
                            String notes, Instant slotAt, UUID operatorId, UUID ticketId, List<String> coveredSteps, Instant createdAt, Instant closedAt) {
    }

    public record Transitioned(UUID id, String status, Boolean summarySent) {
    }

    private final WhiteGloveRepository repo;
    private final OwnerPlanRepository plans;
    private final TenantContext tenantContext;
    private final TenantScope tenantScope;
    private final ActorDirectory actors;
    private final StepUpVerifier stepUp;
    private final AuditService audit;
    private final PlatformAudit platformAudit;
    private final TicketService tickets;
    private final SetupProgress setupProgress;
    private final NotificationPort notifications;
    private final Clock clock;
    private final ClinicClock clinicClock;

    WhiteGloveService(WhiteGloveRepository repo, OwnerPlanRepository plans, TenantContext tenantContext, TenantScope tenantScope, ActorDirectory actors,
                      StepUpVerifier stepUp, AuditService audit, PlatformAudit platformAudit, TicketService tickets, SetupProgress setupProgress,
                      NotificationPort notifications, Clock clock, ClinicClock clinicClock) {
        this.repo = repo;
        this.plans = plans;
        this.tenantContext = tenantContext;
        this.tenantScope = tenantScope;
        this.actors = actors;
        this.stepUp = stepUp;
        this.audit = audit;
        this.platformAudit = platformAudit;
        this.tickets = tickets;
        this.setupProgress = setupProgress;
        this.notifications = notifications;
        this.clock = clock;
        this.clinicClock = clinicClock;
    }

    // ---- owner -------------------------------------------------------------------------------------------------------------

    /** The wizard card: the price (or the waiver) before booking, and the clinic's latest engagement. */
    @Transactional(readOnly = true)
    public OwnerView view() {
        UUID tenant = tenantContext.current();
        Offer offer = repo.offer(tenant).filter(o -> o.feeMinor() != null).map(WhiteGloveService::offerOf).orElse(null);
        return new OwnerView(offer, repo.latest(tenant).map(WhiteGloveService::engagement).orElse(null));
    }

    @Transactional
    public Booked book(UUID staffId, String ip, List<Instant> preferredSlots, String notes, String stepUpToken) {
        UUID tenant = tenantContext.current();
        ActorDirectory.Actor owner = actors.findActor(tenant, staffId).filter(a -> "Owner".equals(a.role()))
                .orElseThrow(() -> new ApiException(HttpStatus.FORBIDDEN, "NOT_OWNER", "NOT_OWNER", "Only the clinic owner can book the setup."));
        StepUpVerifier.StepUp su = stepUp.requireFor(stepUpToken, staffId, STEP_UP_TARGET);
        Instant now = clock.instant();
        if (preferredSlots == null || preferredSlots.isEmpty() || preferredSlots.size() > 3
                || preferredSlots.stream().anyMatch(s -> s == null || !s.isAfter(now) || s.isAfter(now.plus(MAX_AHEAD)))) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "validation", "Validation failed", "Give one to three call slots in the next 90 days.");
        }
        WhiteGloveRepository.Offer o = repo.offer(tenant).orElseThrow(() -> new ApiException(HttpStatus.CONFLICT, "NO_SUBSCRIPTION", "NO_SUBSCRIPTION",
                "This clinic has no subscription yet."));
        if (o.feeMinor() == null) {
            throw new ApiException(HttpStatus.CONFLICT, "WHITE_GLOVE_NOT_OFFERED", "WHITE_GLOVE_NOT_OFFERED", "White-glove setup is not offered on this plan.");
        }
        if (repo.hasOpen(tenant)) {
            throw engagementOpen();
        }
        Offer offer = offerOf(o);
        UUID id = UUID.randomUUID();
        UUID invoice = "due".equals(offer.feeStatus())
                ? plans.insertInvoice(tenant, "white_glove", clinicClock.today(tenant), clinicClock.today(tenant), offer.feeMinor(), offer.currency(), null)
                : null;
        String safeNotes = notes == null || notes.isBlank() ? null : TicketPhiRedactor.redact(notes.strip()).text(); // read by Nabd staff
        String slots = preferredSlots.stream().map(Instant::toString).collect(Collectors.joining(", "));
        UUID ticket = tickets.raiseFromSystem(tenant, "White-glove setup booked",
                "Engagement " + id + ", fee " + offer.feeStatus() + ". Preferred call slots: " + slots
                        + ". Request a support-access grant on this ticket to work inside the clinic.", "normal").id();
        try {
            if (!repo.insert(id, tenant, offer.feeMinor(), offer.currency(), offer.feeStatus(), offer.waiverReason(), invoice, preferredSlots, safeNotes,
                    ticket, staffId, su.assertionId())) {
                throw new ApiException(HttpStatus.FORBIDDEN, "STEP_UP_REUSED", "STEP_UP_REUSED", "This confirmation was already used. Confirm again.");
            }
        } catch (DuplicateKeyException e) { // two bookings raced past hasOpen; the partial unique index decides
            throw engagementOpen();
        }
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("feeStatus", offer.feeStatus());
        after.put("feeMinor", offer.feeMinor());
        after.put("currency", offer.currency());
        if (offer.waiverReason() != null) after.put("waiverReason", offer.waiverReason());
        after.put("slots", preferredSlots.size());
        audit.record(tenant, "staff", staffId, owner.name(), owner.role(), ip, "setup.white_glove_booked", "white_glove_engagement", id, null, after);
        log.info("white-glove engagement {} booked for tenant {} ({})", id, tenant, offer.feeStatus());
        return new Booked(id, new Fee(offer.feeMinor(), offer.currency(), offer.feeStatus()), offer.waiverReason(), null);
    }

    // ---- operator ----------------------------------------------------------------------------------------------------------

    @Transactional(readOnly = true)
    public List<QueueItem> queue(boolean includeClosed) {
        return repo.queue(includeClosed).stream().map(r -> new QueueItem(r.id(), r.tenantId(), r.tenantName(), r.status(),
                new Fee(r.feeMinor(), r.currency(), r.feeStatus()), r.waiverReason(), r.preferredSlots(), r.notes(), r.slotAt(), r.operatorId(),
                r.ticketId(), r.coveredSteps(), r.createdAt(), r.closedAt())).toList();
    }

    /** requested -> scheduled (slot) -> in_progress -> done (covered steps); any open state -> cancelled. Runs in the clinic's scope. */
    public Transitioned transition(UUID id, String toStatus, Instant slotAt, List<String> coveredSteps, UUID operatorId, String ip) {
        UUID tenant = repo.tenantOf(id).orElseThrow(() -> ApiException.notFound("No such engagement."));
        return tenantScope.run(tenant, () -> {
            WhiteGloveRepository.Row e = repo.find(tenant, id).orElseThrow(() -> ApiException.notFound("No such engagement."));
            if (!NEXT.getOrDefault(e.status(), Set.of()).contains(toStatus)) {
                throw new ApiException(HttpStatus.CONFLICT, "INVALID_TRANSITION", "INVALID_TRANSITION",
                        "An engagement in " + e.status() + " cannot move to " + toStatus + ".");
            }
            if ("scheduled".equals(toStatus) && slotAt == null) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "validation", "Validation failed", "Give the agreed call slot.");
            }
            List<String> covered = null;
            if ("done".equals(toStatus)) {
                covered = coveredSteps == null ? List.of() : coveredSteps.stream().distinct().toList();
                if (covered.isEmpty() || !COVERABLE.containsAll(covered)) {
                    throw new ApiException(HttpStatus.BAD_REQUEST, "validation", "Validation failed", "coveredSteps: one or more of " + COVERABLE + ".");
                }
            }
            if (repo.move(tenant, id, e.status(), toStatus, "scheduled".equals(toStatus) ? slotAt : null, operatorId, covered) == 0) {
                throw new ApiException(HttpStatus.CONFLICT, "INVALID_TRANSITION", "INVALID_TRANSITION", "The engagement changed meanwhile. Reload.");
            }
            Boolean summary = null;
            if (covered != null) {
                setupProgress.markDone(tenant, covered);
                summary = summarise(tenant, id, covered);
            }
            Map<String, Object> after = new LinkedHashMap<>();
            after.put("from", e.status());
            after.put("to", toStatus);
            if (covered != null) after.put("coveredSteps", covered);
            platformAudit.record(tenant, operatorId, ip, "white_glove.status_changed", "white_glove_engagement", id, after);
            return new Transitioned(id, toStatus, summary);
        });
    }

    /** WhatsApp summary to each active owner: the steps that were set up, nothing else. False when no approved template exists yet. */
    private boolean summarise(UUID tenant, UUID id, List<String> covered) {
        // checked first: a refused request inside this transaction would mark it rollback-only and undo the close
        if (!notifications.sendable(NotificationPurpose.WHITE_GLOVE_SUMMARY, "en")) {
            log.info("white-glove summary for tenant {} not sent: no approved template", tenant);
            return false;
        }
        String what = covered.stream().map(LABELS::get).collect(Collectors.joining(", "));
        boolean any = false;
        for (ActorDirectory.OwnerContact o : actors.activeOwners(tenant)) {
            if (o.mobilePhone() == null || o.mobilePhone().isBlank()) continue;
            notifications.request(new NotificationRequest(NotificationPurpose.WHITE_GLOVE_SUMMARY, "white_glove_engagement", id, null,
                    o.mobilePhone(), "en", List.of(what), null, null, null, "white-glove-summary:" + id + ":" + o.id()));
            any = true;
        }
        return any;
    }

    private static Offer offerOf(WhiteGloveRepository.Offer o) {
        boolean annual = "annual".equals(o.billingPeriod());
        return new Offer(o.feeMinor(), o.currency(), annual ? "waived" : "due", annual ? "annual_plan" : null);
    }

    private static Engagement engagement(WhiteGloveRepository.Row r) {
        return new Engagement(r.id(), r.status(), new Fee(r.feeMinor(), r.currency(), r.feeStatus()), r.waiverReason(), r.preferredSlots(),
                r.slotAt(), r.coveredSteps(), r.createdAt(), r.closedAt());
    }

    private static ApiException engagementOpen() {
        return new ApiException(HttpStatus.CONFLICT, "ENGAGEMENT_OPEN", "ENGAGEMENT_OPEN", "A white-glove setup is already booked for this clinic.",
                Map.of("code", "ENGAGEMENT_OPEN"));
    }
}
