package com.nabd.hms.reports;

import com.nabd.hms.common.ModuleGrant;
import com.nabd.hms.support.ApiTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.zip.ZipInputStream;

import static org.assertj.core.api.Assertions.assertThat;

/** ADM-047: the reports library: frame shape, groups, drill-down paging against rollups, row scope, masking, audited exports, OWN-09 leakage. */
class Adm047ReportsLibraryApiTest extends ApiTestBase {

    @Autowired RollupProjector projector;

    private static final List<String> KEYS = List.of("leakage", "takings", "package-liability", "tax-summary", "patient-sources", "patient-retention",
            "no-show-watch", "staff-activity", "doctor-productivity", "doctor-on-time");

    record Flow(SeededTenant tenant, SeededStaff owner, String token, UUID invoiceId, UUID queueEntryId) {
    }

    private Flow paidVisit(String patientName) {
        SeededTenant tenant = seedTenant();
        SeededStaff owner = seedStaff(tenant, seedFullAccessRole(tenant.id()), "a47-" + UUID.randomUUID() + "@a.com", "+9198" + (10000000 + (int) (Math.random() * 89999999)), false);
        String token = loginAndGetAccessToken(owner);
        String patient = (String) exchange("/v1/patients", HttpMethod.POST, authedJsonBody(token, Map.of(
                "name", patientName, "phone", "+9199" + (10000000 + (int) (Math.random() * 89999999)), "dob", "1971-01-01", "gender", "male")), Map.class).getBody().get("id");
        String entry = (String) exchange("/v1/queue/check-in", HttpMethod.POST, authedJsonBody(token, Map.of("patientId", patient, "doctorId", owner.id().toString())), Map.class).getBody().get("id");
        for (String s : List.of("waiting", "vitals_pending", "vitals_done", "in_consult", "checkout_pending")) {
            exchange("/v1/queue/" + entry + "/status", HttpMethod.PATCH, authedJsonBody(token, Map.of("status", s)), Map.class);
        }
        String invoice = (String) exchange("/v1/billing/checkout/" + entry, HttpMethod.POST, authedJsonBody(token, Map.of("lineItems", List.of(Map.of(
                "override", Map.of("reasonCode", "other"), "chargeCode", "X", "chargeName", "X", "category", "Service", "quantity", 1, "unitPrice", 500, "taxRatePercent", 0)))), Map.class).getBody().get("id");
        exchange("/v1/billing/invoices/" + invoice + "/payments", HttpMethod.POST, authedJsonBody(token, Map.of("method", "cash", "amount", 500)), Map.class);
        return new Flow(tenant, owner, token, UUID.fromString(invoice), UUID.fromString(entry));
    }

