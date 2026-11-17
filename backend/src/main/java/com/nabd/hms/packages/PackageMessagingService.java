package com.nabd.hms.packages;

import com.nabd.hms.common.ClinicClock;
import com.nabd.hms.common.DomainEventType;
import com.nabd.hms.common.EventPublisher;
import com.nabd.hms.common.TenantContext;
import com.nabd.hms.patient.api.SecureDocumentLinks;
import com.nabd.hms.queue.DeskExceptions;
import com.nabd.hms.shared.notify.CancelReason;
import com.nabd.hms.shared.notify.NotificationOutcome;
import com.nabd.hms.shared.notify.NotificationPort;
import com.nabd.hms.shared.notify.NotificationPurpose;
import com.nabd.hms.shared.notify.NotificationRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static com.nabd.hms.packages.PackageModels.InstanceRow;

/**
 * COM-044: the package's messages are real sends through the COM-004 notification contract (consent, quiet hours, templates and
 * the channel are the contract's business). Card after the sale (billing.package.sold.v1, with an IDC-015 secure link), the
 * desk's Send reminder, and the daily job: 30/14/7-day warnings (deduped per instance + tier + validity end), the grace notice and
 * the next-session nudge at the doctor-set interval. Every request is a ledger event with its request id and delivery status
 * (synced from comms.message.status_changed.v1). A request blocked for lack of consent opens a desk exception. A completed or
 * refunded package cancels what is still pending and is never picked again (the job reads status 'active' only).
 * Templates carry the first name, a date or the link only: never the package name (it can reveal the treatment) or an amount.
 */
@Service
public class PackageMessagingService {

