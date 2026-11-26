package com.nabd.hms.billing.einvoice;

import com.nabd.hms.billing.gst.Fin011TestBase;
import com.nabd.hms.common.ModuleGrant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.scheduling.annotation.Scheduled;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * FIN-011 AC2 to AC5: the switchable IRP e-invoice adapter, against {@link FakeIrp} (nothing leaves the JVM). The background
 * worker is off in tests (ApiTestBase); each test runs the worker step itself, for its own clinic.
 */
@SuppressWarnings({"unchecked", "rawtypes"})
class Fin011EinvoiceApiTest extends Fin011TestBase {

    @Autowired EinvoiceService einvoices;
    @Autowired EinvoiceWorker worker;
    @Autowired com.nabd.hms.common.ClinicClock clinicClock;

    private Env clinic() {
        Env e = env();
        registration(e, CLINIC_KA);
        head(e, "LASER", "1000.00", 18, "taxable", "999799", false, false);
        return e;
    }

    private ResponseEntity<Map> switchIrp(String token, boolean on) {
        return exchange("/v1/billing/einvoice/settings", HttpMethod.PUT, authedJsonBody(token, Map.of("irpEnabled", on)), Map.class);
    }

    private Env clinicWithIrp() {
        Env e = clinic();
        assertThat(switchIrp(e.ownerToken(), true).getStatusCode()).isEqualTo(HttpStatus.OK);
        return e;
    }

    private Map b2b(Env e) {
        return checkout(e, Map.of("lineItems", List.of(line("LASER", 1)), "buyerGstin", BUYER_KA)).getBody();
    }

    private Map b2c(Env e) {
        return checkout(e, Map.of("lineItems", List.of(line("LASER", 1)))).getBody();
    }

    /** The clinic's e-invoice row of an invoice, or null. */
    private Map<String, Object> doc(Env e, Object invoiceId) {
        List<Map<String, Object>> rows = inTenantTx(e.tenant().id(), () -> jdbc.queryForList(
                "SELECT status, external_id, ack_ref, ack_at, qr_payload, attempts, next_attempt_at, errors::text AS errors, regime, document_type " +
                        "FROM einvoice_documents WHERE document_id = ?::uuid", invoiceId));
        return rows.isEmpty() ? null : rows.get(0);
    }

    private void makeDue(Env e, Object invoiceId) {
        inTenantTx(e.tenant().id(), () -> jdbc.update("UPDATE einvoice_documents SET next_attempt_at = now() - interval '1 second' WHERE document_id = ?::uuid", invoiceId));
    }

    private ResponseEntity<Map> retry(String token, Object invoiceId) {
        return exchange("/v1/billing/invoices/" + invoiceId + "/einvoice/retry", HttpMethod.POST, authedJsonBody(token, Map.of()), Map.class);
    }

    private Map<String, Object> listed(Env e, Object invoiceId) {
        Map body = exchange("/v1/billing/einvoices", HttpMethod.GET, authed(e.ownerToken()), Map.class).getBody();
        return ((List<Map<String, Object>>) body.get("items")).stream().filter(i -> invoiceId.equals(i.get("invoiceId"))).findFirst().orElse(null);
    }

    private int calls(Env e) {
        return FakeIrp.calls(e.tenant().id()).size();
    }

    // AC5
    @Test
    void theSwitchIsOffByDefault_soNoRowIsWrittenAndNoIrpCallIsEverMade() {
        Env e = clinic();
        assertThat(exchange("/v1/billing/einvoice/settings", HttpMethod.GET, authed(e.ownerToken()), Map.class).getBody()).containsEntry("irpEnabled", false);
        Map inv = b2b(e);
        assertThat(inv.get("buyerGstin")).isEqualTo(BUYER_KA);

        assertThat(doc(e, inv.get("id"))).isNull();
        assertThat(einvoices.processDue(e.tenant().id())).isZero();
        worker.runOnce();
        assertThat(calls(e)).isZero();
        Map list = exchange("/v1/billing/einvoices", HttpMethod.GET, authed(e.ownerToken()), Map.class).getBody();
        assertThat(list).containsEntry("irpEnabled", false);
        assertThat((List) list.get("items")).isEmpty();
        ResponseEntity<Map> r = retry(e.ownerToken(), inv.get("id"));
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(code(r)).isEqualTo("NOT_APPLICABLE");
        assertThat(calls(e)).isZero();
        assertThat(String.join("\n", invoicePdf(e.ownerToken(), inv.get("id")))).doesNotContain("IRN");
    }

