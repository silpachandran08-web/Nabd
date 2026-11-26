package com.nabd.hms.lab;

import com.nabd.hms.common.ModuleGrant;
import com.nabd.hms.platform.entitlements.PlanEntitlements;
import com.nabd.hms.support.ApiTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PHL-043: lab orders from the consult: order with charges on the visit's checkout, cancel, favourites, STAT first, the order sheet
 * with its barcode, the fasting notice, and the external-sheet-only path of a clinic without the lab module.
 */
@SuppressWarnings({"unchecked", "rawtypes"})
class Phl043LabOrderApiTest extends ApiTestBase {

    private static final AtomicInteger SEQ = new AtomicInteger((int) (System.nanoTime() % 400_000) + 100_000);
    private static final String[] MODULES = {"staff", "patients", "queue", "setup", "clinical", "billing", "reports", "pharmacy", "departments", "lab"};

    @Autowired
    private PlanEntitlements planEntitlements;

    private record Clinic(SeededTenant t, SeededStaff owner, String token) {
    }

    private record Visit(String patientId, String patientName, String entry) {
    }

    private String phone() {
        return "+9194" + (10000000 + SEQ.incrementAndGet());
    }

    private Clinic clinic() {
        SeededTenant t = seedTenant();
        ModuleGrant[] grants = java.util.Arrays.stream(MODULES).map(ApiTestBase::fullGrant).toArray(ModuleGrant[]::new);
        SeededStaff o = seedStaff(t, seedRole(t.id(), "Owner", true, grants), "o" + SEQ.incrementAndGet() + "@p43.test", phone(), false);
        return new Clinic(t, o, loginAndGetAccessToken(o));
    }

    private String tokenWith(Clinic c, String roleName, ModuleGrant... grants) {
        UUID role = seedRole(c.t().id(), roleName + " " + SEQ.incrementAndGet(), false, grants);
        return loginAndGetAccessToken(seedStaff(c.t(), role, "s" + SEQ.incrementAndGet() + "@p43.test", phone(), false));
    }

    /** The clinic's plan now lists only these modules (a clinic with no subscription is unrestricted, PLT-059). */
    private void subscribe(UUID tenant, List<String> modules) {
        String op = platformLoginAndGetAccessToken(seedOperator("sa-" + UUID.randomUUID() + "@nabd.internal", "super_admin", false));
        ResponseEntity<Map> p = exchange("/v1/platform/plans", HttpMethod.POST, authedJsonBody(op, Map.of(
                "code", "p43-" + UUID.randomUUID(), "name", "P", "monthlyPriceCents", 100000, "currency", "INR",
                "active", true, "modules", modules, "limits", Map.of("seats", 20, "branches", 2))), Map.class);
        assertThat(p.getStatusCode()).as("%s", p.getBody()).isEqualTo(HttpStatus.CREATED);
        ResponseEntity<Map> s = exchange("/v1/platform/billing/subscriptions/" + tenant, HttpMethod.POST, authedJsonBody(op,
                Map.of("planId", p.getBody().get("id"), "mrrCents", 100000, "renewalDate", LocalDate.now().plusDays(30).toString())), Map.class);
        assertThat(s.getStatusCode()).as("%s", s.getBody()).isEqualTo(HttpStatus.OK);
        planEntitlements.evict(tenant);
    }

    private String test(Clinic c, String code, String name, double price) {
        ResponseEntity<Map> r = exchange("/v1/lab/tests", HttpMethod.POST, authedJsonBody(c.token(), Map.of("code", code, "name", name, "sampleType", "serum",
                "container", "sst_gold", "volumeMl", 3, "unit", "mg/dL", "tatMinutes", 240, "price", price)), Map.class);
        assertThat(r.getStatusCode()).as("%s", r.getBody()).isEqualTo(HttpStatus.CREATED);
        return (String) r.getBody().get("id");
    }

    private String panel(Clinic c, String code, String name, double price, String... testIds) {
        ResponseEntity<Map> r = exchange("/v1/lab/panels", HttpMethod.POST, authedJsonBody(c.token(), Map.of("code", code, "name", name,
                "testIds", List.of(testIds), "price", price)), Map.class);
        assertThat(r.getStatusCode()).as("%s", r.getBody()).isEqualTo(HttpStatus.CREATED);
        return (String) r.getBody().get("id");
    }

    private Visit visit(Clinic c) {
        String name = "Lab Person" + SEQ.incrementAndGet();
        ResponseEntity<Map> p = exchange("/v1/patients", HttpMethod.POST, authedJsonBody(c.token(), Map.of("name", name, "phone", phone(),
                "dob", LocalDate.of(1980, 1, 1).plusDays(SEQ.get() % 9000).toString(), "gender", "female")), Map.class);
        assertThat(p.getStatusCode().is2xxSuccessful()).as("%s", p.getBody()).isTrue();
        String patient = (String) p.getBody().get("id");
        ResponseEntity<Map> q = exchange("/v1/queue/check-in", HttpMethod.POST, authedJsonBody(c.token(), Map.of("patientId", patient,
                "doctorId", c.owner().id().toString())), Map.class);
        assertThat(q.getStatusCode().is2xxSuccessful()).as("%s", q.getBody()).isTrue();
        return new Visit(patient, name, (String) q.getBody().get("id"));
    }

