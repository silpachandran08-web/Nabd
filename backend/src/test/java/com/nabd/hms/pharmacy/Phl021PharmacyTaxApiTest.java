package com.nabd.hms.pharmacy;

import com.nabd.hms.billing.document.S15BillingTestBase;
import com.nabd.hms.support.ApiTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/** PHL-021: pharmacy supplies the tax inputs (MRP-inclusive price, zero-rated flag, MRP ceiling, licence numbers); billing computes every figure. */
@SuppressWarnings({"unchecked", "rawtypes"})
class Phl021PharmacyTaxApiTest extends ApiTestBase {

    private static final AtomicInteger PHONE = new AtomicInteger(5200000);
    private static final String GSTIN = "29ABCDE1234F1Z5";

    private record Clinic(SeededTenant tenant, String token) {
    }

    private static JdbcTemplate admin() {
        return new JdbcTemplate(superuserDataSource());
    }

    /** A hybrid pharmacy clinic in the given market; India gets a GST registration so it issues a Tax Invoice. */
    private Clinic clinic(String region, String tag) {
        SeededTenant t = seedTenant();
        if (!"IN".equals(region)) {
            admin().execute("SET session_replication_role = replica; UPDATE tenants SET region_code = '" + region + "' WHERE id = '" + t.id() + "'");
        } else {
            UUID owner = seedOwner("o" + tag + t.id() + "@a.com").id();
            UUID entity = UUID.randomUUID(), reg = UUID.randomUUID();
            admin().update("INSERT INTO legal_entities (id, owner_id, name, entity_type) VALUES (?,?,?,?)", entity, owner, "LE", "company");
            admin().update("INSERT INTO tax_registrations (id, legal_entity_id, jurisdiction_code, gstin_or_vat_no, registered) VALUES (?,?,?,?,?)",
                    reg, entity, "IN", GSTIN, true);
            admin().update("UPDATE tenants SET tax_registration_id = ?, tax_id = ?, tax_id_type = 'GSTIN' WHERE id = ?", reg, GSTIN, t.id());
        }
        SeededStaff staff = seedStaff(t, seedFullAccessRole(t.id()), tag + t.id() + "@p21.test", "+9196" + String.format("%08d", PHONE.incrementAndGet()), false);
        String token = loginAndGetAccessToken(staff);
        assertThat(exchange("/v1/pharmacy/settings", HttpMethod.PATCH, authedJsonBody(token, Map.of("mode", "hybrid", "reason", "PHL-021 test")),
                Map.class).getStatusCode().is2xxSuccessful()).isTrue();
        return new Clinic(t, token);
    }

    private ResponseEntity<Map> addItem(Clinic c, Map<String, Object> extra) {
        Map<String, Object> body = new HashMap<>(Map.of("name", "Tax item " + UUID.randomUUID().toString().substring(0, 6), "isRx", false, "hsnCode", "3004",
                "price", 112.00, "stockQty", 50));
        body.putAll(extra);
        return exchange("/v1/pharmacy/items", HttpMethod.POST, authedJsonBody(c.token(), body), Map.class);
    }

    private ResponseEntity<Map> quote(Clinic c, List<Map<String, Object>> lines) {
        return exchange("/v1/billing/quotes", HttpMethod.POST, authedJsonBody(c.token(), Map.of("lines", lines)), Map.class);
    }

    private static Map<String, Object> line(Object code, int qty) {
        return new HashMap<>(Map.of("chargeCode", code, "quantity", qty));
    }

    private static List<Map<String, Object>> taxes(ResponseEntity<Map> q, int i) {
        return (List<Map<String, Object>>) ((Map<?, ?>) ((List<?>) q.getBody().get("lines")).get(i)).get("taxes");
    }

    private static long totalMinor(ResponseEntity<Map> q) {
        return ((Number) ((Map<?, ?>) q.getBody().get("totals")).get("totalMinor")).longValue();
    }