    // AC2
    @Test
    void anEnabledClinicsB2bInvoiceGetsIrnAckNoAndSignedQrStoredAndPrinted() throws Exception {
        Env e = clinicWithIrp();
        Instant before = Instant.now();
        Map inv = b2b(e);
        Object id = inv.get("id");

        // issued at once; the e-invoice waits, due immediately
        Map<String, Object> pending = doc(e, id);
        assertThat(pending).containsEntry("status", "pending").containsEntry("regime", "IN_IRP").containsEntry("document_type", "invoice").containsEntry("attempts", 0);
        assertThat(((Timestamp) pending.get("next_attempt_at")).toInstant()).isBeforeOrEqualTo(Instant.now());
        assertThat(calls(e)).isZero(); // nothing is sent from the checkout request itself
        assertThat(listed(e, id)).containsEntry("status", "pending").containsEntry("invoiceNumber", inv.get("invoiceNumber"));
        assertThat(String.join("\n", invoicePdf(e.ownerToken(), id))).contains("IRN: pending");

        // the worker (the due-clinic lookup, then this clinic's step)
        worker.runOnce();
        Map<String, Object> done = doc(e, id);
        assertThat(done).containsEntry("status", "accepted").containsEntry("attempts", 1);
        String irn = (String) done.get("external_id");
        assertThat(irn).matches("[0-9a-f]{64}");
        assertThat((String) done.get("ack_ref")).matches("\\d{15}");
        assertThat(done.get("ack_at")).isNotNull();
        assertThat((String) done.get("qr_payload")).isEqualTo("signed-qr." + irn);
        assertThat(done.get("next_attempt_at")).isNull();
        assertThat(done.get("errors")).isNull();
        assertThat(Duration.between(before, Instant.now())).isLessThan(Duration.ofSeconds(60));

        // one call, made with no DB transaction open, carrying the stored figures and no patient detail
        assertThat(calls(e)).isEqualTo(1);
        assertThat(FakeIrp.calledInsideATransaction(e.tenant().id())).isFalse();
        String sent = FakeIrp.calls(e.tenant().id()).get(0);
        Map<String, Object> payload = objectMapper.readValue(sent, Map.class);
        assertThat((Map<String, Object>) payload.get("DocDtls")).containsEntry("Typ", "INV").containsEntry("No", inv.get("invoiceNumber"));
        assertThat((Map<String, Object>) payload.get("SellerDtls")).containsEntry("Gstin", CLINIC_KA);
        assertThat((Map<String, Object>) payload.get("BuyerDtls")).containsEntry("Gstin", BUYER_KA).containsEntry("Pos", "29");
        assertThat(sent).contains("\"AssVal\":1000.00", "\"CgstVal\":90.00", "\"SgstVal\":90.00", "\"IgstVal\":0.00", "\"TotInvVal\":1180.00", "\"GstRt\":18.00", "\"HsnCd\":\"999799\"");
        assertThat(sent).doesNotContain((String) inv.get("patientName"));

        // the event, the audit row, the list and the print
        Map<String, Object> ev = inTenantTx(e.tenant().id(), () -> jdbc.queryForMap(
                "SELECT envelope->'data' AS d FROM outbox_cloudevents WHERE envelope->>'type' = 'billing.einvoice.accepted.v1' AND envelope->'data'->>'documentId' = ?", id));
        assertThat(ev.get("d").toString()).contains(irn).contains("IN_IRP").contains(e.tenant().id().toString());
        assertThat(inTenantTx(e.tenant().id(), () -> jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE action = 'billing.einvoice_accepted' AND entity_id = ?::uuid",
                Integer.class, id))).isEqualTo(1);
        assertThat(listed(e, id)).containsEntry("status", "accepted").containsEntry("irn", irn).containsEntry("ackNo", done.get("ack_ref"));
        String print = String.join("\n", invoicePdf(e.ownerToken(), id));
        assertThat(print).contains("IRN: " + irn).contains("Ack No: " + done.get("ack_ref")).contains("Ack date: ").doesNotContain("IRN: pending");

