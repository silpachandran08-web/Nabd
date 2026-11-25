package com.nabd.hms.billing.gst;

import com.nabd.hms.billing.document.S15BillingTestBase;
import com.nabd.hms.common.ModuleGrant;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** FIN-011 fixtures: a GST-registered clinic with classified price-list heads and bills issued through the real checkout. Not a test. */
@SuppressWarnings({"unchecked", "rawtypes"})
public abstract class Fin011TestBase extends S15BillingTestBase {

    /** Test GSTINs (format only, not real registrations): the clinic and a buyer in Karnataka (29), a buyer in Maharashtra (27). */
    protected static final String CLINIC_KA = "29ABCDE1234F1Z5", BUYER_KA = "29XYZAB9876C1Z2", BUYER_MH = "27ABCDE1234F1Z5";

    protected JdbcTemplate su() {
        return new JdbcTemplate(superuserDataSource());
    }

    /** The clinic's GST registration as the tax engine reads it (tax_registrations, V2271). */
    protected void registration(Env e, String gstin) {
        UUID ownerId = seedOwner("ow" + e.tenant().id() + "@a.com").id();
        UUID entity = UUID.randomUUID(), reg = UUID.randomUUID();
        JdbcTemplate a = su();
        a.update("INSERT INTO legal_entities (id, owner_id, name, entity_type) VALUES (?,?,?,?)", entity, ownerId, "LE", "company");
        a.update("INSERT INTO tax_registrations (id, legal_entity_id, jurisdiction_code, gstin_or_vat_no, registered) VALUES (?,?,?,?,?)",
                reg, entity, "IN", gstin, true);
        a.update("UPDATE tenants SET tax_registration_id = ? WHERE id = ?", reg, e.tenant().id());
    }

    /** A classified price-list head. goods = an HSN item (inter-state to a buyer GSTIN of another state is IGST). */
    protected void head(Env e, String code, String base, double rate, String taxCategory, String sacHsn, boolean goods, boolean inclusive) {
        inTenantTx(e.tenant().id(), () -> jdbc.update("INSERT INTO charge_catalogue (tenant_id, code, name, category, base_amount, tax_rate_percent, " +
                        "tax_category, sac_hsn, tax_inclusive, hsn_code) VALUES (?,?,?,?,?,?,?,?,?,?)",
                e.tenant().id(), code, "Head " + code, "Service", new java.math.BigDecimal(base), rate, taxCategory, sacHsn, inclusive, goods ? sacHsn : null));
    }

    protected static Map<String, Object> line(String code, int qty) {
        return new HashMap<>(Map.of("chargeCode", code, "quantity", qty));
    }

    /** A visit at the terminal billing stop, checked out with {@code body}; the answer is the issued invoice. */
    protected ResponseEntity<Map> checkout(Env e, Map<String, Object> body) {
        String entry = (String) exchange("/v1/queue/check-in", HttpMethod.POST, authedJsonBody(e.ownerToken(), Map.of(
                "patientId", patient(e), "doctorId", e.owner().id().toString())), Map.class).getBody().get("id");
        for (String s : new String[]{"waiting", "vitals_pending", "vitals_done", "in_consult", "checkout_pending"}) {
            exchange("/v1/queue/" + entry + "/status", HttpMethod.PATCH, authedJsonBody(e.ownerToken(), Map.of("status", s)), Map.class);
        }
        ResponseEntity<Map> r = exchange("/v1/billing/checkout/" + entry, HttpMethod.POST, authedJsonBody(e.ownerToken(), body), Map.class);
        assertThat(r.getStatusCode().is2xxSuccessful()).as("checkout: %s", r.getBody()).isTrue();
        return r;
    }

    /** A token for a non-owner role with these grants on billing and reports. */
    protected String staffToken(Env e, String roleName, ModuleGrant... grants) {
        UUID role = seedRole(e.tenant().id(), roleName, false, grants);
        return loginAndGetAccessToken(seedStaff(e.tenant(), role, roleName + UUID.randomUUID() + "@a.com", phone(), false));
    }

    protected List<String> invoicePdf(String token, Object invoiceId) {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(token);
        h.setAccept(List.of(MediaType.APPLICATION_PDF, MediaType.APPLICATION_PROBLEM_JSON));
        ResponseEntity<byte[]> r = exchange("/v1/billing/invoices/" + invoiceId + "/document?layout=a4&kind=invoice", HttpMethod.GET,
                new org.springframework.http.HttpEntity<>(h), byte[].class);
        assertThat(r.getStatusCode().is2xxSuccessful()).isTrue();
        return pdfLines(r.getBody());
    }

    protected static String code(ResponseEntity<Map> r) {
        return String.valueOf(r.getBody().get("code"));
    }
}
