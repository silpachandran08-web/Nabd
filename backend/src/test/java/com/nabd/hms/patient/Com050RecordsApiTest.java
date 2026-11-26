package com.nabd.hms.patient;

import com.nabd.hms.patient.api.PatientRefs;
import com.nabd.hms.patient.internal.PhoneReverificationService;
import com.nabd.hms.support.ApiTestBase;
import com.nabd.hms.support.ClinicianFixture;
import com.nabd.hms.support.InternalTestTokens;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.JwtEncoder;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * COM-050 (Core side): the record index and the chat-issued secure link. Who may see whose items is decided here from the verified
 * phone: the patient, a guardian's minors and an accepted records proxy; nobody else, in this clinic or another. Answers and audit rows
 * carry a reference, a type and a date only.
 */
@SuppressWarnings({"rawtypes", "unchecked"})
class Com050RecordsApiTest extends ApiTestBase {

    private static final SecureRandom RND = new SecureRandom();
    private static final String DRUG = "Zyloprimsecret 300 mg";
    private static final String REPORT_AT = "2026-10-12T10:00:00Z";
    @Autowired JwtEncoder encoder;
    @Autowired PatientRefs refs;
    private final JdbcTemplate su = new JdbcTemplate(superuserDataSource());

    private record Clinic(SeededTenant t, SeededStaff doctor, String staff, String edge) {
        UUID id() {
            return t.id();
        }
    }

    private record Person(UUID id, String hash, String ref) {
    }

    private static String phone() {
        return "+9193" + String.format("%08d", RND.nextInt(100_000_000));
    }

    private Clinic clinic() {
        SeededTenant t = seedTenant();
        SeededStaff doctor = ClinicianFixture.licensed(seedStaff(t, seedFullAccessRole(t.id()), "c50" + UUID.randomUUID().toString().substring(0, 8) + "@a.com", phone(), false));
        return new Clinic(t, doctor, loginAndGetAccessToken(doctor), InternalTestTokens.mint(encoder, "patient-access-edge", t.id()));
    }

    private Person person(Clinic c, String name, String dob, String phone, UUID guardian) {
        UUID id = inTenantTx(c.id(), () -> jdbc.queryForObject("INSERT INTO patients (tenant_id, name, phone, dob, gender, guardian_id) VALUES (?,?,?,?::date,'other',?) RETURNING id",
                UUID.class, c.id(), name, phone, dob, guardian));
        return new Person(id, PhoneReverificationService.hashOf(phone), inTenantTx(c.id(), () -> refs.refFor(id)));
    }

    private Person adult(Clinic c, String name) {
        return person(c, name, "1985-03-07", phone(), null);
    }

    private void proxy(Clinic c, Person caregiver, Person subject, String scope, String status) {
        inTenantTx(c.id(), () -> jdbc.update("INSERT INTO patient_proxies (tenant_id, proxy_patient_id, subject_patient_id, relation, scope, status, accepted_at) VALUES (?,?,?,'daughter',?,?,?)",
                c.id(), caregiver.id(), subject.id(), scope, status, "active".equals(status) ? java.sql.Timestamp.from(Instant.now()) : null));
    }

    private UUID visit(Clinic c, Person p) {
        ResponseEntity<Map> r = exchange("/v1/queue/check-in", HttpMethod.POST, authedJsonBody(c.staff(), Map.of("patientId", p.id().toString(), "doctorId", c.doctor().id().toString())), Map.class);
        assertThat(r.getStatusCode().is2xxSuccessful()).as("check-in %s", r.getBody()).isTrue();
        return UUID.fromString((String) r.getBody().get("id"));
    }