    @Test
    void indiaMrp112InclusiveOfTwelvePercentGivesTaxable100CgstSixSgstSix() {
        Clinic c = clinic("IN", "in1");
        ResponseEntity<Map> item = addItem(c, Map.of("taxRatePercent", 12, "priceInclusiveOfTax", true, "mrp", 112.00));
        assertThat(item.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(item.getBody().get("priceInclusiveOfTax")).isEqualTo(true);
        assertThat(item.getBody().get("zeroRated")).isEqualTo(false);
        ResponseEntity<Map> q = quote(c, List.of(line(item.getBody().get("code"), 1)));
        assertThat(q.getStatusCode()).as("%s", q.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(q.getBody().get("documentType")).isEqualTo("invoice");
        Map<?, ?> l = (Map<?, ?>) ((List<?>) q.getBody().get("lines")).get(0);
        assertThat(((Number) l.get("taxableMinor")).longValue()).isEqualTo(10000);
        assertThat(taxes(q, 0)).extracting(t -> t.get("type")).containsExactly("CGST", "SGST");
        assertThat(taxes(q, 0)).extracting(t -> ((Number) t.get("amountMinor")).longValue()).containsExactly(600L, 600L);
        assertThat(totalMinor(q)).isEqualTo(11200);
    }

    @Test
    void withoutTheInclusiveFlagTheSamePriceHasTaxAddedOnTop() {
        Clinic c = clinic("IN", "in2");
        ResponseEntity<Map> item = addItem(c, Map.of("taxRatePercent", 12, "price", 100.00));
        assertThat(item.getBody().get("priceInclusiveOfTax")).isNull();
        ResponseEntity<Map> q = quote(c, List.of(line(item.getBody().get("code"), 1)));
        assertThat(totalMinor(q)).isEqualTo(11200);
        assertThat(((Number) ((Map<?, ?>) ((List<?>) q.getBody().get("lines")).get(0)).get("taxableMinor")).longValue()).isEqualTo(10000);
    }

    @Test
    void aSellingPriceAboveTheMrpIsRefusedAtQuoteOverrideAndCheckoutButTheMrpItselfPasses() {
        Clinic c = clinic("IN", "mrp");
        ResponseEntity<Map> item = addItem(c, Map.of("taxRatePercent", 12, "price", 120.00, "mrp", 112.00));
        String code = (String) item.getBody().get("code");
        ResponseEntity<Map> q = quote(c, List.of(line(code, 1)));
        assertThat(q.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(String.valueOf(q.getBody().get("type"))).endsWith("/price-above-mrp");
        ResponseEntity<Map> otc = exchange("/v1/billing/otc-checkout", HttpMethod.POST, authedJsonBody(c.token(), Map.of("lineItems", List.of(line(code, 1)))), Map.class);
        assertThat(otc.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        // a price override above the MRP is refused too; at the MRP it passes
        ResponseEntity<Map> cheap = addItem(c, Map.of("taxRatePercent", 12, "price", 100.00, "mrp", 112.00));
        Map<String, Object> over = line(cheap.getBody().get("code"), 1);
        over.put("override", Map.of("unitPrice", 112.01, "reasonCode", "other"));
        assertThat(quote(c, List.of(over)).getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        over.put("override", Map.of("unitPrice", 112.00, "reasonCode", "other"));
        assertThat(quote(c, List.of(over)).getStatusCode()).isEqualTo(HttpStatus.OK);
        // an item without an MRP has no ceiling
        ResponseEntity<Map> free = addItem(c, Map.of("taxRatePercent", 12, "price", 5000.00));
        assertThat(quote(c, List.of(line(free.getBody().get("code"), 1))).getStatusCode()).isEqualTo(HttpStatus.OK);
        // the PATCH leaves the MRP when the request omits it
        String id = (String) item.getBody().get("id");
        ResponseEntity<Map> patched = exchange("/v1/pharmacy/items/" + id, HttpMethod.PATCH, authedJsonBody(c.token(), Map.of(
                "name", "Renamed", "price", 112.00, "taxRatePercent", 12)), Map.class);
        assertThat(patched.getStatusCode()).as("%s", patched.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(new java.math.BigDecimal(String.valueOf(patched.getBody().get("mrp")))).isEqualByComparingTo("112.00");
    }

    @Test
    void ksaZeroRatedMedicineHasNoVatAndPrintsItsReasonOthersGetFifteenPercent() {
        Clinic c = clinic("SA", "sa");
        ResponseEntity<Map> zero = addItem(c, Map.of("zeroRated", true, "price", 100.00));
        ResponseEntity<Map> std = addItem(c, Map.of("price", 100.00));
        assertThat(std.getBody().get("taxRatePercent")).as("market headline rate by default").isEqualTo(15.0);
        ResponseEntity<Map> q = quote(c, List.of(line(zero.getBody().get("code"), 1), line(std.getBody().get("code"), 1)));
        assertThat(q.getStatusCode()).as("%s", q.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(taxes(q, 0)).isEmpty();
        assertThat(taxes(q, 1)).extracting(t -> t.get("type")).containsExactly("VAT");
        assertThat(((Number) taxes(q, 1).get(0).get("amountMinor")).longValue()).isEqualTo(1500);
        assertThat(totalMinor(q)).isEqualTo(10000 + 11500);
        // the printed invoice states the zero-rating once, on the zero-rated line only
        ResponseEntity<Map> inv = exchange("/v1/billing/otc-checkout", HttpMethod.POST, authedJsonBody(c.token(), Map.of("lineItems",
                List.of(line(zero.getBody().get("code"), 1), line(std.getBody().get("code"), 1)))), Map.class);
        assertThat(inv.getStatusCode()).as("%s", inv.getBody()).isEqualTo(HttpStatus.OK);
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(c.token());
        h.setAccept(List.of(MediaType.APPLICATION_PDF, MediaType.APPLICATION_PROBLEM_JSON));
        ResponseEntity<byte[]> pdf = exchange("/v1/billing/invoices/" + inv.getBody().get("id") + "/document", HttpMethod.GET, new HttpEntity<>(h), byte[].class);
        String text = String.join("\n", S15BillingTestBase.pdfLines(pdf.getBody()));
        assertThat(text).contains("Zero-rated medicine, VAT 0%");
        assertThat(text.split("Zero-rated medicine", -1)).hasSize(2);
    }

    @Test
    void uaeGetsFivePercentVatAndQatarHasNoVatLine() {
        Clinic ae = clinic("AE", "ae");
        ResponseEntity<Map> a = addItem(ae, Map.of("price", 100.00));
        ResponseEntity<Map> qa = quote(ae, List.of(line(a.getBody().get("code"), 1)));
        assertThat(taxes(qa, 0)).extracting(t -> t.get("type")).containsExactly("VAT");
        assertThat(((Number) taxes(qa, 0).get(0).get("amountMinor")).longValue()).isEqualTo(500);
        Clinic qt = clinic("QA", "qa");
        ResponseEntity<Map> b = addItem(qt, Map.of("price", 100.00));
        ResponseEntity<Map> qq = quote(qt, List.of(line(b.getBody().get("code"), 1)));
        assertThat(qq.getStatusCode()).as("%s", qq.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(taxes(qq, 0)).isEmpty();
        assertThat(totalMinor(qq)).isEqualTo(10000);
    }

    @Test
    void anIndianPharmacyInvoiceCarriesTheGstinAndDrugLicenceNumbersInTheHeader() {
        Clinic c = clinic("IN", "lic");
        assertThat(exchange("/v1/pharmacy/settings", HttpMethod.PATCH, authedJsonBody(c.token(), Map.of("licenceNumbers",
                Map.of("20B", "TEST-LIC-20B", "21B", "TEST-LIC-21B"))), Map.class).getStatusCode().is2xxSuccessful()).isTrue();
        ResponseEntity<Map> item = addItem(c, Map.of("taxRatePercent", 12, "priceInclusiveOfTax", true));
        ResponseEntity<Map> inv = exchange("/v1/billing/otc-checkout", HttpMethod.POST, authedJsonBody(c.token(), Map.of("lineItems",
                List.of(line(item.getBody().get("code"), 1)))), Map.class);
        assertThat(inv.getStatusCode()).as("%s", inv.getBody()).isEqualTo(HttpStatus.OK);
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(c.token());
        ResponseEntity<byte[]> pdf = exchange("/v1/billing/invoices/" + inv.getBody().get("id") + "/document", HttpMethod.GET, new HttpEntity<>(h), byte[].class);
        List<String> lines = S15BillingTestBase.pdfLines(pdf.getBody());
        int gstin = lines.indexOf("GSTIN: " + GSTIN + "  State code: 29");
        assertThat(gstin).isGreaterThanOrEqualTo(0);
        assertThat(lines.subList(gstin + 1, gstin + 3)).containsExactly("Drug licence 20B: TEST-LIC-20B", "Drug licence 21B: TEST-LIC-21B");
    }

    @Test
    void anInvoiceWithoutAPharmacyLineCarriesNoLicenceLines() {
        Clinic c = clinic("IN", "nolic");
        exchange("/v1/pharmacy/settings", HttpMethod.PATCH, authedJsonBody(c.token(), Map.of("licenceNumbers", Map.of("20B", "TEST-LIC-20B"))), Map.class);
        admin().update("INSERT INTO charge_catalogue (tenant_id, code, name, category, base_amount, tax_rate_percent) VALUES (?, 'SVC', 'Plain service', 'Service', 100, 0)", c.tenant().id());
        ResponseEntity<Map> inv = exchange("/v1/billing/otc-checkout", HttpMethod.POST, authedJsonBody(c.token(), Map.of("lineItems", List.of(line("SVC", 1)))), Map.class);
        assertThat(inv.getStatusCode()).as("%s", inv.getBody()).isEqualTo(HttpStatus.OK);
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(c.token());
        ResponseEntity<byte[]> pdf = exchange("/v1/billing/invoices/" + inv.getBody().get("id") + "/document", HttpMethod.GET, new HttpEntity<>(h), byte[].class);
        assertThat(String.join("\n", S15BillingTestBase.pdfLines(pdf.getBody()))).doesNotContain("Drug licence");
    }
}
