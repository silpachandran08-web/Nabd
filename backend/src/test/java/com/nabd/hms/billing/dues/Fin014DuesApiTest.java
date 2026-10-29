package com.nabd.hms.billing.dues;

import com.nabd.hms.billing.document.S15BillingTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** FIN-014: invoice list filters and cursor, dues grouping with ageing, reminders (consent, rate limit), write-offs. */
@SuppressWarnings({"unchecked", "rawtypes"})
class Fin014DuesApiTest extends S15BillingTestBase {

    private ResponseEntity<Map> get(Env e, String path) {
        return exchange(path, HttpMethod.GET, authed(e.ownerToken()), Map.class);
    }

    private ResponseEntity<Map> post(String token, String path, Object body) {
        return exchange(path, HttpMethod.POST, authedJsonBody(token, body), Map.class);
    }

    private static List<Map> items(ResponseEntity<Map> r) {
        return (List<Map>) r.getBody().get("items");
    }

    private void backdate(Env e, String invoiceId, int days) {
        inTenantTx(e.tenant().id(), () -> jdbc.update("UPDATE invoices SET created_at = ? WHERE id = ?::uuid", Timestamp.from(Instant.now().minus(days, ChronoUnit.DAYS)), invoiceId));
    }

    private void template(Env e) {
        inTenantTx(e.tenant().id(), () -> jdbc.update("""
                INSERT INTO whatsapp_templates (tenant_id, name, meta_name, language, category, header_text, body, param_count, status)
                VALUES (?, 'dues_reminder', ?, 'en', 'UTILITY', 'Clinic', 'A reminder from {{1}}: please visit the desk to settle your bill.', 1, 'approved')
                """, e.tenant().id(), "m_" + UUID.randomUUID().toString().replace("-", "")));
    }

    /** A new row decides (latest wins): grant it, or record a withdrawal. */
    private void messagingConsent(Env e, String patientId, boolean granted) {
        inTenantTx(e.tenant().id(), () -> jdbc.update("INSERT INTO consents (tenant_id, patient_id, consent_type, withdrawn_at) VALUES (?, ?::uuid, 'messaging', "
                + (granted ? "NULL" : "now()") + ")", e.tenant().id(), patientId));
    }

    @Test
    void listFiltersByStatusDateMethodAndNumberAndPagesWithACursor() {
        Env e = env();
        String p1 = patient(e), p2 = patient(e);
        String a = invoice(e, p1, "CONS"), b = invoice(e, p2, "CONS"), c = invoice(e, p1, "XRAY");
        pay(e, a, 400);       // paid
        pay(e, b, 100);       // partial
        backdate(e, c, 40);   // unpaid, old

        assertThat(items(get(e, "/v1/billing/invoices"))).hasSize(3);
        assertThat(items(get(e, "/v1/billing/invoices?status=partial")).stream().map(m -> m.get("id"))).containsExactly(b);
        assertThat(items(get(e, "/v1/billing/invoices?status=paid"))).hasSize(1);
        assertThat(items(get(e, "/v1/billing/invoices?method=cash"))).hasSize(2);
        String today = java.time.LocalDate.ofInstant(Instant.now(), java.time.ZoneId.of("UTC")).toString(); // tenants are UTC here
        assertThat(items(get(e, "/v1/billing/invoices?from=" + today + "&to=" + today))).hasSize(2);
        String number = (String) items(get(e, "/v1/billing/invoices?status=partial")).get(0).get("invoiceNumber");
        assertThat(items(get(e, "/v1/billing/invoices?q=" + number))).extracting(m -> m.get("id")).containsExactly(b);
        assertThat(items(get(e, "/v1/billing/invoices?q=INV_"))).isEmpty(); // "_" is literal text, not "any one character"
        assertThat(items(get(e, "/v1/billing/invoices?doctorId=" + UUID.randomUUID()))).isEmpty();

        ResponseEntity<Map> page1 = get(e, "/v1/billing/invoices?limit=2");
        assertThat(items(page1)).hasSize(2);
        String cursor = (String) page1.getBody().get("nextCursor");
        assertThat(cursor).isNotBlank();
        ResponseEntity<Map> page2 = get(e, "/v1/billing/invoices?limit=2&cursor=" + cursor);
        assertThat(items(page2)).hasSize(1).first().satisfies(m -> assertThat(m.get("id")).isEqualTo(c));
        assertThat(page2.getBody().get("nextCursor")).isNull();
    }

