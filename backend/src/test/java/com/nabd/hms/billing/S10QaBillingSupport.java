package com.nabd.hms.billing;

import com.nabd.hms.common.ModuleGrant;
import com.nabd.hms.support.ApiTestBase;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/** Shared fixtures for the Sprint 10 independent QA classes (FIN-001, FIN-003, PLT-026). Not a test itself. */
abstract class S10QaBillingSupport extends ApiTestBase {

    private static final AtomicInteger SEQ = new AtomicInteger((int) (System.nanoTime() % 4_000_000));

    protected record Env(SeededTenant tenant, SeededStaff owner, String token) { }

    protected String phone() {
        return "+91981" + String.format("%07d", 1_000_000 + SEQ.incrementAndGet() % 8_000_000);
    }

    /** Tenant + full-access owner + catalogue: SVC 500 (no tax), CONS 400 (follow-up 150), XRAY 1200, FREE 0 is NOT created. */
    protected Env env() {
        SeededTenant t = seedTenant();
        SeededStaff owner = seedStaff(t, seedFullAccessRole(t.id()), "qa" + t.id() + "@a.com", phone(), false);
        Env e = new Env(t, owner, loginAndGetAccessToken(owner));
        catalogue(t, "SVC", "Service", 500, null, 0);
        catalogue(t, "CONS", "Consultation", 400, 150.0, 0);
        catalogue(t, "XRAY", "Procedure", 1200, null, 0);
        return e;
    }

    protected void catalogue(SeededTenant t, String code, String category, double base, Double followUp, double tax) {
        inTenantTx(t.id(), () -> jdbc.update("INSERT INTO charge_catalogue (tenant_id, code, name, category, base_amount, follow_up_amount, tax_rate_percent) VALUES (?,?,?,?,?,?,?)",
                t.id(), code, "Cat " + code, category, base, followUp, tax));
    }

    protected String tokenFor(Env e, String roleName, boolean billingView, boolean billingCreate, boolean billingEdit, boolean discount, boolean queue) {
        List<ModuleGrant> g = new ArrayList<>();
        if (billingView || billingCreate || billingEdit) g.add(new ModuleGrant("billing", billingView, billingCreate, billingEdit, false, false, discount, false));
        if (queue) { g.add(fullGrant("queue")); g.add(fullGrant("patients")); }
        UUID role = seedRole(e.tenant().id(), roleName, false, g.toArray(new ModuleGrant[0]));
        return loginAndGetAccessToken(seedStaff(e.tenant(), role, roleName + UUID.randomUUID() + "@a.com", phone(), false));
    }

    protected String patient(String token) {
        return (String) exchange("/v1/patients", HttpMethod.POST, authedJsonBody(token, Map.of(
                "name", "Qa " + UUID.randomUUID().toString().replace("-", "").substring(0, 12), "phone", phone(), "dob", "1990-01-01", "gender", "male")), Map.class).getBody().get("id");
    }

    protected String checkIn(Env e) {
        return (String) exchange("/v1/queue/check-in", HttpMethod.POST, authedJsonBody(e.token(), Map.of(
                "patientId", patient(e.token()), "doctorId", e.owner().id().toString())), Map.class).getBody().get("id");
    }

    protected void move(Env e, String entry, String... statuses) {
        for (String s : statuses) {
            ResponseEntity<Map> r = exchange("/v1/queue/" + entry + "/status", HttpMethod.PATCH, authedJsonBody(e.token(), Map.of("status", s)), Map.class);
            if (!r.getStatusCode().is2xxSuccessful()) throw new IllegalStateException("move to " + s + " failed: " + r.getBody());
        }
    }

    /** A visit sitting at checkout_pending (default department flow). */
    protected String atCheckout(Env e) {
        String id = checkIn(e);
        move(e, id, "waiting", "vitals_pending", "vitals_done", "in_consult", "checkout_pending");
        return id;
    }

    /** A visit sitting at the interim billing_pending stop (clinic_walkin_with_billing flow, NB-355). */
    protected String atInterimBilling(Env e) {
        String dept = (String) ((Map<?, ?>) exchange("/v1/departments", HttpMethod.GET, authed(e.token()), List.class).getBody().stream()
                .filter(d -> Boolean.TRUE.equals(((Map<?, ?>) d).get("isDefault"))).findFirst().orElseThrow()).get("id");
        exchange("/v1/departments/" + dept + "/workflow", HttpMethod.POST, authedJsonBody(e.token(), Map.of(
                "templateCode", "clinic_walkin_with_billing", "toggles", Map.of("vitals_enabled", true))), Map.class);
        String id = checkIn(e);
        move(e, id, "waiting", "billing_pending");
        return id;
    }

    protected static Map<String, Object> line(String code, int qty) {
        return new HashMap<>(Map.of("chargeCode", code, "quantity", qty));
    }

    protected ResponseEntity<Map> checkout(String token, String entry, List<Map<String, Object>> lines, Object... extra) {
        Map<String, Object> body = new HashMap<>();
        body.put("lineItems", lines);
        for (int i = 0; i < extra.length; i += 2) body.put((String) extra[i], extra[i + 1]);
        return exchange("/v1/billing/checkout/" + entry, HttpMethod.POST, authedJsonBody(token, body), Map.class);
    }

    /** Invoice of exactly {@code code} x 1 at a fresh checkout_pending visit. */
    protected String invoiceOf(Env e, String code) {
        ResponseEntity<Map> r = checkout(e.token(), atCheckout(e), List.of(line(code, 1)));
        if (!r.getStatusCode().is2xxSuccessful()) throw new IllegalStateException("checkout failed: " + r.getBody());
        return (String) r.getBody().get("id");
    }

    protected HttpEntity<String> withKey(String token, String key, Object body) {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        h.setBearerAuth(token);
        if (key != null) h.set("Idempotency-Key", key);
        return new HttpEntity<>(writeJson(body), h);
    }

    protected ResponseEntity<Map> pay(String token, String invoiceId, String method, Object amount) {
        return pay(token, invoiceId, method, amount, UUID.randomUUID().toString());
    }

    protected ResponseEntity<Map> pay(String token, String invoiceId, String method, Object amount, String key) {
        return exchange("/v1/billing/invoices/" + invoiceId + "/payments", HttpMethod.POST,
                withKey(token, key, Map.of("method", method, "amount", amount)), Map.class);
    }

    protected ResponseEntity<Map> getInvoice(String token, String invoiceId) {
        return exchange("/v1/billing/invoices/" + invoiceId, HttpMethod.GET, authed(token), Map.class);
    }

    protected int count(Env e, String sql, Object... args) {
        return inTenantTx(e.tenant().id(), () -> jdbc.queryForObject(sql, Integer.class, args));
    }

    protected static double num(Object o) {
        return ((Number) o).doubleValue();
    }

    protected static String type(ResponseEntity<Map> r) {
        return String.valueOf(r.getBody().get("type"));
    }

    protected static BigDecimal bd(String s) {
        return new BigDecimal(s);
    }

    private String writeJson(Object o) {
        try {
            return objectMapper.writeValueAsString(o);
        } catch (com.fasterxml.jackson.core.JsonProcessingException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