    private void moveTo(Clinic c, String entry, String... statuses) {
        for (String s : statuses) {
            exchange("/v1/queue/" + entry + "/status", HttpMethod.PATCH, authedJsonBody(c.token(), Map.of("status", s)), Map.class);
        }
    }

    private static Map<String, Object> body(Visit v, String priority, String kind, Map<String, Object>... items) {
        Map<String, Object> b = new HashMap<>(Map.of("patientId", v.patientId(), "queueEntryId", v.entry(), "items", List.of(items),
                "priority", priority, "destination", Map.of("kind", kind)));
        return b;
    }

    private static Map<String, Object> t(String id) {
        return Map.of("testId", id);
    }

    private static Map<String, Object> p(String id) {
        return Map.of("panelId", id);
    }

    private ResponseEntity<Map> order(String token, Map<String, Object> body) {
        return exchange("/v1/lab/orders", HttpMethod.POST, authedJsonBody(token, body), Map.class);
    }

    private String placed(String token, Map<String, Object> body) {
        ResponseEntity<Map> r = order(token, body);
        assertThat(r.getStatusCode()).as("%s", r.getBody()).isEqualTo(HttpStatus.CREATED);
        return (String) r.getBody().get("orderId");
    }

    private ResponseEntity<Map> cancel(String token, String orderId) {
        return exchange("/v1/lab/orders/" + orderId + "/cancel", HttpMethod.POST, authedJsonBody(token, Map.of("reason", "Ordered by mistake")), Map.class);
    }

    private List<Map<String, Object>> orders(String token, String entry) {
        ResponseEntity<Map> r = exchange("/v1/lab/orders?queueEntryId=" + entry, HttpMethod.GET, authed(token), Map.class);
        assertThat(r.getStatusCode()).as("%s", r.getBody()).isEqualTo(HttpStatus.OK);
        return (List<Map<String, Object>>) r.getBody().get("items");
    }

    /** The charge codes the visit's checkout pre-loads. */
    private List<String> checkoutCodes(Clinic c, String entry) {
        ResponseEntity<Map> r = exchange("/v1/billing/checkout/" + entry, HttpMethod.GET, authed(c.token()), Map.class);
        assertThat(r.getStatusCode()).as("%s", r.getBody()).isEqualTo(HttpStatus.OK);
        return ((List<Map<String, Object>>) r.getBody().get("pendingProcedures")).stream().map(x -> (String) x.get("code")).toList();
    }

    private <T> T one(Clinic c, String sql, Class<T> type, Object... args) {
        return inTenantTx(c.t().id(), () -> jdbc.queryForObject(sql, type, args));
    }

    private List<Map<String, Object>> rows(Clinic c, String sql, Object... args) {
        return inTenantTx(c.t().id(), () -> jdbc.queryForList(sql, args));
    }

    private List<String> events(Clinic c, String type, String contains) {
        return inTenantTx(c.t().id(), () -> jdbc.queryForList("SELECT envelope::text FROM outbox_cloudevents WHERE tenant_id = ? AND envelope->>'type' = ? "
                + "AND envelope::text LIKE ?", String.class, c.t().id(), type, "%" + contains + "%"));
    }

    /** priority and test_count of the order's lab.order.placed.v1 event. */
    private Map<String, Object> eventData(Clinic c, String orderId) {
        return rows(c, "SELECT envelope->'data'->>'priority' AS priority, (envelope->'data'->>'testCount')::int AS test_count FROM outbox_cloudevents "
                + "WHERE tenant_id = ? AND envelope->>'type' = 'lab.order.placed.v1' AND envelope->'data'->>'orderId' = ?", c.t().id(), orderId).get(0);
    }

    private void fastingTemplate(Clinic c) {
        inTenantTx(c.t().id(), () -> jdbc.update("""
                INSERT INTO whatsapp_templates (tenant_id, name, meta_name, language, category, header_text, body, param_count, status)
                VALUES (?, 'lab_fasting', ?, 'en', 'UTILITY', 'Clinic', 'Hello {{1}}, please do not eat for {{2}} hours before you come. Thank you.', 2, 'approved')
                """, c.t().id(), "m_" + UUID.randomUUID().toString().replace("-", "")));
    }

    private void messagingConsent(Clinic c, String patientId, boolean granted) {
        inTenantTx(c.t().id(), () -> jdbc.update("INSERT INTO consents (tenant_id, patient_id, consent_type, withdrawn_at) VALUES (?, ?::uuid, 'messaging', "
                + (granted ? "NULL" : "now()") + ")", c.t().id(), patientId));
    }

