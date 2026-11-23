package com.nabd.hms.billing;

import com.nabd.hms.common.ModuleGrant;
import com.nabd.hms.insurance.api.ClaimsGateway;
import com.nabd.hms.platform.entitlements.PlanEntitlements;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * FIN-036: payers, plans, patient policies, coverage of a visit and the payer / patient split on the invoice. Lives in the billing
 * test package for its checkout fixtures (S10QaBillingSupport). Seeded clinics are in IN (INR, whole-rupee round-off), zone UTC.
 */
@SuppressWarnings({"unchecked", "rawtypes"})
class Fin036InsuranceApiTest extends S10QaBillingSupport {

    private static final String GSTIN = "29ABCDE1234F1Z5";

    @Autowired
    private PlanEntitlements planEntitlements;
    @Autowired
    private RequestMappingHandlerMapping mappings;

    private static LocalDate today() {
        return LocalDate.now(ZoneOffset.UTC);
    }

    /** A clinic whose Owner holds every module including insurance; SVC 500, CONS 400, XRAY 1200, no tax. No subscription = unrestricted. */
    private Env clinic() {
        SeededTenant t = seedTenant();
        UUID role = seedRole(t.id(), "Owner", true, fullGrant("staff"), fullGrant("patients"), fullGrant("queue"), fullGrant("setup"),
                fullGrant("clinical"), fullGrant("billing"), fullGrant("reports"), fullGrant("pharmacy"), fullGrant("packages"), fullGrant("nursing"),
                fullGrant("departments"), fullGrant("insurance"));
        SeededStaff owner = seedStaff(t, role, "f36" + t.id() + "@a.com", phone(), false);
        Env e = new Env(t, owner, loginAndGetAccessToken(owner));
        catalogue(t, "SVC", "Service", 500, null, 0);
        catalogue(t, "CONS", "Consultation", 400, 150.0, 0);
        catalogue(t, "XRAY", "Procedure", 1200, null, 0);
        return e;
    }

    private String tokenWith(Env e, ModuleGrant... grants) {
        UUID role = seedRole(e.tenant().id(), "R " + UUID.randomUUID(), false, grants);
        return loginAndGetAccessToken(seedStaff(e.tenant(), role, "r" + UUID.randomUUID() + "@a.com", phone(), false));
    }

    /** The clinic's plan now lists only these modules (PLT-059). */
    private void subscribe(UUID tenant, List<String> modules) {
        String op = platformLoginAndGetAccessToken(seedOperator("sa-" + UUID.randomUUID() + "@nabd.internal", "super_admin", false));
        ResponseEntity<Map> p = exchange("/v1/platform/plans", HttpMethod.POST, authedJsonBody(op, Map.of(
                "code", "f36-" + UUID.randomUUID(), "name", "P", "monthlyPriceCents", 100000, "currency", "INR",
                "active", true, "modules", modules, "limits", Map.of("seats", 20, "branches", 2))), Map.class);
        assertThat(p.getStatusCode()).as("%s", p.getBody()).isEqualTo(HttpStatus.CREATED);
        ResponseEntity<Map> s = exchange("/v1/platform/billing/subscriptions/" + tenant, HttpMethod.POST, authedJsonBody(op,
                Map.of("planId", p.getBody().get("id"), "mrrCents", 100000, "renewalDate", LocalDate.now().plusDays(30).toString())), Map.class);
        assertThat(s.getStatusCode()).as("%s", s.getBody()).isEqualTo(HttpStatus.OK);
        planEntitlements.evict(tenant);
    }

    private ResponseEntity<Map> post(String token, String path, Object body) {
        return exchange(path, HttpMethod.POST, authedJsonBody(token, body), Map.class);
    }

    private ResponseEntity<Map> get(String token, String path) {
        return exchange(path, HttpMethod.GET, authed(token), Map.class);
    }

    private String payer(Env e, String name, Map<String, String> identifiers) {
        ResponseEntity<Map> r = post(e.token(), "/v1/insurance/payers", Map.of("name", name, "kind", "insurer", "identifiers", identifiers));
        assertThat(r.getStatusCode()).as("payer %s", r.getBody()).isEqualTo(HttpStatus.CREATED);
        return (String) r.getBody().get("id");
    }

    private String plan(Env e, String payerId, String name, Object copayPercent) {
        ResponseEntity<Map> r = post(e.token(), "/v1/insurance/payers/" + payerId + "/plans", Map.of("name", name, "copayPercent", copayPercent));
        assertThat(r.getStatusCode()).as("plan %s", r.getBody()).isEqualTo(HttpStatus.CREATED);
        return (String) r.getBody().get("id");
    }

    private ResponseEntity<Map> addPolicy(String token, String patientId, String payerId, String planId, String memberId, LocalDate from, LocalDate to) {
        Map<String, Object> b = new HashMap<>(Map.of("payerId", payerId, "memberId", memberId, "policyNumber", "POL-" + memberId,
                "validFrom", from.toString(), "validTo", to.toString(), "relationship", "self"));
        if (planId != null) b.put("planId", planId);
        return post(token, "/v1/insurance/patients/" + patientId + "/policies", b);
    }

    /** A current policy of the patient on a fresh payer and a plan with this co-pay. */
    private String policy(Env e, String patientId, Object copayPercent) {
        String payer = payer(e, "Payer " + UUID.randomUUID(), Map.of());
        ResponseEntity<Map> r = addPolicy(e.token(), patientId, payer, plan(e, payer, "Plan", copayPercent), "M" + UUID.randomUUID().toString().substring(0, 8),
                today().minusDays(30), today().plusDays(30));
        assertThat(r.getStatusCode()).as("policy %s", r.getBody()).isEqualTo(HttpStatus.CREATED);
        return (String) r.getBody().get("id");
    }

    private ResponseEntity<Map> cover(String token, String visit, String mode, String policyId) {
        Map<String, Object> b = new HashMap<>(Map.of("mode", mode));
        if (policyId != null) b.put("policyId", policyId);
        return exchange("/v1/insurance/visits/" + visit + "/coverage", HttpMethod.PUT, authedJsonBody(token, b), Map.class);
    }