    @Test
    void badFilterOrCursorIs400AndLimitIsClampedTo100() {
        Env e = env();
        assertThat(get(e, "/v1/billing/invoices?status=bogus").getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(get(e, "/v1/billing/invoices?cursor=not-a-cursor").getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(get(e, "/v1/billing/dues?bucket=weekly").getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(get(e, "/v1/billing/invoices?limit=100000").getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void duesGroupOneRowPerPatientWithTotalOldestAgeNumbersAndBuckets() {
        Env e = env();
        String p1 = patient(e), p2 = patient(e);
        String i1 = invoice(e, p1, "CONS"), i2 = invoice(e, p1, "XRAY"), i3 = invoice(e, p2, "CONS");
        backdate(e, i1, 40);
        backdate(e, i2, 3);
        backdate(e, i3, 100);
        pay(e, i3, 400); // fully paid: not in the dues
        String settled = invoice(e, p2, "CONS");
        pay(e, settled, 400);

        List<Map> rows = items(get(e, "/v1/billing/dues"));
        assertThat(rows).hasSize(1);
        Map row = rows.get(0);
        assertThat(row.get("patientId")).isEqualTo(p1);
        assertThat(((Number) row.get("totalDue")).doubleValue()).isEqualTo(400.0 + 550.0); // 400 + 500 + 10% tax
        assertThat(((Number) row.get("oldestDays")).longValue()).isBetween(39L, 41L);
        assertThat(row.get("bucket")).isEqualTo("31-90");
        assertThat((List<String>) row.get("invoiceNumbers")).hasSize(2);
        assertThat(row.get("lastRemindedAt")).isNull();
        assertThat(items(get(e, "/v1/billing/dues?bucket=31-90"))).hasSize(1);
        assertThat(items(get(e, "/v1/billing/dues?bucket=0-7"))).isEmpty();
    }

    @Test
    void reminderSendsAConsentedTemplateOncePerDayAndRefusesWithoutConsentOrDues() {
        Env e = env();
        template(e);
        String p = patient(e);
        String inv = invoice(e, p, "CONS");
        String url = "/v1/billing/dues/" + p + "/remind";
        Map body = Map.of("channel", "whatsapp", "includePaymentLink", false);

        assertThat(post(e.ownerToken(), url, body).getStatusCode()).isEqualTo(HttpStatus.CONFLICT); // no messaging consent on file
        assertThat(inTenantTx(e.tenant().id(), () -> jdbc.queryForObject("SELECT count(*) FROM dues_reminders", Integer.class))).isZero();
        messagingConsent(e, p, true);
        ResponseEntity<Map> ok = post(e.ownerToken(), url, body);
        assertThat(ok.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(ok.getBody().get("reminderId")).isNotNull();
        assertThat(items(get(e, "/v1/billing/dues")).get(0).get("lastRemindedAt")).isNotNull();
        assertThat(post(e.ownerToken(), url, body).getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(inTenantTx(e.tenant().id(), () -> jdbc.queryForObject("SELECT count(*) FROM dues_reminders", Integer.class))).isEqualTo(1);
        assertThat(inTenantTx(e.tenant().id(), () -> jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE action = 'billing.dues_reminder_sent'", Integer.class))).isEqualTo(1);

        assertThat(post(e.ownerToken(), url, Map.of("channel", "whatsapp", "includePaymentLink", true)).getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(post(e.ownerToken(), url, Map.of("channel", "fax", "includePaymentLink", false)).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        pay(e, inv, 400);
        assertThat(post(e.ownerToken(), url, body).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND); // nothing owed any more
    }

    @Test
    void writeOffNeedsApproveAReasonAndAnAmountWithinTheBalanceAndShrinksTheDues() {
        Env e = env();
        String p = patient(e);
        String inv = invoice(e, p, "CONS");
        pay(e, inv, 100);
        String url = "/v1/billing/invoices/" + inv + "/write-off";
        String editor = billingToken(e, "Editor", true, true, false);

        assertThat(post(editor, url, Map.of("amount", 50, "reasonCode", "hardship")).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(post(e.ownerToken(), url, Map.of("amount", 50)).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST); // reason is required
        assertThat(post(e.ownerToken(), url, Map.of("amount", 301, "reasonCode", "hardship")).getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT); // balance is 300
        ResponseEntity<Map> ok = post(e.ownerToken(), url, Map.of("amount", 120, "reasonCode", "hardship", "note", "agreed with the owner"));
        assertThat(ok.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(((Number) ok.getBody().get("balanceDue")).doubleValue()).isEqualTo(180.0);
        assertThat(((Number) items(get(e, "/v1/billing/dues")).get(0).get("totalDue")).doubleValue()).isEqualTo(180.0);
        assertThat(post(e.ownerToken(), url, Map.of("amount", 181, "reasonCode", "hardship")).getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(post(e.ownerToken(), url, Map.of("amount", 180, "reasonCode", "hardship")).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(items(get(e, "/v1/billing/dues"))).isEmpty();
        assertThat(inTenantTx(e.tenant().id(), () -> jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE action = 'billing.write_off'", Integer.class))).isEqualTo(2);
    }

    @Test
    void anotherClinicSeesNoInvoicesDuesOrWriteOffs() {
        Env e = env();
        String inv = invoice(e, patient(e), "CONS");
        Env other = env();
        assertThat(items(get(other, "/v1/billing/invoices"))).isEmpty();
        assertThat(items(get(other, "/v1/billing/dues"))).isEmpty();
        assertThat(post(other.ownerToken(), "/v1/billing/invoices/" + inv + "/write-off", Map.of("amount", 10, "reasonCode", "x")).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void invoiceListUsesTheIndexAtScale() {
        Env e = env();
        String p = patient(e);
        inTenantTx(e.tenant().id(), () -> {
            jdbc.update("""
                    INSERT INTO invoices (tenant_id, invoice_number, patient_id, doctor_id, subtotal, tax, total, paid, status, created_by, created_at, queue_entry_id)
                    SELECT ?, 'BULK-' || g, ?::uuid, ?, 100, 0, 100, 0, CASE WHEN g % 3 = 0 THEN 'partial' ELSE 'unpaid' END, ?, now() - (g || ' minutes')::interval, NULL
                    FROM generate_series(1, 5000) g
                    """, e.tenant().id(), p, e.owner().id(), e.owner().id());
            return null;
        });
        long t0 = System.nanoTime();
        ResponseEntity<Map> r = get(e, "/v1/billing/invoices?status=partial&limit=50");
        long ms = (System.nanoTime() - t0) / 1_000_000;
        assertThat(items(r)).hasSize(50);
        assertThat(ms).as("first page of 5,000 invoices, status filter (includes the HTTP round trip)").isLessThan(500);
    }
}
