package com.nabd.hms.billing.document;

import com.nabd.hms.common.ModuleGrant;
import com.nabd.hms.support.ApiTestBase;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Sprint 15 billing fixtures (FIN-012, FIN-014): a clinic with a catalogue, invoices through the real checkout, PDF text readback. */
@SuppressWarnings({"unchecked", "rawtypes"})
public abstract class S15BillingTestBase extends ApiTestBase {

    private static final AtomicInteger PHONES = new AtomicInteger((int) (System.nanoTime() % 4_000_000));
    private int seq = 0;
    private static final String[] NAMES = {"Abdullah", "Meenakshi", "Tomasz", "Priyanka", "Giorgos", "Fatima", "Wei", "Oluwaseun", "Ingrid", "Rashid"};

    public record Env(SeededTenant tenant, SeededStaff owner, String ownerToken) {
    }

    protected Env env() {
        SeededTenant t = seedTenant();
        SeededStaff owner = seedStaff(t, seedFullAccessRole(t.id()), "own" + t.id() + "@a.com", phone(), false);
        Env e = new Env(t, owner, loginAndGetAccessToken(owner));
        catalogue(t, "CONS", "Consultation", 400, 0);
        catalogue(t, "XRAY", "Procedure", 500, 10);
        return e;
    }

    protected String phone() {
        return "+91972" + String.format("%07d", 1_000_000 + PHONES.getAndIncrement());
    }

    protected void catalogue(SeededTenant t, String code, String category, double base, double tax) {
        inTenantTx(t.id(), () -> jdbc.update("INSERT INTO charge_catalogue (tenant_id, code, name, category, base_amount, tax_rate_percent) VALUES (?,?,?,?,?,?)",
                t.id(), code, "Cat " + code, category, base, tax));
    }

    /** A token for a role with only the given billing grants. */
    protected String billingToken(Env e, String roleName, boolean view, boolean edit, boolean approve) {
        UUID role = seedRole(e.tenant().id(), roleName, false, new ModuleGrant("billing", view, false, edit, false, approve, false, false));
        return loginAndGetAccessToken(seedStaff(e.tenant(), role, roleName + e.tenant().id() + "@a.com", phone(), false));
    }

    protected String patient(Env e) {
        seq++;
        return (String) exchange("/v1/patients", HttpMethod.POST, authedJsonBody(e.ownerToken(), Map.of(
                "name", NAMES[seq % NAMES.length] + " Test" + seq, "phone", phone(), "dob", (1950 + 3 * seq) + "-0" + (1 + seq % 9) + "-1" + (seq % 9), "gender", "male")), Map.class).getBody().get("id");
    }

    /** One real checkout of {@code code} x1 for the patient; returns the invoice id. */
    protected String invoice(Env e, String patientId, String code) {
        String entry = (String) exchange("/v1/queue/check-in", HttpMethod.POST, authedJsonBody(e.ownerToken(), Map.of(
                "patientId", patientId, "doctorId", e.owner().id().toString())), Map.class).getBody().get("id");
        for (String s : new String[]{"waiting", "vitals_pending", "vitals_done", "in_consult", "checkout_pending"}) {
            exchange("/v1/queue/" + entry + "/status", HttpMethod.PATCH, authedJsonBody(e.ownerToken(), Map.of("status", s)), Map.class);
        }
        ResponseEntity<Map> r = exchange("/v1/billing/checkout/" + entry, HttpMethod.POST, authedJsonBody(e.ownerToken(),
                Map.of("lineItems", List.of(new HashMap<>(Map.of("chargeCode", code, "quantity", 1))))), Map.class);
        return (String) r.getBody().get("id");
    }

    protected ResponseEntity<Map> pay(Env e, String invoiceId, double amount) {
        return exchange("/v1/billing/invoices/" + invoiceId + "/payments", HttpMethod.POST,
                authedJsonBody(e.ownerToken(), Map.of("method", "cash", "amount", amount)), Map.class);
    }

    /** Text lines of a SimplePdf file (uncompressed Courier text). */
    public static List<String> pdfLines(byte[] pdf) {
        String raw = new String(pdf, StandardCharsets.ISO_8859_1);
        List<String> out = new ArrayList<>();
        Matcher m = Pattern.compile("^\\((.*)\\) Tj T\\*$", Pattern.MULTILINE).matcher(raw);
        while (m.find()) out.add(m.group(1).replace("\\(", "(").replace("\\)", ")").replace("\\\\", "\\"));
        return out;
    }
}