    private String checkIn(Env e, String patientId) {
        ResponseEntity<Map> r = post(e.token(), "/v1/queue/check-in", Map.of("patientId", patientId, "doctorId", e.owner().id().toString()));
        assertThat(r.getStatusCode()).as("check-in %s", r.getBody()).isEqualTo(HttpStatus.CREATED);
        return (String) r.getBody().get("id");
    }

    private String atCheckout(Env e, String patientId) {
        String id = checkIn(e, patientId);
        move(e, id, "waiting", "vitals_pending", "vitals_done", "in_consult", "checkout_pending");
        return id;
    }

    /** A visit at checkout, insured under a fresh policy with this co-pay. Returns {visitId, patientId}. */
    private String[] insuredVisit(Env e, Object copayPercent) {
        String patient = patient(e.token());
        String visit = atCheckout(e, patient);
        ResponseEntity<Map> c = cover(e.token(), visit, "insured", policy(e, patient, copayPercent));
        assertThat(c.getStatusCode()).as("coverage %s", c.getBody()).isEqualTo(HttpStatus.OK);
        return new String[]{visit, patient};
    }

    private ResponseEntity<Map> payerPays(String token, String invoiceId, Object amount, String key) {
        return exchange("/v1/insurance/invoices/" + invoiceId + "/payer-payments", HttpMethod.POST,
                withKey(token, key, Map.of("amount", amount, "reference", "RA-1")), Map.class);
    }

    private static Map<String, Object> insurance(ResponseEntity<Map> invoice) {
        return (Map<String, Object>) invoice.getBody().get("insurance");
    }

    private static String code(ResponseEntity<Map> r) {
        return String.valueOf(r.getBody().get("code"));
    }

    private Map<String, Object> row(Env e, String sql, Object... args) {
        return inTenantTx(e.tenant().id(), () -> jdbc.queryForMap(sql, args));
    }

    private static long lng(Object o) {
        return ((Number) o).longValue();
    }

    private boolean inDues(Env e, String patientId) {
        List<Map<String, Object>> items = (List<Map<String, Object>>) get(e.token(), "/v1/billing/dues").getBody().get("items");
        return items.stream().anyMatch(d -> patientId.equals(d.get("patientId")));
    }

    /** patient + payer = the line (taxable + tax) for every line, and patient + payer = total for the invoice, all in minor units. */
    private void assertSplitInvariant(Env e, String invoiceId) {
        UUID inv = UUID.fromString(invoiceId);
        List<Map<String, Object>> lines = inTenantTx(e.tenant().id(), () -> jdbc.queryForList(
                "SELECT li.patient_share_minor, li.payer_share_minor, li.taxable_minor + COALESCE((SELECT SUM(t.amount_minor) FROM invoice_line_taxes t " +
                        "WHERE t.line_id = li.id), 0) AS gross FROM invoice_line_items li WHERE li.invoice_id = ?", inv));
        assertThat(lines).isNotEmpty();
        long gross = 0, patientLines = 0, payerLines = 0;
        for (Map<String, Object> l : lines) {
            assertThat(lng(l.get("patient_share_minor")) + lng(l.get("payer_share_minor"))).as("line %s", l).isEqualTo(lng(l.get("gross")));
            assertThat(lng(l.get("patient_share_minor"))).isNotNegative();
            assertThat(lng(l.get("payer_share_minor"))).isNotNegative();
            gross += lng(l.get("gross"));
            patientLines += lng(l.get("patient_share_minor"));
            payerLines += lng(l.get("payer_share_minor"));
        }
        Map<String, Object> i = row(e, "SELECT total_minor, round_off_minor, payer_share_minor, patient_share_minor FROM invoices WHERE id = ?", inv);
        assertThat(gross + lng(i.get("round_off_minor"))).as("lines + round-off = total").isEqualTo(lng(i.get("total_minor")));
        assertThat(lng(i.get("payer_share_minor")) + lng(i.get("patient_share_minor"))).as("patient + payer = total").isEqualTo(lng(i.get("total_minor")));
        assertThat(lng(i.get("payer_share_minor"))).isBetween(0L, lng(i.get("total_minor")));
        // the round-off is the patient's, unless the patient's lines come to nothing or it would push the patient below zero
        long total = lng(i.get("total_minor"));
        assertThat(lng(i.get("payer_share_minor"))).as("the payer's share").isEqualTo(patientLines == 0 ? total : Math.min(payerLines, total));
        if (patientLines + lng(i.get("round_off_minor")) >= 0 && patientLines > 0) {
            assertThat(lng(i.get("patient_share_minor"))).isEqualTo(patientLines + lng(i.get("round_off_minor")));
        }
    }