    private String loginWithGrants(Flow f, String name, ModuleGrant... grants) {
        UUID role = seedRole(f.tenant().id(), name, false, grants);
        return loginAndGetAccessToken(seedStaff(f.tenant(), role, name.toLowerCase() + "-" + UUID.randomUUID() + "@a.com", "+9197" + (10000000 + (int) (Math.random() * 89999999)), false));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> get(String token, String path) {
        return exchange("/v1/reports/" + path, HttpMethod.GET, authed(token), Map.class).getBody();
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> rows(Map<String, Object> frame) {
        return (List<Map<String, Object>>) frame.get("rows");
    }

    private ResponseEntity<Map> export(String token, String report, String format, String from, String to) {
        return exchange("/v1/reports/" + report + "/exports", HttpMethod.POST, authedJsonBody(token, Map.of("format", format, "from", from, "to", to)), Map.class);
    }

    private static byte[] file(ResponseEntity<Map> r) {
        return Base64.getDecoder().decode((String) r.getBody().get("contentBase64"));
    }

    private void backdatePayments(Flow f, LocalDate day, int count, BigDecimal each, UUID recordedBy) {
        inTenantTx(f.tenant().id(), () -> {
            for (int i = 0; i < count; i++) {
                jdbc.update("INSERT INTO invoice_payments (tenant_id, invoice_id, method, amount, recorded_by, recorded_at) VALUES (?,?,?,?,?,?)",
                        f.tenant().id(), f.invoiceId(), "cash", each, recordedBy, Timestamp.from(day.atTime(10, 0, i % 60).plusMinutes(i / 60).toInstant(ZoneOffset.UTC)));
            }
        });
    }

    @Test
    @SuppressWarnings("unchecked")
    void everyReportAnswersTheCommonFrameInItsGroup() { // AC1 and the 10 report shapes
        Flow f = paidVisit("Frame Pat");
        List<String> money = new ArrayList<>(), patients = new ArrayList<>(), team = new ArrayList<>();
        for (String key : KEYS) {
            ResponseEntity<Map> r = exchange("/v1/reports/" + key, HttpMethod.GET, authed(f.token()), Map.class);
            assertThat(r.getStatusCode()).as(key).isEqualTo(HttpStatus.OK);
            Map<String, Object> b = r.getBody();
            assertThat(b).as(key).containsKeys("reportId", "group", "title", "rule", "kpis", "columns", "rows", "nextCursor", "from", "to", "branch", "masked", "aggregateOnly");
            assertThat(b.get("reportId")).isEqualTo(key);
            assertThat((String) b.get("rule")).isNotBlank();
            assertThat((List<?>) b.get("kpis")).as(key).isNotEmpty();
            ((Map<String, Object>) ((List<?>) b.get("kpis")).get(0)).keySet().containsAll(List.of("key", "label", "value", "format"));
            (switch ((String) b.get("group")) { case "money" -> money; case "patients" -> patients; default -> team; }).add(key);
        }
        assertThat(money).containsExactly("leakage", "takings", "package-liability", "tax-summary");
        assertThat(patients).containsExactly("patient-sources", "patient-retention", "no-show-watch");
        assertThat(team).containsExactly("staff-activity", "doctor-productivity", "doctor-on-time");
        assertThat(exchange("/v1/reports/not-a-report", HttpMethod.GET, authed(f.token()), Map.class).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        // the legacy literal paths still answer with their own shapes (aliases of the old reports)
        assertThat(get(f.token(), "daily-money")).containsKey("collectedToday");
    }

    @Test
    @SuppressWarnings("unchecked")
    void drillDownPagesBy50AndTheRollupTotalEqualsTheRowSum() { // AC2 and "rollup totals reconcile with drill-down sums"
        Flow f = paidVisit("Page Pat");
        LocalDate yesterday = LocalDate.now(ZoneOffset.UTC).minusDays(1); // test tenants are UTC
        backdatePayments(f, yesterday, 55, new BigDecimal("10.00"), f.owner().id());
        inTenantTx(f.tenant().id(), () -> projector.recompute(f.tenant().id(), yesterday, "adm047-test"));

        String q = "takings?from=" + yesterday + "&to=" + yesterday;
        Map<String, Object> p1 = get(f.token(), q);
        assertThat(rows(p1)).hasSize(50);
        assertThat(p1.get("nextCursor")).isNotNull();
        Map<String, Object> p2 = get(f.token(), q + "&cursor=" + p1.get("nextCursor"));
        assertThat(rows(p2)).hasSize(5);
        assertThat(p2.get("nextCursor")).isNull();
        BigDecimal sum = BigDecimal.ZERO;
        for (Map<String, Object> r : rows(p1)) sum = sum.add(new BigDecimal(r.get("amount").toString()));
        for (Map<String, Object> r : rows(p2)) sum = sum.add(new BigDecimal(r.get("amount").toString()));
        Map<String, Object> collected = ((List<Map<String, Object>>) p1.get("kpis")).stream().filter(k -> "collected".equals(k.get("key"))).findFirst().orElseThrow();
        assertThat(new BigDecimal(collected.get("value").toString())).isEqualByComparingTo("550.00").isEqualByComparingTo(sum);
        // same rows, no overlap between pages
        List<Object> ids = new ArrayList<>();
        rows(p1).forEach(r -> ids.add(r.get("at") + "|" + r.get("amount")));
        assertThat(rows(p2)).allSatisfy(r -> assertThat(ids).doesNotContain(r.get("at") + "|" + r.get("amount")));
        assertThat(exchange("/v1/reports/" + q + "&cursor=not-a-cursor", HttpMethod.GET, authed(f.token()), Map.class).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void rangeLongerThanAYearAndAnotherBranchAreRefused() {
        Flow f = paidVisit("Range Pat");
        ResponseEntity<Map> r = exchange("/v1/reports/takings?from=2024-01-01&to=2025-06-01", HttpMethod.GET, authed(f.token()), Map.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(r.getBody().toString()).contains("RANGE_TOO_LONG");
        assertThat(exchange("/v1/reports/takings?from=2025-01-01&to=2025-12-31", HttpMethod.GET, authed(f.token()), Map.class).getStatusCode()).isEqualTo(HttpStatus.OK); // 365 days
        SeededTenant other = seedTenant();
        assertThat(exchange("/v1/reports/takings?branch=" + other.id(), HttpMethod.GET, authed(f.token()), Map.class).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(exchange("/v1/reports/takings?branch=" + f.tenant().id(), HttpMethod.GET, authed(f.token()), Map.class).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void anotherClinicSeesNoneOfTheRows() {
        Flow a = paidVisit("Iso Pat A");
        Flow b = paidVisit("Iso Pat B");
        List<Map<String, Object>> mine = rows(get(a.token(), "takings"));
        assertThat(mine).hasSize(1).allSatisfy(r -> assertThat(r.get("patient")).isEqualTo("Iso Pat A"));
        assertThat(rows(get(b.token(), "takings"))).allSatisfy(r -> assertThat(r.get("patient")).isEqualTo("Iso Pat B"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void aStaffMemberWithoutTeamAccessSeesOnlyTheirOwnRow() { // AC3: a receptionist with reports:view
        Flow f = paidVisit("Scope Pat");
        UUID role = seedRole(f.tenant().id(), "Desk", false, new ModuleGrant("reports", true, false, false, false, false, false, false));
        SeededStaff desk = seedStaff(f.tenant(), role, "desk-" + UUID.randomUUID() + "@a.com", "+919712345601", false);
        String deskToken = loginAndGetAccessToken(desk);
        backdatePayments(f, LocalDate.now(ZoneOffset.UTC), 1, new BigDecimal("25.00"), desk.id()); // one payment recorded by the desk staff member

        List<Map<String, Object>> own = rows(get(deskToken, "staff-activity"));
        assertThat(own).hasSize(1).allSatisfy(r -> assertThat(r.get("collected").toString()).startsWith("25"));
        assertThat((String) get(deskToken, "staff-activity").get("scopeNote")).contains("only your own row");
        assertThat(rows(get(f.token(), "staff-activity"))).hasSize(2); // owner: both rows
        // the legacy endpoint follows the same rule
        assertThat((List<?>) get(deskToken, "staff-performance").get("rows")).hasSize(1);
        // doctor reports scope the same way: the desk member is no doctor with activity
        assertThat(rows(get(deskToken, "doctor-productivity"))).isEmpty();
    }

    @Test
    void retentionIsAggregateOnlyAndPackagesNeedTheirOwnGrant() {
        Flow f = paidVisit("Ret Pat");
        Map<String, Object> ret = get(f.token(), "patient-retention");
        assertThat(ret).containsEntry("aggregateOnly", true);
        assertThat(rows(ret)).isEmpty();
        assertThat((List<?>) ret.get("columns")).isEmpty();
        String reportsOnly = loginWithGrants(f, "Viewer", new ModuleGrant("reports", true, false, false, false, false, false, false));
        assertThat(exchange("/v1/reports/package-liability", HttpMethod.GET, authed(reportsOnly), Map.class).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(exchange("/v1/reports/takings", HttpMethod.GET, authed(reportsOnly), Map.class).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    @SuppressWarnings("unchecked")
    void exportsAreAuditedMaskedByPermissionAndCarryTheRowCount() throws Exception { // AC4
        Flow f = paidVisit("Rollup Pat");
        String today = LocalDate.now(ZoneOffset.UTC).toString();
        String accountant = loginWithGrants(f, "Accountant", new ModuleGrant("reports", true, false, false, false, false, false, true)); // no patients:view
        String viewer = loginWithGrants(f, "Viewer", new ModuleGrant("reports", true, false, false, false, false, false, false)); // no reports:export

        // masked on screen and in the file for a role without patients:view; clear for the owner
        Map<String, Object> shown = get(accountant, "takings");
        assertThat(shown).containsEntry("masked", true);
        assertThat(rows(shown).get(0).get("patient")).isEqualTo("R*** P***");
        assertThat(rows(get(f.token(), "takings")).get(0).get("patient")).isEqualTo("Rollup Pat");

        ResponseEntity<Map> csv = export(accountant, "takings", "csv", today, today);
        assertThat(csv.getStatusCode()).isEqualTo(HttpStatus.OK);
        String text = new String(file(csv), StandardCharsets.UTF_8);
        assertThat(text).contains("R*** P***").doesNotContain("Rollup").contains("Invoice");
        assertThat(csv.getBody()).containsEntry("rows", 1).containsEntry("masked", true);

        ResponseEntity<Map> xlsx = export(f.token(), "takings", "xlsx", today, today);
        byte[] zip = file(xlsx);
        String sheet = null, content = null;
        try (ZipInputStream z = new ZipInputStream(new ByteArrayInputStream(zip))) {
            for (var e = z.getNextEntry(); e != null; e = z.getNextEntry()) {
                String body = new String(z.readAllBytes(), StandardCharsets.UTF_8);
                if (e.getName().equals("xl/worksheets/sheet1.xml")) sheet = body;
                if (e.getName().equals("[Content_Types].xml")) content = body;
            }
        }
        assertThat(content).contains("spreadsheetml.sheet.main+xml");
        assertThat(sheet).contains("Rollup Pat").contains("<v>500.00</v>");
        assertThat(xlsx.getBody().get("contentType")).isEqualTo("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");

        byte[] pdf = file(export(f.token(), "takings", "pdf", today, today));
        assertThat(new String(pdf, StandardCharsets.ISO_8859_1)).startsWith("%PDF").contains("DAILY MONEY");

        // one audit row per export: actor, report, filters, row count, masked flag
        List<String> audits = inTenantTx(f.tenant().id(), () -> jdbc.queryForList(
                "SELECT after::text FROM audit_log WHERE action = 'report.export' AND after->>'reportId' = 'takings' ORDER BY created_at", String.class));
        assertThat(audits).hasSize(3);
        assertThat(audits.get(0)).contains("\"format\": \"csv\"").contains("\"rowCount\": 1").contains("\"masked\": true").contains(today).contains(f.tenant().id().toString());
        Integer actors = inTenantTx(f.tenant().id(), () -> jdbc.queryForObject("SELECT count(DISTINCT actor_id) FROM audit_log WHERE action = 'report.export'", Integer.class));
        assertThat(actors).isEqualTo(2);
        // the event: ids, report key, format and row count; never a name
        List<String> evs = inTenantTx(f.tenant().id(), () -> jdbc.queryForList(
                "SELECT payload::text FROM outbox_events WHERE event_type = 'reporting.export.created.v1'", String.class));
        assertThat(evs).hasSize(3).allSatisfy(e -> assertThat(e).contains("\"reportId\": \"takings\"").contains("\"rows\": 1").doesNotContain("Rollup"));

        // no reports:export -> 403 and nothing audited; bad format -> 400
        assertThat(export(viewer, "takings", "csv", today, today).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(export(f.token(), "takings", "doc", today, today).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(export(f.token(), "nope", "csv", today, today).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(inTenantTx(f.tenant().id(), () -> jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE action = 'report.export'", Integer.class))).isEqualTo(3);
    }

    @Test
    void csvNeutralisesSpreadsheetFormulas() {
        assertThat(ReportFiles.csvField("=HYPERLINK(\"x\")")).isEqualTo("\"'=HYPERLINK(\"\"x\"\")\"");
        assertThat(ReportFiles.csvField("plain")).isEqualTo("\"plain\"");
        assertThat(ReportFiles.colName(0)).isEqualTo("A");
        assertThat(ReportFiles.colName(26)).isEqualTo("AA");
        assertThat(ReportLibraryService.mask("Anna Maria")).isEqualTo("A*** M***");
    }

    @Test
    @SuppressWarnings("unchecked")
    void leakageListsDoneProceduresWithNoBillLineAboveTheThreshold() { // AC5, OWN-09
        Flow f = paidVisit("Leak Pat");
        UUID patientId = inTenantTx(f.tenant().id(), () -> jdbc.queryForObject("SELECT patient_id FROM queue_entries WHERE id = ?", UUID.class, f.queueEntryId()));
        // 500 and 100 never billed; 300 with the same code as the invoice line "X" (billed); 400 not completed
        String[][] orders = {{"LHR", "Laser hair", "500", "completed"}, {"NEB", "Nebulisation", "100", "completed"}, {"X", "Billed one", "300", "completed"}, {"PEEL", "Peel", "400", "ordered"}};
        inTenantTx(f.tenant().id(), () -> {
            for (String[] o : orders) {
                jdbc.update("INSERT INTO procedure_orders (tenant_id, queue_entry_id, patient_id, ordered_by, charge_code, charge_name, base_amount, status, billed, completed_by, completed_at) " +
                        "VALUES (?,?,?,?,?,?,?,?,true,?,now())", f.tenant().id(), f.queueEntryId(), patientId, f.owner().id(), o[0], o[1], new BigDecimal(o[2]), o[3], f.owner().id());
            }
        });
        Map<String, Object> all = get(f.token(), "leakage");
        assertThat(rows(all)).extracting(r -> r.get("code")).containsExactlyInAnyOrder("LHR", "NEB");
        Map<String, Object> over = get(f.token(), "leakage?threshold=200");
        assertThat(rows(over)).extracting(r -> r.get("code")).containsExactly("LHR");
        Map<String, Object> value = ((List<Map<String, Object>>) over.get("kpis")).stream().filter(k -> "value".equals(k.get("key"))).findFirst().orElseThrow();
        assertThat(new BigDecimal(value.get("value").toString())).isEqualByComparingTo("500");
        assertThat((String) over.get("rule")).contains("never appears on that visit's invoice");
    }
}