    /** An outside report attached to the visit; returns its item reference. */
    private String report(Clinic c, Person p, UUID visit, String at) {
        UUID file = UUID.randomUUID();
        inTenantTx(c.id(), () -> jdbc.update("INSERT INTO file_objects (id, tenant_id, purpose, storage_key, declared_type, detected_type, size_bytes, status, patient_id) "
                + "VALUES (?,?,'outside_report',?,'application/pdf','application/pdf',1000,'available',?)", file, c.id(), "tenant/" + c.id() + "/" + file, p.id()));
        UUID id = inTenantTx(c.id(), () -> jdbc.queryForObject("INSERT INTO visit_attachments (tenant_id, visit_id, patient_id, file_id, kind, created_at) "
                + "VALUES (?,?,?,?,'outside_report',?::timestamptz) RETURNING id", UUID.class, c.id(), visit, p.id(), file, at));
        return "r" + id.toString().replace("-", "");
    }

    private String report(Clinic c, Person p) {
        return report(c, p, visit(c, p), REPORT_AT);
    }

    /** A signed prescription of the visit (free-text drug DRUG); returns its item reference. */
    private String prescription(Clinic c, UUID visit) {
        ResponseEntity<Map> saved = exchange("/v1/clinical/prescriptions/" + visit, HttpMethod.PATCH, authedJsonBody(c.staff(), Map.of("items",
                List.of(Map.of("drugName", DRUG, "dosage", "1 tab", "frequency", "BD", "quantity", 10, "quantityUnit", "tablets")))), Map.class);
        assertThat(saved.getStatusCode()).as("rx %s", saved.getBody()).isEqualTo(HttpStatus.OK);
        ResponseEntity<Map> signed = exchange("/v1/clinical/prescriptions/" + visit + "/sign", HttpMethod.POST, authed(c.staff()), Map.class);
        assertThat(signed.getStatusCode()).as("sign %s", signed.getBody()).isEqualTo(HttpStatus.OK);
        return "p" + su.queryForObject("SELECT id FROM prescriptions WHERE queue_entry_id = ?", UUID.class, visit).toString().replace("-", "");
    }

    /** A real checkout of one consultation for the visit; returns the invoice's item reference. */
    private String invoice(Clinic c, UUID visit) {
        inTenantTx(c.id(), () -> jdbc.update("INSERT INTO charge_catalogue (tenant_id, code, name, category, base_amount, tax_rate_percent) VALUES (?,'CONS','Cat CONS','Consultation',400,0)", c.id()));
        for (String s : new String[]{"waiting", "vitals_pending", "vitals_done", "in_consult", "checkout_pending"}) {
            exchange("/v1/queue/" + visit + "/status", HttpMethod.PATCH, authedJsonBody(c.staff(), Map.of("status", s)), Map.class);
        }
        ResponseEntity<Map> r = exchange("/v1/billing/checkout/" + visit, HttpMethod.POST, authedJsonBody(c.staff(),
                Map.of("lineItems", List.of(new HashMap<>(Map.of("chargeCode", "CONS", "quantity", 1))))), Map.class);
        assertThat(r.getStatusCode().is2xxSuccessful()).as("checkout %s", r.getBody()).isTrue();
        return "i" + ((String) r.getBody().get("id")).replace("-", "");
    }

    private static HttpHeaders bearer(String token) {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(token);
        return h;
    }

    private String base(Clinic c) {
        return "/internal/v1/tenants/" + c.id();
    }

    private ResponseEntity<Map> subjects(Clinic c, String hash) {
        return exchange(base(c) + "/households/" + hash + "/record-subjects", HttpMethod.GET, new HttpEntity<>(bearer(c.edge())), Map.class);
    }

    private List<String> subjectRefs(Clinic c, String hash) {
        ResponseEntity<Map> r = subjects(c, hash);
        assertThat(r.getStatusCode()).as("%s", r.getBody()).isEqualTo(HttpStatus.OK);
        return ((List<Map<String, Object>>) r.getBody().get("subjects")).stream().map(s -> (String) s.get("patientRef")).toList();
    }

    private ResponseEntity<Map> items(Clinic c, String ref, String hash) {
        return exchange(base(c) + "/patients/" + ref + "/record-items?phoneHash=" + hash, HttpMethod.GET, new HttpEntity<>(bearer(c.edge())), Map.class);
    }