        // nothing more to send: a second run makes no call and publishes nothing again
        worker.runOnce();
        assertThat(einvoices.processDue(e.tenant().id())).isZero();
        assertThat(calls(e)).isEqualTo(1);
        assertThat(retry(e.ownerToken(), id).getBody()).containsEntry("status", "accepted");
        assertThat(calls(e)).isEqualTo(1);

        // "within 60 s": the row is due at once (above) and the worker's default interval is well inside a minute
        Scheduled s = EinvoiceWorker.class.getDeclaredMethod("run").getAnnotation(Scheduled.class);
        assertThat(s.fixedDelayString()).isEqualTo("${app.billing.einvoice-worker-ms:15000}");
    }

    // AC4
    @Test
    void aB2cInvoiceNeverGoesToTheIrp() {
        Env e = clinicWithIrp();
        Map inv = b2c(e);
        assertThat(inv.get("buyerGstin")).isNull();
        assertThat(doc(e, inv.get("id"))).isNull();
        assertThat(einvoices.processDue(e.tenant().id())).isZero();
        worker.runOnce();
        assertThat(calls(e)).isZero();
        ResponseEntity<Map> r = retry(e.ownerToken(), inv.get("id"));
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(code(r)).isEqualTo("NOT_APPLICABLE");
        assertThat(doc(e, inv.get("id"))).isNull();
        assertThat(calls(e)).isZero();
        assertThat(listed(e, inv.get("id"))).isNull();
        assertThat(String.join("\n", invoicePdf(e.ownerToken(), inv.get("id")))).doesNotContain("IRN");
    }

    // AC3
    @Test
    void whenTheIrpIsDownTheInvoiceIsStillIssued_markedIrnPending_andRetriedWithBackoff() {
        Env e = clinicWithIrp();
        FakeIrp.mode(e.tenant().id(), FakeIrp.Mode.DOWN);
        Map inv = b2b(e); // checkout answered 2xx (asserted in the fixture): the desk is not blocked
        Object id = inv.get("id");
        assertThat(pay(e, (String) id, 1180).getStatusCode().is2xxSuccessful()).isTrue(); // and the bill takes its payment

        // first attempt fails: still pending, attempt counted, next try a minute away, reason kept
        Instant t1 = Instant.now();
        assertThat(einvoices.processDue(e.tenant().id())).isEqualTo(1);
        Map<String, Object> d1 = doc(e, id);
        assertThat(d1).containsEntry("status", "pending").containsEntry("attempts", 1);
        assertThat(d1.get("external_id")).isNull();
        assertThat(((Timestamp) d1.get("next_attempt_at")).toInstant()).isBetween(t1.plusSeconds(50), Instant.now().plusSeconds(70));
        assertThat((String) d1.get("errors")).contains("unavailable").doesNotContain("timed out"); // a fixed reason, not the provider's text
        assertThat(listed(e, id)).containsEntry("status", "pending").containsEntry("lastError", "IRP unavailable").containsEntry("attempts", 1);
        assertThat(String.join("\n", invoicePdf(e.ownerToken(), id))).contains("IRN: pending");

        // not due yet: no call, whether the IRP is up again or not
        assertThat(einvoices.processDue(e.tenant().id())).isZero();
        worker.runOnce();
        FakeIrp.mode(e.tenant().id(), FakeIrp.Mode.UP);
        assertThat(einvoices.processDue(e.tenant().id())).isZero();
        assertThat(calls(e)).isEqualTo(1);

        // second attempt, IRP down again: the wait doubles
        FakeIrp.mode(e.tenant().id(), FakeIrp.Mode.DOWN);
        makeDue(e, id);
        Instant t2 = Instant.now();
        assertThat(einvoices.processDue(e.tenant().id())).isEqualTo(1);
        Map<String, Object> d2 = doc(e, id);
        assertThat(d2).containsEntry("status", "pending").containsEntry("attempts", 2);
        assertThat(((Timestamp) d2.get("next_attempt_at")).toInstant()).isBetween(t2.plusSeconds(110), Instant.now().plusSeconds(130));

        // third attempt, IRP back: registered, and the event goes out exactly once
        FakeIrp.mode(e.tenant().id(), FakeIrp.Mode.UP);
        makeDue(e, id);
        assertThat(einvoices.processDue(e.tenant().id())).isEqualTo(1);
        Map<String, Object> d3 = doc(e, id);
        assertThat(d3).containsEntry("status", "accepted").containsEntry("attempts", 3);
        assertThat(d3.get("external_id")).isNotNull();
        assertThat(d3.get("errors")).isNull();
        assertThat(calls(e)).isEqualTo(3);
        assertThat(FakeIrp.calledInsideATransaction(e.tenant().id())).isFalse();
        assertThat(inTenantTx(e.tenant().id(), () -> jdbc.queryForObject(
                "SELECT count(*) FROM outbox_cloudevents WHERE envelope->>'type' = 'billing.einvoice.accepted.v1' AND envelope->'data'->>'documentId' = ?", Integer.class, id))).isEqualTo(1);
    }

    @Test
    void backoffGrowsFromOneMinuteToAnHourAndStaysThere() {
        assertThat(List.of(1, 2, 3, 4, 5, 6, 7, 8, 50).stream().map(n -> EinvoiceService.backoff(n).toMinutes()).toList())
                .containsExactly(1L, 2L, 4L, 8L, 16L, 32L, 60L, 60L, 60L);
    }

    @Test
    void aDocumentTheIrpRefusesIsNotRetriedUntilTheDeskAsks_andRetryIsGuarded() {
        Env e = clinicWithIrp();
        FakeIrp.mode(e.tenant().id(), FakeIrp.Mode.REJECT);
        Object id = b2b(e).get("id");
        assertThat(einvoices.processDue(e.tenant().id())).isEqualTo(1);
        Map<String, Object> rejected = doc(e, id);
        assertThat(rejected).containsEntry("status", "rejected").containsEntry("attempts", 1);
        assertThat(rejected.get("next_attempt_at")).isNull();
        assertThat((String) listed(e, id).get("lastError")).contains("2150");
        worker.runOnce();
        assertThat(einvoices.processDue(e.tenant().id())).isZero();
        assertThat(calls(e)).isEqualTo(1);

        // retry: needs billing:edit, the clinic's own invoice, and an invoice that has an e-invoice at all
        String viewer = staffToken(e, "Viewer", new ModuleGrant("billing", true, false, false, false, false, false, false));
        assertThat(retry(viewer, id).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        Env other = clinicWithIrp();
        assertThat(retry(other.ownerToken(), id).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(retry(e.ownerToken(), UUID.randomUUID()).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(doc(e, id)).containsEntry("status", "rejected");

        FakeIrp.mode(e.tenant().id(), FakeIrp.Mode.UP);
        String biller = staffToken(e, "Biller", new ModuleGrant("billing", true, true, true, false, false, false, false));
        ResponseEntity<Map> r = retry(biller, id);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(r.getBody()).containsEntry("status", "pending");
        assertThat(retry(biller, id).getStatusCode()).isEqualTo(HttpStatus.ACCEPTED); // asking twice changes nothing
        assertThat(calls(e)).isEqualTo(1); // the request itself never calls the IRP
        assertThat(einvoices.processDue(e.tenant().id())).isEqualTo(1);
        assertThat(doc(e, id)).containsEntry("status", "accepted").containsEntry("attempts", 2);
        assertThat(calls(e)).isEqualTo(2);
        assertThat(calls(other)).isZero();
        assertThat(inTenantTx(e.tenant().id(), () -> jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE action = 'billing.einvoice_retry_requested' AND entity_id = ?::uuid",
                Integer.class, id))).isEqualTo(2);
    }

    @Test
    void switchingOffStopsEverySend_andAVoidedBillIsCancelledUnsent() {
        Env e = clinicWithIrp();
        Object waiting = b2b(e).get("id");
        assertThat(switchIrp(e.ownerToken(), false).getBody()).containsEntry("irpEnabled", false);
        Object later = b2b(e).get("id"); // issued while off: no row at all
        assertThat(doc(e, later)).isNull();
        worker.runOnce();
        assertThat(einvoices.processDue(e.tenant().id())).isZero();
        assertThat(calls(e)).isZero();
        assertThat(doc(e, waiting)).containsEntry("status", "pending").containsEntry("attempts", 0);

        // on again: the bill issued while off can be sent on request; a bill voided before it was sent never is
        assertThat(switchIrp(e.ownerToken(), true).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(exchange("/v1/billing/invoices/" + waiting + "/void", HttpMethod.POST, authedJsonBody(e.ownerToken(), Map.of("reason", "billed twice")), Map.class)
                .getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(retry(e.ownerToken(), later).getBody()).containsEntry("status", "pending");
        assertThat(einvoices.processDue(e.tenant().id())).isEqualTo(1);
        assertThat(doc(e, waiting)).containsEntry("status", "cancelled");
        assertThat(doc(e, later)).containsEntry("status", "accepted");
        assertThat(calls(e)).isEqualTo(1);
        assertThat(retry(e.ownerToken(), waiting).getStatusCode()).isEqualTo(HttpStatus.CONFLICT); // void: no e-invoice
        assertThat(String.join("\n", invoicePdf(e.ownerToken(), waiting))).doesNotContain("IRN");
    }

    @Test
    void onlyTheOwnerSwitchesEinvoicing_perClinic_andOnlyInAGstMarket() {
        Env e = clinic(), other = clinic();
        // billing:approve without the Owner role
        String manager = staffToken(e, "Manager", new ModuleGrant("billing", true, true, true, false, true, false, false));
        ResponseEntity<Map> denied = switchIrp(manager, true);
        assertThat(denied.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(code(denied)).isEqualTo("OWNER_REQUIRED");
        String biller = staffToken(e, "Biller", new ModuleGrant("billing", true, true, true, false, false, false, false));
        assertThat(switchIrp(biller, true).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(exchange("/v1/billing/einvoice/settings", HttpMethod.PUT, authedJsonBody(e.ownerToken(), Map.of()), Map.class).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(exchange("/v1/billing/einvoice/settings", HttpMethod.GET, authed(e.ownerToken()), Map.class).getBody()).containsEntry("irpEnabled", false);

        assertThat(switchIrp(e.ownerToken(), true).getBody()).containsEntry("irpEnabled", true).containsEntry("adapterAvailable", true);
        assertThat(inTenantTx(e.tenant().id(), () -> jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE action = 'billing.einvoice_irp_switched'", Integer.class))).isEqualTo(1);
        // the other clinic is untouched and sees nothing of this one
        assertThat(exchange("/v1/billing/einvoice/settings", HttpMethod.GET, authed(other.ownerToken()), Map.class).getBody()).containsEntry("irpEnabled", false);
        Object mine = b2b(e).get("id");
        assertThat(doc(e, mine)).isNotNull();
        assertThat(inTenantTx(other.tenant().id(), () -> jdbc.queryForObject("SELECT count(*) FROM einvoice_documents", Integer.class))).isZero();
        assertThat((List) exchange("/v1/billing/einvoices", HttpMethod.GET, authed(other.ownerToken()), Map.class).getBody().get("items")).isEmpty();
        Object theirs = b2b(other).get("id");
        assertThat(doc(other, theirs)).isNull();

        // a clinic outside GST cannot switch IRP on
        Env gulf = env();
        su().execute("SET session_replication_role = replica; UPDATE tenants SET region_code = 'SA' WHERE id = '" + gulf.tenant().id() + "'");
        clinicClock.evict(gulf.tenant().id());
        ResponseEntity<Map> no = switchIrp(gulf.ownerToken(), true);
        assertThat(no.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(code(no)).isEqualTo("NOT_APPLICABLE");
    }
}
