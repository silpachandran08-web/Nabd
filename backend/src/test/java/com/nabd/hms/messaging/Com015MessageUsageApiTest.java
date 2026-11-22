package com.nabd.hms.messaging;

import com.nabd.hms.common.DomainEventType;
import com.nabd.hms.shared.events.consumer.EventConsumer;
import com.nabd.hms.support.S16E16FakeServicesBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** COM-015 Core side: the owner's usage endpoint over the gateway (setup:view), the plan allowance, and the once-per-threshold alert. */
class Com015MessageUsageApiTest extends S16E16FakeServicesBase {

    static final String NOVEMBER = "{\"month\":\"2026-11\",\"channels\":["
            + "{\"channel\":\"sms\",\"category\":\"otp\",\"count\":4,\"costMinor\":72,\"currency\":\"INR\"},"
            + "{\"channel\":\"whatsapp\",\"category\":\"marketing\",\"count\":10,\"costMinor\":785,\"currency\":\"INR\"},"
            + "{\"channel\":\"whatsapp\",\"category\":\"utility\",\"count\":120,\"costMinor\":1380,\"currency\":\"INR\",\"later\":1}],\"whatsappCount\":130}";

    @Autowired
    MessageUsageAlertConsumer consumer;

    private record Clinic(SeededTenant tenant, String ownerToken, String receptionToken) {
    }

    private Clinic clinic() {
        SeededTenant t = seedTenant();
        SeededStaff owner = seedStaff(t, seedFullAccessRole(t.id()), "own" + t.slug() + "@a.com", "+9195" + (10000000 + (int) (Math.random() * 89999999)), false);
        UUID reception = seedRole(t.id(), "Reception", false, fullGrant("queue"), fullGrant("patients"), fullGrant("billing"));
        SeededStaff rec = seedStaff(t, reception, "rec" + t.slug() + "@a.com", "+9194" + (10000000 + (int) (Math.random() * 89999999)), false);
        return new Clinic(t, loginAndGetAccessToken(owner), loginAndGetAccessToken(rec));
    }

    /** The clinic's subscription, pinned to a plan version that includes {@code allowance} WhatsApp messages a month (null = none). */
    private void subscribe(Clinic c, Integer allowance) {
        JdbcTemplate su = new JdbcTemplate(superuserDataSource());
        UUID plan = seedPlan("c15" + UUID.randomUUID().toString().substring(0, 8), 1000, "INR", 5);
        su.update("INSERT INTO master.plan_versions (plan_id, version, monthly_price_cents, seat_limit, whatsapp_message_allowance) VALUES (?, 1, 1000, 5, ?) "
                + "ON CONFLICT (plan_id, version) DO UPDATE SET whatsapp_message_allowance = EXCLUDED.whatsapp_message_allowance", plan, allowance);
        su.update("INSERT INTO master.subscriptions (tenant_id, plan_id, mrr_cents, currency, renewal_date) VALUES (?,?,1000,'INR', current_date + 30)", c.tenant().id(), plan);
    }

    private void approvedAlertTemplate(Clinic c) {
        inTenantTx(c.tenant().id(), () -> jdbc.update("""
                INSERT INTO whatsapp_templates (tenant_id, name, meta_name, language, category, header_text, body, param_count, status)
                VALUES (?, 'message_usage_alert', ?, 'en', 'UTILITY', 'Nabd', ?, 3, 'approved')""",
                c.tenant().id(), "mua_" + c.tenant().id().toString().substring(0, 8),
                "Your clinic has used {{1}}% of this month's WhatsApp messages ({{2}} of {{3}}). See Plan in Nabd."));
    }

