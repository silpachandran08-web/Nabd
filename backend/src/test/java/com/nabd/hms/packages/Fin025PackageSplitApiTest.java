package com.nabd.hms.packages;

import com.nabd.hms.billing.document.S15BillingTestBase;
import com.nabd.hms.support.ApiTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * FIN-025 end to end: the release gate on the split, the WE-01 sale invoiced per component (figures of golden case IN-047), the
 * KSA tax-inclusive bundle whose invoice total is the bundle price (golden PHL-SA-025), the per-line credit note after 3 of 6
 * sessions, and the "you save" line on the printed invoice.
 */
@SuppressWarnings({"unchecked", "rawtypes"})
class Fin025PackageSplitApiTest extends ApiTestBase {

    private static final AtomicInteger PHONES = new AtomicInteger((int) (System.nanoTime() % 4_000_000));

    @Autowired
    com.nabd.hms.common.ClinicClock clock;

    private record Env(SeededTenant tenant, String maker, String checker) {
    }

    private String phone() {
        return "+91973" + String.format("%07d", 1_000_000 + PHONES.getAndIncrement());
    }

    private Env env(String region) {
        SeededTenant t = seedTenant();
        if (!"IN".equals(region)) { // the region is fixed at provisioning: set it the way Com024MessagingSettingsApiTest does
            try (var c = superuserDataSource().getConnection(); var st = c.createStatement()) {
                st.execute("SET session_replication_role = replica");
                st.executeUpdate("UPDATE tenants SET region_code = '" + region + "', region = '" + region + "' WHERE id = '" + t.id() + "'");
            } catch (java.sql.SQLException e) {
                throw new IllegalStateException(e);
            }
            clock.evict(t.id());
        }
        UUID role = seedFullAccessRole(t.id());
        return new Env(t, loginAndGetAccessToken(seedStaff(t, role, "mk" + UUID.randomUUID() + "@a.com", phone(), false)),
                loginAndGetAccessToken(seedStaff(t, role, "ck" + UUID.randomUUID() + "@a.com", phone(), false)));
    }

    /** A price-list head with its tax class; returns its id as GET /v1/packages/charge-heads lists it. */
    private String head(Env e, String code, String name, double rate, String taxCategory, String sacHsn) {
        inTenantTx(e.tenant().id(), () -> jdbc.update("INSERT INTO charge_catalogue (tenant_id, code, name, category, base_amount, tax_rate_percent, " +
                "tax_category, sac_hsn) VALUES (?,?,?,?,?,?,?,?)", e.tenant().id(), code, name, "Service", 100, rate, taxCategory, sacHsn));
        List<Map<String, Object>> heads = exchange("/v1/packages/charge-heads", HttpMethod.GET, authed(e.maker()), List.class).getBody();
        return (String) heads.stream().filter(h -> code.equals(h.get("code"))).findFirst().orElseThrow().get("id");
    }

    private static Map<String, Object> item(String type, String name, int qty, double price, double fallbackRate, String headId) {
        Map<String, Object> m = new HashMap<>(Map.of("itemType", type, "name", name, "quantity", qty, "unitListPrice", price, "taxRatePercent", fallbackRate));
        if (headId != null) m.put("chargeCatalogueId", headId);
        return m;
    }

    private ResponseEntity<Map> createPackage(Env e, String name, double price, boolean inclusive, List<Map<String, Object>> items) {
        Map<String, Object> body = new HashMap<>(Map.of("name", name, "packageType", "combination", "speciality", "Dermatology", "description", "d",
                "price", price, "taxInclusive", inclusive, "validityDays", 180, "validityStarts", "purchase_date"));
        body.put("graceDays", 7);
        body.put("items", items);
        return exchange("/v1/packages", HttpMethod.POST, authedJsonBody(e.maker(), body), Map.class);
    }

    private ResponseEntity<Map> settings(Env e, Map<String, Object> body) {
        return exchange("/v1/packages/settings", HttpMethod.PATCH, authedJsonBody(e.maker(), body), Map.class);
    }

    private Map sell(Env e, String packageId) {
        String patient = (String) exchange("/v1/patients", HttpMethod.POST, authedJsonBody(e.maker(), Map.of(
                "name", "Package Patient", "phone", phone(), "dob", "1991-02-03", "gender", "female")), Map.class).getBody().get("id");
        assertThat(exchange("/v1/packages/" + packageId + "/activate", HttpMethod.POST, authed(e.maker()), Map.class).getStatusCode()).isEqualTo(HttpStatus.OK);
        ResponseEntity<Map> r = exchange("/v1/packages/sell", HttpMethod.POST, authedJsonBody(e.maker(), Map.of(
                "patientId", patient, "packageId", packageId, "paymentMethod", "upi")), Map.class);
        assertThat(r.getStatusCode()).as(String.valueOf(r.getBody())).isEqualTo(HttpStatus.CREATED);
        return r.getBody();
    }