    private List<Map<String, Object>> listed(Clinic c, Person subject, String hash) {
        ResponseEntity<Map> r = items(c, subject.ref(), hash);
        assertThat(r.getStatusCode()).as("%s", r.getBody()).isEqualTo(HttpStatus.OK);
        return (List<Map<String, Object>>) r.getBody().get("items");
    }

    private ResponseEntity<Map> link(Clinic c, String itemRef, String hash, String key, Map<String, Object> extra) {
        Map<String, Object> body = new HashMap<>(extra);
        body.put("itemRef", itemRef);
        body.put("recipientPhoneHash", hash);
        org.springframework.http.HttpHeaders h = new org.springframework.http.HttpHeaders(); // no key at all when null: authedJsonBody adds a random one
        h.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);
        h.setBearerAuth(c.edge());
        if (key != null) {
            h.set("Idempotency-Key", key);
        }
        return exchange(base(c) + "/secure-links", HttpMethod.POST, new HttpEntity<>(authedJsonBody(c.edge(), body).getBody(), h), Map.class);
    }

    private ResponseEntity<Map> link(Clinic c, String itemRef, String hash) {
        return link(c, itemRef, hash, UUID.randomUUID().toString(), Map.of());
    }

    /** The token of a link the endpoint just issued (the last path segment of linkUrl). */
    private String token(ResponseEntity<Map> issued) {
        assertThat(issued.getStatusCode()).as("%s", issued.getBody()).isEqualTo(HttpStatus.CREATED);
        String url = (String) issued.getBody().get("linkUrl");
        assertThat(url).contains("/l/");
        return url.substring(url.lastIndexOf('/') + 1);
    }

    private static String sha(String s) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
    }

    private Map<String, Object> row(String token) throws Exception {
        return su.queryForMap("SELECT * FROM secure_links WHERE token_hash = ?", sha(token));
    }

    private static HttpHeaders browser() {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        h.set("User-Agent", "com050-test/1.0");
        return h;
    }

    private ResponseEntity<Map> otp(String token) {
        return exchange("/v1/public/links/" + token + "/otp", HttpMethod.POST, new HttpEntity<>(browser()), Map.class);
    }

    /** Opens with a code we set ourselves (the real one goes to the patient's phone). */
    private ResponseEntity<Map> open(String token) throws Exception {
        UUID id = (UUID) row(token).get("id");
        su.update("UPDATE secure_links SET otp_hash = ?, otp_expires_at = now() + interval '5 minutes' WHERE id = ?", sha(id + ":123456"), id);
        return exchange("/v1/public/links/" + token + "/open", HttpMethod.POST, new HttpEntity<>(Map.of("otp", "123456"), browser()), Map.class);
    }

    private List<String> audits(Clinic c, String action, UUID patient) {
        return su.queryForList("SELECT after::text FROM audit_log WHERE tenant_id = ? AND action = ? AND entity_type = 'patient' AND entity_id = ? ORDER BY created_at",
                String.class, c.id(), action, patient);
    }

    private static String code(ResponseEntity<Map> r) {
        return String.valueOf(r.getBody().get("type"));
    }

    // ---- criteria 1, 2, 5, 6: own items, type and date only, an OTP link, both audited --------------------------------------------

    @Test
    void aPatientListsTheirOwnItemsAsTypeAndDateOnlyAndGetsAnOtpLinkAndBothAreAudited() throws Exception {
        Clinic c = clinic();
        Person asha = adult(c, "Asha Rao");
        UUID visit = visit(c, asha);
        String report = report(c, asha, visit, REPORT_AT);
        String rx = prescription(c, visit);

        ResponseEntity<Map> list = items(c, asha.ref(), asha.hash());
        assertThat(list.getStatusCode()).as("%s", list.getBody()).isEqualTo(HttpStatus.OK);
        List<Map<String, Object>> items = (List<Map<String, Object>>) list.getBody().get("items");
        assertThat(items).extracting(i -> i.get("itemRef")).as("newest first").containsExactly(rx, report);
        assertThat(items).extracting(i -> i.get("type")).containsExactly("prescription", "report");
        assertThat(items.get(1)).as("a reference, a type and a date, nothing else").containsOnlyKeys("itemRef", "type", "date").containsEntry("date", "2026-10-12");
        assertThat((String) items.get(0).get("date")).matches("\\d{4}-\\d{2}-\\d{2}");
        assertThat(list.getBody().toString()).doesNotContain("Zyloprim").doesNotContain("Asha");
        List<String> listAudit = audits(c, "records.listed", asha.id());
        assertThat(listAudit).hasSize(1);
        assertThat(listAudit.get(0)).contains(asha.hash()).contains("whatsapp").contains(rx).contains(report).doesNotContain("Zyloprim");

        ResponseEntity<Map> issued = link(c, rx, asha.hash());
        String token = token(issued);
        assertThat(issued.getBody()).containsOnlyKeys("linkUrl", "expiresAt", "type", "date").containsEntry("type", "prescription");
        Instant expires = Instant.parse((String) issued.getBody().get("expiresAt"));
        assertThat(Duration.between(Instant.now(), expires)).as("default lifetime 60 minutes").isBetween(Duration.ofMinutes(58), Duration.ofMinutes(61));
        Map<String, Object> link = row(token);
        assertThat(link).containsEntry("document_type", "rx").containsEntry("patient_id", asha.id()).containsEntry("recipient_patient_id", asha.id());
        assertThat(link.get("created_by")).as("no staff creator").isNull();
        Map<String, Object> file = su.queryForMap("SELECT f.purpose, f.patient_id, t.pdf_a4_file_id FROM prescription_rx_tokens t JOIN file_objects f ON f.id = t.pdf_a4_file_id "
                + "WHERE t.tenant_id = ?", c.id());
        assertThat(file).as("the link opens the stored A4 prescription").containsEntry("purpose", "prescription").containsEntry("patient_id", asha.id())
                .containsEntry("pdf_a4_file_id", link.get("document_id"));
        assertThat(su.queryForObject("SELECT actor_type FROM audit_log WHERE tenant_id = ? AND action = 'prescription.pdf_stored'", String.class, c.id())).isEqualTo("system");
        List<String> linkAudit = audits(c, "records.link_issued", asha.id());
        assertThat(linkAudit).hasSize(1);
        assertThat(linkAudit.get(0)).contains(asha.hash()).contains("whatsapp").contains(rx).contains(link.get("id").toString()).doesNotContain("Zyloprim").doesNotContain(token);

        assertThat(otp(token).getStatusCode()).as("the link asks for a code on the patient's own number").isEqualTo(HttpStatus.ACCEPTED);
        assertThat(exchange("/v1/public/links/" + token + "/open", HttpMethod.POST, new HttpEntity<>(Map.of("otp", "000000"), browser()), Map.class).getStatusCode())
                .as("no document without the code").isEqualTo(HttpStatus.UNAUTHORIZED);
        ResponseEntity<Map> opened = open(token);
        assertThat(opened.getStatusCode()).as("%s", opened.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(opened.getBody()).containsEntry("title", "Prescription").containsEntry("mime", "application/pdf");
        assertThat((String) opened.getBody().get("fileUrl")).isNotBlank();

        // a second link to the same prescription reuses the stored PDF
        token(link(c, rx, asha.hash()));
        assertThat(su.queryForObject("SELECT count(*) FROM file_objects WHERE tenant_id = ? AND purpose = 'prescription'", Integer.class, c.id())).isEqualTo(1);
    }

    @Test
    void theListIsTheNewestTenAcrossTypesAndALinkLifetimeIsAtMostADay() throws Exception {
        Clinic c = clinic();
        Person asha = adult(c, "Asha Rao");
        UUID visit = visit(c, asha);
        List<String> reports = new ArrayList<>();
        for (int day = 1; day <= 12; day++) {
            reports.add(report(c, asha, visit, "2026-09-" + String.format("%02d", day) + "T10:00:00Z"));
        }
        List<Map<String, Object>> items = listed(c, asha, asha.hash());
        assertThat(items).hasSize(10);
        assertThat(items).extracting(i -> i.get("itemRef")).as("the two oldest are left out").doesNotContain(reports.get(0), reports.get(1)).startsWith(reports.get(11), reports.get(10));
        ResponseEntity<Map> three = exchange(base(c) + "/patients/" + asha.ref() + "/record-items?limit=3&phoneHash=" + asha.hash(), HttpMethod.GET, new HttpEntity<>(bearer(c.edge())), Map.class);
        assertThat((List<?>) three.getBody().get("items")).hasSize(3);
        ResponseEntity<Map> many = exchange(base(c) + "/patients/" + asha.ref() + "/record-items?limit=500&phoneHash=" + asha.hash(), HttpMethod.GET, new HttpEntity<>(bearer(c.edge())), Map.class);
        assertThat((List<?>) many.getBody().get("items")).as("never more than 10").hasSize(10);

        assertThat(link(c, reports.get(11), asha.hash(), UUID.randomUUID().toString(), Map.of("ttlMinutes", 1441)).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        ResponseEntity<Map> day = link(c, reports.get(11), asha.hash(), UUID.randomUUID().toString(), Map.of("ttlMinutes", 1440));
        token(day);
        assertThat(Duration.between(Instant.now(), Instant.parse((String) day.getBody().get("expiresAt")))).isBetween(Duration.ofMinutes(1438), Duration.ofMinutes(1441));
        assertThat(day.getBody()).containsEntry("type", "report").containsEntry("date", "2026-09-12");
    }

    // ---- criterion 3: the guardian's children and accepted records proxies, never anyone out of scope ------------------------------

    @Test
    void aGuardianSeesEachChildAndAnAcceptedRecordsProxyAndNobodyOutOfScope() throws Exception {
        Clinic c = clinic();
        Person asha = adult(c, "Asha Rao");
        Person aarav = person(c, "Aarav Rao", java.time.LocalDate.now().minusYears(6).toString(), phone(), asha.id()); // the child's record carries an older number
        Person ravi = adult(c, "Ravi Rao");      // father, gave Asha FULL
        Person meera = adult(c, "Meera Rao");    // gave Asha booking only
        Person dev = adult(c, "Dev Rao");        // has not answered Asha's request yet
        Person zed = adult(c, "Zed Khan");       // no relation at all
        proxy(c, asha, ravi, "FULL", "active");
        proxy(c, asha, meera, "BOOK_ONLY", "active");
        proxy(c, asha, dev, null, "pending");
        String ashaReport = report(c, asha), aaravReport = report(c, aarav), meeraReport = report(c, meera), devReport = report(c, dev), zedReport = report(c, zed);
        UUID raviVisit = visit(c, ravi);
        String raviReport = report(c, ravi, raviVisit, REPORT_AT);
        String raviInvoice = invoice(c, raviVisit);

        ResponseEntity<Map> who = subjects(c, asha.hash());
        assertThat(who.getStatusCode()).isEqualTo(HttpStatus.OK);
        List<Map<String, Object>> subjects = (List<Map<String, Object>>) who.getBody().get("subjects");
        assertThat(subjects).extracting(s -> s.get("patientRef")).as("herself first, then the child, then the accepted records proxy; nobody else")
                .containsExactly(asha.ref(), aarav.ref(), ravi.ref());
        assertThat(subjects).extracting(s -> s.get("relation")).containsExactly("self", "dependent", "proxy");
        assertThat(subjects).extracting(s -> s.get("firstName")).containsExactly("Asha", "Aarav", "Ravi");
        assertThat(subjects.get(0)).containsOnlyKeys("patientRef", "firstName", "relation");

        // the child: listed, and the link is addressed to the guardian (the code goes to the phone that asked)
        assertThat(listed(c, aarav, asha.hash())).extracting(i -> i.get("itemRef")).containsExactly(aaravReport);
        assertThat(row(token(link(c, aaravReport, asha.hash())))).containsEntry("patient_id", aarav.id()).containsEntry("recipient_patient_id", asha.id()).containsEntry("document_type", "report");
        // the father through the FULL proxy: his report and his bill
        List<Map<String, Object>> father = listed(c, ravi, asha.hash());
        assertThat(father).extracting(i -> i.get("itemRef")).containsExactly(raviInvoice, raviReport);
        assertThat(father).extracting(i -> i.get("type")).containsExactly("invoice", "report");
        assertThat(father).as("no charge name, code or amount").allSatisfy(i -> assertThat(i).containsOnlyKeys("itemRef", "type", "date"));
        ResponseEntity<Map> bill = link(c, raviInvoice, asha.hash());
        Map<String, Object> billLink = row(token(bill));
        assertThat(bill.getBody()).containsEntry("type", "invoice");
        assertThat(billLink).containsEntry("document_type", "invoice").containsEntry("patient_id", ravi.id()).containsEntry("recipient_patient_id", asha.id());
        assertThat(su.queryForMap("SELECT d.kind, d.layout, f.patient_id, f.detected_type FROM billing_documents d JOIN file_objects f ON f.id = d.file_id WHERE d.file_id = ?",
                billLink.get("document_id"))).containsEntry("kind", "invoice").containsEntry("layout", "a4").containsEntry("patient_id", ravi.id()).containsEntry("detected_type", "application/pdf");
        assertThat(su.queryForObject("SELECT count(*) FROM audit_log WHERE tenant_id = ? AND action = 'billing.document_rendered' AND actor_type = 'staff' AND actor_id IS NULL",
                Integer.class, c.id())).as("a render for the patient's own request is not booked on a nameless staff member").isZero();
        ResponseEntity<Map> billOpened = open(token(bill));
        assertThat(billOpened.getStatusCode()).as("%s", billOpened.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(billOpened.getBody()).containsEntry("title", "Invoice");
        token(link(c, raviInvoice, asha.hash()));
        assertThat(su.queryForObject("SELECT count(*) FROM file_objects WHERE tenant_id = ? AND purpose = 'claim_pack'", Integer.class, c.id())).as("one stored copy per invoice version").isEqualTo(1);
        assertThat(audits(c, "records.link_issued", ravi.id())).hasSize(2).allSatisfy(a -> assertThat(a).contains(asha.hash()).contains(raviInvoice).contains(asha.id().toString()));

        // out of scope: booking-only, pending, unrelated, unknown. The same 403 for the list, the same 404 for a link, and nothing is written.
        int links = su.queryForObject("SELECT count(*) FROM secure_links WHERE tenant_id = ?", Integer.class, c.id());
        for (Person other : List.of(meera, dev, zed)) {
            ResponseEntity<Map> refused = items(c, other.ref(), asha.hash());
            assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
            assertThat(code(refused)).endsWith("NOT_IN_PROXY_SCOPE");
            assertThat(refused.getBody().toString()).doesNotContain("itemRef");
            assertThat(audits(c, "records.listed", other.id())).isEmpty();
        }
        ResponseEntity<Map> unknown = items(c, "no-such-ref", asha.hash());
        assertThat(unknown.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(code(unknown)).as("an unknown reference answers like a known one").endsWith("NOT_IN_PROXY_SCOPE");
        for (String theirs : List.of(meeraReport, devReport, zedReport, "r" + UUID.randomUUID().toString().replace("-", ""), "p" + UUID.randomUUID().toString().replace("-", ""))) {
            assertThat(link(c, theirs, asha.hash()).getStatusCode()).as(theirs).isEqualTo(HttpStatus.NOT_FOUND);
        }
        assertThat(su.queryForObject("SELECT count(*) FROM secure_links WHERE tenant_id = ?", Integer.class, c.id())).as("no link for anyone out of scope").isEqualTo(links);
        assertThat(su.queryForObject("SELECT count(*) FROM audit_log WHERE tenant_id = ? AND action = 'records.link_issued' AND entity_id IN (?,?,?)", Integer.class,
                c.id(), meera.id(), dev.id(), zed.id())).isZero();

        // scope runs one way: the father, the child's own number and the stranger do not see Asha
        assertThat(subjectRefs(c, ravi.hash())).containsExactly(ravi.ref());
        assertThat(subjectRefs(c, aarav.hash())).as("a minor on their own number sees only themself").containsExactly(aarav.ref());
        assertThat(subjectRefs(c, zed.hash())).containsExactly(zed.ref());
        for (Person other : List.of(ravi, aarav, zed)) {
            assertThat(items(c, asha.ref(), other.hash()).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
            assertThat(link(c, ashaReport, other.hash()).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        }
        assertThat(subjectRefs(c, PhoneReverificationService.hashOf(phone()))).as("a number the clinic does not know").isEmpty();
    }

    // ---- a link issued in chat dies with the scope it was issued on ----------------------------------------------------------------

    @Test
    void aChatLinkStopsWorkingWhenTheProxyIsRevokedTheChildTurns18OrConsentIsWithdrawn() throws Exception {
        Clinic c = clinic();
        Person asha = adult(c, "Asha Rao");
        Person aarav = person(c, "Aarav Rao", java.time.LocalDate.now().minusYears(17).toString(), phone(), asha.id());
        Person ravi = adult(c, "Ravi Rao");
        proxy(c, asha, ravi, "BOOK_AND_RECORDS", "active");
        String fatherLink = token(link(c, report(c, ravi), asha.hash()));
        String childLink = token(link(c, report(c, aarav), asha.hash()));
        String ownReport = report(c, asha);
        String ownLink = token(link(c, ownReport, asha.hash()));
        assertThat(open(fatherLink).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(open(childLink).getStatusCode()).isEqualTo(HttpStatus.OK);

        su.update("UPDATE patient_proxies SET status = 'revoked', revoked_at = now(), revoked_by = 'patient' WHERE tenant_id = ? AND subject_patient_id = ?", c.id(), ravi.id());
        assertThat(subjectRefs(c, asha.hash())).doesNotContain(ravi.ref());
        assertThat(items(c, ravi.ref(), asha.hash()).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        ResponseEntity<Map> dead = open(fatherLink);
        assertThat(dead.getStatusCode()).as("the link issued before the revocation").isEqualTo(HttpStatus.GONE);
        assertThat(otp(fatherLink).getStatusCode()).as("and no code is sent for it").isEqualTo(HttpStatus.GONE);

        su.update("UPDATE patients SET dob = ? WHERE id = ?", java.sql.Date.valueOf(java.time.LocalDate.now().minusYears(18).minusDays(1)), aarav.id());
        assertThat(subjectRefs(c, asha.hash())).as("an adult child is no longer in the guardian's list").containsExactly(asha.ref());
        assertThat(items(c, aarav.ref(), asha.hash()).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(open(childLink).getStatusCode()).isEqualTo(HttpStatus.GONE);

        assertThat(open(ownLink).getStatusCode()).isEqualTo(HttpStatus.OK);
        inTenantTx(c.id(), () -> jdbc.update("INSERT INTO consents (tenant_id, patient_id, consent_type, withdrawn_at) VALUES (?,?,'data_processing', now())", c.id(), asha.id()));
        assertThat(subjectRefs(c, asha.hash())).as("no processing consent, no records in chat").isEmpty();
        assertThat(items(c, asha.ref(), asha.hash()).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(link(c, ownReport, asha.hash()).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(open(ownLink).getStatusCode()).isEqualTo(HttpStatus.GONE);
    }

    @Test
    void aRevokedPrescriptionIsNeitherListedNorLinked() throws Exception {
        Clinic c = clinic();
        Person asha = adult(c, "Asha Rao");
        String rx = prescription(c, visit(c, asha));
        assertThat(listed(c, asha, asha.hash())).extracting(i -> i.get("itemRef")).containsExactly(rx);
        su.update("UPDATE prescription_rx_tokens SET revoked_at = now(), revoke_reason = 'test', revoked_by = ? WHERE tenant_id = ?", c.doctor().id(), c.id());
        assertThat(listed(c, asha, asha.hash())).isEmpty();
        assertThat(link(c, rx, asha.hash()).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    // ---- criterion 4: a number flagged for re-verification gets nothing --------------------------------------------------------------

    @Test
    void aNumberFlaggedForReverificationGetsNoListAndNoLinkUntilItIsVerifiedAgain() throws Exception {
        Clinic c = clinic();
        Person asha = adult(c, "Asha Rao");
        String report = report(c, asha);
        String reverify = base(c) + "/households/" + asha.hash() + "/reverification";
        assertThat(exchange(reverify, HttpMethod.POST, authedJsonBody(c.edge(), Map.of("step", "flagged", "reason", "changed_number")), Map.class).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        for (ResponseEntity<Map> r : List.of(subjects(c, asha.hash()), items(c, asha.ref(), asha.hash()), link(c, report, asha.hash()))) {
            assertThat(r.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
            assertThat(code(r)).endsWith("PHONE_REVERIFY_REQUIRED");
        }
        assertThat(audits(c, "records.listed", asha.id())).isEmpty();
        assertThat(su.queryForObject("SELECT count(*) FROM secure_links WHERE tenant_id = ?", Integer.class, c.id())).isZero();

        assertThat(exchange(reverify, HttpMethod.POST, authedJsonBody(c.edge(), Map.of("step", "verified", "method", "otp+dob")), Map.class).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(listed(c, asha, asha.hash())).hasSize(1);
        token(link(c, report, asha.hash()));
    }

    // ---- the caller: the Edge's service identity for this clinic only; a link is issued once per key --------------------------------

    @Test
    void onlyTheEdgeOfThisClinicMayAskAndARepeatedRequestIssuesOneLink() throws Exception {
        Clinic c = clinic(), other = clinic();
        Person asha = adult(c, "Asha Rao");
        String report = report(c, asha);
        Person theirs = adult(other, "Bina Shah");
        String theirReport = report(other, theirs);

        // another clinic's Edge token on this clinic's path; a staff token; no token
        String path = base(c) + "/patients/" + asha.ref() + "/record-items?phoneHash=" + asha.hash();
        assertThat(exchange(path, HttpMethod.GET, new HttpEntity<>(bearer(other.edge())), Map.class).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(exchange(path, HttpMethod.GET, new HttpEntity<>(bearer(c.staff())), Map.class).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(exchange(path, HttpMethod.GET, HttpEntity.EMPTY, Map.class).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(exchange(base(c) + "/secure-links", HttpMethod.POST, authedJsonBodyWithHeader(other.edge(), "Idempotency-Key", UUID.randomUUID().toString(),
                Map.of("itemRef", report, "recipientPhoneHash", asha.hash())), Map.class).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        // this clinic's Edge cannot reach the other clinic's patient or item, even with the right phone
        assertThat(items(c, theirs.ref(), theirs.hash()).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(link(c, theirReport, theirs.hash()).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(subjectRefs(c, theirs.hash())).isEmpty();
        // malformed input
        assertThat(items(c, asha.ref(), "not-a-hash").getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(link(c, "x" + report.substring(1), asha.hash()).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(link(c, report, asha.hash(), null, Map.of()).getStatusCode()).as("Idempotency-Key is required").isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(su.queryForObject("SELECT count(*) FROM secure_links WHERE tenant_id IN (?,?)", Integer.class, c.id(), other.id())).isZero();

        String key = UUID.randomUUID().toString();
        ResponseEntity<Map> first = link(c, report, asha.hash(), key, Map.of());
        ResponseEntity<Map> again = link(c, report, asha.hash(), key, Map.of());
        assertThat(token(again)).as("the same key replays the same link").isEqualTo(token(first));
        assertThat(su.queryForObject("SELECT count(*) FROM secure_links WHERE tenant_id = ?", Integer.class, c.id())).isEqualTo(1);
        assertThat(audits(c, "records.link_issued", asha.id())).hasSize(1);
        assertThat(inTenantTx(other.id(), () -> jdbc.queryForObject("SELECT count(*) FROM secure_links", Integer.class))).as("tenant isolation of the table").isZero();
    }
}