    // ---- AC1: a clinic that does not accept insurance: every insurance API is 403 and its bills are never split ----
    @Test
    void ac1_aClinicWithoutInsuranceGets403OnEveryInsuranceEndpoint_andItsBillIsNotSplit() {
        Env e = clinic();
        String[] v = insuredVisit(e, 20); // decided while the clinic was unrestricted (no subscription yet)
        subscribe(e.tenant().id(), List.of("billing"));

        String id = UUID.randomUUID().toString();
        int checked = 0;
        for (Map.Entry<RequestMappingInfo, HandlerMethod> m : mappings.getHandlerMethods().entrySet()) {
            var patterns = m.getKey().getPathPatternsCondition();
            if (patterns == null) continue;
            for (String p : patterns.getPatternValues()) {
                if (!p.startsWith("/v1/insurance")) continue;
                for (var method : m.getKey().getMethodsCondition().getMethods()) {
                    HttpMethod hm = HttpMethod.valueOf(method.name());
                    ResponseEntity<Map> r = exchange(p.replaceAll("\\{[^}]+}", id), hm,
                            hm == HttpMethod.GET ? authed(e.token()) : authedJsonBody(e.token(), Map.of("mode", "self_pay")), Map.class);
                    assertThat(r.getStatusCode()).as(method + " " + p + " " + r.getBody()).isEqualTo(HttpStatus.FORBIDDEN);
                    assertThat(code(r)).as(method + " " + p).isEqualTo("MODULE_NOT_ENTITLED");
                    checked++;
                }
            }
        }
        assertThat(checked).as("insurance operations found").isEqualTo(8);
        Map ent = get(e.token(), "/v1/me/entitlements").getBody();
        assertThat(((List<Map<String, Object>>) ent.get("modules")).stream().filter(x -> "insurance".equals(x.get("code"))).findFirst().orElseThrow()
                .get("enabled")).as("what the screens are hidden by").isEqualTo(false);

        // the coverage row exists, but the clinic has no insurance now: the bill is an ordinary one
        ResponseEntity<Map> inv = checkout(e.token(), v[0], List.of(line("SVC", 2)));
        assertThat(inv.getStatusCode()).as("%s", inv.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(inv.getBody()).doesNotContainKey("insurance");
        assertThat(num(inv.getBody().get("balanceDue"))).isEqualTo(1000.0);

        Env with = clinic();
        subscribe(with.tenant().id(), List.of("billing", "insurance"));
        assertThat(get(with.token(), "/v1/insurance/payers").getStatusCode()).as("a plan with insurance").isEqualTo(HttpStatus.OK);
    }

    @Test
    void permissions_viewReads_editChanges_andBillingAloneIsNotEnough() {
        Env e = clinic();
        String billingOnly = tokenWith(e, fullGrant("billing"), fullGrant("patients"));
        assertThat(get(billingOnly, "/v1/insurance/payers").getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(post(billingOnly, "/v1/insurance/payers", Map.of("name", "X", "kind", "insurer")).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        String viewer = tokenWith(e, new ModuleGrant("insurance", true, false, false, false, false, false, false));
        assertThat(get(viewer, "/v1/insurance/payers").getStatusCode()).isEqualTo(HttpStatus.OK);
        String patient = patient(e.token());
        String visit = checkIn(e, patient);
        assertThat(get(viewer, "/v1/insurance/patients/" + patient + "/policies").getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(get(viewer, "/v1/insurance/visits/" + visit + "/coverage").getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(post(viewer, "/v1/insurance/payers", Map.of("name", "X", "kind", "insurer")).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(post(viewer, "/v1/insurance/payers/" + UUID.randomUUID() + "/plans", Map.of("name", "P", "copayPercent", 10)).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(addPolicy(viewer, patient, UUID.randomUUID().toString(), null, "M1", today(), today()).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(cover(viewer, visit, "self_pay", null).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(payerPays(viewer, UUID.randomUUID().toString(), 10, UUID.randomUUID().toString()).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(count(e, "SELECT count(*) FROM insurance_payers WHERE tenant_id = ?", e.tenant().id())).isZero();

        // a pharmacy-only tenant has no visits to insure: the edition guard refuses the whole area
        Env ph = clinic();
        new JdbcTemplate(superuserDataSource()).update("UPDATE tenants SET facility_type = 'pharmacy' WHERE id = ?", ph.tenant().id());
        planEntitlements.evict(ph.tenant().id());
        ResponseEntity<Map> r = get(ph.token(), "/v1/insurance/payers");
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(String.valueOf(r.getBody())).contains("MODULE_NOT_ENTITLED");
    }

    // ---- "an uninsured visit's invoice is exactly what it is today" ----
    @Test
    void anUninsuredVisitsInvoiceIsUntouched_withNoDecisionAndWithAnExplicitSelfPay() {
        Env e = clinic();
        String p1 = patient(e.token()), p2 = patient(e.token());
        String noDecision = atCheckout(e, p1), selfPay = atCheckout(e, p2);
        assertThat(cover(e.token(), selfPay, "insured", policy(e, p2, 20)).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(cover(e.token(), selfPay, "self_pay", null).getBody().get("mode")).as("decided insured, then back to self-pay").isEqualTo("self_pay");
        assertThat(count(e, "SELECT count(*) FROM visit_coverages WHERE visit_id = ? AND mode = 'self_pay' AND policy_id IS NULL", UUID.fromString(selfPay))).isEqualTo(1);
        for (String visit : List.of(noDecision, selfPay)) {
            ResponseEntity<Map> inv = checkout(e.token(), visit, List.of(line("SVC", 1), line("XRAY", 1)));
            assertThat(inv.getStatusCode()).as("%s", inv.getBody()).isEqualTo(HttpStatus.OK);
            assertThat(inv.getBody()).as("no insurance key at all").doesNotContainKey("insurance");
            assertThat(num(inv.getBody().get("total"))).isEqualTo(1700.0);
            assertThat(num(inv.getBody().get("balanceDue"))).isEqualTo(1700.0);
            assertThat(inv.getBody().get("status")).isEqualTo("unpaid");
            UUID id = UUID.fromString((String) inv.getBody().get("id"));
            Map<String, Object> r = row(e, "SELECT payer_id, payer_share, payer_share_minor, patient_share_minor, total_minor, payer_paid, payer_shortfall_status FROM invoices WHERE id = ?", id);
            assertThat(r.get("payer_id")).isNull();
            assertThat(bd(r.get("payer_share").toString())).isEqualByComparingTo("0");
            assertThat(lng(r.get("payer_share_minor"))).isZero();
            assertThat(lng(r.get("patient_share_minor"))).isEqualTo(lng(r.get("total_minor")));
            assertThat(r.get("payer_shortfall_status")).isNull();
            assertThat(count(e, "SELECT count(*) FROM invoice_line_items WHERE invoice_id = ? AND (patient_share_minor IS NOT NULL OR payer_share_minor <> 0)", id)).isZero();
            assertThat(pay(e.token(), id.toString(), "cash", 1700).getBody().get("status")).isEqualTo("paid");
            // an insurer payment has nowhere to go on it
            ResponseEntity<Map> pp = payerPays(e.token(), id.toString(), 100, UUID.randomUUID().toString());
            assertThat(pp.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
            assertThat(code(pp)).isEqualTo("INVOICE_NOT_INSURED");
        }
    }

    // ---- AC2: 20% co-pay: each line shows both shares and the desk collects only the patient's ----
    @Test
    void ac2_insuredVisitWithA20PercentCopay_everyLineShowsBothShares_andTheDeskCollectsOnlyThePatientShare() {
        Env e = clinic();
        String[] v = insuredVisit(e, 20);
        ResponseEntity<Map> inv = checkout(e.token(), v[0], List.of(line("SVC", 1), line("XRAY", 1)));
        assertThat(inv.getStatusCode()).as("%s", inv.getBody()).isEqualTo(HttpStatus.OK);
        String id = (String) inv.getBody().get("id");
        assertThat(num(inv.getBody().get("total"))).as("the invoice is still the full value").isEqualTo(1700.0);
        Map<String, Object> ins = insurance(inv);
        assertThat(num(ins.get("patientShare"))).isEqualTo(340.0);
        assertThat(num(ins.get("payerShare"))).isEqualTo(1360.0);
        assertThat(num(ins.get("payerPaid"))).isZero();
        assertThat(num(ins.get("pendingDecision"))).isZero();
        assertThat(ins.get("payerId")).isNotNull();
        assertThat(num(inv.getBody().get("balanceDue"))).as("the desk's balance is the patient's share").isEqualTo(340.0);
        assertThat(inv.getBody().get("status")).isEqualTo("unpaid");

        List<Map<String, Object>> items = (List<Map<String, Object>>) inv.getBody().get("lineItems");
        List<Map<String, Object>> shares = (List<Map<String, Object>>) ins.get("lines");
        assertThat(shares).hasSize(2);
        Map<String, long[]> byCode = new HashMap<>();
        for (Map<String, Object> li : items) {
            Map<String, Object> s = shares.stream().filter(x -> x.get("lineId").equals(li.get("id"))).findFirst().orElseThrow();
            byCode.put((String) li.get("chargeCode"), new long[]{lng(s.get("patientShareMinor")), lng(s.get("payerShareMinor"))});
        }
        assertThat(byCode.get("SVC")).containsExactly(10_000L, 40_000L);
        assertThat(byCode.get("XRAY")).containsExactly(24_000L, 96_000L);
        assertSplitInvariant(e, id);

        // the desk cannot take the insurer's part from the patient
        ResponseEntity<Map> over = pay(e.token(), id, "cash", 340.01);
        assertThat(over.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(num(over.getBody().get("balance"))).isEqualTo(340.0);
        assertThat(pay(e.token(), id, "cash", 100).getBody().get("status")).isEqualTo("partial");
        assertThat(inDues(e, v[1])).as("240 of the patient's share is still due").isTrue();
        ResponseEntity<Map> paid = pay(e.token(), id, "upi", 240);
        assertThat(paid.getBody().get("status")).as("paid once the patient's share is in").isEqualTo("paid");
        assertThat(num(paid.getBody().get("balanceDue"))).isZero();
        assertThat(num(paid.getBody().get("paid"))).isEqualTo(340.0);
        assertThat(inDues(e, v[1])).as("the insurer's 1360 is never the patient's due").isFalse();
        assertThat(pay(e.token(), id, "cash", 1).getStatusCode()).as("nothing more to collect at the desk").isIn(HttpStatus.UNPROCESSABLE_CONTENT, HttpStatus.CONFLICT);
        ResponseEntity<Map> again = getInvoice(e.token(), id);
        assertThat(num(insurance(again).get("payerShare"))).isEqualTo(1360.0);

        // the event carries ids and the mode only; the coverage is audited
        String ev = inTenantTx(e.tenant().id(), () -> jdbc.queryForObject("SELECT payload::text FROM outbox_events WHERE tenant_id = ? " +
                "AND event_type = 'insurance.coverage.set.v1' AND aggregate_id = ?", String.class, e.tenant().id(), UUID.fromString(v[0])));
        assertThat(ev).contains("\"mode\": \"insured\"").contains(v[0]).doesNotContain("policy").doesNotContain("member");
        assertThat(count(e, "SELECT count(*) FROM audit_log WHERE tenant_id = ? AND action = 'insurance.coverage_set' AND entity_id = ?",
                e.tenant().id(), UUID.fromString(v[0]))).isEqualTo(1);
    }

    // ---- money: odd percentages, GST and the whole-rupee round-off ----
    @Test
    void oddCopayPercentagesWithGst_everyLineAndTheTotalAddUpToThePaisa() {
        Env e = clinic();
        UUID owner = seedOwner("ow" + e.tenant().id() + "@a.com").id(), entity = UUID.randomUUID(), reg = UUID.randomUUID();
        JdbcTemplate su = new JdbcTemplate(superuserDataSource());
        su.update("INSERT INTO legal_entities (id, owner_id, name, entity_type) VALUES (?,?,?,?)", entity, owner, "LE", "company");
        su.update("INSERT INTO tax_registrations (id, legal_entity_id, jurisdiction_code, gstin_or_vat_no, registered) VALUES (?,?,?,?,?)", reg, entity, "IN", GSTIN, true);
        su.update("UPDATE tenants SET tax_registration_id = ? WHERE id = ?", reg, e.tenant().id());
        inTenantTx(e.tenant().id(), () -> {
            for (Object[] h : new Object[][]{{"T18", 333.33, 18.0, false}, {"T12", 99.99, 12.0, false}, {"T5I", 1049.5, 5.0, true}, {"TEX", 77.77, 0.0, false}}) {
                jdbc.update("INSERT INTO charge_catalogue (tenant_id, code, name, category, base_amount, tax_rate_percent, tax_category, sac_hsn, tax_inclusive) " +
                        "VALUES (?,?,?,?,?,?,?,?,?)", e.tenant().id(), h[0], "Head " + h[0], "Service", h[1], h[2],
                        ((double) h[2]) > 0 ? "taxable" : "exempt", "999312", h[3]);
            }
        });
        for (String pct : List.of("33.33", "12.5", "0.01", "99.99", "66.67")) {
            String[] v = insuredVisit(e, pct);
            ResponseEntity<Map> inv = checkout(e.token(), v[0], List.of(line("T18", 3), line("T12", 7), line("T5I", 1), line("TEX", 1), line("SVC", 1)));
            assertThat(inv.getStatusCode()).as(pct + " %s", inv.getBody()).isEqualTo(HttpStatus.OK);
            String id = (String) inv.getBody().get("id");
            assertThat(num(inv.getBody().get("tax"))).as("GST was charged").isPositive();
            assertSplitInvariant(e, id);
            Map<String, Object> ins = insurance(inv);
            assertThat(bd(ins.get("patientShare").toString()).add(bd(ins.get("payerShare").toString())))
                    .as(pct + "%% patient + payer").isEqualByComparingTo(bd(inv.getBody().get("total").toString()));
            assertThat(bd(inv.getBody().get("balanceDue").toString())).isEqualByComparingTo(bd(ins.get("patientShare").toString()));
            // the patient's share to the paisa settles the desk's side
            assertThat(bd(ins.get("patientShare").toString())).as(pct + "%% leaves the patient something to pay here").isPositive();
            ResponseEntity<Map> paid = pay(e.token(), id, "cash", bd(ins.get("patientShare").toString()));
            assertThat(paid.getStatusCode()).as("%s", paid.getBody()).isEqualTo(HttpStatus.CREATED);
            assertThat(paid.getBody().get("status")).isEqualTo("paid");
            assertThat(bd(paid.getBody().get("balanceDue").toString())).isEqualByComparingTo("0");
        }
    }

    @Test
    void aZeroCopayBillIsPaidForThePatientAtOnce_andAHundredPercentCopayLeavesThePayerNothing() {
        Env e = clinic();
        String[] zero = insuredVisit(e, 0);
        ResponseEntity<Map> inv = checkout(e.token(), zero[0], List.of(line("SVC", 1)));
        assertThat(num(insurance(inv).get("payerShare"))).isEqualTo(500.0);
        assertThat(num(inv.getBody().get("balanceDue"))).isZero();
        assertThat(inv.getBody().get("status")).as("nothing for the desk to collect").isEqualTo("paid");
        assertThat(inDues(e, zero[1])).isFalse();
        assertSplitInvariant(e, (String) inv.getBody().get("id"));

        String[] full = insuredVisit(e, 100);
        ResponseEntity<Map> all = checkout(e.token(), full[0], List.of(line("SVC", 1)));
        assertThat(num(all.getBody().get("balanceDue"))).isEqualTo(500.0);
        assertThat(count(e, "SELECT count(*) FROM invoices WHERE id = ? AND payer_share = 0 AND payer_id IS NOT NULL", UUID.fromString((String) all.getBody().get("id")))).isEqualTo(1);
    }

    @Test
    void anInsuredVisitBilledAtTwoStopsIsSplitByOneRuleAcrossAllItsLines() {
        Env e = clinic();
        String dept = (String) ((Map<?, ?>) exchange("/v1/departments", HttpMethod.GET, authed(e.token()), List.class).getBody().stream()
                .filter(d -> Boolean.TRUE.equals(((Map<?, ?>) d).get("isDefault"))).findFirst().orElseThrow()).get("id");
        post(e.token(), "/v1/departments/" + dept + "/workflow", Map.of("templateCode", "clinic_walkin_with_billing", "toggles", Map.of("vitals_enabled", true)));
        String patient = patient(e.token());
        String visit = checkIn(e, patient);
        move(e, visit, "waiting", "billing_pending");
        assertThat(cover(e.token(), visit, "insured", policy(e, patient, "12.5")).getStatusCode()).isEqualTo(HttpStatus.OK);

        ResponseEntity<Map> first = checkout(e.token(), visit, List.of(line("CONS", 1)));
        assertThat(first.getStatusCode()).as("%s", first.getBody()).isEqualTo(HttpStatus.OK);
        String id = (String) first.getBody().get("id");
        assertThat(num(first.getBody().get("balanceDue"))).isEqualTo(50.0); // 12.5% of 400
        assertThat(pay(e.token(), id, "cash", 50).getBody().get("status")).isEqualTo("paid");

        move(e, visit, "vitals_done", "in_consult", "checkout_pending");
        ResponseEntity<Map> ext = checkout(e.token(), visit, List.of(line("XRAY", 1)));
        assertThat(ext.getStatusCode()).as("%s", ext.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(ext.getBody().get("id")).isEqualTo(id);
        assertThat(num(ext.getBody().get("total"))).isEqualTo(1600.0);
        assertThat(num(insurance(ext).get("payerShare"))).isEqualTo(1400.0);
        assertThat(num(ext.getBody().get("balanceDue"))).as("200 patient share, 50 already paid").isEqualTo(150.0);
        assertThat(ext.getBody().get("status")).isEqualTo("partial");
        assertSplitInvariant(e, id);
    }

    // ---- AC3: the payer pays 750 of its 800: an insurance payment of 750, 50 waits for a decision, never the patient's ----
    @Test
    void ac3_payerPays750OfIts800Share_750IsRecordedAsInsurance_and50WaitsForADecisionNotThePatient() {
        Env e = clinic();
        String[] v = insuredVisit(e, 20);
        ResponseEntity<Map> inv = checkout(e.token(), v[0], List.of(line("SVC", 2)));
        String id = (String) inv.getBody().get("id");
        UUID uid = UUID.fromString(id);
        assertThat(num(insurance(inv).get("payerShare"))).isEqualTo(800.0);
        assertThat(pay(e.token(), id, "cash", 200).getBody().get("status")).isEqualTo("paid");

        String key = UUID.randomUUID().toString();
        ResponseEntity<Map> r = payerPays(e.token(), id, 750, key);
        assertThat(r.getStatusCode()).as("%s", r.getBody()).isEqualTo(HttpStatus.CREATED);
        assertThat(num(r.getBody().get("payerShare"))).isEqualTo(800.0);
        assertThat(num(r.getBody().get("payerPaid"))).isEqualTo(750.0);
        assertThat(num(r.getBody().get("pendingDecision"))).isEqualTo(50.0);
        assertThat(num(r.getBody().get("patientShare"))).as("the patient's share did not move").isEqualTo(200.0);
        // the same request again (a retry) records nothing more
        assertThat(payerPays(e.token(), id, 750, key).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        Map<String, Object> pp = row(e, "SELECT count(*) AS n, min(method) AS method, sum(amount) AS amount FROM invoice_payer_payments WHERE invoice_id = ?", uid);
        assertThat(lng(pp.get("n"))).isEqualTo(1);
        assertThat(pp.get("method")).isEqualTo("insurance");
        assertThat(bd(pp.get("amount").toString())).isEqualByComparingTo("750");
        assertThat(row(e, "SELECT payer_shortfall_status FROM invoices WHERE id = ?", uid).get("payer_shortfall_status")).isEqualTo("pending_decision");

        ResponseEntity<Map> after = getInvoice(e.token(), id);
        assertThat(num(insurance(after).get("payerPaid"))).isEqualTo(750.0);
        assertThat(num(insurance(after).get("pendingDecision"))).isEqualTo(50.0);
        assertThat(num(insurance(after).get("patientShare"))).isEqualTo(200.0);
        assertThat(num(after.getBody().get("balanceDue"))).as("the 50 is not moved to the patient").isZero();
        assertThat(num(after.getBody().get("paid"))).as("the desk's takings are only the patient's 200").isEqualTo(200.0);
        assertThat(after.getBody().get("status")).isEqualTo("paid");
        assertThat((List<?>) after.getBody().get("payments")).as("the insurer's payment is not a desk payment").hasSize(1);
        assertThat(count(e, "SELECT count(*) FROM invoice_payments WHERE invoice_id = ?", uid)).isEqualTo(1);
        assertThat(inDues(e, v[1])).isFalse();
        assertThat(pay(e.token(), id, "cash", 50).getStatusCode()).as("the desk cannot collect the 50 from the patient").isIn(HttpStatus.UNPROCESSABLE_CONTENT, HttpStatus.CONFLICT);
        assertThat(count(e, "SELECT count(*) FROM audit_log WHERE tenant_id = ? AND action = 'billing.payer_payment_recorded' AND entity_id = ?", e.tenant().id(), uid)).isEqualTo(1);

        // the payer cannot pay more than it still owes; paying the rest clears the pending decision
        ResponseEntity<Map> over = payerPays(e.token(), id, 50.01, UUID.randomUUID().toString());
        assertThat(over.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(code(over)).isEqualTo("PAYER_OVERPAYMENT");
        ResponseEntity<Map> rest = payerPays(e.token(), id, 50, UUID.randomUUID().toString());
        assertThat(num(rest.getBody().get("payerPaid"))).isEqualTo(800.0);
        assertThat(num(rest.getBody().get("pendingDecision"))).isZero();
        assertThat(row(e, "SELECT payer_shortfall_status FROM invoices WHERE id = ?", uid).get("payer_shortfall_status")).isNull();

        // an insured bill is not refunded or voided from the desk
        Object paymentId = ((List<Map<String, Object>>) after.getBody().get("payments")).get(0).get("id");
        Map<String, Object> refund = new HashMap<>(Map.of("amount", 100, "reasonCode", "billing_error", "refundTo", List.of(Map.of("paymentId", paymentId, "amount", 100))));
        ResponseEntity<Map> rf = post(e.token(), "/v1/billing/invoices/" + id + "/refund-requests", refund);
        assertThat(rf.getStatusCode()).as("%s", rf.getBody()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(code(rf)).isEqualTo("INSURED_INVOICE");
        assertThat(payerPays(e.token(), UUID.randomUUID().toString(), 10, UUID.randomUUID().toString()).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void anInvoiceTheInsurerHasPaidOnCannotBeVoided() {
        Env e = clinic();
        String[] v = insuredVisit(e, 0);
        String id = (String) checkout(e.token(), v[0], List.of(line("SVC", 1))).getBody().get("id");
        assertThat(payerPays(e.token(), id, 100, UUID.randomUUID().toString()).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        ResponseEntity<Map> r = post(e.token(), "/v1/billing/invoices/" + id + "/void", Map.of("reason", "entered by mistake"));
        assertThat(r.getStatusCode()).as("%s", r.getBody()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(getInvoice(e.token(), id).getBody().get("status")).isNotEqualTo("void");
    }

    // ---- AC4: a policy expired on the visit date: warned, and the visit is not marked insured ----
    @Test
    void ac4_aPolicyExpiredOnTheVisitDateIsFlaggedBeforeAndRefusedOnMarkingTheVisitInsured() {
        Env e = clinic();
        String patient = patient(e.token()), other = patient(e.token());
        String visit = atCheckout(e, patient);
        String payer = payer(e, "Star " + UUID.randomUUID(), Map.of());
        String plan = plan(e, payer, "Gold", 20);
        String expired = (String) addPolicy(e.token(), patient, payer, plan, "EXPIRED-01", today().minusDays(400), today().minusDays(2)).getBody().get("id");
        String future = (String) addPolicy(e.token(), patient, payer, plan, "FUTURE-01", today().plusDays(3), today().plusDays(300)).getBody().get("id");
        String noPlan = (String) addPolicy(e.token(), patient, payer, null, "NOPLAN-01", today().minusDays(5), today().plusDays(5)).getBody().get("id");
        String current = (String) addPolicy(e.token(), patient, payer, plan, "CURRENT-01", today().minusDays(5), today().plusDays(5)).getBody().get("id");
        String someoneElses = policy(e, other, 20);

        // before marking: the desk sees which policy cannot be used and why
        Map cov = get(e.token(), "/v1/insurance/visits/" + visit + "/coverage").getBody();
        assertThat(cov.get("mode")).isEqualTo("self_pay");
        assertThat(cov.get("locked")).isEqualTo(false);
        Map<String, Object> warnings = new HashMap<>();
        for (Map<String, Object> p : (List<Map<String, Object>>) cov.get("policies")) {
            warnings.put((String) p.get("id"), p.get("warning"));
            assertThat((String) p.get("memberIdMasked")).as("masked in the picker").doesNotContain("EXPIRED").doesNotContain("CURRENT").doesNotContain("FUTURE").endsWith("-01");
        }
        assertThat(warnings).containsEntry(expired, "POLICY_EXPIRED").containsEntry(future, "POLICY_NOT_STARTED").containsEntry(noPlan, "PLAN_REQUIRED")
                .containsEntry(current, null).doesNotContainKey(someoneElses);

        ResponseEntity<Map> r = cover(e.token(), visit, "insured", expired);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(code(r)).isEqualTo("POLICY_EXPIRED");
        assertThat((String) r.getBody().get("detail")).as("a sentence the desk can read").contains("expired on " + today().minusDays(2));
        assertThat(code(cover(e.token(), visit, "insured", future))).isEqualTo("POLICY_NOT_STARTED");
        assertThat(code(cover(e.token(), visit, "insured", noPlan))).isEqualTo("PLAN_REQUIRED");
        assertThat(code(cover(e.token(), visit, "insured", someoneElses))).as("another patient's policy").isEqualTo("POLICY_UNKNOWN");
        assertThat(code(cover(e.token(), visit, "insured", null))).isEqualTo("POLICY_REQUIRED");
        assertThat(get(e.token(), "/v1/insurance/visits/" + visit + "/coverage").getBody().get("mode")).as("still self-pay").isEqualTo("self_pay");
        assertThat(count(e, "SELECT count(*) FROM visit_coverages WHERE tenant_id = ?", e.tenant().id())).isZero();
        assertThat(cover(e.token(), UUID.randomUUID().toString(), "insured", current).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

        // the list on the patient's file marks the expired one too
        List<Map<String, Object>> policies = (List<Map<String, Object>>) get(e.token(), "/v1/insurance/patients/" + patient + "/policies").getBody().get("items");
        assertThat(policies).hasSize(4);
        assertThat(policies.stream().filter(p -> p.get("id").equals(expired)).findFirst().orElseThrow().get("expired")).isEqualTo(true);
        assertThat(policies.stream().filter(p -> p.get("id").equals(current)).findFirst().orElseThrow().get("expired")).isEqualTo(false);

        // a policy that is valid on the visit date covers it; the co-pay is the plan's
        ResponseEntity<Map> ok = cover(e.token(), visit, "insured", current);
        assertThat(ok.getStatusCode()).as("%s", ok.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(ok.getBody().get("mode")).isEqualTo("insured");
        assertThat(num(ok.getBody().get("copayPercent"))).isEqualTo(20.0);
        assertThat(cover(e.token(), visit, "self_pay", null).getBody().get("mode")).as("the decision can change until the visit is billed").isEqualTo("self_pay");
        assertThat(cover(e.token(), visit, "insured", current).getStatusCode()).isEqualTo(HttpStatus.OK);

        // once billed, the decision stands: the bill was split under it
        String id = (String) checkout(e.token(), visit, List.of(line("SVC", 1))).getBody().get("id");
        assertThat(get(e.token(), "/v1/insurance/visits/" + visit + "/coverage").getBody().get("locked")).isEqualTo(true);
        ResponseEntity<Map> late = cover(e.token(), visit, "self_pay", null);
        assertThat(late.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(code(late)).isEqualTo("VISIT_BILLED");
        assertThat(cover(e.token(), visit, "insured", current).getStatusCode()).as("the standing decision sent again changes nothing").isEqualTo(HttpStatus.OK);
        assertThat(num(getInvoice(e.token(), id).getBody().get("balanceDue"))).isEqualTo(100.0);
    }

    // ---- AC5: each payer carries per-market identifiers the adapters use ----
    @Test
    void ac5_payersCarryPerMarketIdentifiers_globalMasterRowsAreReadByEveryClinicAndWrittenByNone() {
        Env e = clinic();
        String name = "Acme Health " + UUID.randomUUID();
        String own = payer(e, name, Map.of("irdai", "IRDAI-123"));
        plan(e, own, "Silver", "12.5");
        UUID global = UUID.randomUUID();
        new JdbcTemplate(superuserDataSource()).update("INSERT INTO insurance_payers (id, tenant_id, name, kind, jurisdiction, identifiers) " +
                "VALUES (?, NULL, ?, 'insurer', 'AE', ?::jsonb)", global, "Gulf Master " + global, "{\"dha_payer_id\": \"DHA-9\", \"shafafiya_id\": \"A001\"}");

        List<Map<String, Object>> all = (List<Map<String, Object>>) get(e.token(), "/v1/insurance/payers").getBody().get("items");
        Map<String, Object> mine = all.stream().filter(p -> p.get("id").equals(own)).findFirst().orElseThrow();
        assertThat(mine.get("jurisdiction")).as("the clinic's own market").isEqualTo("IN");
        assertThat(mine.get("global")).isEqualTo(false);
        assertThat((Map<String, Object>) mine.get("identifiers")).containsEntry("irdai", "IRDAI-123");
        assertThat(((List<Map<String, Object>>) mine.get("plans")).get(0)).containsEntry("name", "Silver").containsEntry("copayPercent", 12.5);
        Map<String, Object> master = all.stream().filter(p -> p.get("id").equals(global.toString())).findFirst().orElseThrow();
        assertThat(master.get("global")).isEqualTo(true);
        assertThat((Map<String, Object>) master.get("identifiers")).containsEntry("dha_payer_id", "DHA-9").containsEntry("shafafiya_id", "A001");

        List<Map<String, Object>> ae = (List<Map<String, Object>>) get(e.token(), "/v1/insurance/payers?jurisdiction=AE").getBody().get("items");
        assertThat(ae).extracting(p -> p.get("id")).contains(global.toString()).doesNotContain(own);
        List<Map<String, Object>> byName = (List<Map<String, Object>>) get(e.token(), "/v1/insurance/payers?q=acme").getBody().get("items");
        assertThat(byName).extracting(p -> p.get("id")).contains(own).doesNotContain(global.toString());
        assertThat(get(e.token(), "/v1/insurance/payers?jurisdiction=India").getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        // an adapter addresses the payer by its market's key
        ClaimsGateway dha = new ClaimsGateway() {
            public String jurisdiction() { return "AE"; }
            public String payerIdentifierKey() { return "dha_payer_id"; }
        };
        assertThat(dha.payerIdentifier((Map<String, String>) master.get("identifiers"))).isEqualTo(Optional.of("DHA-9"));
        assertThat(dha.payerIdentifier((Map<String, String>) mine.get("identifiers"))).as("no DHA id on an Indian payer: the claim cannot be sent").isEmpty();

        // only known identifier keys; one name once per clinic; the master cannot be written through the API role
        ResponseEntity<Map> bad = post(e.token(), "/v1/insurance/payers", Map.of("name", "Bad " + UUID.randomUUID(), "kind", "tpa", "identifiers", Map.of("ssn", "1")));
        assertThat(bad.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(code(bad)).isEqualTo("UNKNOWN_IDENTIFIER");
        assertThat(post(e.token(), "/v1/insurance/payers", Map.of("name", name.toUpperCase(), "kind", "insurer")).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(post(e.token(), "/v1/insurance/payers", Map.of("name", "K " + UUID.randomUUID(), "kind", "bank")).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(inTenantTx(e.tenant().id(), () -> jdbc.update("UPDATE insurance_payers SET name = 'hijacked' WHERE id = ?", global))).as("RLS: a clinic cannot change a master row").isZero();
        // a clinic may add its own plan to a master payer and use it
        String masterPlan = plan(e, global.toString(), "Local plan", 10);
        String patient = patient(e.token());
        assertThat(addPolicy(e.token(), patient, global.toString(), masterPlan, "GM1", today(), today().plusDays(1)).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(post(e.token(), "/v1/insurance/payers/" + UUID.randomUUID() + "/plans", Map.of("name", "P", "copayPercent", 10)).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(post(e.token(), "/v1/insurance/payers/" + own + "/plans", Map.of("name", "P", "copayPercent", 100.5)).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void policies_memberIdAndPolicyNumberAreEncryptedAtRest_validated_andNeverCrossTenants() {
        Env a = clinic(), b = clinic();
        String patient = patient(a.token());
        String payer = payer(a, "Payer A " + UUID.randomUUID(), Map.of());
        String plan = plan(a, payer, "Plan A", 20);
        ResponseEntity<Map> created = addPolicy(a.token(), patient, payer, plan, "MEMBER-778899", today().minusDays(1), today().plusDays(1));
        assertThat(created.getStatusCode()).as("%s", created.getBody()).isEqualTo(HttpStatus.CREATED);
        UUID id = UUID.fromString((String) created.getBody().get("id"));
        assertThat(created.getBody()).containsEntry("memberId", "MEMBER-778899").containsEntry("policyNumber", "POL-MEMBER-778899")
                .containsEntry("payerId", payer).containsEntry("planId", plan).containsEntry("relationship", "self").containsEntry("status", "active");

        Map<String, Object> raw = row(a, "SELECT member_id_enc, policy_number_enc FROM patient_policies WHERE id = ?", id);
        assertThat(new String((byte[]) raw.get("member_id_enc"), StandardCharsets.ISO_8859_1)).doesNotContain("MEMBER-778899").doesNotContain("778899");
        assertThat(new String((byte[]) raw.get("policy_number_enc"), StandardCharsets.ISO_8859_1)).doesNotContain("778899");
        String audited = inTenantTx(a.tenant().id(), () -> jdbc.queryForObject("SELECT string_agg(COALESCE(after::text, '') || COALESCE(before::text, ''), ' ') " +
                "FROM audit_log WHERE tenant_id = ? AND action LIKE 'insurance.%'", String.class, a.tenant().id()));
        assertThat(audited).contains(patient).doesNotContain("778899");
        assertThat(count(a, "SELECT count(*) FROM audit_log WHERE tenant_id = ? AND action = 'insurance.policy_added' AND entity_id = ?", a.tenant().id(), id)).isEqualTo(1);

        // validation
        assertThat(code(addPolicy(a.token(), patient, UUID.randomUUID().toString(), null, "M2", today(), today()))).isEqualTo("PAYER_UNKNOWN");
        String otherPayer = payer(a, "Payer A2 " + UUID.randomUUID(), Map.of());
        assertThat(code(addPolicy(a.token(), patient, otherPayer, plan, "M3", today(), today()))).as("a plan of another payer").isEqualTo("PLAN_UNKNOWN");
        assertThat(code(addPolicy(a.token(), patient, payer, plan, "M4", today(), today().minusDays(1)))).isEqualTo("INVALID_VALIDITY");
        assertThat(addPolicy(a.token(), UUID.randomUUID().toString(), payer, plan, "M5", today(), today()).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(addPolicy(a.token(), patient, payer, plan, " ", today(), today()).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        // tenant B: sees none of A's payers, plans or policies and cannot use them
        List<Map<String, Object>> bPayers = (List<Map<String, Object>>) get(b.token(), "/v1/insurance/payers").getBody().get("items");
        assertThat(bPayers).extracting(p -> p.get("id")).doesNotContain(payer, otherPayer);
        assertThat(get(b.token(), "/v1/insurance/patients/" + patient + "/policies").getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        String bPatient = patient(b.token());
        assertThat(code(addPolicy(b.token(), bPatient, payer, null, "MB", today(), today()))).isEqualTo("PAYER_UNKNOWN");
        assertThat(post(b.token(), "/v1/insurance/payers/" + payer + "/plans", Map.of("name", "P", "copayPercent", 10)).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        String aVisit = atCheckout(a, patient);
        assertThat(cover(b.token(), aVisit, "self_pay", null).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(get(b.token(), "/v1/insurance/visits/" + aVisit + "/coverage").getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        String bVisit = atCheckout(b, bPatient);
        assertThat(code(cover(b.token(), bVisit, "insured", id.toString()))).as("A's policy on B's visit").isEqualTo("POLICY_UNKNOWN");
        String aInvoice = (String) checkout(a.token(), aVisit, List.of(line("SVC", 1))).getBody().get("id");
        assertThat(payerPays(b.token(), aInvoice, 10, UUID.randomUUID().toString()).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        for (String table : List.of("patient_policies", "visit_coverages", "insurance_plans", "invoice_payer_payments")) {
            assertThat(count(b, "SELECT count(*) FROM " + table)).as("RLS on " + table).isZero();
        }
        assertThat(count(b, "SELECT count(*) FROM insurance_payers WHERE tenant_id IS NOT NULL")).as("RLS: only master rows of other owners").isZero();
    }
}