    private static Map<String, Object> itemNamed(Map instance, String name) {
        return ((List<Map<String, Object>>) instance.get("items")).stream().filter(i -> name.equals(i.get("name"))).findFirst().orElseThrow();
    }

    private static long l(Object o) {
        return ((Number) o).longValue();
    }

    private static double d(Object o) {
        return ((Number) o).doubleValue();
    }

    private static String type(ResponseEntity<Map> r) {
        return String.valueOf(r.getBody().get("type"));
    }

    private long count(UUID tenantId, String sql, Object... args) {
        return inTenantTx(tenantId, () -> jdbc.queryForObject(sql, Long.class, args));
    }

    /** WE-01 'Acne Clear' in an India clinic with the split on: 6 exempt sessions 2,000 + 2 peels 1,500 at 18% + 1 cream 800 at 18%, sold at 12,999. */
    private Map sellAcneClear(Env e) {
        String sessions = head(e, "ACNE", "Acne session", 0, "exempt", "9993");
        String peel = head(e, "PEEL", "Chemical peel", 18, "taxable", null);
        String cream = head(e, "CREAM", "Acne cream", 18, "taxable", null); // the cream's HSN rate is test data: needs the CA's check
        ResponseEntity<Map> pkg = createPackage(e, "Acne Clear", 12999.00, false, List.of(
                // the typed fallback rates are deliberately wrong: the linked head decides
                item("service_session", "Acne session", 6, 2000.00, 18.0, sessions),
                item("procedure", "Peel", 2, 1500.00, 0.0, peel),
                item("take_home_product", "Cream", 1, 800.00, 0.0, cream)));
        assertThat(pkg.getStatusCode()).as(String.valueOf(pkg.getBody())).isEqualTo(HttpStatus.CREATED);
        assertThat(((List<Map<String, Object>>) pkg.getBody().get("items")).get(0).get("chargeCatalogueId")).isEqualTo(sessions);
        assertThat(settings(e, Map.of("priceFloorPercent", 72, "taxSplitEnabled", true, "gstOpinionDecisionId", "DEC-2026-014")).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        return sell(e, (String) pkg.getBody().get("id"));
    }

    // AC4: the release gate
    @Test
    void aGstClinicCannotTurnTheSplitOnUntilTheGstOpinionDecisionIdIsRecorded() {
        Env e = env("IN");
        Map before = exchange("/v1/packages/settings", HttpMethod.GET, authed(e.maker()), Map.class).getBody();
        assertThat(before.get("taxSplitEnabled")).isEqualTo(false);
        assertThat(before.get("gstOpinionRequired")).isEqualTo(true);

        ResponseEntity<Map> refused = settings(e, Map.of("priceFloorPercent", 72, "taxSplitEnabled", true));
        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(type(refused)).endsWith("gst-opinion-required");
        assertThat(settings(e, Map.of("priceFloorPercent", 72, "taxSplitEnabled", true, "gstOpinionDecisionId", "  ")).getStatusCode())
                .as("a blank id is no id").isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(exchange("/v1/packages/settings", HttpMethod.GET, authed(e.maker()), Map.class).getBody().get("taxSplitEnabled")).isEqualTo(false);

        ResponseEntity<Map> on = settings(e, Map.of("priceFloorPercent", 72, "taxSplitEnabled", true, "gstOpinionDecisionId", "DEC-2026-014"));
        assertThat(on.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(on.getBody().get("taxSplitEnabled")).isEqualTo(true);
        assertThat(on.getBody().get("gstOpinionDecisionId")).isEqualTo("DEC-2026-014");
        assertThat(count(e.tenant().id(), "SELECT count(*) FROM audit_log WHERE action = 'packages.tax_split_changed' AND entity_id = ?", e.tenant().id())).isEqualTo(1);

        // the id cannot be cleared while the split is on; an unrelated settings save keeps both
        assertThat(type(settings(e, Map.of("priceFloorPercent", 72, "gstOpinionDecisionId", "")))).endsWith("gst-opinion-required");
        Map kept = settings(e, Map.of("priceFloorPercent", 80)).getBody();
        assertThat(kept.get("taxSplitEnabled")).isEqualTo(true);
        assertThat(kept.get("gstOpinionDecisionId")).isEqualTo("DEC-2026-014");

        // a VAT clinic needs no GST opinion
        Env ksa = env("SA");
        ResponseEntity<Map> vat = settings(ksa, Map.of("priceFloorPercent", 72, "taxSplitEnabled", true));
        assertThat(vat.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(vat.getBody().get("gstOpinionRequired")).isEqualTo(false);
    }

    // AC1 + AC5
    @Test
    void we01IsInvoicedPerComponentWithEachLinesOwnTaxAndPrintsTheSaving() {
        Env e = env("IN");
        Map instance = sellAcneClear(e);

        // golden IN-047: 9,872.66 exempt + 2,468.16 at 18% + 658.18 at 18%
        Map<String, Object> sessions = itemNamed(instance, "Acne session"), peel = itemNamed(instance, "Peel"), cream = itemNamed(instance, "Cream");
        assertThat(List.of(l(sessions.get("allocatedMinor")), l(peel.get("allocatedMinor")), l(cream.get("allocatedMinor")))).containsExactly(987_266L, 246_816L, 65_818L);
        assertThat(List.of(l(sessions.get("taxMinor")), l(peel.get("taxMinor")), l(cream.get("taxMinor")))).containsExactly(0L, 44_426L, 11_848L);
        assertThat(List.of(sessions.get("taxCategory"), peel.get("taxCategory"), cream.get("taxCategory"))).containsExactly("EXEMPT", "TAXABLE", "TAXABLE");
        assertThat(d(instance.get("soldTax"))).isEqualTo(562.74);

        String invoiceId = (String) instance.get("invoiceId");
        Map invoice = exchange("/v1/billing/invoices/" + invoiceId, HttpMethod.GET, authed(e.maker()), Map.class).getBody();
        assertThat(invoice.get("status")).isEqualTo("paid");
        assertThat(d(invoice.get("subtotal"))).isEqualTo(12999.00);
        assertThat(d(invoice.get("tax"))).isEqualTo(562.74);
        assertThat(d(invoice.get("roundOff"))).isEqualTo(0.26);
        assertThat(d(invoice.get("total"))).isEqualTo(13562.00);
        List<Map<String, Object>> lines = (List<Map<String, Object>>) invoice.get("lineItems");
        assertThat(lines).hasSize(3);
        assertThat(lines).extracting(x -> x.get("chargeName")).containsExactly("Acne session x 6", "Peel x 2", "Cream");
        assertThat(lines).extracting(x -> l(x.get("taxableMinor"))).containsExactly(987_266L, 246_816L, 65_818L);
        assertThat((List<?>) lines.get(0).get("taxes")).as("exempt sessions carry no GST").isEmpty();
        assertThat(((List<Map<String, Object>>) lines.get(1).get("taxes"))).extracting(x -> x.get("type") + " " + l(x.get("amountMinor")))
                .containsExactly("CGST 22213", "SGST 22213");

        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(e.maker());
        h.setAccept(List.of(MediaType.APPLICATION_PDF, MediaType.APPLICATION_PROBLEM_JSON));
        ResponseEntity<byte[]> pdf = exchange("/v1/billing/invoices/" + invoiceId + "/document?layout=a4&kind=invoice", HttpMethod.GET,
                new org.springframework.http.HttpEntity<>(h), byte[].class);
        assertThat(pdf.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(String.join("\n", S15BillingTestBase.pdfLines(pdf.getBody()))).contains("Total if bought separately 15800.00 / you save 2801.00");
    }

    // AC2
    @Test
    void aKsaTaxInclusiveBundleBacksTheVatOutPerLineAndTheInvoiceTotalIsTheBundlePrice() {
        Env e = env("SA");
        String laser = head(e, "LASER", "Laser session", 15, "taxable", null);
        String consult = head(e, "CONS", "Consultation", 0, "exempt", null);
        ResponseEntity<Map> pkg = createPackage(e, "Laser Smooth", 2999.00, true, List.of(
                item("service_session", "Laser session", 4, 800.00, 0.0, laser),
                item("consultation", "Consultation", 1, 300.00, 15.0, consult),
                item("take_home_product", "Serum", 1, 250.00, 15.0, null))); // no head: the typed rate stands
        assertThat(pkg.getStatusCode()).as(String.valueOf(pkg.getBody())).isEqualTo(HttpStatus.CREATED);
        assertThat(settings(e, Map.of("priceFloorPercent", 72, "taxSplitEnabled", true)).getStatusCode()).isEqualTo(HttpStatus.OK);
        Map instance = sell(e, (String) pkg.getBody().get("id"));

        // golden PHL-SA-025
        Map<String, Object> sessions = itemNamed(instance, "Laser session"), cons = itemNamed(instance, "Consultation"), serum = itemNamed(instance, "Serum");
        assertThat(List.of(l(sessions.get("allocatedMinor")), l(cons.get("allocatedMinor")), l(serum.get("allocatedMinor")))).containsExactly(222_535L, 23_992L, 17_385L);
        assertThat(List.of(l(sessions.get("taxMinor")), l(cons.get("taxMinor")), l(serum.get("taxMinor")))).containsExactly(33_380L, 0L, 2_608L);
        Map invoice = exchange("/v1/billing/invoices/" + instance.get("invoiceId"), HttpMethod.GET, authed(e.maker()), Map.class).getBody();
        assertThat(d(invoice.get("total"))).as("the invoice total is the bundle price").isEqualTo(2999.00);
        assertThat(d(invoice.get("tax"))).isEqualTo(359.88);
        assertThat(d(invoice.get("subtotal"))).isEqualTo(2639.12);
        assertThat(d(invoice.get("roundOff"))).isEqualTo(0.0);
        assertThat(invoice.get("status")).isEqualTo("paid");

        // refund after 2 of 4 sessions: the bundle price minus two sessions at the 800 list price, VAT reversed inside each line
        for (int i = 0; i < 2; i++) {
            assertThat(exchange("/v1/packages/instances/items/" + sessions.get("id") + "/redeem", HttpMethod.POST, authed(e.maker()), Map.class).getStatusCode())
                    .isEqualTo(HttpStatus.OK);
        }
        Map preview = exchange("/v1/packages/instances/" + instance.get("id") + "/refund-preview", HttpMethod.GET, authed(e.maker()), Map.class).getBody();
        assertThat(l(preview.get("refundMinor"))).isEqualTo(299_900L - 160_000L);
        Map<String, Object> line = ((List<Map<String, Object>>) preview.get("lines")).stream().filter(x -> "Laser session".equals(x.get("component"))).findFirst().orElseThrow();
        assertThat(List.of(l(line.get("usedQty")), l(line.get("usedValueMinor")), l(line.get("refundTaxableMinor")), l(line.get("refundTaxMinor"))))
                .containsExactly(2L, 160_000L, 83_404L, 12_511L);
    }

    // AC3
    @Test
    void refundAfterThreeOfSixSessionsIssuesACreditNoteThatReversesTaxLineByLineWithNoFee() {
        Env e = env("IN");
        Map instance = sellAcneClear(e);
        String instanceId = (String) instance.get("id");
        Map<String, Object> sessions = itemNamed(instance, "Acne session");
        for (int i = 0; i < 3; i++) {
            assertThat(exchange("/v1/packages/instances/items/" + sessions.get("id") + "/redeem", HttpMethod.POST, authed(e.maker()), Map.class).getStatusCode())
                    .isEqualTo(HttpStatus.OK);
        }

        Map preview = exchange("/v1/packages/instances/" + instanceId + "/refund-preview", HttpMethod.GET, authed(e.maker()), Map.class).getBody();
        List<Map<String, Object>> lines = (List<Map<String, Object>>) preview.get("lines");
        Map<String, List<Long>> byComponent = new HashMap<>();
        for (Map<String, Object> x : lines) {
            byComponent.put((String) x.get("component"), List.of(l(x.get("usedQty")), l(x.get("usedValueMinor")), l(x.get("refundTaxableMinor")), l(x.get("refundTaxMinor"))));
        }
        assertThat(byComponent.get("Acne session")).as("three sessions at the full 2,000, no tax to reverse").containsExactly(3L, 600_000L, 387_266L, 0L);
        assertThat(byComponent.get("Peel")).containsExactly(0L, 0L, 246_816L, 44_426L);
        assertThat(byComponent.get("Cream")).containsExactly(0L, 0L, 65_818L, 11_848L);
        // paid 13,561.74 before round-off, minus 6,000 used: nothing else is deducted (no processing fee)
        assertThat(l(preview.get("refundMinor"))).isEqualTo(1_356_174L - 600_000L);
        assertThat(l(preview.get("owedMinor"))).isZero();
        assertThat(d(preview.get("refundAmount"))).isEqualTo(7561.74);

        String refundId = (String) exchange("/v1/packages/instances/" + instanceId + "/refund", HttpMethod.POST,
                authedJsonBody(e.maker(), Map.of("reason", "Patient relocated")), Map.class).getBody().get("id");
        ResponseEntity<Map> approved = exchange("/v1/packages/refunds/" + refundId + "/approve", HttpMethod.POST,
                authedJsonBody(e.checker(), Map.of("expectedAmount", 7561.74)), Map.class);
        assertThat(approved.getStatusCode()).as(String.valueOf(approved.getBody())).isEqualTo(HttpStatus.OK);
        String number = (String) approved.getBody().get("creditNoteNumber");
        assertThat(number).startsWith("CN-");

        // one FIN-013 credit note against the package invoice, one line per component
        Map<String, Object> note = inTenantTx(e.tenant().id(), () -> jdbc.queryForMap(
                "SELECT id, invoice_id::text AS invoice_id, taxable_minor, tax_minor, round_off_minor, total_minor FROM credit_notes WHERE number = ?", number));
        assertThat(note.get("invoice_id")).isEqualTo(instance.get("invoiceId"));
        assertThat(List.of(l(note.get("taxable_minor")), l(note.get("tax_minor")), l(note.get("round_off_minor")), l(note.get("total_minor"))))
                .containsExactly(699_900L, 56_274L, 0L, 756_174L);
        List<Map<String, Object>> noteLines = inTenantTx(e.tenant().id(), () -> jdbc.queryForList(
                "SELECT li.charge_name, l.taxable_minor, l.tax_minor, l.taxes::text AS taxes FROM credit_note_lines l " +
                        "JOIN invoice_line_items li ON li.id = l.invoice_line_id WHERE l.credit_note_id = ? ORDER BY li.display_order", note.get("id")));
        assertThat(noteLines).extracting(x -> x.get("charge_name") + " " + x.get("taxable_minor") + " " + x.get("tax_minor"))
                .containsExactly("Acne session x 6 387266 0", "Peel x 2 246816 44426", "Cream 65818 11848");
        assertThat((String) noteLines.get(0).get("taxes")).as("the exempt line reverses no tax component").isEqualTo("[]");
        assertThat((String) noteLines.get(1).get("taxes")).contains("CGST", "SGST", "22213");

        // the same refund cannot be credited twice, and the invoice cannot be credited beyond its total through this path again
        assertThat(exchange("/v1/packages/refunds/" + refundId + "/approve", HttpMethod.POST,
                authedJsonBody(e.checker(), Map.of("expectedAmount", 7561.74)), Map.class).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(count(e.tenant().id(), "SELECT count(*) FROM credit_notes WHERE invoice_id = ?::uuid", instance.get("invoiceId"))).isEqualTo(1);
    }

    @Test
    void aComponentCannotBeLinkedToAnotherClinicsHeadAndTheSplitOffKeepsTheOldInvoice() {
        Env a = env("IN"), b = env("IN");
        String foreign = head(b, "PEEL", "Chemical peel", 18, "taxable", null);
        ResponseEntity<Map> refused = createPackage(a, "Borrowed head", 1000.00, false, List.of(item("procedure", "Peel", 1, 1200.00, 18.0, foreign)));
        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(type(refused)).endsWith("unknown-charge-head");
        List<Map<String, Object>> heads = exchange("/v1/packages/charge-heads", HttpMethod.GET, authed(a.maker()), List.class).getBody();
        assertThat(new ArrayList<>(heads)).as("clinic A sees none of clinic B's heads").isEmpty();

        // split off (the default): one line per item at quantity x unit price, the item's own typed rate, as before FIN-025
        String own = head(a, "FAC", "Facial", 0, "exempt", "9993");
        ResponseEntity<Map> pkg = createPackage(a, "Legacy", 2400.00, false, List.of(item("service_session", "Facial", 2, 1500.00, 18.0, own)));
        Map instance = sell(a, (String) pkg.getBody().get("id"));
        Map invoice = exchange("/v1/billing/invoices/" + instance.get("invoiceId"), HttpMethod.GET, authed(a.maker()), Map.class).getBody();
        Map<String, Object> line = ((List<Map<String, Object>>) invoice.get("lineItems")).get(0);
        assertThat(line.get("chargeName")).isEqualTo("Facial");
        assertThat(l(line.get("quantity"))).isEqualTo(2L);
        assertThat(d(invoice.get("tax"))).isEqualTo(432.00);
        // and the instance still records what its line was invoiced at, so a refund reverses exactly that
        Map<String, Object> item = itemNamed(instance, "Facial");
        assertThat(List.of(l(item.get("allocatedMinor")), l(item.get("taxMinor")))).containsExactly(240_000L, 43_200L);
    }
}
