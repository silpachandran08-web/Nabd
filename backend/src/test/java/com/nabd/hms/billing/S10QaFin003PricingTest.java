package com.nabd.hms.billing;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Independent QA (S10) for FIN-003: the server prices every line; price-bearing changes need billing.discount and a reason. */
class S10QaFin003PricingTest extends S10QaBillingSupport {

    private static Map<?, ?> firstLine(ResponseEntity<Map> r) {
        return (Map<?, ?>) ((List<?>) r.getBody().get("lineItems")).get(0);
    }

    private static Map<String, Object> withOverride(String code, int qty, Object price, String reason) {
        Map<String, Object> l = line(code, qty);
        Map<String, Object> o = new HashMap<>();
        o.put("unitPrice", price);
        if (reason != null) o.put("reasonCode", reason);
        l.put("override", o);
        return l;
    }

    private static Map<String, Object> disc(Object value, String reason) {
        Map<String, Object> d = new HashMap<>(Map.of("kind", "amount", "value", value));
        if (reason != null) d.put("reasonCode", reason);
        return d;
    }

    private static Map<String, Object> body(Object... kv) {
        Map<String, Object> m = new HashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }

    private void su(String sql, Object... args) {
        try (Connection c = superuserConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) ps.setObject(i + 1, args[i]);
            ps.executeUpdate();
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    // AC1 (J9)
    @Test
    void receptionistClientPriceNameAndTaxAreIgnoredAndCatalogueValuesAreReturned() {
        Env e = env();
        catalogue(e.tenant(), "TAXED", "Procedure", 100, null, 10);
        String reception = tokenFor(e, "Reception", true, true, false, false, true);
        Map<String, Object> l = line("CONS", 1);
        l.putAll(Map.of("unitPrice", 0, "chargeName", "free", "taxRatePercent", 0, "category", "Gift"));
        Map<String, Object> t = line("TAXED", 2);
        t.putAll(Map.of("unitPrice", 1, "taxRatePercent", 0));
        ResponseEntity<Map> r = checkout(reception, atCheckout(e), List.of(l, t));
        assertThat(r.getStatusCode().is2xxSuccessful()).as(String.valueOf(r.getBody())).isTrue();
        List<?> lines = (List<?>) r.getBody().get("lineItems");
        Map<?, ?> cons = (Map<?, ?>) lines.stream().filter(x -> "CONS".equals(((Map<?, ?>) x).get("chargeCode"))).findFirst().orElseThrow();
        Map<?, ?> taxed = (Map<?, ?>) lines.stream().filter(x -> "TAXED".equals(((Map<?, ?>) x).get("chargeCode"))).findFirst().orElseThrow();
        assertThat(num(cons.get("unitPrice"))).isEqualTo(400.0);
        assertThat(cons.get("chargeName")).isEqualTo("Cat CONS");
        assertThat(cons.get("category")).isEqualTo("Consultation");
        assertThat(num(taxed.get("unitPrice"))).isEqualTo(100.0);
        assertThat(num(taxed.get("taxRatePercent"))).isEqualTo(10.0);
        assertThat(num(r.getBody().get("tax"))).isEqualTo(20.0);
        assertThat(num(r.getBody().get("total"))).isEqualTo(620.0);
    }

    @Test
    void quantityBelowOneAndMissingCodeAre400() {
        Env e = env();
        assertThat(checkout(e.token(), atCheckout(e), List.of(line("CONS", 0))).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(checkout(e.token(), atCheckout(e), List.of(line("CONS", -2))).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(checkout(e.token(), atCheckout(e), List.of(body("quantity", 1))).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    // AC2
    @Test
    void unknownInactiveAndForeignTenantCodesAre422WithoutDiscountRight() {
        Env e = env();
        Env other = env();
        catalogue(other.tenant(), "THEIRS", "Procedure", 50, null, 0);
        catalogue(e.tenant(), "OLD", "Procedure", 50, null, 0);
        inTenantTx(e.tenant().id(), () -> jdbc.update("UPDATE charge_catalogue SET active = false WHERE code = 'OLD'"));
        String reception = tokenFor(e, "Reception", true, true, false, false, true);
        for (String code : List.of("GHOST", "OLD", "THEIRS")) {
            Map<String, Object> l = line(code, 1);
            l.putAll(Map.of("chargeName", "x", "category", "Service", "unitPrice", 0, "taxRatePercent", 0));
            ResponseEntity<Map> r = checkout(reception, atCheckout(e), List.of(l));
            assertThat(r.getStatusCode()).as(code).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
            assertThat(type(r)).as(code).contains("unknown-charge");
        }
        assertThat(count(e, "SELECT count(*) FROM invoices")).isZero();
    }

    @Test
    void freeTextChargeIsAllowedOnlyWithDiscountRightAndNeedsNameAndPrice() {
        Env e = env();
        Map<String, Object> ghost = line("GHOST", 1);
        ghost.putAll(Map.of("chargeName", "Ghost", "category", "Service", "unitPrice", 75, "taxRatePercent", 0, "override", Map.of("reasonCode", "other")));
        ResponseEntity<Map> ok = checkout(e.token(), atCheckout(e), List.of(ghost));
        assertThat(ok.getStatusCode().is2xxSuccessful()).as(String.valueOf(ok.getBody())).isTrue();
        assertThat(firstLine(ok).get("tier")).isEqualTo("custom");
        assertThat(num(firstLine(ok).get("unitPrice"))).isEqualTo(75.0);
        ResponseEntity<Map> nameless = checkout(e.token(), atCheckout(e), List.of(line("GHOST2", 1)));
        assertThat(nameless.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    // context: "Free-text charges are allowed only with billing.discount and a reason."
    @Test
    void freeTextChargeWithoutAReasonIsRefusedWith422ReasonRequired() {
        Env e = env();
        Map<String, Object> ghost = line("GHOST", 1);
        ghost.putAll(Map.of("chargeName", "Ghost", "category", "Service", "unitPrice", 75, "taxRatePercent", 0));
        ResponseEntity<Map> r = checkout(e.token(), atCheckout(e), List.of(ghost));
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(type(r)).contains("reason-required");
    }

    // AC3
    @Test
    void overrideNeedsRightThenValidReasonThenIsStoredWithProvenanceAndAudited() {
        Env e = env();
        String entry = atCheckout(e);
        ResponseEntity<Map> noReason = checkout(e.token(), entry, List.of(withOverride("CONS", 1, 300, null)));
        assertThat(noReason.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(type(noReason)).contains("reason-required");
        ResponseEntity<Map> badReason = checkout(e.token(), entry, List.of(withOverride("CONS", 1, 300, "because")));
        assertThat(badReason.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(count(e, "SELECT count(*) FROM invoices")).isZero();
        assertThat(checkout(e.token(), entry, List.of(withOverride("CONS", 1, -1, "goodwill"))).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        ResponseEntity<Map> ok = checkout(e.token(), entry, List.of(withOverride("CONS", 1, 300, "goodwill")));
        assertThat(ok.getStatusCode().is2xxSuccessful()).as(String.valueOf(ok.getBody())).isTrue();
        assertThat(num(firstLine(ok).get("unitPrice"))).isEqualTo(300.0);
        assertThat(num(firstLine(ok).get("listUnitPrice"))).isEqualTo(400.0);
        assertThat(firstLine(ok).get("overrideReason")).isEqualTo("goodwill");
        inTenantTx(e.tenant().id(), () -> {
            Map<String, Object> row = jdbc.queryForMap("SELECT list_unit_price, unit_price, override_reason_code, overridden_by FROM invoice_line_items");
            assertThat(((BigDecimal) row.get("list_unit_price")).doubleValue()).isEqualTo(400.0);
            assertThat(((BigDecimal) row.get("unit_price")).doubleValue()).isEqualTo(300.0);
            assertThat(row.get("override_reason_code")).isEqualTo("goodwill");
            assertThat(row.get("overridden_by").toString()).isEqualTo(e.owner().id().toString());
            assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE action = 'billing.price_overridden'", Integer.class)).isEqualTo(1);
        });
    }

    @Test
    void anOverrideToZeroWithAReasonIsAllowedForTheRightHolder() {
        Env e = env();
        ResponseEntity<Map> r = checkout(e.token(), atCheckout(e), List.of(withOverride("CONS", 1, 0, "waiver")));
        assertThat(r.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(num(firstLine(r).get("unitPrice"))).isZero();
        assertThat(num(firstLine(r).get("listUnitPrice"))).isEqualTo(400.0);
    }

    // AC7 matrix: role x price-bearing field
    @Test
    void onlyRolesWithBillingDiscountCanChangeAnyPriceBearingField() {
        Env e = env();
        String reception = tokenFor(e, "Reception", true, true, false, false, true);
        String billing = tokenFor(e, "BillingClerk", true, true, true, false, false);
        String doctor = tokenFor(e, "Doctor", false, false, false, false, true);
        String owner = e.token();
        Map<String, Object> free = line("GHOST", 1);
        free.putAll(Map.of("chargeName", "Ghost", "category", "Service", "unitPrice", 10, "taxRatePercent", 0, "override", Map.of("reasonCode", "other")));
        Map<String, Map<String, Object>> attempts = new java.util.LinkedHashMap<>();
        attempts.put("override", body("lineItems", List.of(withOverride("CONS", 1, 1, "goodwill"))));
        attempts.put("free-text", body("lineItems", List.of(free)));
        attempts.put("discounts[]", body("lineItems", List.of(line("CONS", 1)), "discounts", List.of(disc(10, "goodwill"))));
        attempts.put("legacy discount", body("lineItems", List.of(line("CONS", 1)), "discount", 10));
        for (String role : List.of("reception", "billing", "doctor")) {
            String token = role.equals("reception") ? reception : role.equals("billing") ? billing : doctor;
            for (var a : attempts.entrySet()) {
                ResponseEntity<Map> r = exchange("/v1/billing/checkout/" + atCheckout(e), HttpMethod.POST, authedJsonBody(token, a.getValue()), Map.class);
                assertThat(r.getStatusCode().value()).as(role + " / " + a.getKey()).isIn(403, 422);
                assertThat(r.getStatusCode().is2xxSuccessful()).as(role + " / " + a.getKey()).isFalse();
            }
        }
        assertThat(count(e, "SELECT count(*) FROM invoices")).isZero();
        assertThat(count(e, "SELECT count(*) FROM invoice_discounts")).isZero();
        // the owner (has billing.discount) may do all four
        for (var a : attempts.entrySet()) {
            ResponseEntity<Map> r = exchange("/v1/billing/checkout/" + atCheckout(e), HttpMethod.POST, authedJsonBody(owner, a.getValue()), Map.class);
            assertThat(r.getStatusCode().is2xxSuccessful()).as("owner / " + a.getKey() + " " + r.getBody()).isTrue();
        }
    }

    // QA-12 hole named in the engineer notes: a catalogue row flagged doctor_override lets any caller set the price
    @Test
    void receptionistCannotSetThePriceOfADoctorOverrideChargeEither() {
        Env e = env();
        inTenantTx(e.tenant().id(), () -> jdbc.update("UPDATE charge_catalogue SET doctor_override = true WHERE code = 'XRAY'"));
        String reception = tokenFor(e, "Reception", true, true, false, false, true);
        Map<String, Object> l = line("XRAY", 1);
        l.put("unitPrice", 1);
        ResponseEntity<Map> r = checkout(reception, atCheckout(e), List.of(l));
        // either refused, or billed at the catalogue price 1200 - never at the client's 1
        if (r.getStatusCode().is2xxSuccessful()) {
            assertThat(num(firstLine(r).get("unitPrice"))).isEqualTo(1200.0);
        } else {
            assertThat(r.getStatusCode().value()).isIn(403, 422);
        }
    }

    // AC4 + AC5
    @Test
    void discountsAccumulateAcrossTwoCheckpointsAsLedgerRows() {
        Env e = env();
        String entry = atInterimBilling(e);
        ResponseEntity<Map> interim = checkout(e.token(), entry, List.of(line("SVC", 1)), "discounts", List.of(disc(100, "goodwill")));
        assertThat(interim.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(num(interim.getBody().get("discount"))).isEqualTo(100.0);
        String inv = (String) interim.getBody().get("id");
        move(e, entry, "vitals_done", "in_consult", "checkout_pending");
        Map<String, Object> fifty = disc(50, "staff");
        fifty.put("note", "second");
        ResponseEntity<Map> fin = checkout(e.token(), entry, List.of(), "discounts", List.of(fifty));
        assertThat(fin.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(num(fin.getBody().get("discount"))).isEqualTo(150.0);
        assertThat(num(fin.getBody().get("total"))).isEqualTo(350.0);
        inTenantTx(e.tenant().id(), () -> {
            assertThat(jdbc.queryForList("SELECT amount FROM invoice_discounts WHERE invoice_id = ?::uuid", BigDecimal.class, inv))
                    .extracting(BigDecimal::doubleValue).containsExactlyInAnyOrder(100.0, 50.0);
            assertThat(jdbc.queryForObject("SELECT discount FROM invoices WHERE id = ?::uuid", BigDecimal.class, inv).doubleValue()).isEqualTo(150.0);
            assertThat(jdbc.queryForObject("SELECT SUM(amount) FROM invoice_discounts WHERE invoice_id = ?::uuid", BigDecimal.class, inv).doubleValue()).isEqualTo(150.0);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE action = 'billing.discount_applied'", Integer.class)).isEqualTo(2);
        });
    }

    @Test
    void discountLargerThanRemainingSubtotalIs422AndNothingPersists() {
        Env e = env();
        String entry = atInterimBilling(e);
        ResponseEntity<Map> big = checkout(e.token(), entry, List.of(line("SVC", 1)), "discounts", List.of(disc("500.01", "goodwill")));
        assertThat(big.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(type(big)).contains("discount-exceeds-subtotal");
        assertThat(count(e, "SELECT count(*) FROM invoices")).isZero();
        assertThat(count(e, "SELECT count(*) FROM invoice_discounts")).isZero();
        // first checkpoint takes 300, second tries 200.01 of the remaining 200
        String inv = (String) checkout(e.token(), entry, List.of(line("SVC", 1)), "discounts", List.of(disc(300, "goodwill"))).getBody().get("id");
        move(e, entry, "vitals_done", "in_consult", "checkout_pending");
        // remaining subtotal on the one invoice is 200
        ResponseEntity<Map> second = checkout(e.token(), entry, List.of(), "discounts", List.of(disc("200.01", "goodwill")));
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(type(second)).contains("discount-exceeds-subtotal");
        assertThat(num(getInvoice(e.token(), inv).getBody().get("discount"))).isEqualTo(300.0);
    }

    @Test
    void discountEqualToTheSubtotalIsAcceptedAndZeroOrNegativeIsRefused() {
        Env e = env();
        ResponseEntity<Map> all = checkout(e.token(), atCheckout(e), List.of(line("SVC", 1)), "discounts", List.of(disc(500, "waiver")));
        assertThat(all.getStatusCode().is2xxSuccessful()).as(String.valueOf(all.getBody())).isTrue();
        assertThat(num(all.getBody().get("total"))).isZero();
        for (Object bad : new Object[]{0, -10}) {
            ResponseEntity<Map> r = checkout(e.token(), atCheckout(e), List.of(line("SVC", 1)), "discounts", List.of(disc(bad, "waiver")));
            assertThat(r.getStatusCode().is4xxClientError()).as("discount " + bad).isTrue();
        }
    }

    @Test
    void discountNeedsAKnownReasonCode() {
        Env e = env();
        ResponseEntity<Map> none = checkout(e.token(), atCheckout(e), List.of(line("SVC", 1)), "discounts", List.of(disc(10, null)));
        assertThat(none.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(type(none)).contains("reason-required");
        assertThat(checkout(e.token(), atCheckout(e), List.of(line("SVC", 1)), "discounts", List.of(disc(10, "nope"))).getStatusCode())
                .isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(count(e, "SELECT count(*) FROM invoice_discounts")).isZero();
    }

    @Test
    void percentDiscountIsBookedAsItsComputedAmount() {
        Env e = env();
        ResponseEntity<Map> r = checkout(e.token(), atCheckout(e), List.of(line("SVC", 2)),
                "discounts", List.of(new HashMap<>(Map.of("kind", "percent", "value", 10, "reasonCode", "staff"))));
        assertThat(r.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(num(r.getBody().get("discount"))).isEqualTo(100.0);
        assertThat(num(r.getBody().get("total"))).isEqualTo(900.0);
    }

    // AC6
    @Test
    void followUpPriceIsServerChosenInsideTheWindowOnlyAndClientTierIsIgnored() {
        Env e = env();
        String patientId = patient(e.token());
        String first = (String) exchange("/v1/queue/check-in", HttpMethod.POST, authedJsonBody(e.token(), Map.of(
                "patientId", patientId, "doctorId", e.owner().id().toString())), Map.class).getBody().get("id");
        move(e, first, "waiting", "vitals_pending", "vitals_done", "in_consult", "checkout_pending");
        Map<String, Object> asksFollowUp = line("CONS", 1);
        asksFollowUp.put("tier", "follow_up");
        ResponseEntity<Map> r1 = checkout(e.token(), first, List.of(asksFollowUp));
        assertThat(num(firstLine(r1).get("unitPrice"))).as("first visit cannot force follow-up").isEqualTo(400.0);
        assertThat(firstLine(r1).get("tier")).isEqualTo("normal");
        assertThat(inTenantTx(e.tenant().id(), () -> jdbc.queryForObject("SELECT status FROM queue_entries WHERE id = ?::uuid", String.class, first))).isEqualTo("completed");

        // FIN-015: the clinic window now defaults to 7 days (was a fixed 14). 6 days ago: still inside the window
        su("UPDATE queue_entries SET queue_date = CURRENT_DATE - 6, token_number = 9001 WHERE id = ?::uuid", first);
        String second = (String) exchange("/v1/queue/check-in", HttpMethod.POST, authedJsonBody(e.token(), Map.of(
                "patientId", patientId, "doctorId", e.owner().id().toString())), Map.class).getBody().get("id");
        ResponseEntity<Map> ctx = exchange("/v1/billing/checkout/" + second, HttpMethod.GET, authed(e.token()), Map.class);
        assertThat(ctx.getBody().get("followUpEligible")).isEqualTo(true);
        move(e, second, "waiting", "vitals_pending", "vitals_done", "in_consult", "checkout_pending");
        // FIN-016: an explicit "normal" is now a legitimate tier choice, so the server-chosen follow-up is checked with no tier sent
        ResponseEntity<Map> r2 = checkout(e.token(), second, List.of(line("CONS", 1)));
        assertThat(num(firstLine(r2).get("unitPrice"))).isEqualTo(150.0);
        assertThat(firstLine(r2).get("tier")).isEqualTo("follow_up");

        // 9 days ago: outside the 7-day window
        su("UPDATE queue_entries SET queue_date = CURRENT_DATE - 9, token_number = 9002 WHERE id = ?::uuid", first);
        su("UPDATE queue_entries SET queue_date = CURRENT_DATE - 10, token_number = 9003 WHERE id = ?::uuid", second);
        String third = (String) exchange("/v1/queue/check-in", HttpMethod.POST, authedJsonBody(e.token(), Map.of(
                "patientId", patientId, "doctorId", e.owner().id().toString())), Map.class).getBody().get("id");
        assertThat(exchange("/v1/billing/checkout/" + third, HttpMethod.GET, authed(e.token()), Map.class).getBody().get("followUpEligible")).isEqualTo(false);
        move(e, third, "waiting", "vitals_pending", "vitals_done", "in_consult", "checkout_pending");
        assertThat(num(firstLine(checkout(e.token(), third, List.of(asksFollowUp))).get("unitPrice"))).isEqualTo(400.0);
    }

    @Test
    void checkoutContextSuggestsTheConsultTierAndPrice() {
        Env e = env();
        ResponseEntity<Map> ctx = exchange("/v1/billing/checkout/" + atCheckout(e), HttpMethod.GET, authed(e.token()), Map.class);
        assertThat(ctx.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(ctx.getBody()).containsKeys("suggestedLines", "charges", "followUpEligible", "currency");
        Map<?, ?> s = (Map<?, ?>) ((List<?>) ctx.getBody().get("suggestedLines")).get(0);
        assertThat(s.get("tier")).isEqualTo("normal");
        assertThat(num(s.get("unitPrice"))).isEqualTo(400.0);
    }

    // tenant isolation + RLS
    @Test
    void otherTenantsCannotReadOrBillAVisitAndInvoiceDiscountsAreRlsForced() {
        Env a = env();
        Env b = env();
        String entry = atInterimBilling(a);
        String inv = (String) checkout(a.token(), entry, List.of(line("SVC", 1)), "discounts", List.of(disc(10, "goodwill"))).getBody().get("id");
        assertThat(exchange("/v1/billing/checkout/" + entry, HttpMethod.GET, authed(b.token()), Map.class).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(checkout(b.token(), entry, List.of(line("SVC", 1))).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(inTenantTx(b.tenant().id(), () -> jdbc.queryForObject("SELECT count(*) FROM invoice_discounts WHERE invoice_id = ?::uuid", Integer.class, inv))).isZero();
        assertThat(inTenantTx(a.tenant().id(), () -> jdbc.queryForObject("SELECT count(*) FROM invoice_discounts WHERE invoice_id = ?::uuid", Integer.class, inv))).isEqualTo(1);
        List<Map<String, Object>> flags = jdbc.queryForList("SELECT relrowsecurity, relforcerowsecurity FROM pg_class WHERE relname = 'invoice_discounts'");
        assertThat(flags).singleElement().satisfies(f -> assertThat(f).containsEntry("relrowsecurity", true).containsEntry("relforcerowsecurity", true));
    }

    // idempotency on the checkout endpoint
    @Test
    void checkoutRetryWithTheSameKeyCreatesOneInvoice() {
        Env e = env();
        String entry = atCheckout(e);
        String key = UUID.randomUUID().toString();
        Map<String, Object> body = body("lineItems", List.of(line("SVC", 1)));
        ResponseEntity<Map> first = exchange("/v1/billing/checkout/" + entry, HttpMethod.POST, withKey(e.token(), key, body), Map.class);
        ResponseEntity<Map> again = exchange("/v1/billing/checkout/" + entry, HttpMethod.POST, withKey(e.token(), key, body), Map.class);
        assertThat(first.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(again.getHeaders().getFirst("Idempotent-Replayed")).isEqualTo("true");
        assertThat(again.getBody()).isEqualTo(first.getBody());
        assertThat(count(e, "SELECT count(*) FROM invoices")).isEqualTo(1);
        assertThat(exchange("/v1/billing/checkout/" + entry, HttpMethod.POST, withKey(e.token(), null, body), Map.class).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    // contract points of the story that the engineer reports as not built
    @Test
    @Tag("known-defect-FIN-003")
    void checkoutPostReturns201PerTheStoryApi() {
        Env e = env();
        ResponseEntity<Map> r = checkout(e.token(), atCheckout(e), List.of(line("SVC", 1)));
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    @Test
    @Tag("known-defect-FIN-003")
    void invoiceIssuedEventIsPublishedToTheOutbox() {
        Env e = env();
        checkout(e.token(), atCheckout(e), List.of(line("SVC", 1)));
        assertThat(count(e, "SELECT count(*) FROM outbox_events WHERE event_type = 'billing.invoice.issued.v1'")).isEqualTo(1);
    }
}