    // ---- AC1: the order is written with its rows, charges and event ----
    @Test
    void ac1_aPanelAndATestBecomeOneOrderWithChargesOnTheVisitsCheckoutAndOneEvent() {
        Clinic c = clinic();
        String chol = test(c, "CHOL", "Cholesterol total", 200), hdl = test(c, "HDL", "HDL cholesterol", 250), tg = test(c, "TG", "Triglycerides", 250);
        String lipid = panel(c, "LIPID", "Lipid profile", 600, chol, hdl, tg);
        String crp = test(c, "CRP", "C-reactive protein", 500);
        Visit v = visit(c);

        Map<String, Object> b = body(v, "routine", "inhouse", p(lipid), t(crp));
        b.put("clinicalNote", "Review in two weeks");
        ResponseEntity<Map> r = order(c.token(), b);
        assertThat(r.getStatusCode()).as("%s", r.getBody()).isEqualTo(HttpStatus.CREATED);
        String id = (String) r.getBody().get("orderId");
        assertThat((String) r.getBody().get("accessionHint")).matches("LO-[0-9A-F]{12}").isEqualTo(LabOrderService.reference(UUID.fromString(id)));
        List<Map<String, Object>> charges = (List<Map<String, Object>>) r.getBody().get("charges");
        assertThat(charges).extracting(x -> x.get("chargeCode")).containsExactly("LABP-LIPID", "LAB-CRP");
        assertThat(charges).extracting(x -> new BigDecimal(String.valueOf(x.get("amount"))).intValue()).containsExactly(600, 500);
        assertThat(r.getBody().get("fastingNotice")).isNull();

        Map<String, Object> row = rows(c, "SELECT * FROM lab_orders WHERE id = ?::uuid", id).get(0);
        assertThat(row).containsEntry("status", "ordered").containsEntry("source", "consult").containsEntry("priority", "routine")
                .containsEntry("destination_kind", "inhouse").containsEntry("clinical_note", "Review in two weeks")
                .containsEntry("ordering_doctor_id", c.owner().id());
        assertThat(String.valueOf(row.get("queue_entry_id"))).isEqualTo(v.entry());
        List<Map<String, Object>> items = rows(c, "SELECT i.*, t.code FROM lab_order_items i JOIN lab_tests t ON t.id = i.test_id "
                + "WHERE i.order_id = ?::uuid ORDER BY i.display_order", id);
        assertThat(items).extracting(x -> x.get("code")).containsExactly("CHOL", "HDL", "TG", "CRP");
        assertThat(items).extracting(x -> x.get("status")).containsOnly("ordered");
        assertThat(items).extracting(x -> ((BigDecimal) x.get("price_snapshot")).intValue()).as("a panel is one charge on its first row").containsExactly(600, 0, 0, 500);
        assertThat(items.subList(0, 3)).extracting(x -> String.valueOf(x.get("panel_id"))).containsOnly(lipid);
        assertThat(items.get(3).get("panel_id")).isNull();
        assertThat(items.subList(0, 3)).extracting(x -> x.get("charge_line_ref")).doesNotContainNull().containsOnly(items.get(0).get("charge_line_ref"));
        assertThat(items.get(3).get("charge_line_ref")).isNotNull().isNotEqualTo(items.get(0).get("charge_line_ref"));
        assertThat(rows(c, "SELECT id FROM visit_pending_charges WHERE source = 'lab' AND source_ref = ?::uuid AND status = 'pending'", id))
                .extracting(x -> x.get("id")).containsExactlyInAnyOrder(items.get(0).get("charge_line_ref"), items.get(3).get("charge_line_ref"));

        // the visit's checkout offers both charges; the list shows the order
        assertThat(checkoutCodes(c, v.entry())).containsExactlyInAnyOrder("LABP-LIPID", "LAB-CRP");
        Map<String, Object> listed = orders(c.token(), v.entry()).get(0);
        assertThat(listed).containsEntry("id", id).containsEntry("status", "ordered").containsEntry("cancellable", true).containsEntry("patientId", v.patientId());
        assertThat(new BigDecimal(String.valueOf(listed.get("total"))).intValue()).isEqualTo(1100);
        assertThat((List<Map<String, Object>>) listed.get("items")).extracting(x -> x.get("code") + "/" + x.get("panelName"))
                .containsExactly("CHOL/Lipid profile", "HDL/Lipid profile", "TG/Lipid profile", "CRP/null");

        // one event: ids, priority and a count, no patient id, name or test name
        List<String> ev = events(c, "lab.order.placed.v1", id);
        assertThat(ev).hasSize(1);
        assertThat(eventData(c, id)).containsEntry("priority", "routine").containsEntry("test_count", 4);
        assertThat(ev.get(0)).contains("patientRef").doesNotContain(v.patientId()).doesNotContain(v.patientName()).doesNotContain("Cholesterol").doesNotContain("CRP");
        assertThat(one(c, "SELECT count(*) FROM audit_log WHERE action = 'lab.order_placed' AND entity_id = ?::uuid", Integer.class, id)).isEqualTo(1);
    }

