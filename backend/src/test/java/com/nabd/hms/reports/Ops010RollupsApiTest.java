package com.nabd.hms.reports;

import tools.jackson.databind.ObjectMapper;
import com.nabd.hms.common.ClinicClock;
import com.nabd.hms.shared.events.consumer.EventConsumer;
import com.nabd.hms.support.ApiTestBase;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** OPS-010: rollups from events, closed-day immutability, range overview, readOnly reads, tenant isolation. */
class Ops010RollupsApiTest extends ApiTestBase {

    @Autowired RollupProjector projector;
    @Autowired ClinicClock clock;
    @Autowired ObjectMapper json;
    @Autowired MeterRegistry meters;

    record Flow(SeededTenant tenant, SeededStaff owner, String token, LocalDate today, UUID paymentId, UUID invoiceId) {
    }

    private Flow paidVisit() {
        SeededTenant tenant = seedTenant();
        SeededStaff owner = seedStaff(tenant, seedFullAccessRole(tenant.id()), "o10-" + UUID.randomUUID() + "@a.com", "+9198" + (10000000 + (int) (Math.random() * 89999999)), false);
        String token = loginAndGetAccessToken(owner);
        String patient = (String) exchange("/v1/patients", HttpMethod.POST, authedJsonBody(token, Map.of(
                "name", "Rollup Pat", "phone", "+9199" + (10000000 + (int) (Math.random() * 89999999)), "dob", "1971-01-01", "gender", "male")), Map.class).getBody().get("id");
        String entry = (String) exchange("/v1/queue/check-in", HttpMethod.POST, authedJsonBody(token, Map.of("patientId", patient, "doctorId", owner.id().toString())), Map.class).getBody().get("id");
        for (String s : List.of("waiting", "vitals_pending", "vitals_done", "in_consult", "checkout_pending")) {
            exchange("/v1/queue/" + entry + "/status", HttpMethod.PATCH, authedJsonBody(token, Map.of("status", s)), Map.class);
        }
        String invoice = (String) exchange("/v1/billing/checkout/" + entry, HttpMethod.POST, authedJsonBody(token, Map.of("lineItems", List.of(Map.of(
                "override", Map.of("reasonCode", "other"), "chargeCode", "X", "chargeName", "X", "category", "Service", "quantity", 1, "unitPrice", 500, "taxRatePercent", 0)))), Map.class).getBody().get("id");
        exchange("/v1/billing/invoices/" + invoice + "/payments", HttpMethod.POST, authedJsonBody(token, Map.of("method", "cash", "amount", 500)), Map.class);
        UUID payment = inTenantTx(tenant.id(), () -> jdbc.queryForObject("SELECT id FROM invoice_payments WHERE tenant_id = ? AND invoice_id = ?::uuid", UUID.class, tenant.id(), invoice));
        return new Flow(tenant, owner, token, clock.today(tenant.id()), payment, UUID.fromString(invoice));
    }

    private void deliver(Flow f, String type, Map<String, Object> data) {
        inTenantTx(f.tenant().id(), () -> projector.handle(new EventConsumer.Event("ev:in:x", "1-0", UUID.randomUUID().toString(), type, f.tenant().id(), null, 1,
                json.valueToTree(data))));
    }

    private Map<String, Object> today(Flow f) {
        return inTenantTx(f.tenant().id(), () -> jdbc.queryForMap("SELECT visits, completed, collected, billed, by_method::text AS by_method FROM tenant_daily_metrics WHERE tenant_id = ? AND day = ?",
                f.tenant().id(), java.sql.Date.valueOf(f.today())));
    }

    @Test
    void replayedPaymentEventCountsOnceAndMatchesTheLiveFigures() {
        Flow f = paidVisit();
        Map<String, Object> pay = Map.of("paymentId", f.paymentId().toString(), "invoiceId", f.invoiceId().toString());
        deliver(f, "billing.payment.recorded.v1", pay);
        deliver(f, "billing.payment.recorded.v1", pay);
        deliver(f, "billing.invoice.paid.v1", Map.of("invoiceId", f.invoiceId().toString()));
        deliver(f, "visit.queue_entry.created.v1", Map.of("queueDate", f.today().toString()));
        Map<String, Object> row = today(f);
        assertThat((BigDecimal) row.get("collected")).isEqualByComparingTo("500");
        assertThat((BigDecimal) row.get("billed")).isEqualByComparingTo("500");
        assertThat(row.get("visits")).isEqualTo(1);
        assertThat((String) row.get("by_method")).contains("\"cash\"");
        // parity with the live report of the same day, to the minor unit
        Map live = exchange("/v1/reports/daily-money", HttpMethod.GET, authed(f.token()), Map.class).getBody();
        assertThat(new BigDecimal(live.get("collectedToday").toString())).isEqualByComparingTo((BigDecimal) row.get("collected"));
        assertThat(new BigDecimal(live.get("billedToday").toString())).isEqualByComparingTo((BigDecimal) row.get("billed"));
        // doctor rollup: median consult time stays null until T-105
        Map<String, Object> doc = inTenantTx(f.tenant().id(), () -> jdbc.queryForMap("SELECT visits, collected, median_consult_s FROM doctor_daily_metrics WHERE tenant_id = ? AND doctor_id = ? AND day = ?",
                f.tenant().id(), f.owner().id(), java.sql.Date.valueOf(f.today())));
        assertThat((BigDecimal) doc.get("collected")).isEqualByComparingTo("500");
        assertThat(doc.get("visits")).isEqualTo(1);
        assertThat(doc.get("median_consult_s")).isNull();
    }

