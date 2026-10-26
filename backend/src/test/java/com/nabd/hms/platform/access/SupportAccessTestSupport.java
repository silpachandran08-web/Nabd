package com.nabd.hms.platform.access;

import com.nabd.hms.common.ModuleGrant;
import com.nabd.hms.support.ApiTestBase;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;

import java.util.Map;
import java.util.UUID;

/** PLT-057 fixtures: a clinic owner (built-in role with compliance grants), the operator request and the owner's decision. */
abstract class SupportAccessTestSupport extends ApiTestBase {

    record Owner(SeededStaff staff, String token) {
    }

    Owner seedOwner(SeededTenant tenant) {
        String tag = UUID.randomUUID().toString().substring(0, 8);
        UUID role = seedRole(tenant.id(), "Owner " + tag, true, fullGrant("compliance"), fullGrant("staff")); // role names are unique per clinic
        String phone = String.format("+919%09d", java.util.concurrent.ThreadLocalRandom.current().nextInt(1_000_000_000));
        SeededStaff staff = seedStaff(tenant, role, "owner-" + tag + "@clinic.test", phone, false);
        return new Owner(staff, loginAndGetAccessToken(staff));
    }

    String ownerPhone(Owner o) {
        return inTenantTx(o.staff().tenantId(), () -> jdbc.queryForObject("SELECT mobile_phone FROM staff WHERE id = ?", String.class, o.staff().id()));
    }

    UUID seedTicket(UUID tenantId) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO master.support_tickets (id, tenant_id, source, raised_by_name, raised_by_role, subject, "
                + "description, sla_due_at) VALUES (?, ?, 'system', 'Nabd System', 'System', 'Grant test', 'd', now() + interval '1 day')",
                id, tenantId);
        return id;
    }

    Map<String, Object> grantBody(SeededTenant tenant, String scope, int minutes, String reason) {
        return Map.of("tenantId", tenant.id().toString(), "ticketId", seedTicket(tenant.id()).toString(),
                "scope", scope, "minutes", minutes, "reason", reason);
    }

    @SuppressWarnings("unchecked")
    ResponseEntity<Map> request(String opToken, Map<String, Object> body) {
        return http.exchange(url("/v1/platform/support-access/grants"), HttpMethod.POST, authedJsonBody(opToken, body), Map.class);
    }

    @SuppressWarnings("unchecked")
    ResponseEntity<Map> decide(String ownerToken, Object grantId, String decision, String note) {
        Map<String, Object> b = note == null ? Map.of("decision", decision) : Map.of("decision", decision, "note", note);
        return http.exchange(url("/v1/support-access/requests/" + grantId + "/decision"), HttpMethod.POST, authedJsonBody(ownerToken, b), Map.class);
    }

    /** The operator asks for a redacted_patient_view of the tenant for 30 minutes; the tenant's owner approves. Returns the grant id. */
    String requestAndApprove(String opToken, SeededTenant tenant, Owner owner) {
        ResponseEntity<Map> r = request(opToken, grantBody(tenant, "redacted_patient_view", 30, "Support ticket follow-up"));
        String id = (String) r.getBody().get("id");
        ResponseEntity<Map> d = decide(owner.token(), id, "approve", null);
        if (d.getStatusCode().value() != 200) {
            throw new IllegalStateException("approve failed: " + d.getStatusCode() + " " + d.getBody());
        }
        return id;
    }
}
