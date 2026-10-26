package com.nabd.hms.platform.ticket;

import com.nabd.hms.support.ApiTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** PLT-057: support tickets never hold PHI. Unit rules per market, then the stored row and the warning before submitting. */
class Plt057TicketRedactionApiTest extends ApiTestBase {

    private static String r(String s) {
        return TicketPhiRedactor.redact(s).text();
    }

    // ---------- unit: patterns per market ----------
    @Test
    void indiaPatternsAreRedacted() {
        assertThat(r("call +91 98765 43210 now")).isEqualTo("call [redacted] now");
        assertThat(r("mobile 9876543210.")).isEqualTo("mobile [redacted].");
        assertThat(r("phone 098765-43210")).isEqualTo("phone [redacted]");
        assertThat(r("aadhaar 1234 5678 9012 on file")).isEqualTo("aadhaar [redacted] on file");
        assertThat(r("ABHA 12-3456-7890-1234")).isEqualTo("ABHA [redacted]");
        assertThat(r("PAN ABCDE1234F given")).isEqualTo("PAN [redacted] given");
    }

    @Test
    void saudiPatternsAreRedacted() {
        assertThat(r("call +966 50 123 4567")).isEqualTo("call [redacted]");
        assertThat(r("mobile 0501234567")).isEqualTo("mobile [redacted]");
        assertThat(r("iqama 2345678901 expired")).isEqualTo("iqama [redacted] expired");
        assertThat(r("national id 1098765432")).isEqualTo("national id [redacted]");
    }

    @Test
    void emailsAndMrnsAreRedacted_andOrdinaryTextIsKept() {
        assertThat(r("mail asha.rao+x@gmail.com please")).isEqualTo("mail [redacted] please");
        assertThat(r("see MRN-000123 and mrn 77")).isEqualTo("see [redacted] and [redacted]");
        String plain = "Queue board shows 12 patients and 3 doctors since 10am; error 500 on /v1/queue";
        assertThat(TicketPhiRedactor.redact(plain)).isEqualTo(new TicketPhiRedactor.Result(plain, 0));
        assertThat(TicketPhiRedactor.redact(null).text()).isNull();
    }

    @Test
    void patientNamesFromTheListAreRedacted_longestFirst_caseInsensitive_wholeWordsOnly() {
        List<String> names = List.of("Asha", "Asha Rao", "Li");
        TicketPhiRedactor.Result res = TicketPhiRedactor.redact("Asha  rao could not check in; ASHA RAO again. Ashamed? Li is short", names);
        assertThat(res.text()).isEqualTo("[redacted] could not check in; [redacted] again. Ashamed? Li is short");
        assertThat(res.count()).isEqualTo(2);
    }

    // ---------- integration: stored text, warning, cross-tenant ----------
    @Test
    void theStoredTicketTextIsRedacted_thePreviewWarnsBefore_andOperatorsOnlyEverSeeTheRedactedText() {
        SeededTenant tenant = seedTenant();
        SeededTenant other = seedTenant();
        UUID role = seedRole(tenant.id(), "Reception", false, fullGrant("patients"));
        String token = loginAndGetAccessToken(seedStaff(tenant, role, "p57-rec@ticket.health", "+919400300001", false));
        inTenantTx(tenant.id(), () -> jdbc.update("INSERT INTO patients (id, tenant_id, name, phone, dob, gender) VALUES (?,?,?,?,?,?)",
                UUID.randomUUID(), tenant.id(), "Zainab Fakhoury", "+919000000777", java.sql.Date.valueOf("1980-01-01"), "female"));
        inTenantTx(other.id(), () -> jdbc.update("INSERT INTO patients (id, tenant_id, name, phone, dob, gender) VALUES (?,?,?,?,?,?)",
                UUID.randomUUID(), other.id(), "Quentin Ostrander", "+919000000778", java.sql.Date.valueOf("1980-01-01"), "male"));
        Map<String, Object> body = Map.of("subject", "Zainab Fakhoury cannot check in",
                "description", "Patient Zainab Fakhoury phone +91 90000 00777 mail zainab@example.com; Quentin Ostrander is another clinic's patient. Queue stuck.");

        // the warning: same redaction, nothing stored
        ResponseEntity<Map> preview = http.exchange(url("/v1/support/tickets/redaction-preview"), HttpMethod.POST, authedJsonBody(token, body), Map.class);
        assertThat(preview.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(((Number) preview.getBody().get("redactionCount")).intValue()).isEqualTo(4);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM master.support_tickets WHERE tenant_id = ?", Integer.class, tenant.id())).isZero();

        ResponseEntity<Map> resp = http.exchange(url("/v1/support/tickets"), HttpMethod.POST, authedJsonBody(token, body), Map.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(resp.getBody().get("bodyRedacted")).isEqualTo(true);
        assertThat(resp.getBody().get("redactionCount")).isEqualTo(4);
        assertThat(resp.getBody().get("subject")).isEqualTo("[redacted] cannot check in");

        Map<String, Object> row = jdbc.queryForMap("SELECT subject, description, body_redacted, redaction_count FROM master.support_tickets WHERE tenant_id = ?", tenant.id());
        String stored = row.get("subject") + " | " + row.get("description");
        assertThat(stored).contains("[redacted]").doesNotContain("Zainab").doesNotContain("Fakhoury").doesNotContain("90000").doesNotContain("example.com");
        assertThat(stored).contains("Queue stuck").contains("Quentin Ostrander"); // another clinic's patient list is not this clinic's to match
        assertThat(row.get("body_redacted")).isEqualTo(true);
        assertThat(row.get("redaction_count")).isEqualTo(4);

        // what a support engineer reads across tenants is the redacted text
        String op = platformLoginAndGetAccessToken(seedOperator("p57-tk@nabd.health", "support_engineer", false));
        ResponseEntity<Map[]> list = exchange("/v1/platform/support/tickets", HttpMethod.GET, authed(op), Map[].class);
        String all = Arrays.toString(list.getBody());
        assertThat(all).doesNotContain("Zainab").doesNotContain("Fakhoury").doesNotContain("90000 00777").doesNotContain("zainab@example.com");
    }

    @Test
    void aCleanTicketIsStoredUnchangedAndNotFlagged() {
        SeededTenant tenant = seedTenant();
        UUID role = seedRole(tenant.id(), "Reception", false, fullGrant("patients"));
        String token = loginAndGetAccessToken(seedStaff(tenant, role, "p57-clean@ticket.health", "+919400300002", false));
        ResponseEntity<Map> resp = http.exchange(url("/v1/support/tickets"), HttpMethod.POST,
                authedJsonBody(token, Map.of("subject", "Printer offline", "description", "Receipt printer at the front desk is offline")), Map.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(resp.getBody().get("bodyRedacted")).isEqualTo(false);
        assertThat(resp.getBody().get("redactionCount")).isEqualTo(0);
        assertThat(resp.getBody().get("description")).isEqualTo("Receipt printer at the front desk is offline");
    }
}