    @Test
    void closedDayIgnoresLateEventsAndReopenRecalculates() {
        Flow f = paidVisit();
        Map<String, Object> pay = Map.of("paymentId", f.paymentId().toString());
        deliver(f, "billing.payment.recorded.v1", pay);
        inTenantTx(f.tenant().id(), () -> jdbc.update("INSERT INTO day_closes (tenant_id, close_date, status, expected_cash, counted_cash, variance, closed_by) VALUES (?,?,'closed',500,500,0,?)",
                f.tenant().id(), java.sql.Date.valueOf(f.today()), f.owner().id()));
        inTenantTx(f.tenant().id(), () -> jdbc.update("INSERT INTO invoice_payments (tenant_id, invoice_id, method, amount, recorded_by) VALUES (?,?,'card',100,?)",
                f.tenant().id(), f.invoiceId(), f.owner().id()));
        double before = meters.counter("reporting.late.event").count();
        deliver(f, "billing.payment.recorded.v1", pay);
        assertThat((BigDecimal) today(f).get("collected")).isEqualByComparingTo("500");
        assertThat(meters.counter("reporting.late.event").count()).isEqualTo(before + 1);
        inTenantTx(f.tenant().id(), () -> jdbc.update("UPDATE day_closes SET status = 'reopened', reopened_at = now(), reopened_by = ?, reopen_reason = 'test' WHERE tenant_id = ?",
                f.owner().id(), f.tenant().id()));
        deliver(f, "billing.payment.recorded.v1", pay);
        assertThat((BigDecimal) today(f).get("collected")).isEqualByComparingTo("600");
    }

    @Test
    @SuppressWarnings("unchecked")
    void overviewRangeReadsRollupsPlusTodayAndRefusesHugeRanges() {
        Flow f = paidVisit();
        LocalDate d3 = f.today().minusDays(3);
        inTenantTx(f.tenant().id(), () -> jdbc.update("INSERT INTO tenant_daily_metrics (tenant_id, day, visits, completed, collected, billed, by_method) VALUES (?,?,4,3,250,300,'{\"upi\":250}')",
                f.tenant().id(), java.sql.Date.valueOf(d3)));
        Map<String, Object> o = exchange("/v1/reports/overview?from=" + d3 + "&to=" + f.today(), HttpMethod.GET, authed(f.token()), Map.class).getBody();
        Map<String, Object> r = (Map<String, Object>) o.get("range");
        assertThat(r.get("visits")).isEqualTo(5);            // 4 from the rollup + today's live visit
        assertThat(new BigDecimal(r.get("collected").toString())).isEqualByComparingTo("750");
        assertThat((Map<String, Object>) r.get("byMethod")).containsKeys("upi", "cash");
        assertThat(((Map<String, Object>) exchange("/v1/reports/overview", HttpMethod.GET, authed(f.token()), Map.class).getBody()).get("range")).isNull();
        ResponseEntity<String> big = exchange("/v1/reports/overview?from=" + f.today().minusDays(400) + "&to=" + f.today(), HttpMethod.GET, authed(f.token()), String.class);
        assertThat(big.getStatusCode().value()).isEqualTo(400);
        assertThat(big.getBody()).contains("range-too-large");
    }

    @Test
    void rollupsAreTenantIsolated() {
        Flow a = paidVisit();
        deliver(a, "billing.payment.recorded.v1", Map.of("paymentId", a.paymentId().toString()));
        SeededTenant b = seedTenant();
        Integer seen = inTenantTx(b.id(), () -> jdbc.queryForObject("SELECT count(*) FROM tenant_daily_metrics WHERE tenant_id = ?", Integer.class, a.tenant().id()));
        Integer seenDoc = inTenantTx(b.id(), () -> jdbc.queryForObject("SELECT count(*) FROM doctor_daily_metrics WHERE tenant_id = ?", Integer.class, a.tenant().id()));
        assertThat(seen).isZero();
        assertThat(seenDoc).isZero();
    }

    @Test
    void everyPublicReadMethodOfTheReportsServiceIsReadOnly() {
        for (Method m : ReportsService.class.getDeclaredMethods()) {
            if (!java.lang.reflect.Modifier.isPublic(m.getModifiers()) || m.getName().equals("exportCsv")) { // export writes its audit row
                continue;
            }
            Transactional t = m.getAnnotation(Transactional.class);
            assertThat(t).as(m.getName()).isNotNull();
            assertThat(t.readOnly()).as(m.getName()).isTrue();
        }
    }
}
