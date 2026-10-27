package com.nabd.hms.organization.trial;

import com.nabd.hms.platform.tenant.TrialClockJob;
import com.nabd.hms.support.ApiTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** ADM-004: the daily trial clock (reminders at 7/3/1 days, expiry to suspended or to the free plan) and the trial subscription an operator starts. */
class Adm004TrialClockApiTest extends ApiTestBase {

    private final JdbcTemplate su = new JdbcTemplate(superuserDataSource());

    @Autowired
    private TrialClockJob job;

    private record Clinic(SeededTenant tenant, SeededStaff owner, String token) {
    }

    private Clinic trialClinic(String endsAtSql, String onExpiry) {
        SeededTenant t = seedTenant();
        su.update("UPDATE tenants SET status = 'trialing' WHERE id = ?", t.id());
        SeededStaff owner = seedStaff(t, seedFullAccessRole(t.id()), "own" + UUID.randomUUID() + "@a.com", "+9198" + (10000000 + (int) (Math.random() * 89999999)), false);
        UUID plan = seedPlan("clk-" + UUID.randomUUID().toString().substring(0, 8), 1000, "INR", 5);
        if (onExpiry != null) {
            su.update("UPDATE master.plans SET on_trial_expiry = ? WHERE id = ?", onExpiry, plan);
        }
        su.update("INSERT INTO master.subscriptions (tenant_id, plan_id, mrr_cents, currency, renewal_date, status, trial_ends_at) VALUES (?,?,1000,'INR',current_date + 30,'trialing'," + endsAtSql + ")", t.id(), plan);
        return new Clinic(t, owner, loginAndGetAccessToken(owner));
    }

    private void approveTemplate(UUID tenantId) {
        inTenantTx(tenantId, () -> jdbc.update("""
                INSERT INTO whatsapp_templates (tenant_id, name, meta_name, language, category, header_text, body, param_count, status)
                VALUES (?, 'trial_expiring', ?, 'en', 'UTILITY', 'Nabd', 'Your Nabd trial ends in {{1}} days.', 1, 'approved')
                """, tenantId, "trial_expiring_" + tenantId.toString().substring(0, 8)));
    }

    private long reminders(UUID tenantId) {
        return inTenantTx(tenantId, () -> jdbc.queryForObject("SELECT count(*) FROM notification_requests WHERE purpose = 'trial_expiring'", Long.class));
    }

    private String tenantStatus(UUID id) {
        return jdbc.queryForObject("SELECT status FROM tenants WHERE id = ?", String.class, id);
    }

    private String subStatus(UUID id) {
        return jdbc.queryForObject("SELECT status FROM master.subscriptions WHERE tenant_id = ?", String.class, id);
    }

    @Test
    void threeDaysLeft_ownerGetsOneUtilityMessageAndTheBannerShowsTheDays_notRepeated() {
        Clinic c = trialClinic("(current_date + 3) + time '12:00'", null);
        approveTemplate(c.tenant().id());
        job.run();
        assertThat(reminders(c.tenant().id())).isEqualTo(1);
        assertThat(subStatus(c.tenant().id())).isEqualTo("trialing");
        assertThat(jdbc.queryForObject("SELECT trial_reminded_days FROM master.subscriptions WHERE tenant_id = ?", Integer.class, c.tenant().id())).isEqualTo(3);
        Map<?, ?> banner = exchange("/v1/setup/trial", HttpMethod.GET, authed(c.token()), Map.class).getBody();
        assertThat(banner.get("daysLeft")).isEqualTo(3);

        job.run(); // the daily job runs again: same day, same threshold
        assertThat(reminders(c.tenant().id())).isEqualTo(1);

        su.update("UPDATE master.subscriptions SET trial_ends_at = (current_date + 1) + time '12:00' WHERE tenant_id = ?", c.tenant().id());
        job.run(); // the 1-day threshold is a new reminder
        assertThat(reminders(c.tenant().id())).isEqualTo(2);
    }

    @Test
    void moreThanSevenDaysLeft_noReminder_andNoTemplateMeansTryAgainTomorrow() {
        Clinic far = trialClinic("(current_date + 10) + time '12:00'", null);
        approveTemplate(far.tenant().id());
        job.run();
        assertThat(reminders(far.tenant().id())).isZero();

        Clinic noTemplate = trialClinic("(current_date + 2) + time '12:00'", null);
        job.run();
        assertThat(reminders(noTemplate.tenant().id())).isZero();
        assertThat(jdbc.queryForObject("SELECT trial_reminded_days FROM master.subscriptions WHERE tenant_id = ?", Integer.class, noTemplate.tenant().id()))
                .as("not marked as sent, so tomorrow's run tries again").isNull();
        approveTemplate(noTemplate.tenant().id());
        job.run();
        assertThat(reminders(noTemplate.tenant().id())).isEqualTo(1);
    }

