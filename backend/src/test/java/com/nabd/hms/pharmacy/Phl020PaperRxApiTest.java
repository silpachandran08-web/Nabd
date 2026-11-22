package com.nabd.hms.pharmacy;

import com.nabd.hms.common.ModuleGrant;
import com.nabd.hms.support.ApiTestBase;
import com.nabd.hms.support.Hazard;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PHL-020 (hazard H-04): an outside paper / photo prescription recorded at the counter lets a prescription-only line be billed,
 * within its quantity; Schedule X (the class whose rule keeps a duplicate copy) needs the tick and the photo; a photo that fails
 * the scan blocks the sale; the photo is readable only with pharmacy:dispense through a short-lived URL and every view is audited.
 * Item and prescriber names are test fixtures, not clinical content.
 */
@Hazard("H-04")
class Phl020PaperRxApiTest extends ApiTestBase {

    private static final AtomicInteger SEQ = new AtomicInteger((int) (System.nanoTime() % 400_000));
    private static final String EICAR = "X5O!P%@AP[4\\PZX54(P^)7CC)7}$EICAR-STANDARD-ANTIVIRUS-TEST-FILE!$H+H*";
    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 0, 1, 2, 3, 4};
    private static final String BASE = "/v1/pharmacy/paper-prescriptions";

    private record Ctx(SeededTenant tenant, SeededStaff staff, String token) {
    }

    private static String phone() {
        return "+9197" + String.format("%08d", SEQ.incrementAndGet());
    }

    private static JdbcTemplate su() {
        return new JdbcTemplate(superuserDataSource());
    }

    private Ctx ctx() {
        SeededTenant tenant = seedTenant();
        SeededStaff staff = seedStaff(tenant, seedFullAccessRole(tenant.id()), "prx" + SEQ.incrementAndGet() + "@a.com", phone(), false);
        String token = loginAndGetAccessToken(staff);
        exchange("/v1/pharmacy/settings", HttpMethod.PATCH, authedJsonBody(token, Map.of("mode", "hybrid", "reason", "Test setup")), Map.class);
        return new Ctx(tenant, staff, token);
    }

    private String tokenFor(Ctx c, ModuleGrant... grants) {
        UUID role = seedRole(c.tenant().id(), "Role " + SEQ.incrementAndGet(), false, grants);
        return loginAndGetAccessToken(seedStaff(c.tenant(), role, "prx" + SEQ.incrementAndGet() + "@a.com", phone(), false));
    }

    /** [id, code] of a stocked prescription-only item of the given schedule class. */
    private String[] item(Ctx c, String name, String scheduleClass) {
        ResponseEntity<Map> r = exchange("/v1/pharmacy/items", HttpMethod.POST, authedJsonBody(c.token(), Map.of(
                "name", name, "isRx", true, "hsnCode", "3004", "price", 10.00, "taxRatePercent", 0, "stockQty", 50, "scheduleClass", scheduleClass)), Map.class);
        assertThat(r.getStatusCode().is2xxSuccessful()).as("item create %s", r.getBody()).isTrue();
        return new String[]{(String) r.getBody().get("id"), (String) r.getBody().get("code")};
    }

    /** Upload through the pharmacy endpoints and wait for the scan; returns [fileId, terminal status]. */
    private String[] upload(String token, String type, byte[] data) throws InterruptedException {
        ResponseEntity<Map> slot = exchange(BASE + "/uploads", HttpMethod.POST, authedJsonBody(token, Map.of("contentType", type, "sizeBytes", data.length)), Map.class);
        assertThat(slot.getStatusCode()).as("slot %s", slot.getBody()).isEqualTo(HttpStatus.CREATED);
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_OCTET_STREAM);
        assertThat(http.exchange(URI.create(url((String) slot.getBody().get("putUrl"))), HttpMethod.PUT, new HttpEntity<>(data, h), Void.class)
                .getStatusCode().value()).isEqualTo(200);
        ResponseEntity<Map> done = exchange(BASE + "/uploads/" + slot.getBody().get("uploadId") + "/complete", HttpMethod.POST, authed(token), Map.class);
        assertThat(done.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        String fileId = (String) done.getBody().get("fileId");
        for (int i = 0; i < 300; i++) {
            String s = (String) exchange(BASE + "/uploads/" + fileId, HttpMethod.GET, authed(token), Map.class).getBody().get("status");
            if (List.of("available", "infected", "rejected_type").contains(s)) return new String[]{fileId, s};
            Thread.sleep(100);
        }
        throw new AssertionError("scan never finished for " + fileId);
    }

    private static Map<String, Object> rxBody(Object line, String documentId, boolean copy) {
        Map<String, Object> m = new HashMap<>(Map.of("prescriberName", "Dr Test Prescriber", "prescriberRegNo", "REG-" + SEQ.incrementAndGet(),
                "rxDate", LocalDate.now(java.time.ZoneOffset.UTC).toString(), "lines", List.of(line), "duplicateCopyRetained", copy));
        if (documentId != null) m.put("documentId", documentId);
        return m;
    }

    private ResponseEntity<Map> record(String token, Map<String, Object> body) {
        return exchange(BASE, HttpMethod.POST, authedJsonBody(token, body), Map.class);
    }

    /** Records a paper Rx with one line for {@code itemId} and returns [paperRxId, paperRxLineId]. */
    private String[] recorded(Ctx c, String itemId, int qty, String documentId, boolean copy) {
        ResponseEntity<Map> r = record(c.token(), rxBody(Map.of("itemId", itemId, "qty", qty), documentId, copy));
        assertThat(r.getStatusCode()).as("paper rx %s", r.getBody()).isEqualTo(HttpStatus.CREATED);
        return new String[]{(String) r.getBody().get("paperRxId"), (String) ((Map<?, ?>) ((List<?>) r.getBody().get("lines")).get(0)).get("paperRxLineId")};
    }

    private ResponseEntity<Map> otc(String token, String code, int qty, String paperRxLineId) {
        Map<String, Object> line = new HashMap<>(Map.of("chargeCode", code, "quantity", qty));
        if (paperRxLineId != null) line.put("paperRxLineId", paperRxLineId);
        return exchange("/v1/billing/otc-checkout", HttpMethod.POST, authedJsonBody(token, Map.of("lineItems", List.of(line))), Map.class);
    }

    private static void assertProblem(ResponseEntity<Map> r, HttpStatus status, String type) {
        assertThat(r.getStatusCode()).as("%s", r.getBody()).isEqualTo(status);
        assertThat((String) r.getBody().get("type")).endsWith("/" + type);
    }

    private static int audits(UUID tenantId, String action) {
        return su().queryForObject("SELECT count(*) FROM audit_log WHERE tenant_id = ? AND action = ?", Integer.class, tenantId, action);
    }

    // ---- AC1: doctor name + registration no + date + photo, linked to the line, attached to the bill in < 10 s -------------

    @Test
    void ac1_paperRxWithPhotoLinksTheRxOnlyLineAndTheBillCarriesItWithinTenSeconds() throws Exception {
        Ctx c = ctx();
        String[] rx = item(c, "Paper Rx Item A", "IN_H");
        assertProblem(otc(c.token(), rx[1], 2, null), HttpStatus.UNPROCESSABLE_CONTENT, "rx-required"); // PHL-001 still blocks the bare line

        long t0 = System.nanoTime();
        String[] photo = upload(c.token(), "image/png", PNG);
        assertThat(photo[1]).isEqualTo("available");
        String[] paper = recorded(c, rx[0], 10, photo[0], false);
        ResponseEntity<Map> sale = otc(c.token(), rx[1], 4, paper[1]);
        long ms = (System.nanoTime() - t0) / 1_000_000;

        assertThat(sale.getStatusCode()).as("%s", sale.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(ms).as("photo to bill").isLessThan(10_000);
        Map<String, Object> row = su().queryForMap("SELECT li.is_rx, li.prescription_item_id, p.id AS paper_rx_id, p.document_id FROM invoice_line_items li " +
                "JOIN paper_prescription_lines l ON l.id = li.paper_rx_line_id JOIN paper_prescriptions p ON p.id = l.paper_rx_id " +
                "WHERE li.tenant_id = ? AND li.invoice_id = ?::uuid", c.tenant().id(), sale.getBody().get("id"));
        assertThat(row.get("is_rx")).isEqualTo(true);
        assertThat(row.get("prescription_item_id")).isNull();
        assertThat(row.get("paper_rx_id")).isEqualTo(UUID.fromString(paper[0]));
        assertThat(row.get("document_id")).isEqualTo(UUID.fromString(photo[0])); // the photo hangs off the bill line
        // the audit row carries counts and flags only, never the prescriber or the customer
        String after = su().queryForObject("SELECT after::text FROM audit_log WHERE tenant_id = ? AND action = 'pharmacy.paper_rx.recorded'", String.class, c.tenant().id());
        assertThat(after).contains("hasPhoto").doesNotContain("Prescriber");
        // recent prescribers feed the dialog's autocomplete
        ResponseEntity<List> who = exchange(BASE + "/prescribers", HttpMethod.GET, authed(c.token()), List.class);
        assertThat(who.getBody()).hasSize(1);
        assertThat(((Map<?, ?>) who.getBody().get(0)).get("name")).isEqualTo("Dr Test Prescriber");
    }

    // ---- AC2: reuse beyond the quantity ---------------------------------------------------------------------------------

    @Test
    void ac2_reuseBeyondTheQuantityIs422RxQuantityExhaustedAndAVoidGivesTheUnitsBack() {
        Ctx c = ctx();
        String[] rx = item(c, "Paper Rx Item B", "IN_H");
        String[] paper = recorded(c, rx[0], 10, null, false);

        ResponseEntity<Map> first = otc(c.token(), rx[1], 10, paper[1]); // boundary: exactly the quantity on the paper
        assertThat(first.getStatusCode()).as("%s", first.getBody()).isEqualTo(HttpStatus.OK);

        ResponseEntity<Map> again = otc(c.token(), rx[1], 1, paper[1]);
        assertProblem(again, HttpStatus.UNPROCESSABLE_CONTENT, "rx-quantity-exhausted");
        assertThat(su().queryForObject("SELECT count(*) FROM audit_log WHERE tenant_id = ? AND action = 'pharmacy.rx_gate_blocked' " +
                "AND after->>'reason' = 'rx-quantity-exhausted'", Integer.class, c.tenant().id())).isEqualTo(1);
        assertThat(su().queryForObject("SELECT count(*) FROM invoices WHERE tenant_id = ?", Integer.class, c.tenant().id())).isEqualTo(1);

        assertThat(exchange("/v1/billing/invoices/" + first.getBody().get("id") + "/void", HttpMethod.POST,
                authedJsonBody(c.token(), Map.of("reason", "Test void")), Map.class).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(otc(c.token(), rx[1], 6, paper[1]).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertProblem(otc(c.token(), rx[1], 5, paper[1]), HttpStatus.UNPROCESSABLE_CONTENT, "rx-quantity-exhausted"); // 6 + 5 > 10
    }

    // ---- AC3: Schedule X needs 'duplicate copy retained' and the photo -----------------------------------------------------

    @Test
    void ac3_scheduleXNeedsTheDuplicateCopyTickAndThePhotoAtRecordingAndAtTheSale() throws Exception {
        Ctx c = ctx();
        String[] x = item(c, "Paper Rx Item X", "IN_X");
        String photo = upload(c.token(), "image/png", PNG)[0];
        Map<String, Object> line = Map.of("itemId", x[0], "qty", 5);

        assertProblem(record(c.token(), rxBody(line, photo, false)), HttpStatus.UNPROCESSABLE_CONTENT, "rx-copy-required"); // photo, no tick
        assertProblem(record(c.token(), rxBody(line, null, true)), HttpStatus.UNPROCESSABLE_CONTENT, "rx-copy-required"); // tick, no photo
        assertThat(su().queryForObject("SELECT count(*) FROM paper_prescriptions WHERE tenant_id = ?", Integer.class, c.tenant().id())).isZero();

        String[] ok = recorded(c, x[0], 5, photo, true);
        assertThat(otc(c.token(), x[1], 5, ok[1]).getStatusCode()).isEqualTo(HttpStatus.OK);

        // a record written as free text without the tick or a photo cannot be used for the Schedule X item at the sale either
        ResponseEntity<Map> loose = record(c.token(), rxBody(Map.of("drugText", "as written", "qty", 5), null, false));
        assertThat(loose.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String looseLine = (String) ((Map<?, ?>) ((List<?>) loose.getBody().get("lines")).get(0)).get("paperRxLineId");
        assertProblem(otc(c.token(), x[1], 1, looseLine), HttpStatus.UNPROCESSABLE_CONTENT, "rx-copy-required");
        // ... while a plain Schedule H item may be sold against it
        String[] h = item(c, "Paper Rx Item H", "IN_H");
        assertThat(otc(c.token(), h[1], 1, looseLine).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    // ---- AC4: a photo that fails the scan blocks the sale and says why -----------------------------------------------------

    @Test
    void ac4_infectedPhotoIs422DocumentNotCleanWithTheReasonAndTheSaleCannotComplete() throws Exception {
        Ctx c = ctx();
        String[] rx = item(c, "Paper Rx Item C", "IN_H");
        String[] bad = upload(c.token(), "application/pdf", ("%PDF-1.4\n" + EICAR).getBytes(StandardCharsets.ISO_8859_1));
        assertThat(bad[1]).isEqualTo("infected");

        ResponseEntity<Map> r = record(c.token(), rxBody(Map.of("itemId", rx[0], "qty", 5), bad[0], false));
        assertProblem(r, HttpStatus.UNPROCESSABLE_CONTENT, "document-not-clean");
        assertThat((String) r.getBody().get("detail")).contains("virus scan");
        assertThat(r.getBody().get("scanStatus")).isEqualTo("infected");
        assertThat(su().queryForObject("SELECT count(*) FROM paper_prescriptions WHERE tenant_id = ?", Integer.class, c.tenant().id())).isZero();
        assertProblem(otc(c.token(), rx[1], 1, null), HttpStatus.UNPROCESSABLE_CONTENT, "rx-required"); // nothing to link: no sale
        assertThat(su().queryForObject("SELECT count(*) FROM invoices WHERE tenant_id = ?", Integer.class, c.tenant().id())).isZero();

        // re-checked at the sale: a photo that stops being clean after the record was made blocks the bill too
        String[] good = upload(c.token(), "image/png", PNG);
        String[] paper = recorded(c, rx[0], 5, good[0], false);
        su().update("UPDATE file_objects SET status = 'infected' WHERE id = ?::uuid", good[0]);
        ResponseEntity<Map> sale = otc(c.token(), rx[1], 1, paper[1]);
        assertProblem(sale, HttpStatus.UNPROCESSABLE_CONTENT, "document-not-clean");
        assertThat((String) sale.getBody().get("detail")).contains("virus scan");
    }

    // ---- AC5: pharmacy roles only, signed short-lived URL, every view audited ------------------------------------------

    @Test
    void ac5_photoUrlNeedsPharmacyDispenseIsShortLivedAndEveryViewIsAudited() throws Exception {
        Ctx c = ctx();
        String[] rx = item(c, "Paper Rx Item D", "IN_H");
        String photo = upload(c.token(), "image/png", PNG)[0];
        String[] paper = recorded(c, rx[0], 5, photo, false);

        ResponseEntity<Map> url = exchange(BASE + "/" + paper[0] + "/photo-url", HttpMethod.POST, authed(c.token()), Map.class);
        assertThat(url.getStatusCode()).as("%s", url.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(Instant.parse((String) url.getBody().get("expiresAt"))).isBefore(Instant.now().plusSeconds(61));
        assertThat(http.exchange(URI.create(url((String) url.getBody().get("url"))), HttpMethod.GET, null, byte[].class).getBody()).isEqualTo(PNG);
        exchange(BASE + "/" + paper[0] + "/photo-url", HttpMethod.POST, authed(c.token()), Map.class);
        assertThat(audits(c.tenant().id(), "pharmacy.paper_rx.photo_viewed")).isEqualTo(2);
        assertThat(su().queryForObject("SELECT actor_id FROM audit_log WHERE tenant_id = ? AND action = 'pharmacy.paper_rx.photo_viewed' LIMIT 1",
                UUID.class, c.tenant().id())).isEqualTo(c.staff().id());

        // a clinical role (every clinical grant, pharmacy view + create but not edit, so no pharmacy:dispense) gets nothing
        String doctor = tokenFor(c, fullGrant("clinical"), fullGrant("queue"), fullGrant("patients"),
                new ModuleGrant("pharmacy", true, true, false, false, false, false, false));
        assertThat(exchange(BASE + "/" + paper[0] + "/photo-url", HttpMethod.POST, authed(doctor), Map.class).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(exchange(BASE + "/uploads/" + photo, HttpMethod.GET, authed(doctor), Map.class).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(record(doctor, rxBody(Map.of("itemId", rx[0], "qty", 1), null, false)).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        // ... and the generic file paths do not know the file: no status, no download URL, no visit attachment
        assertThat(exchange("/v1/files/" + photo, HttpMethod.GET, authed(doctor), Map.class).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(exchange("/v1/files/" + photo + "/download-url", HttpMethod.POST, authed(doctor), Map.class).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        String patient = (String) exchange("/v1/patients", HttpMethod.POST, authedJsonBody(c.token(), Map.of(
                "name", "Paper Rx Patient", "phone", phone(), "dob", "1990-01-01", "gender", "male")), Map.class).getBody().get("id");
        String visit = (String) exchange("/v1/queue/check-in", HttpMethod.POST, authedJsonBody(c.token(), Map.of(
                "patientId", patient, "doctorId", c.staff().id().toString())), Map.class).getBody().get("id");
        assertThat(exchange("/v1/clinical/visits/" + visit + "/attachments", HttpMethod.POST,
                authedJsonBody(c.token(), Map.of("fileId", photo, "kind", "photo")), Map.class).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(su().queryForObject("SELECT patient_id FROM file_objects WHERE id = ?::uuid", UUID.class, photo)).isNull();
        assertThat(audits(c.tenant().id(), "pharmacy.paper_rx.photo_viewed")).isEqualTo(2); // refused attempts are not views

        // another clinic's pharmacist: the record does not exist
        Ctx other = ctx();
        assertThat(exchange(BASE + "/" + paper[0] + "/photo-url", HttpMethod.POST, authed(other.token()), Map.class).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(exchange(BASE + "/uploads/" + photo, HttpMethod.GET, authed(other.token()), Map.class).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    // ---- the link is bound to its object ---------------------------------------------------------------------------------

    @Test
    void aPaperLineIsBoundToItsItemItsTenantAndOnePhoto() throws Exception {
        Ctx c = ctx();
        String[] a = item(c, "Paper Rx Item E", "IN_H");
        String[] b = item(c, "Paper Rx Item F", "IN_H");
        String photo = upload(c.token(), "image/png", PNG)[0];
        String[] paper = recorded(c, a[0], 5, photo, false);

        assertProblem(otc(c.token(), b[1], 1, paper[1]), HttpStatus.UNPROCESSABLE_CONTENT, "rx-item-mismatch"); // recorded for item A
        assertProblem(record(c.token(), rxBody(Map.of("itemId", a[0], "qty", 5), photo, false)), HttpStatus.CONFLICT, "document-already-used");

        Map<String, Object> both = new HashMap<>(Map.of("chargeCode", a[1], "quantity", 1, "paperRxLineId", paper[1], "prescriptionItemId", UUID.randomUUID().toString()));
        assertThat(exchange("/v1/billing/otc-checkout", HttpMethod.POST, authedJsonBody(c.token(), Map.of("lineItems", List.of(both))), Map.class)
                .getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        Map<String, Object> future = rxBody(Map.of("itemId", a[0], "qty", 1), null, false);
        future.put("rxDate", LocalDate.now(java.time.ZoneOffset.UTC).plusDays(2).toString());
        assertThat(record(c.token(), future).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(record(c.token(), rxBody(Map.of("qty", 1), null, false)).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST); // neither item nor text

        // another clinic cannot bill against this line, and cannot name this clinic's item or photo in its own record
        Ctx other = ctx();
        String[] theirs = item(other, "Paper Rx Item G", "IN_H");
        ResponseEntity<Map> cross = otc(other.token(), theirs[1], 1, paper[1]);
        assertProblem(cross, HttpStatus.UNPROCESSABLE_CONTENT, "rx-required");
        assertThat(record(other.token(), rxBody(Map.of("itemId", a[0], "qty", 1), null, false)).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        String otherPhoto = upload(c.token(), "image/png", PNG)[0];
        assertProblem(record(other.token(), rxBody(Map.of("itemId", theirs[0], "qty", 1), otherPhoto, false)), HttpStatus.UNPROCESSABLE_CONTENT, "document-not-clean");
        assertThat(su().queryForObject("SELECT count(*) FROM invoice_line_items WHERE paper_rx_line_id = ?::uuid", Integer.class, paper[1])).isZero();
    }

    /** A validity rule on the class (none is seeded: the periods await clinical sign-off) expires the paper Rx like a Nabd Rx. */
    @Test
    void aPaperRxPastItsRecordedValidityIs422RxExpired() {
        Ctx c = ctx();
        String[] rx = item(c, "Paper Rx Item V", "IN_H");
        String[] paper = recorded(c, rx[0], 5, null, false);
        su().update("UPDATE paper_prescriptions SET valid_until = rx_date - 1 WHERE id = ?::uuid", paper[0]);
        assertProblem(otc(c.token(), rx[1], 1, paper[1]), HttpStatus.UNPROCESSABLE_CONTENT, "rx-expired");
    }
}