    static final String SUBJECT = "package_instance";
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH);

    private final PackageRepository packages;
    private final PackageMessagingRepository repo;
    private final NotificationPort notifications;
    private final SecureDocumentLinks links;
    private final DeskExceptions desk;
    private final EventPublisher events;
    private final ClinicClock clock;
    private final TenantContext tenantContext;

    PackageMessagingService(PackageRepository packages, PackageMessagingRepository repo, NotificationPort notifications, SecureDocumentLinks links,
                            DeskExceptions desk, EventPublisher events, ClinicClock clock, TenantContext tenantContext) {
        this.packages = packages;
        this.repo = repo;
        this.notifications = notifications;
        this.links = links;
        this.desk = desk;
        this.events = events;
        this.clock = clock;
        this.tenantContext = tenantContext;
    }

    /** In the sale's transaction: the doctor-set nudge interval, and the event whose consumer sends the card. */
    @Transactional
    public void sold(UUID tenantId, UUID instanceId, UUID patientId, Integer nudgeIntervalDays) {
        if (nudgeIntervalDays != null) {
            repo.setNudge(tenantId, instanceId, nudgeIntervalDays, clock.today(tenantId).plusDays(nudgeIntervalDays));
        }
        events.publish(tenantId, DomainEventType.PACKAGE_SOLD, instanceId, Map.of("packageInstanceId", instanceId, "patientId", patientId));
    }

    /** billing.package.sold.v1 consumer: the card message with a fresh secure link. Idempotent (one card event per instance). */
    @Transactional
    public void sendCard(UUID instanceId) {
        UUID tenantId = tenantContext.current();
        Optional<InstanceRow> found = packages.findInstance(tenantId, instanceId);
        if (found.isEmpty() || !"active".equals(found.get().status()) || repo.hasEvent(tenantId, instanceId, "card_requested")) {
            return;
        }
        InstanceRow row = found.get();
        if (!notifications.sendable(NotificationPurpose.PACKAGE_CARD, null)) {
            repo.insertMessageEvent(tenantId, instanceId, "card_requested", "Card not sent: the clinic has no approved package_card template", null, null, null);
            return;
        }
        UUID soldBy = repo.messaging(tenantId, instanceId).map(PackageMessagingRepository.Messaging::soldBy).orElseThrow();
        Optional<SecureDocumentLinks.CardLink> link = links.issuePackageCard(soldBy, instanceId, row.patientId());
        if (link.isEmpty()) {
            repo.insertMessageEvent(tenantId, instanceId, "card_requested", "Card not sent: no mobile number or no processing consent on file", null, null, null);
            return;
        }
        NotificationOutcome out = notifications.request(new NotificationRequest(NotificationPurpose.PACKAGE_CARD, SUBJECT, instanceId, row.patientId(), null,
                null, List.of(link.get().firstName(), link.get().url()), null, null, null, "pkg-card:" + instanceId));
        record(tenantId, row, "card_requested", "Package card", null, out, NotificationPurpose.PACKAGE_CARD);
    }

    /** The desk's Send reminder (tier computed by the caller from the instance's own dates). One per instance, tier and clinic day. */
    @Transactional
    public void sendReminder(UUID tenantId, UUID staffId, InstanceRow row, int tier) {
        LocalDate today = clock.today(tenantId);
        boolean grace = today.isAfter(row.validityEnd()); // the caller allows active and grace only
        NotificationPurpose purpose = grace ? NotificationPurpose.PACKAGE_GRACE : NotificationPurpose.PACKAGE_EXPIRY;
        LocalDate shown = grace ? row.validityEnd().plusDays(row.graceDays()) : row.validityEnd();
        NotificationOutcome out = notifications.request(new NotificationRequest(purpose, SUBJECT, row.id(), row.patientId(), null,
                null, List.of(firstName(row), DAY.format(shown)), null, null, null, "pkg-reminder:" + row.id() + ":" + tier + ":" + today));
        packages.updateLastAlertTier(tenantId, row.id(), tier);
        record(tenantId, row, "expiry_warning_sent", tier + "-day reminder (desk)", staffId, out, purpose);
    }

    /** One clinic's daily pass (bound by the job). Returns how many new requests were made. */
    @Transactional
    public int runDue() {
        UUID tenantId = tenantContext.current();
        LocalDate today = clock.today(tenantId);
        int made = 0;
        for (UUID id : repo.dueForMessaging(tenantId, today)) {
            InstanceRow row = packages.findInstance(tenantId, id).orElse(null);
            if (row == null || packages.allItemsConsumed(tenantId, id)) {
                continue;
            }
            String status = PackageInstanceService.effectiveStatus(row, today);
            int tier = PackageInstanceService.currentAlertTier(row, today);
            if ("active".equals(status) && tier > 0 && (row.lastAlertTier() == null || row.lastAlertTier() > tier)) {
                NotificationOutcome out = notifications.request(new NotificationRequest(NotificationPurpose.PACKAGE_EXPIRY, SUBJECT, id, row.patientId(), null,
                        null, List.of(firstName(row), DAY.format(row.validityEnd())), null, null, null, "pkg-expiry:" + id + ":" + tier + ":" + row.validityEnd()));
                packages.updateLastAlertTier(tenantId, id, tier);
                made += record(tenantId, row, "expiry_warning_sent", tier + "-day warning", null, out, NotificationPurpose.PACKAGE_EXPIRY);
            }
            if ("grace".equals(status)) {
                LocalDate graceEnd = row.validityEnd().plusDays(row.graceDays());
                NotificationOutcome out = notifications.request(new NotificationRequest(NotificationPurpose.PACKAGE_GRACE, SUBJECT, id, row.patientId(), null,
                        null, List.of(firstName(row), DAY.format(graceEnd)), null, null, null, "pkg-grace:" + id + ":" + row.validityEnd()));
                made += record(tenantId, row, "grace_notice", "Grace period until " + graceEnd, null, out, NotificationPurpose.PACKAGE_GRACE);
            }
            LocalDate nudgeAt = repo.messaging(tenantId, id).map(PackageMessagingRepository.Messaging::nextNudgeAt).orElse(null);
            if (nudgeAt != null && !nudgeAt.isAfter(today) && ("active".equals(status) || "grace".equals(status))) {
                NotificationOutcome out = notifications.request(new NotificationRequest(NotificationPurpose.PACKAGE_SESSION_NUDGE, SUBJECT, id, row.patientId(), null,
                        null, List.of(firstName(row)), null, null, null, "pkg-nudge:" + id + ":" + nudgeAt));
                repo.setNextNudge(tenantId, id, null); // the next redemption sets the next one
                made += record(tenantId, row, "session_nudge", "Next session reminder", null, out, NotificationPurpose.PACKAGE_SESSION_NUDGE);
            }
        }
        return made;
    }

    /** After a redemption (in its transaction): stop everything once complete, else schedule the next session nudge. */
    @Transactional
    public void redeemed(UUID tenantId, UUID instanceId) {
        if (packages.allItemsConsumed(tenantId, instanceId)) {
            stop(tenantId, instanceId);
            return;
        }
        repo.messaging(tenantId, instanceId).map(PackageMessagingRepository.Messaging::nudgeIntervalDays)
                .ifPresent(days -> repo.setNextNudge(tenantId, instanceId, clock.today(tenantId).plusDays(days)));
    }

    /** Completed or refunded: pending sends are cancelled, no new nudge is scheduled, the desk alert closes. */
    @Transactional
    public void stop(UUID tenantId, UUID instanceId) {
        notifications.cancelBySubject(SUBJECT, instanceId, CancelReason.SUBJECT_CHANGED);
        repo.setNextNudge(tenantId, instanceId, null);
        desk.resolveRef(tenantId, instanceId, DeskExceptions.PACKAGE_NUDGE_BLOCKED);
    }

    /** A new validity end: the 30/14/7 warnings apply again. */
    @Transactional
    public void extended(UUID tenantId, UUID instanceId) {
        repo.resetAlertTier(tenantId, instanceId);
    }

    /** comms.message.status_changed.v1: the ledger row of that request follows (forward only). */
    @Transactional
    public void deliveryChanged(UUID requestId, String status) {
        repo.updateDelivery(tenantContext.current(), requestId, status);
    }

    @Transactional
    public Optional<String> lastReminderStatus(UUID tenantId, UUID instanceId) {
        return repo.lastReminderStatus(tenantId, instanceId);
    }

    /** A ledger event for a new request (a dedupe hit adds nothing); a consent block also opens the desk alert. Returns 1 when new. */
    @Transactional(propagation = Propagation.MANDATORY) // only from the request methods above, inside their transaction
    private int record(UUID tenantId, InstanceRow row, String eventType, String note, UUID actorId, NotificationOutcome out, NotificationPurpose purpose) {
        if (out.duplicate()) {
            return 0;
        }
        String status = "blocked".equalsIgnoreCase(out.status()) ? "blocked" : "requested";
        repo.insertMessageEvent(tenantId, row.id(), eventType, "blocked".equals(status) ? note + " · not sent: no messaging consent" : note,
                actorId, out.requestId(), status);
        if ("blocked".equals(status)) {
            desk.open(tenantId, DeskExceptions.PACKAGE_NUDGE_BLOCKED, "warn", SUBJECT, row.id(), Map.of("reason", "no_consent", "purpose", purpose.code()));
        }
        return 1;
    }

    private static String firstName(InstanceRow row) {
        return row.patientName().trim().split("\\s+")[0];
    }
}
