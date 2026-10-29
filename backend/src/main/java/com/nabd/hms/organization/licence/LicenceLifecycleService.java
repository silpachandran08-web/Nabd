package com.nabd.hms.organization.licence;

import com.nabd.hms.common.ApiException;
import com.nabd.hms.common.AuditService;
import com.nabd.hms.common.ClinicClock;
import com.nabd.hms.common.EventPublisher;
import com.nabd.hms.common.TenantContext;
import com.nabd.hms.iam.api.ActorDirectory;
import com.nabd.hms.iam.api.StaffLicenceSync;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * ADM-032: the licence register's daily lifecycle for the bound tenant. {@link #refresh()} is the one pass (the job runs it, a save
 * runs it): status from the expiry date, one reminder per threshold (an outbox event; the WhatsApp message to the holder and owners
 * is sent by {@link LicenceEvents}), and the holder's bookability. A clinician is bookable online unless a licence of theirs
 * is expired without a running owner override; their signing expiry follows the earliest expiry (T-058 reads it).
 * Everything is derived from dates and stored markers, so running it twice changes nothing.
 */
@Service
public class LicenceLifecycleService {

    private static final Logger log = LoggerFactory.getLogger(LicenceLifecycleService.class);
    /** shortcut: fixed ceiling like CLN-005's signing override (MAX_OVERRIDE); make it a clinic policy if a regulator needs another window. */
    static final int MAX_OVERRIDE_DAYS = 30;

    private final LicenceRepository repo;
    private final StaffLicenceSync staff;
    private final EventPublisher events;
    private final ClinicClock clock;
    private final TenantContext tenantContext;
    private final AuditService audit;
    private final ActorDirectory actors;

    LicenceLifecycleService(LicenceRepository repo, StaffLicenceSync staff, EventPublisher events, ClinicClock clock, TenantContext tenantContext,
                            AuditService audit, ActorDirectory actors) {
        this.repo = repo;
        this.staff = staff;
        this.events = events;
        this.clock = clock;
        this.tenantContext = tenantContext;
        this.audit = audit;
        this.actors = actors;
    }

    /** What one pass did, for logs and tests. */
    public record Result(int reminders, int lapsed, int holders) {
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public Result refresh() {
        UUID tenant = tenantContext.current();
        LocalDate today = clock.today(tenant);
        int reminders = 0;
        int lapsed = 0;
        Set<UUID> holders = new LinkedHashSet<>();
        List<LicenceRepository.Row> rows = repo.all();
        for (LicenceRepository.Row r : rows) {
            long left = ChronoUnit.DAYS.between(today, r.expiry());
            String status = LicenceReminderPolicy.status(left);
            if (!status.equals(r.status())) {
                repo.setStatus(r.id(), status);
                if ("expired".equals(status) && isClinician(r)) {
                    events.publish(tenant, LicenceEvents.LAPSED, Map.of("tenantId", tenant, "licenceId", r.id(), "holderStaffId", r.holderId(), "lapsedOn", r.expiry().toString()));
                    lapsed++;
                }
            }
            var due = LicenceReminderPolicy.due(left, r.sent());
            if (due.isPresent()) {
                repo.markReminded(r.id(), due.get(), clock.now());
                Map<String, Object> p = new LinkedHashMap<>();
                p.put("tenantId", tenant);
                p.put("licenceId", r.id());
                if (r.holderId() != null) {
                    p.put("holderStaffId", r.holderId());
                }
                p.put("daysLeft", left);
                p.put("threshold", due.get());
                events.publish(tenant, LicenceEvents.EXPIRING, p);
                reminders++;
            }
            if (isClinician(r)) {
                holders.add(r.holderId());
            }
        }
        for (UUID holder : holders) {
            boolean lapsedNow = rows.stream().anyMatch(r -> isClinician(r) && holder.equals(r.holderId()) && r.expiry().isBefore(today)
                    && (r.overrideUntil() == null || r.overrideUntil().isBefore(today)));
            LocalDate earliest = rows.stream().filter(r -> isClinician(r) && holder.equals(r.holderId())).map(LicenceRepository.Row::expiry).min(LocalDate::compareTo).orElse(null);
            staff.apply(holder, !lapsedNow, earliest);
        }
        if (reminders + lapsed > 0) {
            log.info("licence lifecycle for tenant {}: {} reminder(s), {} lapse(s)", tenant, reminders, lapsed);
        }
        return new Result(reminders, lapsed, holders.size());
    }

