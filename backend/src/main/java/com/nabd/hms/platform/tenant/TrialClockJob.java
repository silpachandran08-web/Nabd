package com.nabd.hms.platform.tenant;

import com.nabd.hms.common.ApiException;
import com.nabd.hms.common.ClinicClock;
import com.nabd.hms.common.TenantLifecyclePort;
import com.nabd.hms.common.TenantScope;
import com.nabd.hms.iam.api.ActorDirectory;
import com.nabd.hms.platform.entitlements.EntitlementEvents;
import com.nabd.hms.shared.notify.NotificationPort;
import com.nabd.hms.shared.notify.NotificationPurpose;
import com.nabd.hms.shared.notify.NotificationRequest;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

/**
 * ADM-004: the daily trial clock. Reminders at 7, 3 and 1 days left (a utility WhatsApp message to each active owner, the number
 * of days only); a trial past its end either moves the tenant to 'suspended' or, when the plan says downgrade_to_free and a free
 * plan exists, onto that plan. One tenant failing never stops the others. Logs ids and counts only.
 */
@Component
public class TrialClockJob {

    private static final Logger log = LoggerFactory.getLogger(TrialClockJob.class);
    static final int[] REMINDER_DAYS = {7, 3, 1};

    private record Trial(UUID tenantId, Instant endsAt, Integer reminded, String onExpiry) {
    }

    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final ClinicClock clinicClock;
    private final TenantScope tenantScope;
    private final TenantLifecyclePort lifecycle;
    private final ActorDirectory actors;
    private final NotificationPort notifications;
    private final EntitlementEvents entitlementEvents;

    TrialClockJob(JdbcTemplate jdbc, Clock clock, ClinicClock clinicClock, TenantScope tenantScope, TenantLifecyclePort lifecycle,
                  ActorDirectory actors, NotificationPort notifications, EntitlementEvents entitlementEvents) {
        this.jdbc = jdbc;
        this.clock = clock;
        this.clinicClock = clinicClock;
        this.tenantScope = tenantScope;
        this.lifecycle = lifecycle;
        this.actors = actors;
        this.notifications = notifications;
        this.entitlementEvents = entitlementEvents;
    }

    @Scheduled(cron = "${app.trial.clock-cron:0 15 3 * * *}")
    @SchedulerLock(name = "adm-trial-clock", lockAtMostFor = "PT30M")
    public void scheduled() { // ShedLock cannot lock a method returning a primitive
        run();
    }

    /** @return trials acted on (a reminder sent or an expiry applied) */
    public int run() {
        List<Trial> trials = jdbc.query("""
                SELECT s.tenant_id, s.trial_ends_at, s.trial_reminded_days, p.on_trial_expiry
                FROM master.subscriptions s JOIN master.plans p ON p.id = s.plan_id
                WHERE s.status = 'trialing' AND s.trial_ends_at IS NOT NULL ORDER BY s.trial_ends_at
                """, (rs, i) -> new Trial(rs.getObject("tenant_id", UUID.class), rs.getTimestamp("trial_ends_at").toInstant(),
                (Integer) rs.getObject("trial_reminded_days"), rs.getString("on_trial_expiry")));
        int acted = 0;
        for (Trial t : trials) {
            try {
                if (!clock.instant().isBefore(t.endsAt())) {
                    expire(t);
                    acted++;
                } else if (remind(t)) {
                    acted++;
                }
            } catch (RuntimeException e) {
                log.warn("trial clock failed for tenant {}", t.tenantId());
            }
        }
        return acted;
    }

    private boolean remind(Trial t) {
        var zone = clinicClock.zone(t.tenantId());
        long daysLeft = ChronoUnit.DAYS.between(clinicClock.today(zone), t.endsAt().atZone(zone).toLocalDate());
        Integer threshold = null;
        for (int d : REMINDER_DAYS) { // smallest threshold the trial has reached
            if (daysLeft <= d) {
                threshold = d;
            }
        }
        if (threshold == null || (t.reminded() != null && t.reminded() <= threshold)) {
            return false;
        }
        int th = threshold;
        boolean sent = tenantScope.run(t.tenantId(), () -> {
            boolean any = false;
            for (ActorDirectory.OwnerContact o : actors.activeOwners(t.tenantId())) {
                if (o.mobilePhone() == null || o.mobilePhone().isBlank()) {
                    continue;
                }
                try {
                    notifications.request(new NotificationRequest(NotificationPurpose.TRIAL_EXPIRING, "tenant", t.tenantId(), null,
                            o.mobilePhone(), "en", List.of(String.valueOf(Math.max(daysLeft, 0))), null, null, null,
                            "trial-expiring:" + t.tenantId() + ":" + th + ":" + o.id()));
                    any = true;
                } catch (ApiException e) { // no approved template yet: try again tomorrow, the banner still shows the days
                    log.info("trial reminder for tenant {} not sent: {}", t.tenantId(), e.getMessage());
                }
            }
            return any;
        });
        if (sent) {
            jdbc.update("UPDATE master.subscriptions SET trial_reminded_days = ? WHERE tenant_id = ?", th, t.tenantId());
        }
        return sent;
    }

    private void expire(Trial t) {
        if ("downgrade_to_free".equals(t.onExpiry()) && downgrade(t)) {
            return;
        }
        tenantScope.run(t.tenantId(), () -> {
            String status = jdbc.queryForObject("SELECT status FROM tenants WHERE id = ?", String.class, t.tenantId());
            if ("trialing".equals(status) || "active".equals(status) || "overdue".equals(status)) { // already suspended or closed by an operator: only the subscription changes
                lifecycle.transitionBy("suspended", null, "trial expired without conversion");
            }
        });
        jdbc.update("UPDATE master.subscriptions SET status = 'cancelled' WHERE tenant_id = ?", t.tenantId());
        entitlementEvents.changed(t.tenantId(), List.of("plan"));
        log.info("trial of tenant {} expired: suspended", t.tenantId());
    }

    /** The cheapest active free plan other than the trial's own; false when the platform has none (then the tenant is suspended). */
    private boolean downgrade(Trial t) {
        List<Object[]> free = jdbc.query("SELECT id, version FROM master.plans WHERE active AND monthly_price_cents = 0 AND id <> (SELECT plan_id FROM master.subscriptions WHERE tenant_id = ?) ORDER BY code LIMIT 1",
                (rs, i) -> new Object[] {rs.getObject("id", UUID.class), rs.getInt("version")}, t.tenantId());
        if (free.isEmpty()) {
            log.warn("trial of tenant {} wants a free plan but none is active", t.tenantId());
            return false;
        }
        jdbc.update("UPDATE master.subscriptions SET plan_id = ?, plan_version = ?, mrr_cents = 0, status = 'free', trial_ends_at = NULL, trial_reminded_days = NULL WHERE tenant_id = ?",
                free.get(0)[0], free.get(0)[1], t.tenantId());
        tenantScope.run(t.tenantId(), () -> {
            String status = jdbc.queryForObject("SELECT status FROM tenants WHERE id = ?", String.class, t.tenantId());
            if ("trialing".equals(status)) {
                lifecycle.transitionBy("active", null, "trial expired: moved to the free plan");
            }
        });
        entitlementEvents.changed(t.tenantId(), List.of("plan"));
        log.info("trial of tenant {} expired: moved to the free plan", t.tenantId());
        return true;
    }
}
