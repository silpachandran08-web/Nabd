package com.nabd.hms.reports;

import com.nabd.hms.common.ModuleGrant;
import com.nabd.hms.support.ApiTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.zip.ZipInputStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ADM-049: package sales, redemption and expiry loss in the reports library, and their reconciliation with the package ledger
 * (package_instance_events) and the packages module's own instance list. Test tenants are UTC.
 */
@SuppressWarnings({"unchecked", "rawtypes"})
class Adm049PackageReportsApiTest extends ApiTestBase {

    private final LocalDate today = LocalDate.now(ZoneOffset.UTC);

    private record Clinic(SeededTenant tenant, String token) {
    }

    private Clinic clinic() {
        SeededTenant t = seedTenant();
        return new Clinic(t, loginAndGetAccessToken(seedStaff(t, seedFullAccessRole(t.id()), "a49-" + UUID.randomUUID() + "@a.com", phone(), false)));
    }

    private static String phone() {
        return "+9198" + (10000000 + (int) (Math.random() * 89999999));
    }

    private JdbcTemplate su() {
        return new JdbcTemplate(superuserDataSource());
    }

    private String patient(Clinic c, String name) {
        ResponseEntity<Map> r = exchange("/v1/patients", HttpMethod.POST, authedJsonBody(c.token(), Map.of(
                "name", name, "phone", phone(), "dob", "1980-02-02", "gender", "female", "confirmedNotDuplicate", true)), Map.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return (String) r.getBody().get("id");
    }

    /** One item of {@code sessions} sessions at 1000 list each, sold at 80% of list (above the 72% floor), no tax. */
    private String pkg(Clinic c, String name, int sessions) {
        ResponseEntity<Map> r = exchange("/v1/packages", HttpMethod.POST, authedJsonBody(c.token(), Map.of(
                "name", name, "packageType", "session", "speciality", "Dermatology", "description", "d", "price", 800 * sessions, "taxInclusive", false,
                "validityDays", 180, "validityStarts", "purchase_date", "graceDays", 7,
                "items", List.of(Map.of("itemType", "service_session", "name", name + " session", "quantity", sessions, "unitListPrice", 1000.00, "taxRatePercent", 0.0)))), Map.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String id = (String) r.getBody().get("id");
        assertThat(exchange("/v1/packages/" + id + "/activate", HttpMethod.POST, authed(c.token()), Map.class).getStatusCode()).isEqualTo(HttpStatus.OK);
        return id;
    }

    /** Sells and redeems {@code used} sessions; returns the instance id. */
    private String sell(Clinic c, String patientId, String packageId, int used) {
        ResponseEntity<Map> r = exchange("/v1/packages/sell", HttpMethod.POST, authedJsonBody(c.token(), Map.of(
                "patientId", patientId, "packageId", packageId, "paymentMethod", "upi")), Map.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String item = (String) ((List<Map<String, Object>>) r.getBody().get("items")).get(0).get("id");
        for (int i = 0; i < used; i++) {
            assertThat(exchange("/v1/packages/instances/items/" + item + "/redeem", HttpMethod.POST, authed(c.token()), Map.class).getStatusCode()).isEqualTo(HttpStatus.OK);
        }
        return (String) r.getBody().get("id");
    }

    /** Moves the sale (the instance and its ledger rows) to noon of a past clinic day. */
    private void soldOn(String instanceId, LocalDate day) {
        Timestamp at = Timestamp.from(day.atTime(12, 0).toInstant(ZoneOffset.UTC));
        su().update("UPDATE package_instances SET created_at = ? WHERE id = ?::uuid", at, instanceId);
        su().update("UPDATE package_instance_events SET created_at = ? WHERE instance_id = ?::uuid", at, instanceId);
    }

    /** Validity ended on {@code end}; with the 7 grace days the last usable day is end + 7. */
    private void validUntil(String instanceId, LocalDate end) {
        su().update("UPDATE package_instances SET validity_end = ? WHERE id = ?::uuid", Date.valueOf(end), instanceId);
    }

    private void consent(Clinic c, String patientId, String type) {
        inTenantTx(c.tenant().id(), () -> jdbc.update("INSERT INTO consents (tenant_id, patient_id, consent_type) VALUES (?,?::uuid,?)", c.tenant().id(), patientId, type));
    }

    private Map<String, Object> get(String token, String path) {
        ResponseEntity<Map> r = exchange("/v1/reports/" + path, HttpMethod.GET, authed(token), Map.class);
        assertThat(r.getStatusCode()).as(path).isEqualTo(HttpStatus.OK);
        return r.getBody();
    }

    private int status(String token, String path) {
        return exchange("/v1/reports/" + path, HttpMethod.GET, authed(token), Map.class).getStatusCode().value();
    }

    private static List<Map<String, Object>> rows(Map<String, Object> frame) {
        return (List<Map<String, Object>>) frame.get("rows");
    }

    private static Map<String, Object> row(Map<String, Object> frame, String key, String value) {
        return rows(frame).stream().filter(r -> value.equals(r.get(key))).findFirst().orElseThrow(() -> new AssertionError("no row " + value + " in " + rows(frame)));
    }

    private static BigDecimal kpi(Map<String, Object> frame, String key) {
        return new BigDecimal(((List<Map<String, Object>>) frame.get("kpis")).stream().filter(k -> key.equals(k.get("key"))).findFirst().orElseThrow().get("value").toString());
    }

    private static BigDecimal sum(Map<String, Object> frame, String column) {
        return rows(frame).stream().map(r -> new BigDecimal(r.get(column).toString())).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private String range(LocalDate from, LocalDate to) {
        return "from=" + from + "&to=" + to;
    }

    private String loginWithGrants(Clinic c, String name, ModuleGrant... grants) {
        return loginAndGetAccessToken(seedStaff(c.tenant(), seedRole(c.tenant().id(), name, false, grants), name.toLowerCase() + "-" + UUID.randomUUID() + "@a.com", phone(), false));
    }

    @Test
    void ac1_salesShowUnitsValueAndChannelPerPackageForTheMonthWithBranchFilterAndAuditedExcelAndPdf() throws Exception {
        Clinic c = clinic();
        String laser = pkg(c, "Laser Six", 3), peel = pkg(c, "Peel Four", 4);
        LocalDate first = today.withDayOfMonth(1).minusMonths(1), last = today.withDayOfMonth(1).minusDays(1); // last calendar month
        String s1 = sell(c, patient(c, "Asha Rao"), laser, 0), s2 = sell(c, patient(c, "Ravi Iyer"), laser, 0), s3 = sell(c, patient(c, "Mina Das"), peel, 0);
        soldOn(s1, first);
        soldOn(s2, last);
        soldOn(s3, first.plusDays(9));
        sell(c, patient(c, "This Month"), peel, 0); // sold today: outside last month
        // a patient-channel sale has no staff actor on the ledger's 'sold' event (none can be made through the product yet)
        su().update("UPDATE package_instance_events SET actor_id = NULL WHERE instance_id = ?::uuid AND event_type = 'sold'", s2);

        Map<String, Object> f = get(c.token(), "package-sales?" + range(first, last) + "&branch=" + c.tenant().id());
        assertThat(f).containsEntry("reportId", "package-sales").containsEntry("group", "money").containsEntry("branch", c.tenant().id().toString());
        assertThat(rows(f)).hasSize(2);
        assertThat(row(f, "package", "Laser Six")).containsEntry("units", 2).containsEntry("desk", 1).containsEntry("whatsapp", 1);
        assertThat(new BigDecimal(row(f, "package", "Laser Six").get("salesValue").toString())).isEqualByComparingTo("4800.00");
        assertThat(row(f, "package", "Peel Four")).containsEntry("units", 1).containsEntry("desk", 1).containsEntry("whatsapp", 0);
        assertThat(kpi(f, "pkgUnits")).isEqualByComparingTo("3");
        assertThat(kpi(f, "pkgValue")).isEqualByComparingTo("8000.00").isEqualByComparingTo(sum(f, "salesValue"));
        assertThat(kpi(f, "pkgDesk")).isEqualByComparingTo("2");
        assertThat(kpi(f, "pkgWhatsapp")).isEqualByComparingTo("1");
        assertThat(kpi(get(c.token(), "package-sales?" + range(today, today)), "pkgUnits")).as("today's sale only").isEqualByComparingTo("1");

        // branch = the clinic itself; another clinic's id is refused, and another clinic sees none of these sales
        Clinic other = clinic();
        assertThat(status(c.token(), "package-sales?" + range(first, last) + "&branch=" + other.tenant().id())).isEqualTo(404);
        assertThat(kpi(get(other.token(), "package-sales?" + range(first, today)), "pkgUnits")).isEqualByComparingTo("0");

        // the spec's address answers the same frame; without view the path keeps its OWN-026 shape
        Map<String, Object> alias = get(c.token(), "packages?view=sales&" + range(first, last));
        assertThat(alias).containsEntry("reportId", "package-sales");
        assertThat(rows(alias)).isEqualTo(rows(f));
        assertThat(get(c.token(), "packages?view=redemption&" + range(first, last))).containsEntry("reportId", "package-redemption");
        assertThat(get(c.token(), "packages?view=expiry&" + range(first, last))).containsEntry("reportId", "package-expiry-loss");
        assertThat(status(c.token(), "packages?view=everything")).isEqualTo(400);
        assertThat(get(c.token(), "packages?" + range(first, last))).containsKeys("salesByDay", "redemption", "expiredWithValue");

        // Excel and PDF through the ADM-047 audited export
        Map<String, Object> body = Map.of("format", "xlsx", "from", first.toString(), "to", last.toString(), "branch", c.tenant().id().toString());
        ResponseEntity<Map> xlsx = exchange("/v1/reports/package-sales/exports", HttpMethod.POST, authedJsonBody(c.token(), body), Map.class);
        assertThat(xlsx.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(xlsx.getBody()).containsEntry("rows", 2).containsEntry("fileName", "package-sales-" + first + "-" + last + ".xlsx");
        String sheet = null;
        try (ZipInputStream z = new ZipInputStream(new ByteArrayInputStream(Base64.getDecoder().decode((String) xlsx.getBody().get("contentBase64"))))) {
            for (var e = z.getNextEntry(); e != null; e = z.getNextEntry()) {
                if (e.getName().equals("xl/worksheets/sheet1.xml")) sheet = new String(z.readAllBytes(), StandardCharsets.UTF_8);
            }
        }
        assertThat(sheet).contains("Laser Six").contains("<v>4800.00</v>").contains("WhatsApp");
        ResponseEntity<Map> pdf = exchange("/v1/reports/package-sales/exports", HttpMethod.POST, authedJsonBody(c.token(),
                Map.of("format", "pdf", "from", first.toString(), "to", last.toString())), Map.class);
        assertThat(new String(Base64.getDecoder().decode((String) pdf.getBody().get("contentBase64")), StandardCharsets.ISO_8859_1)).startsWith("%PDF");
        List<String> audits = inTenantTx(c.tenant().id(), () -> jdbc.queryForList(
                "SELECT after::text FROM audit_log WHERE action = 'report.export' AND after->>'reportId' = 'package-sales' ORDER BY created_at", String.class));
        assertThat(audits).hasSize(2);
        assertThat(audits.get(0)).contains("\"format\": \"xlsx\"").contains("\"rowCount\": 2").contains(first.toString());
    }

    @Test
    void ac2_aPackageUnderHalfRedeemed90DaysAfterItsSaleIsFlaggedLowRedemption() {
        Clinic c = clinic();
        String slow = pkg(c, "Slow Four", 4), good = pkg(c, "Good Four", 4), fresh = pkg(c, "Fresh Four", 4);
        soldOn(sell(c, patient(c, "Slow One"), slow, 1), today.minusDays(90));   // 1 of 4 used, sold exactly 90 days ago
        soldOn(sell(c, patient(c, "Good One"), good, 3), today.minusDays(120));  // 3 of 4 used
        soldOn(sell(c, patient(c, "Fresh One"), fresh, 0), today.minusDays(89)); // nothing used, but only 89 days old

        Map<String, Object> f = get(c.token(), "package-redemption?" + range(today.minusDays(200), today));
        assertThat(row(f, "package", "Slow Four")).containsEntry("flag", "low_redemption").containsEntry("sessSold", 4).containsEntry("sessUsed", 1)
                .containsEntry("matureUnits", 1).containsEntry("lowUnits", 1);
        assertThat(new BigDecimal(row(f, "package", "Slow Four").get("redemption").toString())).isEqualByComparingTo("25.0");
        assertThat(row(f, "package", "Good Four").get("flag")).isNull();
        assertThat(row(f, "package", "Fresh Four").get("flag")).as("not judged before 90 days").isNull();
        assertThat(rows(f).get(0).get("package")).as("flagged first").isEqualTo("Slow Four");
        assertThat(kpi(f, "lowPackages")).isEqualByComparingTo("1");
        assertThat(kpi(f, "sessSold")).isEqualByComparingTo("12");
        assertThat(kpi(f, "sessUsed")).isEqualByComparingTo("4");
        assertThat(kpi(f, "redemption")).isEqualByComparingTo("33.3");
        // the range is the sale date: a range that starts after the slow sale no longer holds it
        assertThat(rows(get(c.token(), "package-redemption?" + range(today.minusDays(89), today)))).extracting(r -> r.get("package")).containsExactly("Fresh Four");
    }

    @Test
    void ac3_expiryLossListsPatientPackageSessionsLostAndValueAndOnlyConsentedPatientsAreWinBackTargets() {
        Clinic c = clinic();
        String laser = pkg(c, "Laser Three", 3);
        String asha = patient(c, "Asha Rao"), ravi = patient(c, "Ravi Iyer"), mina = patient(c, "Mina Das"), noor = patient(c, "Noor Ali"), dev = patient(c, "Dev Jain");
        LocalDate first = today.withDayOfMonth(1).minusMonths(1), last = today.withDayOfMonth(1).minusDays(1); // last calendar month
        String a = sell(c, asha, laser, 0), r = sell(c, ravi, laser, 1), m = sell(c, mina, laser, 2);
        validUntil(a, first.plusDays(3)); // last usable day = first + 10: lapsed last month, 3 sessions lost
        validUntil(r, first.plusDays(5)); // 2 lost
        validUntil(m, first.plusDays(1)); // 1 lost -> 6 sessions lapsed last month
        validUntil(sell(c, noor, laser, 0), today.minusDays(3));              // in grace today: nothing lost yet
        validUntil(sell(c, dev, laser, 0), first.minusMonths(1).plusDays(2)); // lapsed the month before: outside the range
        consent(c, asha, "data_processing");
        consent(c, asha, "marketing");
        consent(c, ravi, "data_processing"); // processing only: not a marketing target
        consent(c, mina, "data_processing");
        consent(c, mina, "marketing");
        inTenantTx(c.tenant().id(), () -> jdbc.update("UPDATE consents SET withdrawn_at = now() WHERE tenant_id = ? AND patient_id = ?::uuid AND consent_type = 'marketing'",
                c.tenant().id(), mina)); // withdrawn: not a target any more

        Map<String, Object> f = get(c.token(), "package-expiry-loss?" + range(first, last));
        assertThat(rows(f)).hasSize(3);
        assertThat(row(f, "patient", "Asha Rao")).containsEntry("package", "Laser Three").containsEntry("lost", 3).containsEntry("winBack", "marketing_consent")
                .containsEntry("lapsedOn", first.plusDays(10).toString());
        assertThat(new BigDecimal(row(f, "patient", "Asha Rao").get("lostValue").toString())).isEqualByComparingTo("2400.00");
        assertThat(row(f, "patient", "Ravi Iyer")).containsEntry("lost", 2).containsEntry("winBack", "no_marketing_consent");
        assertThat(new BigDecimal(row(f, "patient", "Ravi Iyer").get("lostValue").toString())).isEqualByComparingTo("1600.00");
        assertThat(row(f, "patient", "Mina Das")).containsEntry("lost", 1).containsEntry("winBack", "no_marketing_consent");
        assertThat(kpi(f, "lapsedSessions")).isEqualByComparingTo("6").isEqualByComparingTo(sum(f, "lost"));
        assertThat(kpi(f, "lapsedValue")).isEqualByComparingTo("4800.00").isEqualByComparingTo(sum(f, "lostValue"));
        assertThat(kpi(f, "lapsedPatients")).isEqualByComparingTo("3");
        assertThat(kpi(f, "winBackReach")).isEqualByComparingTo("1");
        assertThat((String) f.get("scopeNote")).startsWith("1 of 3 patients have marketing consent");

        // a role without patients:view sees masked names, on screen and in the export
        String accountant = loginWithGrants(c, "Accountant", new ModuleGrant("reports", true, false, false, false, false, false, true),
                new ModuleGrant("packages", true, false, false, false, false, false, false));
        Map<String, Object> masked = get(accountant, "package-expiry-loss?" + range(first, last));
        assertThat(masked).containsEntry("masked", true);
        assertThat(rows(masked)).extracting(x -> x.get("patient")).containsExactlyInAnyOrder("A*** R***", "R*** I***", "M*** D***");
        ResponseEntity<Map> csv = exchange("/v1/reports/package-expiry-loss/exports", HttpMethod.POST, authedJsonBody(accountant,
                Map.of("format", "csv", "from", first.toString(), "to", last.toString())), Map.class);
        assertThat(new String(Base64.getDecoder().decode((String) csv.getBody().get("contentBase64")), StandardCharsets.UTF_8)).contains("A*** R***").doesNotContain("Asha");

        // reports:view without packages:view cannot open any of the three; without reports:export nothing is exported
        String noPackages = loginWithGrants(c, "Reporter", new ModuleGrant("reports", true, false, false, false, false, false, false));
        for (String path : List.of("package-sales", "package-redemption", "package-expiry-loss", "packages?view=expiry")) {
            assertThat(status(noPackages, path)).as(path).isEqualTo(403);
        }
        String viewer = loginWithGrants(c, "Viewer", new ModuleGrant("reports", true, false, false, false, false, false, false),
                new ModuleGrant("packages", true, false, false, false, false, false, false));
        assertThat(exchange("/v1/reports/package-expiry-loss/exports", HttpMethod.POST, authedJsonBody(viewer, Map.of("format", "csv")), Map.class).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void ac4_soldUsedAndLapsedCountsEqualThePackageLedgerExactly() {
        Clinic c = clinic();
        String three = pkg(c, "Recon Three", 3), four = pkg(c, "Recon Four", 4);
        String i1 = sell(c, patient(c, "Recon A"), three, 1), i2 = sell(c, patient(c, "Recon B"), three, 3), i3 = sell(c, patient(c, "Recon C"), four, 2);
        String i4 = sell(c, patient(c, "Recon D"), four, 0), i5 = sell(c, patient(c, "Recon E"), four, 1);
        soldOn(i1, today.minusDays(150));
        soldOn(i3, today.minusDays(100));
        validUntil(i1, today.minusDays(20)); // lapsed 13 days ago: 2 lost
        validUntil(i4, today.minusDays(30)); // lapsed 23 days ago: 4 lost
        validUntil(i5, today.minusDays(2));  // in grace: still usable
        // i2 is completed (3 of 3): nothing can lapse
        String all = range(today.minusDays(200), today);

        // the ledger: one 'sold' and one 'session_redeemed' event per sale and per session used
        long ledgerSold = inTenantTx(c.tenant().id(), () -> jdbc.queryForObject("SELECT count(*) FROM package_instance_events WHERE tenant_id = ? AND event_type = 'sold'", Long.class, c.tenant().id()));
        long ledgerUsed = inTenantTx(c.tenant().id(), () -> jdbc.queryForObject("SELECT count(*) FROM package_instance_events WHERE tenant_id = ? AND event_type = 'session_redeemed'", Long.class, c.tenant().id()));
        // the packages module's own list: sessions sold, and what its 'expired' packages still hold
        List<Map<String, Object>> instances = exchange("/v1/packages/instances", HttpMethod.GET, authed(c.token()), List.class).getBody();
        long moduleSold = 0, moduleUsed = 0, moduleLapsed = 0;
        for (Map<String, Object> i : instances) {
            for (Map<String, Object> item : (List<Map<String, Object>>) i.get("items")) {
                int total = (Integer) item.get("quantityTotal"), used = (Integer) item.get("quantityConsumed");
                moduleSold += total;
                moduleUsed += used;
                if ("expired".equals(i.get("status"))) moduleLapsed += total - used;
            }
        }
        assertThat(ledgerSold).isEqualTo(5);
        assertThat(ledgerUsed).isEqualTo(7).isEqualTo(moduleUsed);
        assertThat(moduleLapsed).isEqualTo(6);

        Map<String, Object> sales = get(c.token(), "package-sales?" + all);
        Map<String, Object> redemption = get(c.token(), "package-redemption?" + all);
        Map<String, Object> expiry = get(c.token(), "package-expiry-loss?" + all);
        assertThat(kpi(sales, "pkgUnits").longValueExact()).as("sold = the ledger's sold events").isEqualTo(ledgerSold).isEqualTo(sum(sales, "units").longValueExact())
                .isEqualTo(sum(redemption, "units").longValueExact());
        assertThat(kpi(redemption, "sessSold").longValueExact()).as("sessions sold = the module's item totals").isEqualTo(moduleSold).isEqualTo(sum(redemption, "sessSold").longValueExact());
        assertThat(kpi(redemption, "sessUsed").longValueExact()).as("used = the ledger's redeemed events").isEqualTo(ledgerUsed).isEqualTo(sum(redemption, "sessUsed").longValueExact());
        assertThat(kpi(expiry, "lapsedSessions").longValueExact()).as("lapsed = what the module's expired packages hold").isEqualTo(moduleLapsed).isEqualTo(sum(expiry, "lost").longValueExact());
        assertThat(rows(expiry)).extracting(r -> r.get("patient")).containsExactly("Recon A", "Recon D"); // newest lapse first
    }
}
