package com.nabd.hms.organization.series;

import com.nabd.hms.common.ClinicClock;
import com.nabd.hms.organization.api.SeriesAllocator;
import com.nabd.hms.organization.api.SeriesAllocator.Kind;
import com.nabd.hms.shared.money.Money;
import com.nabd.hms.support.ApiTestBase;
import com.nabd.hms.support.ClinicianFixture;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** FIN-006: gapless series per tenant/kind/FY, 16-char GST limit, own kinds, minor units. */
class InvoiceSeriesApiTest extends ApiTestBase {

    @Autowired
    SeriesAllocator allocator;
    @Autowired
    InvoiceSeriesService service;
    @Autowired
    ClinicClock clinicClock;

    private final JdbcTemplate su = new JdbcTemplate(superuserDataSource());

    @Override
    protected SeededStaff seedStaff(SeededTenant tenant, UUID roleId, String email, String mobilePhone, boolean mfaEnabled,
                                    boolean emailVerified, boolean mobileVerified) {
        return ClinicianFixture.licensed(super.seedStaff(tenant, roleId, email, mobilePhone, mfaEnabled, emailVerified, mobileVerified));
    }

    private String currentFy(SeededTenant t) {
        return InvoiceSeriesService.fiscalYear(clinicClock.today(t.id()), 4);
    }

    private static String uniqueName(int n) {
        StringBuilder b = new StringBuilder();
        for (int x = n, i = 0; i < 6; i++, x /= 26) b.append((char) ('a' + (x * 7 + i * 3) % 26)).append(i % 2 == 0 ? "" : "");
        return Character.toUpperCase(b.charAt(0)) + b.substring(1) + " Q" + n;
    }

    private String checkoutPendingEntry(String token, UUID doctorId, int n) {
        String phone = "+91" + (9_000_000_000L + (n % 100_000) * 7_919_173L % 999_999_999L);
        ResponseEntity<Map> reg = exchange("/v1/patients", HttpMethod.POST, authedJsonBody(token, Map.of(
                "name", uniqueName(n), "phone", phone, "dob", LocalDate.of(1950 + n % 50, 1 + n % 12, 1 + n % 28).toString(), "gender", "male")), Map.class);
        assertThat(reg.getStatusCode().is2xxSuccessful()).as("register %s: %s", n, reg.getBody()).isTrue();
        String patientId = (String) reg.getBody().get("id");
        String entry = (String) exchange("/v1/queue/check-in", HttpMethod.POST, authedJsonBody(token, Map.of(
                "patientId", patientId, "doctorId", doctorId.toString())), Map.class).getBody().get("id");
        for (String s : new String[]{"waiting", "vitals_pending", "vitals_done", "in_consult", "checkout_pending"}) {
            exchange("/v1/queue/" + entry + "/status", HttpMethod.PATCH, authedJsonBody(token, Map.of("status", s)), Map.class);
        }
        return entry;
    }

    private ResponseEntity<Map> checkout(String token, String entry) {
        return exchange("/v1/billing/checkout/" + entry, HttpMethod.POST, authedJsonBody(token, Map.of(
                "lineItems", List.of(Map.of("override", Map.of("reasonCode", "other"), "chargeCode", "CONSULT", "chargeName", "Consultation",
                        "category", "Consultation", "quantity", 1, "unitPrice", 500.50, "taxRatePercent", 18.00)),
                "discount", 0)), Map.class);
    }

    /** tenants.region is immutable by trigger (NB-002); a test fixture bypasses it on one connection only. */
    private SeededTenant tenantIn(String region) {
        SeededTenant t = seedTenant();
        su.execute((java.sql.Connection c) -> {
            try (var st = c.createStatement()) {
                st.execute("SET session_replication_role = replica");
                st.execute("UPDATE tenants SET region = '" + region + "' WHERE id = '" + t.id() + "'");
                st.execute("SET session_replication_role = origin");
            }
            return null;
        });
        clinicClock.evict(t.id());
        return t;
    }

    private SeededStaff owner(SeededTenant t, String email, String phone) {
        return seedStaff(t, seedFullAccessRole(t.id()), email, phone, false);
    }