    /** What the Comms Gateway publishes when a day closes, as the usage-alerts group hands it to the consumer. */
    private void reported(Clinic c, LocalDate day, long whatsappMonthCount) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("tenantId", c.tenant().id().toString());
        data.put("day", day.toString());
        data.put("items", List.of(Map.of("channel", "whatsapp", "category", "utility", "count", 1, "costMinor", 12, "currency", "INR")));
        data.put("whatsappMonthCount", whatsappMonthCount);
        data.put("version", day.toEpochDay());
        inTenantTx(c.tenant().id(), () -> consumer.handle(new EventConsumer.Event("ev:in:comms", "1-0", "usage:" + c.tenant().id() + ":" + day,
                DomainEventType.USAGE_REPORTED.type(), c.tenant().id(), null, day.toEpochDay(), objectMapper.valueToTree(data))));
    }

    private List<Map<String, Object>> alertRows(Clinic c) {
        return inTenantTx(c.tenant().id(), () -> jdbc.queryForList("SELECT threshold_pct, used, allowance, month::text AS month FROM message_usage_alerts ORDER BY threshold_pct"));
    }

    private int whatsAppRequests(Clinic c) {
        return inTenantTx(c.tenant().id(), () -> jdbc.queryForObject("SELECT count(*) FROM notification_requests WHERE purpose = 'message_usage_alert'", Integer.class));
    }

    // AC1 + AC3: counts and cost by channel and category; a plan without an allowance shows cost only
    @Test
    void theOwnerSeesCountsAndCostByChannelAndCategoryAndNoAllowanceWhenThePlanHasNone() {
        Clinic c = clinic();
        usageReply = NOVEMBER;
        COMMS_SEEN.clear();
        ResponseEntity<Map> r = exchange("/v1/owner/messaging/usage?month=2026-11", HttpMethod.GET, authed(c.ownerToken()), Map.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(r.getBody().get("month")).isEqualTo("2026-11");
        List<Map<String, Object>> channels = (List<Map<String, Object>>) r.getBody().get("channels");
        assertThat(channels).hasSize(3);
        assertThat(channels.get(2)).containsOnlyKeys("channel", "category", "count", "costMinor", "currency")
                .containsEntry("channel", "whatsapp").containsEntry("category", "utility").containsEntry("count", 120).containsEntry("costMinor", 1380).containsEntry("currency", "INR");
        assertThat(r.getBody()).doesNotContainKey("allowance");
        assertThat((List<?>) r.getBody().get("alerts")).isEmpty();
        assertThat(COMMS_SEEN).anyMatch(x -> x.contains("/internal/v1/usage?month=2026-11") && x.endsWith("tenant=" + c.tenant().id()));

        // a subscription whose plan version has no allowance is the same: cost only
        subscribe(c, null);
        assertThat(exchange("/v1/owner/messaging/usage?month=2026-11", HttpMethod.GET, authed(c.ownerToken()), Map.class).getBody()).doesNotContainKey("allowance");

        // no month = the clinic's current month
        COMMS_SEEN.clear();
        assertThat(exchange("/v1/owner/messaging/usage", HttpMethod.GET, authed(c.ownerToken()), Map.class).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(COMMS_SEEN).anyMatch(x -> x.matches(".*/internal/v1/usage\\?month=\\d{4}-\\d{2} tenant=" + c.tenant().id()));
    }

    @Test
    void onlyAStaffMemberWithTheSetupPermissionMayReadItAndABadMonthIsA400() {
        Clinic c = clinic();
        assertThat(exchange("/v1/owner/messaging/usage", HttpMethod.GET, authed(c.receptionToken()), Map.class).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(exchange("/v1/owner/messaging/usage", HttpMethod.GET, null, Map.class).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(exchange("/v1/owner/messaging/usage?month=November", HttpMethod.GET, authed(c.ownerToken()), Map.class).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    // AC2 + AC3: used vs allowance, and one alert per threshold and month
    @Test
    void usageIsMeasuredAgainstThePlanAllowanceAndEachThresholdAlertsTheOwnerOnce() {
        Clinic c = clinic();
        Clinic other = clinic();
        subscribe(c, 100);
        subscribe(other, 100);
        approvedAlertTemplate(c);
        usageReply = NOVEMBER;
        Map<?, ?> allowance = (Map<?, ?>) exchange("/v1/owner/messaging/usage?month=2026-11", HttpMethod.GET, authed(c.ownerToken()), Map.class).getBody().get("allowance");
        assertThat(allowance.get("limit")).isEqualTo(100);
        assertThat(allowance.get("used")).isEqualTo(130);

        reported(c, LocalDate.of(2026, 11, 10), 79);
        assertThat(alertRows(c)).isEmpty();
        assertThat(whatsAppRequests(c)).isZero();

        reported(c, LocalDate.of(2026, 11, 11), 80);
        assertThat(alertRows(c)).hasSize(1);
        assertThat(alertRows(c).get(0)).containsEntry("threshold_pct", 80).containsEntry("used", 80L).containsEntry("allowance", 100L).containsEntry("month", "2026-11-01");
        assertThat(whatsAppRequests(c)).as("one owner, one message").isEqualTo(1);

        reported(c, LocalDate.of(2026, 11, 11), 80); // the same event again (at-least-once)
        reported(c, LocalDate.of(2026, 11, 12), 93); // still above 80%, the next day
        assertThat(alertRows(c)).hasSize(1);
        assertThat(whatsAppRequests(c)).as("the 80% alert is sent once").isEqualTo(1);

        reported(c, LocalDate.of(2026, 11, 13), 104);
        assertThat(alertRows(c)).extracting(m -> m.get("threshold_pct")).containsExactly(80, 100);
        assertThat(whatsAppRequests(c)).isEqualTo(2);

        // a new month starts clean; a jump past both thresholds in one day is one message
        reported(c, LocalDate.of(2026, 12, 1), 250);
        assertThat(alertRows(c)).hasSize(4);
        assertThat(whatsAppRequests(c)).isEqualTo(3);

        List<Map<String, Object>> shown = (List<Map<String, Object>>) exchange("/v1/owner/messaging/usage?month=2026-11", HttpMethod.GET, authed(c.ownerToken()), Map.class)
                .getBody().get("alerts");
        assertThat(shown).extracting(m -> m.get("thresholdPct")).containsExactly(80, 100);
        // another clinic sees none of it
        assertThat((List<?>) exchange("/v1/owner/messaging/usage?month=2026-11", HttpMethod.GET, authed(other.ownerToken()), Map.class).getBody().get("alerts")).isEmpty();
        assertThat(alertRows(other)).isEmpty();
    }

    @Test
    void noAllowanceMeansNoAlertAndAMissingTemplateStillLeavesTheInAppAlert() {
        Clinic none = clinic();
        reported(none, LocalDate.of(2026, 11, 11), 5000);
        subscribe(none, null);
        reported(none, LocalDate.of(2026, 11, 12), 6000);
        assertThat(alertRows(none)).isEmpty();

        Clinic noTemplate = clinic();
        subscribe(noTemplate, 50);
        reported(noTemplate, LocalDate.of(2026, 11, 11), 45);
        assertThat(alertRows(noTemplate)).extracting(m -> m.get("threshold_pct")).containsExactly(80);
        assertThat(whatsAppRequests(noTemplate)).isZero();
    }
}
