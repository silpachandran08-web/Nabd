package com.nabd.hms.pharmacy;

import com.nabd.hms.common.ModuleGrant;
import com.nabd.hms.pharmacy.api.StockCommands;
import com.nabd.hms.pharmacy.api.StockCommands.BatchReceipt;
import com.nabd.hms.support.ApiTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/** S24 QA (t1) PHL-008: CSV formula injection in every user-typed column, the report for a view-only role, acknowledge replay and roles. */
@SuppressWarnings({"unchecked", "rawtypes"})
class S24QaPhl008StockAlertsApiTest extends ApiTestBase {

    private static final AtomicInteger PHONE = new AtomicInteger(7200000);
    @Autowired StockCommands stock;
    @Autowired StockAlertJob job;

    private record Clinic(SeededTenant tenant, String token, UUID staffId) {
        UUID t() {
            return tenant.id();
        }
    }

    private static String phone() {
        return "+9189" + String.format("%08d", PHONE.incrementAndGet());
    }

    private Clinic clinic() {
        SeededTenant tenant = seedTenant();
        SeededStaff s = seedStaff(tenant, seedFullAccessRole(tenant.id()), UUID.randomUUID().toString().substring(0, 8) + "@q8.test", phone(), false);
        String token = loginAndGetAccessToken(s);
        assertThat(exchange("/v1/pharmacy/settings", HttpMethod.PATCH, authedJsonBody(token, Map.of("mode", "in_house", "reason", "QA test",
                "alertRecipientStaffId", s.id().toString())), Map.class).getStatusCode()).isEqualTo(HttpStatus.OK);
        return new Clinic(tenant, token, s.id());
    }

    private UUID item(Clinic c, String name) {
        ResponseEntity<Map> r = exchange("/v1/pharmacy/items", HttpMethod.POST, authedJsonBody(c.token(), Map.of("name", name, "isRx", false, "price", 100.00,
                "taxRatePercent", 12.00, "scheduleClass", "OTC")), Map.class);
        if (r.getStatusCode() != HttpStatus.CREATED) {
            System.out.println("  item name refused at entry: " + name.replace("\t", "\\t") + " -> " + r.getStatusCode());
            return null;
        }
        return UUID.fromString((String) r.getBody().get("id"));
    }

    private void receive(Clinic c, UUID item, String batchNo, LocalDate expiry) {
        if (item == null) return;
        inTenantTx(c.t(), () -> stock.receiveBatch(item, new BatchReceipt(batchNo, expiry, new BigDecimal("20.00"), new BigDecimal("2.0000"), 5,
                "A1", null, null), c.staffId(), "GRN " + batchNo));
    }

    private String viewer(Clinic c) {
        UUID role = seedRole(c.t(), "QPhView", false, new ModuleGrant("pharmacy", true, false, false, false, false, false, false));
        return loginAndGetAccessToken(seedStaff(c.tenant(), role, "v" + UUID.randomUUID().toString().substring(0, 6) + "@q8.test", phone(), false));
    }

    // C-P08-A04: every user-typed CSV column (item name, batch number) is neutralised for = + - @ and a leading tab; a view-only role's export
    @Test
    void p08a04_csvInjectionInNameAndBatchNo_andViewOnlyExport() {
        Clinic c = clinic();
        LocalDate soon = LocalDate.now().plusDays(20);
        receive(c, item(c, "+SUM(1+1) QA"), "B-plus", soon);
        receive(c, item(c, "-2+3 QA"), "B-minus", soon.plusDays(1));
        receive(c, item(c, "@cmd QA"), "B-at", soon.plusDays(2));
        receive(c, item(c, "\tTab QA"), "B-tab", soon.plusDays(3));
        receive(c, item(c, "Plain QA"), "=HYPERLINK(\"http://x\")", soon.plusDays(4));
        ResponseEntity<String> csv = exchange("/v1/pharmacy/reports/expiry?withinDays=60&format=csv", HttpMethod.GET, authed(c.token()), String.class);
        assertThat(csv.getStatusCode()).isEqualTo(HttpStatus.OK);
        List<String> unsafe = new ArrayList<>();
        for (String line : csv.getBody().split("\r\n")) {
            for (String cell : line.split(",")) {
                String v = cell.startsWith("\"") ? cell.substring(1) : cell;
                if (!v.isEmpty() && "=+-@\t\r".indexOf(v.charAt(0)) >= 0 && !v.matches("-?[0-9.]+\"?")) unsafe.add(cell);
            }
        }
        ResponseEntity<String> viewOnly = exchange("/v1/pharmacy/reports/expiry?withinDays=60&format=csv", HttpMethod.GET, authed(viewer(c)), String.class);
        Integer audits = inTenantTx(c.t(), () -> jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE tenant_id = ? AND action = 'pharmacy.expiry_report_exported'",
                Integer.class, c.t()));
        System.out.println("C-P08-A04 unsafe cells=" + unsafe + " viewOnlyCsv=" + viewOnly.getStatusCode() + " (decision: spec says pharmacy:report) audits=" + audits);
        assertThat(unsafe).isEmpty();
        assertThat(audits).isEqualTo(viewOnly.getStatusCode().is2xxSuccessful() ? 2 : 1);
    }

    // P08-A01/A02/A03: acknowledge replay keeps the first action; view-only 403; other clinic 404
    @Test
    void p08a02_acknowledgeReplayAndRoles() {
        Clinic c = clinic();
        receive(c, item(c, "Zinc QA8"), "B-30", LocalDate.now().plusDays(25));
        job.runFor(c.t());
        List<Map<String, Object>> items = (List<Map<String, Object>>) exchange("/v1/pharmacy/alerts?type=expiry", HttpMethod.GET, authed(c.token()), Map.class)
                .getBody().get("items");
        assertThat(items).isNotEmpty();
        String id = (String) items.get(0).get("id");
        int viewerAck = exchange("/v1/pharmacy/alerts/" + id + "/acknowledge", HttpMethod.POST, authedJsonBody(viewer(c), Map.of("action", "none")), Map.class)
                .getStatusCode().value();
        int otherAck = exchange("/v1/pharmacy/alerts/" + id + "/acknowledge", HttpMethod.POST, authedJsonBody(clinic().token(), Map.of("action", "none")), Map.class)
                .getStatusCode().value();
        ResponseEntity<Map> first = exchange("/v1/pharmacy/alerts/" + id + "/acknowledge", HttpMethod.POST, authedJsonBody(c.token(), Map.of("action", "returned")), Map.class);
        ResponseEntity<Map> second = exchange("/v1/pharmacy/alerts/" + id + "/acknowledge", HttpMethod.POST, authedJsonBody(c.token(), Map.of("action", "quarantined")), Map.class);
        System.out.println("P08-A01/A02/A03 viewerAck=" + viewerAck + " otherClinicAck=" + otherAck + " first=" + first.getStatusCode() + " " + first.getBody().get("action")
                + " second=" + second.getStatusCode() + " " + second.getBody().get("action"));
        assertThat(viewerAck).isEqualTo(403);
        assertThat(otherAck).isEqualTo(404);
        assertThat(second.getBody()).containsEntry("action", "returned");
    }
}
