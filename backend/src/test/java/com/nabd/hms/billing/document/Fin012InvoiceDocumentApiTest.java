package com.nabd.hms.billing.document;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** FIN-012: the invoice / receipt PDF over HTTP: stored once per version, reprint keeps the original values, permissions, tenant isolation. */
@SuppressWarnings({"unchecked", "rawtypes"})
class Fin012InvoiceDocumentApiTest extends S15BillingTestBase {

    private ResponseEntity<byte[]> doc(String token, String invoiceId, String query) {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(token);
        h.setAccept(List.of(MediaType.APPLICATION_PDF, MediaType.APPLICATION_PROBLEM_JSON));
        return exchange("/v1/billing/invoices/" + invoiceId + "/document" + query, HttpMethod.GET, new org.springframework.http.HttpEntity<>(h), byte[].class);
    }

    private int stored(Env e, String invoiceId) {
        return inTenantTx(e.tenant().id(), () -> jdbc.queryForObject("SELECT count(*) FROM billing_documents WHERE invoice_id = ?::uuid", Integer.class, invoiceId));
    }

    @Test
    void paidInvoiceRendersA4AndThermalWithTheLegalFieldsInUnderTwoSeconds() {
        Env e = env();
        jdbc.update("UPDATE tenants SET tax_id = '29ABCDE1234F1Z5', tax_id_type = 'GSTIN' WHERE id = ?", e.tenant().id());
        String inv = invoice(e, patient(e), "XRAY");
        pay(e, inv, 1000);
        long t0 = System.nanoTime();
        ResponseEntity<byte[]> a4 = doc(e.ownerToken(), inv, "?layout=a4&kind=invoice");
        assertThat((System.nanoTime() - t0) / 1_000_000).isLessThan(2000);
        assertThat(a4.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(a4.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PDF);
        assertThat(new String(a4.getBody(), java.nio.charset.StandardCharsets.ISO_8859_1)).startsWith("%PDF-1.4");
        String text = String.join("\n", pdfLines(a4.getBody()));
        assertThat(text).contains("Test Clinic", "GSTIN: 29ABCDE1234F1Z5", "Invoice No: ", "Patient: ", "Cat XRAY", "TOTAL INR");

        ResponseEntity<byte[]> roll = doc(e.ownerToken(), inv, "?layout=thermal80&kind=invoice");
        assertThat(roll.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(pdfLines(roll.getBody())).isNotEmpty().allSatisfy(l -> assertThat(l.length()).isLessThanOrEqualTo(48));
    }

    @Test
    void reprintAfterAPriceChangeReturnsTheStoredOriginalAndRendersOnlyOnce() {
        Env e = env();
        String inv = invoice(e, patient(e), "CONS");
        byte[] first = doc(e.ownerToken(), inv, "").getBody();
        inTenantTx(e.tenant().id(), () -> jdbc.update("UPDATE charge_catalogue SET base_amount = 999 WHERE code = 'CONS'"));
        byte[] again = doc(e.ownerToken(), inv, "?layout=a4&kind=invoice").getBody();
        assertThat(again).isEqualTo(first);
        assertThat(stored(e, inv)).isEqualTo(1);
        assertThat(String.join("\n", pdfLines(again))).contains("400.00").doesNotContain("999.00");
        assertThat(inTenantTx(e.tenant().id(), () -> jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE action = 'billing.document_rendered'", Integer.class))).isEqualTo(1);
    }

    @Test
    void aPaymentMovesTheInvoiceVersionSoTheNewIssueShowsItAndTheReceiptCarriesItsNumber() {
        Env e = env();
        String inv = invoice(e, patient(e), "CONS");
        assertThat(doc(e.ownerToken(), inv, "?kind=receipt").getStatusCode()).isEqualTo(HttpStatus.CONFLICT); // nothing paid yet
        String before = String.join("\n", pdfLines(doc(e.ownerToken(), inv, "").getBody()));
        assertThat(before).contains("none yet");
        pay(e, inv, 100);
        String after = String.join("\n", pdfLines(doc(e.ownerToken(), inv, "").getBody()));
        assertThat(after).doesNotContain("none yet").contains("Balance due");
        ResponseEntity<byte[]> receipt = doc(e.ownerToken(), inv, "?kind=receipt&layout=thermal80");
        assertThat(receipt.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(String.join("\n", pdfLines(receipt.getBody()))).contains("PAYMENT RECEIPT", "Receipt No: ");
        assertThat(stored(e, inv)).isEqualTo(3);
    }

    @Test
    void badLayoutOrKindIs400UnknownInvoiceIs404AndAnotherClinicsInvoiceIs404() {
        Env e = env();
        String inv = invoice(e, patient(e), "CONS");
        assertThat(doc(e.ownerToken(), inv, "?layout=a3").getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(doc(e.ownerToken(), inv, "?kind=estimate").getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(doc(e.ownerToken(), UUID.randomUUID().toString(), "").getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        Env other = env();
        assertThat(doc(other.ownerToken(), inv, "").getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        doc(e.ownerToken(), inv, "");
        assertThat(inTenantTx(other.tenant().id(), () -> jdbc.queryForObject("SELECT count(*) FROM billing_documents", Integer.class))).isZero(); // RLS: invisible to the other clinic
    }

    @Test
    void aRoleWithoutBillingViewGets403() {
        Env e = env();
        String inv = invoice(e, patient(e), "CONS");
        String noBilling = billingToken(e, "NoBilling", false, false, false);
        assertThat(doc(noBilling, inv, "").getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(doc(billingToken(e, "ViewOnly", true, false, false), inv, "").getStatusCode()).isEqualTo(HttpStatus.OK);
    }
}