    @Test
    void ac1_twentyTimedOrders_serverSideWriteTime() {
        Clinic c = clinic();
        String crp = test(c, "CRP", "C-reactive protein", 500);
        Visit v = visit(c);
        List<Long> ms = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            Visit each = i == 0 ? v : visit(c);
            long t0 = System.nanoTime();
            placed(c.token(), body(each, "routine", "inhouse", t(crp)));
            ms.add((System.nanoTime() - t0) / 1_000_000);
        }
        List<Long> sorted = ms.stream().sorted().toList();
        System.out.println("PHL-043 order write ms " + ms + " p80=" + sorted.get(15) + " p95=" + sorted.get(18));
        assertThat(sorted.get(15)).as("p80 of 20 timed order writes (the doctor's own typing time is not measured here)").isLessThan(10_000L);
    }

    @Test
    void theSameIdempotencyKeyPlacesOneOrder() {
        Clinic c = clinic();
        String crp = test(c, "CRP", "C-reactive protein", 500);
        Visit v = visit(c);
        String key = UUID.randomUUID().toString();
        Map<String, Object> b = body(v, "urgent", "inhouse", t(crp));
        ResponseEntity<Map> a = exchange("/v1/lab/orders", HttpMethod.POST, authedJsonBodyWithHeader(c.token(), "Idempotency-Key", key, b), Map.class);
        ResponseEntity<Map> again = exchange("/v1/lab/orders", HttpMethod.POST, authedJsonBodyWithHeader(c.token(), "Idempotency-Key", key, b), Map.class);
        assertThat(a.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(again.getBody().get("orderId")).isEqualTo(a.getBody().get("orderId"));
        assertThat(one(c, "SELECT count(*) FROM lab_orders WHERE queue_entry_id = ?::uuid", Integer.class, v.entry())).isEqualTo(1);
        assertThat(one(c, "SELECT count(*) FROM visit_pending_charges WHERE queue_entry_id = ?::uuid", Integer.class, v.entry())).isEqualTo(1);
    }

    // ---- AC2 (this story's part): STAT sorts first whatever the time ----
    @Test
    void ac2_statOrdersComeFirstThenUrgentThenRoutine() {
        Clinic c = clinic();
        String a = test(c, "A1", "Test A", 100), b = test(c, "B1", "Test B", 100), d = test(c, "D1", "Test D", 100);
        Visit v = visit(c);
        String routine = placed(c.token(), body(v, "routine", "inhouse", t(a)));
        String urgent = placed(c.token(), body(v, "urgent", "inhouse", t(b)));
        String stat = placed(c.token(), body(v, "stat", "inhouse", t(d)));
        assertThat(orders(c.token(), v.entry())).extracting(x -> x.get("id")).containsExactly(stat, urgent, routine);
        assertThat(eventData(c, stat)).containsEntry("priority", "stat").containsEntry("test_count", 1);
    }

    // ---- AC3: an order for a lab outside Nabd gives a printable sheet with patient, tests and barcode; nothing is charged ----
    @Test
    void ac3_anExternalOrderHasAPrintableSheetWithPatientTestsAndBarcode_andNoCharges() {
        Clinic c = clinic();
        String chol = test(c, "CHOL", "Cholesterol total", 200), hdl = test(c, "HDL", "HDL cholesterol", 250);
        String lipid = panel(c, "LIPID", "Lipid profile", 400, chol, hdl);
        Visit v = visit(c);
        Map<String, Object> b = body(v, "urgent", "external", p(lipid));
        b.put("fastingHours", 10);
        ResponseEntity<Map> r = order(c.token(), b);
        assertThat(r.getStatusCode()).as("%s", r.getBody()).isEqualTo(HttpStatus.CREATED);
        String id = (String) r.getBody().get("orderId");
        assertThat((List) r.getBody().get("charges")).as("the outside lab bills the patient").isEmpty();
        assertThat(one(c, "SELECT count(*) FROM visit_pending_charges WHERE source_ref = ?::uuid", Integer.class, id)).isZero();
        assertThat(checkoutCodes(c, v.entry())).isEmpty();
        assertThat(rows(c, "SELECT charge_line_ref FROM lab_order_items WHERE order_id = ?::uuid", id)).extracting(x -> x.get("charge_line_ref")).containsOnlyNulls();

        ResponseEntity<byte[]> pdf = exchange("/v1/lab/orders/" + id + "/sheet", HttpMethod.GET, authed(c.token()), byte[].class);
        assertThat(pdf.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(pdf.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PDF);
        String ref = LabOrderService.reference(UUID.fromString(id));
        List<String> lines = LabPdfTest.lines(pdf.getBody());
        assertThat(lines).contains(ref, "LAB TEST ORDER", "Order reference: " + ref, "Priority: URGENT", "Fasting: 10 hours",
                "1. CHOL  Cholesterol total  [Lipid profile]", "2. HDL  HDL cholesterol  [Lipid profile]");
        assertThat(lines).anyMatch(l -> l.startsWith("Patient: " + v.patientName()));
        assertThat(lines).anyMatch(l -> l.startsWith("Test Clinic"));
        assertThat(LabPdfTest.barsOf(pdf.getBody(), LabPdf.A4.module())).as("the barcode is the order reference in Code 128").isEqualTo(Code128.modules(ref));
        assertThat(one(c, "SELECT count(*) FROM audit_log WHERE action = 'lab.order_sheet_printed' AND entity_id = ?::uuid", Integer.class, id)).isEqualTo(1);

        assertThat(cancel(c.token(), id).getStatusCode()).isEqualTo(HttpStatus.OK);
        ResponseEntity<Map> gone = exchange("/v1/lab/orders/" + id + "/sheet", HttpMethod.GET, authed(c.token()), Map.class);
        assertThat(gone.getStatusCode()).as("a cancelled order has no sheet").isEqualTo(HttpStatus.CONFLICT);
    }

    // ---- AC4: a fasting order sends the fasting notice at order time ----
    @Test
    void ac4_aFastingOrderRequestsTheFastingNotice_withoutATestName() {
        Clinic c = clinic();
        String glu = test(c, "GLUF", "Glucose fasting", 100);
        Visit v = visit(c);
        Map<String, Object> b = body(v, "routine", "inhouse", t(glu));
        b.put("fastingHours", 8);

        // no approved template yet: the order is still placed and says why nothing was sent
        ResponseEntity<Map> none = order(c.token(), b);
        assertThat(none.getStatusCode()).as("%s", none.getBody()).isEqualTo(HttpStatus.CREATED);
        assertThat(none.getBody().get("fastingNotice")).isEqualTo("template_missing");
        assertThat(one(c, "SELECT count(*) FROM notification_requests WHERE purpose = 'lab_fasting'", Integer.class)).isZero();

        fastingTemplate(c);
        messagingConsent(c, v.patientId(), true);
        ResponseEntity<Map> sent = order(c.token(), b);
        assertThat(sent.getStatusCode()).as("%s", sent.getBody()).isEqualTo(HttpStatus.CREATED);
        assertThat(sent.getBody().get("fastingNotice")).isEqualTo("requested");
        String id = (String) sent.getBody().get("orderId");
        Map<String, Object> req = rows(c, "SELECT id, purpose, subject_type, recipient_patient_id FROM notification_requests WHERE subject_id = ?::uuid", id).get(0);
        assertThat(req).containsEntry("purpose", "lab_fasting").containsEntry("subject_type", "lab_order");
        assertThat(String.valueOf(req.get("recipient_patient_id"))).isEqualTo(v.patientId());
        List<String> ev = events(c, "comms.notification.requested.v1", String.valueOf(req.get("id")));
        assertThat(ev).hasSize(1);
        assertThat(ev.get(0)).contains("lab_fasting").doesNotContain("Glucose").doesNotContain("GLUF").doesNotContain(v.patientName());
        assertThat(one(c, "SELECT fasting_hours FROM lab_orders WHERE id = ?::uuid", Integer.class, id)).isEqualTo(8);

        // consent withdrawn: the request is kept as blocked and nothing is sent
        messagingConsent(c, v.patientId(), false);
        ResponseEntity<Map> blocked = order(c.token(), b);
        assertThat(blocked.getBody().get("fastingNotice")).isEqualTo("blocked");
        assertThat(events(c, "comms.notification.requested.v1", "lab_fasting")).hasSize(1);

        // an order without fasting sends nothing
        Map<String, Object> plain = body(v, "routine", "inhouse", t(glu));
        assertThat(order(c.token(), plain).getBody().get("fastingNotice")).isNull();
        assertThat(one(c, "SELECT count(*) FROM notification_requests WHERE purpose = 'lab_fasting'", Integer.class)).isEqualTo(2);

        b.put("fastingHours", 30);
        assertThat(order(c.token(), b).getStatusCode()).as("fasting hours outside 1 to 24").isEqualTo(HttpStatus.BAD_REQUEST);
    }

    // ---- AC5: cancelling an uncollected order removes its charges from checkout ----
    @Test
    void ac5_cancelRemovesTheChargesFromCheckout_isIdempotent_andACollectedOrderIsRefused() {
        Clinic c = clinic();
        String chol = test(c, "CHOL", "Cholesterol total", 200), hdl = test(c, "HDL", "HDL cholesterol", 250);
        String lipid = panel(c, "LIPID", "Lipid profile", 400, chol, hdl);
        String crp = test(c, "CRP", "C-reactive protein", 500);
        Visit v = visit(c);
        String id = placed(c.token(), body(v, "routine", "inhouse", p(lipid), t(crp)));
        String kept = placed(c.token(), body(v, "routine", "inhouse", t(test(c, "TSH", "Thyroid stimulating hormone", 300))));
        assertThat(checkoutCodes(c, v.entry())).containsExactlyInAnyOrder("LABP-LIPID", "LAB-CRP", "LAB-TSH");

        ResponseEntity<Map> r = cancel(c.token(), id);
        assertThat(r.getStatusCode()).as("%s", r.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(r.getBody()).containsEntry("status", "cancelled").containsEntry("chargesRemoved", 2).containsEntry("chargesAlreadyBilled", 0);
        assertThat(checkoutCodes(c, v.entry())).as("only the other order's charge is left").containsExactly("LAB-TSH");
        assertThat(rows(c, "SELECT status, cancel_reason, cancelled_by FROM lab_orders WHERE id = ?::uuid", id).get(0))
                .containsEntry("status", "cancelled").containsEntry("cancel_reason", "Ordered by mistake").containsEntry("cancelled_by", c.owner().id());
        assertThat(rows(c, "SELECT status FROM lab_order_items WHERE order_id = ?::uuid", id)).extracting(x -> x.get("status")).hasSize(3).containsOnly("cancelled");
        assertThat(rows(c, "SELECT status FROM visit_pending_charges WHERE source_ref = ?::uuid", id)).extracting(x -> x.get("status")).containsOnly("removed");
        Map<String, Object> listed = orders(c.token(), v.entry()).stream().filter(o -> id.equals(o.get("id"))).findFirst().orElseThrow();
        assertThat(listed).containsEntry("status", "cancelled").containsEntry("cancellable", false);
        assertThat(one(c, "SELECT count(*) FROM audit_log WHERE action = 'lab.order_cancelled' AND entity_id = ?::uuid", Integer.class, id)).isEqualTo(1);

        // cancelling again answers the same and changes nothing
        ResponseEntity<Map> again = cancel(c.token(), id);
        assertThat(again.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(again.getBody()).containsEntry("status", "cancelled").containsEntry("chargesRemoved", 0);
        assertThat(one(c, "SELECT count(*) FROM audit_log WHERE action = 'lab.order_cancelled' AND entity_id = ?::uuid", Integer.class, id)).isEqualTo(1);
        assertThat(checkoutCodes(c, v.entry())).containsExactly("LAB-TSH");

        // a sample was collected (PHL-045 will write this status): the order can no longer be cancelled and keeps its charge
        inTenantTx(c.t().id(), () -> jdbc.update("UPDATE lab_orders SET status = 'partially_collected' WHERE id = ?::uuid", kept));
        ResponseEntity<Map> late = cancel(c.token(), kept);
        assertThat(late.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(String.valueOf(late.getBody().get("type"))).endsWith("/already-collected");
        assertThat(checkoutCodes(c, v.entry())).containsExactly("LAB-TSH");
        assertThat(one(c, "SELECT status FROM lab_orders WHERE id = ?::uuid", String.class, kept)).isEqualTo("partially_collected");

        ResponseEntity<Map> blank = exchange("/v1/lab/orders/" + kept + "/cancel", HttpMethod.POST, authedJsonBody(c.token(), Map.of("reason", " ")), Map.class);
        assertThat(blank.getStatusCode()).as("a reason is needed").isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void checkoutBillsTheOrderedTests_andACancelAfterBillingLeavesTheInvoiceAlone() {
        Clinic c = clinic();
        String crp = test(c, "CRP", "C-reactive protein", 500), tsh = test(c, "TSH", "Thyroid stimulating hormone", 300);
        Visit v = visit(c);
        String billed = placed(c.token(), body(v, "routine", "inhouse", t(crp)));
        String leftOut = placed(c.token(), body(v, "routine", "inhouse", t(tsh)));
        moveTo(c, v.entry(), "waiting", "vitals_pending", "vitals_done", "in_consult", "checkout_pending");
        assertThat(checkoutCodes(c, v.entry())).containsExactlyInAnyOrder("LAB-CRP", "LAB-TSH");

        // the desk bills CRP and takes TSH off the bill
        ResponseEntity<Map> inv = exchange("/v1/billing/checkout/" + v.entry(), HttpMethod.POST, authedJsonBody(c.token(),
                Map.of("lineItems", List.of(new HashMap<>(Map.of("chargeCode", "LAB-CRP", "quantity", 1))))), Map.class);
        assertThat(inv.getStatusCode().is2xxSuccessful()).as("%s", inv.getBody()).isTrue();
        String invoiceId = (String) inv.getBody().get("id");
        Map<String, Object> charge = rows(c, "SELECT status, invoice_id FROM visit_pending_charges WHERE source_ref = ?::uuid", billed).get(0);
        assertThat(charge).containsEntry("status", "billed");
        assertThat(String.valueOf(charge.get("invoice_id"))).isEqualTo(invoiceId);
        assertThat(rows(c, "SELECT status FROM visit_pending_charges WHERE source_ref = ?::uuid", leftOut).get(0)).containsEntry("status", "pending");

        // the order is still uncollected, so it can be cancelled; the invoice keeps its line (a refund is the desk's decision)
        ResponseEntity<Map> r = cancel(c.token(), billed);
        assertThat(r.getStatusCode()).as("%s", r.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(r.getBody()).containsEntry("chargesRemoved", 0).containsEntry("chargesAlreadyBilled", 1);
        assertThat(one(c, "SELECT count(*) FROM invoice_line_items WHERE invoice_id = ?::uuid AND charge_code = 'LAB-CRP'", Integer.class, invoiceId)).isEqualTo(1);
        assertThat(one(c, "SELECT status FROM invoices WHERE id = ?::uuid", String.class, invoiceId)).isNotEqualTo("void");

        // the visit is closed now: a new in-house order cannot reach its bill
        ResponseEntity<Map> closed = order(c.token(), body(v, "routine", "inhouse", t(crp)));
        assertThat(closed.getStatusCode()).as("%s", closed.getBody()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(String.valueOf(closed.getBody().get("type"))).endsWith("/visit-closed");
    }

    // ---- AC6: a clinic without the lab module gets external order sheets only ----
    @Test
    void ac6_withoutTheLabModuleOnlyTheExternalOrderSheetWorks() {
        Clinic c = clinic();
        String crp = test(c, "CRP", "C-reactive protein", 500); // while the clinic was unrestricted
        Visit v = visit(c);
        subscribe(c.t().id(), List.of("billing"));

        ResponseEntity<Map> refused = order(c.token(), body(v, "routine", "external", t(crp)));
        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(refused.getBody()).containsEntry("code", "MODULE_NOT_ENTITLED");
        for (String path : List.of("/v1/lab/orders?queueEntryId=" + v.entry(), "/v1/lab/order-favourites", "/v1/lab/orders/" + UUID.randomUUID() + "/sheet")) {
            assertThat(exchange(path, HttpMethod.GET, authed(c.token()), Map.class).getStatusCode()).as(path).isEqualTo(HttpStatus.FORBIDDEN);
        }
        assertThat(cancel(c.token(), UUID.randomUUID().toString()).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        Map<String, Object> sheet = Map.of("tests", List.of("Lipid profile", "HbA1c"), "labName", "City Diagnostics", "priority", "urgent", "fastingHours", 12,
                "clinicalNote", "Known diabetic");
        String path = "/v1/clinical/visits/" + v.entry() + "/lab-order-sheet";
        ResponseEntity<byte[]> pdf = exchange(path, HttpMethod.POST, authedJsonBody(c.token(), sheet), byte[].class);
        assertThat(pdf.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(pdf.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PDF);
        List<String> lines = LabPdfTest.lines(pdf.getBody());
        String ref = lines.get(0);
        assertThat(ref).matches("LX-[0-9A-F]{12}");
        assertThat(lines).contains("LAB TEST ORDER", "Order reference: " + ref, "To: City Diagnostics", "Priority: URGENT", "Fasting: 12 hours",
                "Clinical note: Known diabetic", "1. Lipid profile", "2. HbA1c");
        assertThat(lines).anyMatch(l -> l.startsWith("Patient: " + v.patientName()));
        assertThat(LabPdfTest.barsOf(pdf.getBody(), LabPdf.A4.module())).isEqualTo(Code128.modules(ref));
        assertThat(one(c, "SELECT count(*) FROM lab_orders", Integer.class)).as("no lab order row without the lab module").isZero();
        assertThat(one(c, "SELECT count(*) FROM visit_pending_charges", Integer.class)).isZero();
        Map<String, Object> audit = rows(c, "SELECT after::text AS after FROM audit_log WHERE action = 'lab.external_sheet_issued' AND entity_id = ?::uuid", v.entry()).get(0);
        assertThat((String) audit.get("after")).contains(ref).doesNotContain("HbA1c");

        // validation, permission and tenancy
        assertThat(exchange(path, HttpMethod.POST, authedJsonBody(c.token(), Map.of("tests", List.of())), Map.class).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(exchange(path, HttpMethod.POST, authedJsonBody(c.token(), Map.of("tests", List.of(" "))), Map.class).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        String viewer = tokenWith(c, "Viewer", new ModuleGrant("clinical", true, false, false, false, false, false, false));
        assertThat(exchange(path, HttpMethod.POST, authedJsonBody(viewer, sheet), Map.class).getStatusCode()).as("needs clinical:edit").isEqualTo(HttpStatus.FORBIDDEN);
        Clinic other = clinic();
        assertThat(exchange(path, HttpMethod.POST, authedJsonBody(other.token(), sheet), Map.class).getStatusCode()).as("another clinic's visit").isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void favouritesAreTheDoctorsOwnMostOrderedActiveTestsAndPanels() {
        Clinic c = clinic();
        String chol = test(c, "CHOL", "Cholesterol total", 200), hdl = test(c, "HDL", "HDL cholesterol", 250);
        String lipid = panel(c, "LIPID", "Lipid profile", 400, chol, hdl);
        String crp = test(c, "CRP", "C-reactive protein", 500), tsh = test(c, "TSH", "Thyroid stimulating hormone", 300);
        Visit v = visit(c);
        placed(c.token(), body(v, "routine", "inhouse", p(lipid), t(crp)));
        placed(c.token(), body(v, "routine", "inhouse", p(lipid)));
        placed(c.token(), body(v, "routine", "inhouse", p(lipid), t(tsh)));
        placed(c.token(), body(v, "routine", "inhouse", t(tsh)));
        String cancelled = placed(c.token(), body(v, "routine", "inhouse", t(crp)));
        assertThat(cancel(c.token(), cancelled).getStatusCode()).isEqualTo(HttpStatus.OK);

        List<Map<String, Object>> fav = (List<Map<String, Object>>) exchange("/v1/lab/order-favourites", HttpMethod.GET, authed(c.token()), Map.class).getBody().get("items");
        assertThat(fav).extracting(x -> x.get("kind") + ":" + x.get("code") + ":" + x.get("orders")).as("a panel counts once per order; a cancelled order does not count")
                .containsExactly("panel:LIPID:3", "test:TSH:2", "test:CRP:1");
        assertThat(new BigDecimal(String.valueOf(fav.get(0).get("price"))).intValue()).isEqualTo(400);

        // a test taken out of the catalogue is no longer offered
        assertThat(exchange("/v1/lab/tests/" + tsh, HttpMethod.PATCH, authedJsonBody(c.token(), Map.of("active", false)), Map.class).getStatusCode()).isEqualTo(HttpStatus.OK);
        fav = (List<Map<String, Object>>) exchange("/v1/lab/order-favourites", HttpMethod.GET, authed(c.token()), Map.class).getBody().get("items");
        assertThat(fav).extracting(x -> x.get("code")).containsExactly("LIPID", "CRP");

        // another doctor has their own (empty) list
        String doctor = tokenWith(c, "Doctor", new ModuleGrant("lab", true, true, false, false, false, false, false), fullGrant("clinical"), fullGrant("queue"), fullGrant("patients"));
        assertThat((List) exchange("/v1/lab/order-favourites", HttpMethod.GET, authed(doctor), Map.class).getBody().get("items")).isEmpty();
    }

    @Test
    void whatCannotBeOrderedIsRefusedAndWritesNothing() {
        Clinic c = clinic();
        String chol = test(c, "CHOL", "Cholesterol total", 200), hdl = test(c, "HDL", "HDL cholesterol", 250);
        String lipid = panel(c, "LIPID", "Lipid profile", 400, chol, hdl);
        String off = test(c, "OFF", "Switched off", 100);
        assertThat(exchange("/v1/lab/tests/" + off, HttpMethod.PATCH, authedJsonBody(c.token(), Map.of("active", false)), Map.class).getStatusCode()).isEqualTo(HttpStatus.OK);
        Visit v = visit(c), someoneElse = visit(c);

        ResponseEntity<Map> inactive = order(c.token(), body(v, "routine", "inhouse", t(off)));
        assertThat(inactive.getStatusCode().value()).isEqualTo(422);
        assertThat(String.valueOf(inactive.getBody().get("type"))).endsWith("/test-inactive");
        assertThat(order(c.token(), body(v, "routine", "inhouse", t(UUID.randomUUID().toString()))).getStatusCode().value()).as("unknown test").isEqualTo(422);
        assertThat(order(c.token(), body(v, "routine", "inhouse", p(UUID.randomUUID().toString()))).getStatusCode().value()).as("unknown panel").isEqualTo(422);

        ResponseEntity<Map> twice = order(c.token(), body(v, "routine", "inhouse", p(lipid), t(chol)));
        assertThat(twice.getStatusCode().value()).isEqualTo(422);
        assertThat(String.valueOf(twice.getBody().get("type"))).endsWith("/duplicate-test");

        Map<String, Object> partner = body(v, "routine", "inhouse", t(chol));
        partner.put("destination", Map.of("kind", "partner", "partnerLabId", UUID.randomUUID().toString()));
        ResponseEntity<Map> noPartner = order(c.token(), partner);
        assertThat(noPartner.getStatusCode().value()).isEqualTo(422);
        assertThat(String.valueOf(noPartner.getBody().get("type"))).endsWith("/partner-lab-unknown");

        assertThat(order(c.token(), body(v, "routine", "inhouse", Map.of())).getStatusCode()).as("neither test nor panel").isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(order(c.token(), body(v, "routine", "inhouse", Map.of("testId", chol, "panelId", lipid))).getStatusCode()).as("both").isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(order(c.token(), body(v, "soon", "inhouse", t(chol))).getStatusCode()).as("unknown priority").isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(order(c.token(), body(v, "routine", "inhouse")).getStatusCode()).as("no items").isEqualTo(HttpStatus.BAD_REQUEST);

        Map<String, Object> wrongVisit = body(v, "routine", "inhouse", t(chol));
        wrongVisit.put("queueEntryId", someoneElse.entry());
        assertThat(order(c.token(), wrongVisit).getStatusCode()).as("the visit is another patient's").isEqualTo(HttpStatus.BAD_REQUEST);
        Map<String, Object> noPatient = body(v, "routine", "inhouse", t(chol));
        noPatient.put("patientId", UUID.randomUUID().toString());
        noPatient.remove("queueEntryId");
        assertThat(order(c.token(), noPatient).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

        assertThat(one(c, "SELECT count(*) FROM lab_orders", Integer.class)).isZero();
        assertThat(one(c, "SELECT count(*) FROM visit_pending_charges", Integer.class)).isZero();
        assertThat(one(c, "SELECT count(*) FROM outbox_events WHERE tenant_id = ? AND event_type = 'lab.order.placed.v1'", Integer.class, c.t().id())).isZero();

        // an order without a visit is kept, with nothing to charge (no checkout to put it on)
        Map<String, Object> noVisit = body(v, "routine", "inhouse", t(chol));
        noVisit.remove("queueEntryId");
        ResponseEntity<Map> walkIn = order(c.token(), noVisit);
        assertThat(walkIn.getStatusCode()).as("%s", walkIn.getBody()).isEqualTo(HttpStatus.CREATED);
        assertThat((List) walkIn.getBody().get("charges")).isEmpty();
        assertThat(one(c, "SELECT source FROM lab_orders WHERE id = ?::uuid", String.class, walkIn.getBody().get("orderId"))).isEqualTo("walk_in");
    }

    @Test
    void orderingNeedsLabOrder_andAnotherClinicNeverSeesOrCancelsAnOrder() {
        Clinic c = clinic();
        String crp = test(c, "CRP", "C-reactive protein", 500);
        Visit v = visit(c);
        String id = placed(c.token(), body(v, "routine", "inhouse", t(crp)));

        // lab:view without lab:order (the technician): no ordering, no cancelling, no favourites
        String tech = tokenWith(c, "Technician", new ModuleGrant("lab", true, false, true, false, false, false, false), fullGrant("clinical"), fullGrant("patients"));
        assertThat(order(tech, body(v, "routine", "inhouse", t(crp))).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(cancel(tech, id).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(exchange("/v1/lab/order-favourites", HttpMethod.GET, authed(tech), Map.class).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(exchange("/v1/lab/orders?queueEntryId=" + v.entry(), HttpMethod.GET, authed(tech), Map.class).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(exchange("/v1/lab/orders/" + id + "/sheet", HttpMethod.GET, authed(tech), Map.class).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        Clinic other = clinic();
        String theirTest = test(other, "CRP", "C-reactive protein", 100);
        Visit theirs = visit(other);
        assertThat(cancel(other.token(), id).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(exchange("/v1/lab/orders/" + id + "/sheet", HttpMethod.GET, authed(other.token()), Map.class).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(exchange("/v1/lab/orders?queueEntryId=" + v.entry(), HttpMethod.GET, authed(other.token()), Map.class).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(order(other.token(), body(theirs, "routine", "inhouse", t(crp))).getStatusCode().value()).as("another clinic's test id").isEqualTo(422);
        assertThat(order(other.token(), body(v, "routine", "inhouse", t(theirTest))).getStatusCode()).as("another clinic's patient").isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(one(c, "SELECT status FROM lab_orders WHERE id = ?::uuid", String.class, id)).isEqualTo("ordered");
        assertThat(one(other, "SELECT count(*) FROM lab_orders", Integer.class)).as("row level security: the other clinic sees none").isZero();
        assertThat(one(other, "SELECT count(*) FROM lab_order_items", Integer.class)).isZero();
        assertThat(one(other, "SELECT count(*) FROM visit_pending_charges", Integer.class)).isZero();
    }
}