    private <T> List<T> parallel(int n, java.util.function.IntFunction<Callable<T>> job) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(n);
        try {
            List<Future<T>> fs = new ArrayList<>();
            for (int i = 0; i < n; i++) fs.add(pool.submit(job.apply(i)));
            List<T> out = new ArrayList<>();
            for (Future<T> f : fs) out.add(f.get());
            return out;
        } finally {
            pool.shutdown();
        }
    }

    @Test
    void fiftyParallelCheckoutsGetConsecutiveNumbersWithNoGapsOrDuplicates() throws Exception {
        SeededTenant tenant = seedTenant();
        SeededStaff staff = owner(tenant, "fin6a@a.com", "+919700006001");
        String token = loginAndGetAccessToken(staff);
        List<String> entries = new ArrayList<>();
        for (int i = 0; i < 50; i++) entries.add(checkoutPendingEntry(token, staff.id(), 10_000 + i));

        List<ResponseEntity<Map>> resps = parallel(50, i -> () -> checkout(token, entries.get(i)));

        assertThat(resps).allSatisfy(r -> assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK));
        String prefix = "INV-" + currentFy(tenant) + "-";
        List<String> numbers = resps.stream().map(r -> (String) r.getBody().get("invoiceNumber")).sorted().toList();
        List<String> expected = new ArrayList<>();
        for (int i = 1; i <= 50; i++) expected.add(prefix + String.format("%06d", i));
        assertThat(numbers).containsExactlyElementsOf(expected);
        assertThat(prefix + "000001").hasSizeLessThanOrEqualTo(16);
        // every receipt-less invoice still has currency and minor units
        List<Map<String, Object>> rows = su.queryForList("SELECT currency, total, total_minor, subtotal_minor, invoice_series_id FROM invoices WHERE tenant_id = ?", tenant.id());
        assertThat(rows).hasSize(50).allSatisfy(r -> {
            assertThat(r.get("currency")).isEqualTo("INR");
            assertThat(((Number) r.get("total_minor")).longValue()).isEqualTo(((BigDecimal) r.get("total")).movePointRight(2).longValueExact());
        });
        assertThat(su.queryForObject("SELECT next_number FROM invoice_series WHERE tenant_id = ? AND kind = 'invoice'", Long.class, tenant.id())).isEqualTo(51L);
    }

    @Test
    void parallelAllocationsWithHalfRolledBackLeaveNoGap() throws Exception {
        SeededTenant tenant = seedTenant();
        List<Long> committed = parallel(50, i -> () -> {
            try {
                return inTenantTx(tenant.id(), () -> {
                    long n = allocator.allocate(Kind.INVOICE).number();
                    if (i % 2 == 1) throw new IllegalStateException("rollback");
                    return n;
                });
            } catch (IllegalStateException e) {
                return -1L;
            }
        });
        List<Long> got = committed.stream().filter(n -> n > 0).sorted().toList();
        assertThat(got).hasSize(25).containsExactlyElementsOf(java.util.stream.LongStream.rangeClosed(1, 25).boxed().toList());
        assertThat(su.queryForObject("SELECT next_number FROM invoice_series WHERE tenant_id = ? AND kind = 'invoice'", Long.class, tenant.id())).isEqualTo(26L);
    }

    @Test
    void rolledBackAllocationConsumesNoNumber() {
        SeededTenant tenant = seedTenant();
        assertThatThrownBy(() -> inTenantTx(tenant.id(), () -> {
            assertThat(allocator.allocate(Kind.INVOICE).number()).isEqualTo(1L);
            throw new IllegalStateException("boom");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(inTenantTx(tenant.id(), () -> allocator.allocate(Kind.INVOICE).number())).isEqualTo(1L);
    }

    @Test
    void financialYearRollsOverOnFirstAprilInIndiaWithALazyRowAndRestartAtOne() {
        SeededTenant tenant = seedTenant(); // region IN
        var last = inTenantTx(tenant.id(), () -> service.allocate(Kind.INVOICE, LocalDate.of(2026, 3, 31)));
        var first = inTenantTx(tenant.id(), () -> service.allocate(Kind.INVOICE, LocalDate.of(2026, 3, 31)));
        assertThat(last.formatted()).isEqualTo("INV-25-26-000001");
        assertThat(first.formatted()).isEqualTo("INV-25-26-000002");
        assertThat(su.queryForObject("SELECT count(*) FROM invoice_series WHERE tenant_id = ?", Long.class, tenant.id())).isEqualTo(1L);

        var april = inTenantTx(tenant.id(), () -> service.allocate(Kind.INVOICE, LocalDate.of(2026, 4, 1)));
        assertThat(april.formatted()).isEqualTo("INV-26-27-000001");
        assertThat(su.queryForObject("SELECT count(*) FROM invoice_series WHERE tenant_id = ?", Long.class, tenant.id())).isEqualTo(2L);
    }

    @Test
    void gccSeriesRollOnFirstJanuary() {
        SeededTenant tenant = tenantIn("KSA");
        assertThat(inTenantTx(tenant.id(), () -> service.allocate(Kind.INVOICE, LocalDate.of(2026, 12, 31))).formatted()).isEqualTo("INV-2026-000001");
        assertThat(inTenantTx(tenant.id(), () -> service.allocate(Kind.INVOICE, LocalDate.of(2027, 1, 1))).formatted()).isEqualTo("INV-2027-000001");
    }

    @Test
    void fiscalYearLabelByJurisdiction() {
        assertThat(InvoiceSeriesService.fiscalYear(LocalDate.of(2026, 3, 31), 4)).isEqualTo("25-26");
        assertThat(InvoiceSeriesService.fiscalYear(LocalDate.of(2026, 4, 1), 4)).isEqualTo("26-27");
        assertThat(InvoiceSeriesService.fiscalYear(LocalDate.of(2099, 12, 31), 4)).isEqualTo("99-00");
        assertThat(InvoiceSeriesService.fiscalYear(LocalDate.of(2026, 12, 31), 1)).isEqualTo("2026");
        assertThat(InvoiceSeriesService.fiscalYear(LocalDate.of(2027, 1, 1), 1)).isEqualTo("2027");
        assertThat(InvoiceSeriesService.defaultStartMonth("IN")).isEqualTo(4);
        assertThat(InvoiceSeriesService.defaultStartMonth("KSA")).isEqualTo(1);
    }

    @Test
    void twoBranchesOnOneGstinHaveIndependentSeriesAndPrefixes() {
        SeededTenant a = seedTenant();
        SeededTenant b = seedTenant();
        UUID owner = UUID.randomUUID();
        UUID legal = UUID.randomUUID();
        UUID reg = UUID.randomUUID();
        su.update("INSERT INTO owners (id, name, email, status) VALUES (?, 'O', ?, 'active')", owner, "fin6-" + owner + "@a.com");
        su.update("INSERT INTO legal_entities (id, owner_id, name, entity_type) VALUES (?, ?, 'LE', 'company')", legal, owner);
        su.update("INSERT INTO tax_registrations (id, legal_entity_id, jurisdiction_code, gstin_or_vat_no, registered) VALUES (?, ?, 'IN', 'TESTGSTIN', true)", reg, legal);
        su.update("UPDATE tenants SET tax_registration_id = ? WHERE id IN (?, ?)", reg, a.id(), b.id());
        String tokenA = loginAndGetAccessToken(owner(a, "fin6b1@a.com", "+919700006002"));
        String tokenB = loginAndGetAccessToken(owner(b, "fin6b2@a.com", "+919700006003"));

        assertThat(exchange("/v1/organization/invoice-series/invoice", HttpMethod.PUT, authedJsonBody(tokenA, Map.of(
                "prefixPattern", "SKA/{FY}/", "taxRegistrationId", reg.toString())), Map.class).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(exchange("/v1/organization/invoice-series/invoice", HttpMethod.PUT, authedJsonBody(tokenB, Map.of(
                "prefixPattern", "SKB/{FY}/", "taxRegistrationId", reg.toString())), Map.class).getStatusCode()).isEqualTo(HttpStatus.OK);

        String fy = currentFy(a);
        assertThat(inTenantTx(a.id(), () -> allocator.allocate(Kind.INVOICE).formatted())).isEqualTo("SKA/" + fy + "/000001");
        assertThat(inTenantTx(a.id(), () -> allocator.allocate(Kind.INVOICE).formatted())).isEqualTo("SKA/" + fy + "/000002");
        assertThat(inTenantTx(b.id(), () -> allocator.allocate(Kind.INVOICE).formatted())).isEqualTo("SKB/" + fy + "/000001");

        // tenant isolation: B's listing never shows A's rows
        ResponseEntity<Map> listB = exchange("/v1/organization/invoice-series", HttpMethod.GET, authed(tokenB), Map.class);
        List<Map<String, Object>> items = (List<Map<String, Object>>) listB.getBody().get("items");
        assertThat(items).isNotEmpty().allSatisfy(i -> assertThat((String) i.get("prefix")).startsWith("SKB/"));
        // a registration that is not the facility's is refused
        assertThat(exchange("/v1/organization/invoice-series/receipt", HttpMethod.PUT, authedJsonBody(tokenB, Map.of(
                "prefixPattern", "R/{FY}/", "taxRegistrationId", UUID.randomUUID().toString())), Map.class).getStatusCode())
                .isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
    }

    @Test
    void creditNoteAndReceiptDrawFromTheirOwnSeries() {
        SeededTenant tenant = seedTenant();
        String fy = currentFy(tenant);
        assertThat(inTenantTx(tenant.id(), () -> allocator.allocate(Kind.INVOICE).formatted())).isEqualTo("INV-" + fy + "-000001");
        assertThat(inTenantTx(tenant.id(), () -> allocator.allocate(Kind.INVOICE).formatted())).isEqualTo("INV-" + fy + "-000002");
        assertThat(inTenantTx(tenant.id(), () -> allocator.allocate(Kind.CREDIT_NOTE).formatted())).isEqualTo("CN-" + fy + "-000001");
        assertThat(inTenantTx(tenant.id(), () -> allocator.allocate(Kind.RECEIPT).formatted())).isEqualTo("RCT-" + fy + "-000001");
        assertThat(su.queryForList("SELECT kind FROM invoice_series WHERE tenant_id = ? ORDER BY kind", String.class, tenant.id()))
                .containsExactly("credit_note", "invoice", "receipt");
    }

    @Test
    void aPaymentTakesAReceiptNumberFromTheReceiptSeries() {
        SeededTenant tenant = seedTenant();
        SeededStaff staff = owner(tenant, "fin6r@a.com", "+919700006004");
        String token = loginAndGetAccessToken(staff);
        String entry = checkoutPendingEntry(token, staff.id(), 20_001);
        String invoiceId = (String) checkout(token, entry).getBody().get("id");
        ResponseEntity<Map> pay = exchange("/v1/billing/invoices/" + invoiceId + "/payments", HttpMethod.POST,
                authedJsonBody(token, Map.of("method", "cash", "amount", 100.00)), Map.class);
        assertThat(pay.getStatusCode().is2xxSuccessful()).isTrue();
        Map<String, Object> row = su.queryForMap("SELECT receipt_number, currency, amount_minor FROM invoice_payments WHERE tenant_id = ?", tenant.id());
        assertThat(row.get("receipt_number")).isEqualTo("RCT-" + currentFy(tenant) + "-000001");
        assertThat(row.get("currency")).isEqualTo("INR");
        assertThat(((Number) row.get("amount_minor")).longValue()).isEqualTo(10_000L);
    }

    @Test
    void configurationIsRefusedOnceUsedAndWhenTooLongForAnIndianGstin() {
        SeededTenant tenant = seedTenant();
        String token = loginAndGetAccessToken(owner(tenant, "fin6c@a.com", "+919700006005"));
        String fy = currentFy(tenant);

        // "SKN/25-26/" is 10 characters + 6 digits = exactly 16: accepted (boundary)
        ResponseEntity<Map> ok = exchange("/v1/organization/invoice-series/invoice", HttpMethod.PUT,
                authedJsonBody(token, Map.of("prefixPattern", "SKN/{FY}/")), Map.class);
        assertThat(ok.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(ok.getBody().get("prefix")).isEqualTo("SKN/" + fy + "/");
        assertThat(ok.getBody().get("nextNumber")).isEqualTo(1);

        ResponseEntity<Map> tooLong = exchange("/v1/organization/invoice-series/invoice", HttpMethod.PUT,
                authedJsonBody(token, Map.of("prefixPattern", "SKN/{FY}/X")), Map.class);
        assertThat(tooLong.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(tooLong.getBody().get("code")).isEqualTo("NUMBER_TOO_LONG");

        assertThat(exchange("/v1/organization/invoice-series/invoice", HttpMethod.PUT,
                authedJsonBody(token, Map.of("prefixPattern", "NOFY-")), Map.class).getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(exchange("/v1/organization/invoice-series/bogus", HttpMethod.PUT,
                authedJsonBody(token, Map.of("prefixPattern", "A/{FY}/")), Map.class).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

        assertThat(inTenantTx(tenant.id(), () -> allocator.allocate(Kind.INVOICE).formatted())).isEqualTo("SKN/" + fy + "/000001");
        ResponseEntity<Map> inUse = exchange("/v1/organization/invoice-series/invoice", HttpMethod.PUT,
                authedJsonBody(token, Map.of("prefixPattern", "ABC/{FY}/")), Map.class);
        assertThat(inUse.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(inUse.getBody().get("code")).isEqualTo("SERIES_IN_USE");

        // the 7th digit would make an Indian number 17 characters: refused at allocation, nothing consumed
        su.update("UPDATE invoice_series SET next_number = 1000000 WHERE tenant_id = ? AND kind = 'invoice'", tenant.id());
        assertThatThrownBy(() -> inTenantTx(tenant.id(), () -> allocator.allocate(Kind.INVOICE))).hasMessageContaining("16");
        assertThat(su.queryForObject("SELECT next_number FROM invoice_series WHERE tenant_id = ? AND kind = 'invoice'", Long.class, tenant.id())).isEqualTo(1000000L);
    }

    @Test
    void seriesEndpointsNeedSetupPermissions() {
        SeededTenant tenant = seedTenant();
        UUID roleId = seedRole(tenant.id(), "Reader", false, fullGrant("patients"));
        String token = loginAndGetAccessToken(seedStaff(tenant, roleId, "fin6p@a.com", "+919700006006", false));
        assertThat(exchange("/v1/organization/invoice-series", HttpMethod.GET, authed(token), Map.class).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(exchange("/v1/organization/invoice-series/invoice", HttpMethod.PUT, authedJsonBody(token, Map.of("prefixPattern", "A/{FY}/")), Map.class)
                .getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void minorUnitsMatchTheDatabaseForInrSarAedQarKwd() {
        Random rnd = new Random(42);
        for (String cur : List.of("INR", "SAR", "AED", "QAR", "KWD")) {
            int exp = java.util.Currency.getInstance(cur).getDefaultFractionDigits();
            assertThat(exp).isEqualTo(cur.equals("KWD") ? 3 : 2);
            for (int i = 0; i < 200; i++) {
                BigDecimal amount = BigDecimal.valueOf(rnd.nextLong(0, 1_000_000_000L), exp);
                Money m = Money.of(amount, cur);
                assertThat(m.amountMinor()).isEqualTo(amount.movePointRight(exp).longValueExact());
                assertThat(m.amount()).isEqualByComparingTo(amount); // round trip
                assertThat(su.queryForObject("SELECT to_minor(?, ?)", Long.class, amount, cur)).isEqualTo(m.amountMinor());
            }
        }
        assertThat(Money.of(new BigDecimal("0.005"), "INR").amountMinor()).isEqualTo(1L); // half away from zero, same as SQL
        assertThat(su.queryForObject("SELECT to_minor(0.005, 'INR')", Long.class)).isEqualTo(1L);
    }

    @Test
    void everyMonetaryHeaderCarriesCurrencyAndMinorUnits() {
        SeededTenant tenant = tenantIn("KSA");
        UUID staff = owner(tenant, "fin6m@a.com", "+919700006007").id();
        su.update("INSERT INTO day_closes (tenant_id, close_date, status, expected_cash, counted_cash, variance, closed_by) VALUES (?, '2026-01-02', 'closed', 100.10, 100.05, -0.05, ?)",
                tenant.id(), staff);
        Map<String, Object> d = su.queryForMap("SELECT currency, expected_cash_minor, counted_cash_minor, variance_minor FROM day_closes WHERE tenant_id = ?", tenant.id());
        assertThat(d.get("currency")).isEqualTo("SAR");
        assertThat(((Number) d.get("expected_cash_minor")).longValue()).isEqualTo(10010L);
        assertThat(((Number) d.get("variance_minor")).longValue()).isEqualTo(-5L);
        // a tenant with no known region cannot write money rows at all (currency would be null)
        assertThat(su.queryForObject("SELECT tenant_currency(?)", String.class, UUID.randomUUID())).isNull();
    }
}