    @Test
    void expiryWithoutAFreePlan_suspendsTheTenant() {
        Clinic c = trialClinic("now() - interval '1 hour'", null);
        job.run();
        assertThat(tenantStatus(c.tenant().id())).isEqualTo("suspended");
        assertThat(subStatus(c.tenant().id())).isEqualTo("cancelled");
        Map<String, Object> ev = su.queryForMap("SELECT changed_by, changed_by_staff, reason FROM tenant_lifecycle_events WHERE tenant_id = ? AND to_status = 'suspended'", c.tenant().id());
        assertThat(ev.get("changed_by")).isNull();
        assertThat(ev.get("changed_by_staff")).isNull();
        // suspended: the owner's session is gone and login is refused (platform rule)
        assertThat(exchange("/v1/setup/trial", HttpMethod.GET, authed(c.token()), Map.class).getStatusCode().is2xxSuccessful()).isFalse();
    }

    @Test
    void expiryOnAPlanThatDowngrades_movesToTheFreePlanAndKeepsTheClinicActive() {
        UUID free = seedPlan("free-" + UUID.randomUUID().toString().substring(0, 8), 0, "INR", 1);
        su.update("UPDATE master.plans SET visits_per_month = 100 WHERE id = ?", free);
        Clinic c = trialClinic("now() - interval '1 hour'", "downgrade_to_free");
        job.run();
        assertThat(tenantStatus(c.tenant().id())).isEqualTo("active");
        Map<String, Object> sub = su.queryForMap("SELECT plan_id, status, mrr_cents, trial_ends_at FROM master.subscriptions WHERE tenant_id = ?", c.tenant().id());
        assertThat(sub.get("status")).isEqualTo("free");
        assertThat(sub.get("mrr_cents")).isEqualTo(0);
        assertThat(sub.get("trial_ends_at")).isNull();
        assertThat(sub.get("plan_id")).as("one of the active free plans, never the trial's own plan").isNotNull();
        assertThat(su.queryForObject("SELECT monthly_price_cents FROM master.plans WHERE id = ?", Integer.class, sub.get("plan_id"))).isZero();
        // a second run finds nothing more to do for this tenant
        job.run();
        assertThat(tenantStatus(c.tenant().id())).isEqualTo("active");
    }

    @Test
    void anOperatorStartsATrialWithThePlansTrialDays_andALaterRealSubscriptionEndsIt() {
        SeededTenant t = seedTenant();
        UUID plan = seedPlan("trialplan-" + t.slug(), 100000, "INR", 3);
        su.update("UPDATE master.plans SET trial_days = 21 WHERE id = ?", plan);
        String token = platformLoginAndGetAccessToken(seedOperator("billing-trial@nabd.internal", "billing", false));
        String path = "/v1/platform/billing/subscriptions/" + t.id();

        ResponseEntity<Map> started = exchange(path, HttpMethod.POST, authedJsonBody(token, Map.of("planId", plan.toString(),
                "renewalDate", LocalDate.now().plusDays(30).toString(), "startTrial", true)), Map.class);
        assertThat(started.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> row = su.queryForMap("SELECT status, (trial_ends_at::date - current_date) AS days FROM master.subscriptions WHERE tenant_id = ?", t.id());
        assertThat(row.get("status")).isEqualTo("trialing");
        assertThat(((Number) row.get("days")).intValue()).isBetween(20, 22);

        ResponseEntity<Map> real = exchange(path, HttpMethod.POST, authedJsonBody(token, Map.of("planId", plan.toString(),
                "renewalDate", LocalDate.now().plusDays(30).toString())), Map.class);
        assertThat(real.getStatusCode()).isEqualTo(HttpStatus.OK);
        row = su.queryForMap("SELECT status, trial_ends_at, trial_reminded_days FROM master.subscriptions WHERE tenant_id = ?", t.id());
        assertThat(row.get("status")).isEqualTo("active");
        assertThat(row.get("trial_ends_at")).isNull();
    }
}