    /** After the register row was added or edited: a changed expiry date starts a new cycle, then one pass so booking follows within the request. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void afterSave(UUID licenceId, UUID staffId, String ip, LocalDate previousExpiry) {
        LocalDate now = repo.expiryOf(licenceId);
        if (previousExpiry != null && !previousExpiry.equals(now)) {
            repo.resetCycle(licenceId);
        }
        refresh();
        audit(staffId, ip, previousExpiry == null ? "organization.licence_added" : "organization.licence_updated", licenceId, Map.of());
    }

    /** The owner lets a lapsed clinician stay bookable until a date (at most 30 days away) while the renewal is in progress. */
    @Transactional(propagation = Propagation.MANDATORY)
    public LicenceRepository.Row override(UUID licenceId, UUID staffId, String ip, LocalDate until, String reason) {
        UUID tenant = tenantContext.current();
        LocalDate today = clock.today(tenant);
        String why = reason == null ? "" : reason.strip();
        if (why.length() < 3 || why.length() > 200) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "invalid-reason", "Reason needed", "Give a reason of 3 to 200 characters.");
        }
        if (until == null || until.isBefore(today)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "invalid-override-window", "Invalid override window", "The override must end today or later.");
        }
        if (until.isAfter(today.plusDays(MAX_OVERRIDE_DAYS))) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "override-too-long", "Override too long",
                    "An override can run for at most " + MAX_OVERRIDE_DAYS + " days.", Map.of("code", "OVERRIDE_TOO_LONG", "maxDays", MAX_OVERRIDE_DAYS));
        }
        LicenceRepository.Row row = repo.find(licenceId).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "licence-not-found", "Licence not found", "Licence not found"));
        if (!isClinician(row) || !row.expiry().isBefore(today)) {
            throw new ApiException(HttpStatus.CONFLICT, "not-lapsed", "Licence not lapsed", "An override applies to an expired clinician licence only.");
        }
        repo.setOverride(licenceId, until, why, staffId);
        refresh();
        audit(staffId, ip, "organization.licence_override_applied", licenceId, Map.of("until", until.toString(), "reason", why));
        return repo.find(licenceId).orElseThrow();
    }

    /** The clinic's date (the register is judged on it). */
    @Transactional(propagation = Propagation.MANDATORY)
    public LocalDate today() {
        return clock.today(tenantContext.current());
    }

    /** The largest reminder threshold still ahead and not sent yet, if any. */
    public static java.util.Optional<Integer> nextReminder(long daysLeft, Set<Integer> sent) {
        return LicenceReminderPolicy.next(daysLeft, sent);
    }

    /** Doctors hidden from online booking right now (the register screen and the Edge availability read this). */
    @Transactional(propagation = Propagation.MANDATORY)
    public Set<UUID> hiddenFromBooking() {
        return staff.hiddenFromOnlineBooking();
    }

    private static boolean isClinician(LicenceRepository.Row r) {
        return "clinician".equals(r.kind()) && r.holderId() != null;
    }

    private void audit(UUID staffId, String ip, String action, UUID licenceId, Map<String, Object> after) {
        UUID tenant = tenantContext.current();
        ActorDirectory.Actor a = actors.findActor(tenant, staffId).orElse(null);
        audit.record(tenant, "staff", staffId, a == null ? "Staff" : a.name(), a == null ? "Staff" : a.role(), ip, action, "licence", licenceId, null, after);
    }
}
