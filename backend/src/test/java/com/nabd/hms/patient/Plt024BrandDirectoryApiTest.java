package com.nabd.hms.patient;

import com.nabd.hms.patient.api.PatientRefs;
import com.nabd.hms.support.ApiTestBase;
import com.nabd.hms.support.InternalTestTokens;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.JwtEncoder;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PLT-024: a patient registered at one branch is recognised at a sibling branch through an explicit, logged link. Every limit
 * has its negative test here: own brand and region only, brand profile DEMOGRAPHICS, name / sex / year of birth before a link,
 * never an opted-out (or otherwise unshareable) patient, exact mobile + date of birth, both clinics logged, owner-only controls.
 * All names, numbers and dates are synthetic.
 */
@SuppressWarnings({"unchecked", "rawtypes"})
class Plt024BrandDirectoryApiTest extends ApiTestBase {

    private static final AtomicInteger SEQ = new AtomicInteger(24_100_000);
    private static final String DOB = "1988-02-03";

    @Autowired JwtEncoder encoder;
    @Autowired PatientRefs refs;

    /** owner = staff on the built-in Owner role (full access); desk = a receptionist: patients only, not built in. */
    private record Clinic(SeededTenant tenant, SeededStaff owner, String ownerToken, SeededStaff desk, String deskToken) {
        UUID id() {
            return tenant.id();
        }
    }

    private record Chain(SeededOwner account, SeededBrand brand, Clinic a, Clinic b) {
    }

    private static String tag() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    /** A valid Indian mobile as the desk types it (national digits); stored as +91 followed by the same digits. */
    private static String phone() {
        return "97" + SEQ.incrementAndGet();
    }

    private JdbcTemplate su() {
        return new JdbcTemplate(superuserDataSource());
    }

    private Clinic clinic(SeededBrand brand) {
        SeededTenant t = brand == null ? seedTenant() : seedClinicInBrand(brand);
        UUID ownerRole = brand == null ? seedFullAccessRole(t.id())
                : inTenantTx(t.id(), () -> jdbc.queryForObject("SELECT id FROM roles WHERE tenant_id = ? AND built_in", UUID.class, t.id()));
        UUID deskRole = seedRole(t.id(), "Front Desk", false, fullGrant("patients"));
        SeededStaff owner = seedStaff(t, ownerRole, "own-" + tag() + "@plt024.test", "+9196" + SEQ.incrementAndGet(), false);
        SeededStaff desk = seedStaff(t, deskRole, "desk-" + tag() + "@plt024.test", "+9196" + SEQ.incrementAndGet(), false);
        return new Clinic(t, owner, loginAndGetAccessToken(owner), desk, loginAndGetAccessToken(desk));
    }

    private void profile(SeededBrand brand, String profile) {
        jdbc.update("UPDATE brands SET chain_sharing_profile = ? WHERE id = ?", profile, brand.id());
    }

    /** One brand with branches A and B, sharing demographics unless a test switches it. */
    private Chain chain() {
        SeededOwner account = seedOwner("owner-" + tag() + "@plt024.test");
        SeededBrand brand = seedBrand(account, "Brand " + tag());
        Chain c = new Chain(account, brand, clinic(brand), clinic(brand));
        profile(brand, "DEMOGRAPHICS");
        return c;
    }

    private UUID register(Clinic c, String name, String phone, String dob, Map<String, Object> extra) {
        Map<String, Object> body = new HashMap<>(Map.of("name", name, "phone", phone, "dob", dob, "gender", "female"));
        body.putAll(extra);
        ResponseEntity<Map> r = exchange("/v1/patients", HttpMethod.POST, authedJsonBody(c.deskToken(), body), Map.class);
        assertThat(r.getStatusCode()).as(String.valueOf(r.getBody())).isEqualTo(HttpStatus.CREATED);
        return UUID.fromString((String) r.getBody().get("id"));
    }

    private UUID register(Clinic c, String phone) {
        return register(c, "Asha Rao", phone, DOB, Map.of());
    }

    private ResponseEntity<String> lookupRaw(String token, String query, String requestId) {
        HttpHeaders h = new HttpHeaders();
        h.putAll(authed(token).getHeaders());
        if (requestId != null) h.set("X-Request-Id", requestId);
        return exchange("/v1/patients/brand-directory?" + query, HttpMethod.GET, new HttpEntity<>(h), String.class);
    }

    private Map<String, Object> lookup(Clinic c, String query) throws Exception {
        ResponseEntity<String> r = lookupRaw(c.deskToken(), query, null);
        assertThat(r.getStatusCode()).as(r.getBody()).isEqualTo(HttpStatus.OK);
        return objectMapper.readValue(r.getBody(), Map.class);
    }

    private List<Map<String, Object>> matches(Clinic c, String phone) throws Exception {
        return (List<Map<String, Object>>) lookup(c, "phone=" + phone).get("matches");
    }

    private ResponseEntity<Map> link(String token, Object sourceTenantId, Object ref, String phone, String dob) {
        return exchange("/v1/patients/brand-links", HttpMethod.POST, authedJsonBody(token, Map.of(
                "sourceTenantId", String.valueOf(sourceTenantId), "sourcePatientRef", String.valueOf(ref), "phone", phone, "dateOfBirth", dob)), Map.class);
    }

    /** Looks the phone up from {@code at} and links the first match with the right date of birth. */
    private UUID linkFirst(Clinic at, String phone) throws Exception {
        Map<String, Object> m = matches(at, phone).get(0);
        ResponseEntity<Map> r = link(at.deskToken(), m.get("sourceTenantId"), m.get("sourcePatientRef"), phone, DOB);
        assertThat(r.getStatusCode()).as(String.valueOf(r.getBody())).isEqualTo(HttpStatus.CREATED);
        return UUID.fromString((String) r.getBody().get("patientId"));
    }

    private int count(String sql, Object... args) {
        return su().queryForObject(sql, Integer.class, args);
    }

    private int links(UUID tenantId) {
        return count("SELECT count(*) FROM patient_branch_links WHERE tenant_id = ?", tenantId);
    }

    private int audits(UUID tenantId, String action) {
        return count("SELECT count(*) FROM audit_log WHERE tenant_id = ? AND action = ?", tenantId, action);
    }

    private int directoryReads(UUID tenantId) {
        return count("SELECT count(*) FROM phi_read_log WHERE tenant_id = ? AND purpose_code = 'brand_directory_lookup'", tenantId);
    }

    // ---- AC1 + AC5: demographics only, logged in both clinics ----------------------------------------------------

    @Test
    void lookupShowsNameSexAndBirthYearOnlyAndIsLoggedInTheSourceAndTheSearchingClinic() throws Exception {
        Chain c = chain();
        String phone = phone();
        UUID atA = register(c.a(), "Asha Rao", phone, DOB, Map.of("address", "12 Synthetic Road", "nationalId", "SYN-0001"));
        String mrn = inTenantTx(c.a().id(), () -> jdbc.queryForObject("SELECT mrn FROM patients WHERE tenant_id = ? AND id = ?", String.class, c.a().id(), atA));

        lookupRaw(c.b().deskToken(), "phone=" + phone, null); // warm-up
        String rid = UUID.randomUUID().toString();
        long t0 = System.nanoTime();
        ResponseEntity<String> r = lookupRaw(c.b().deskToken(), "phone=" + phone, rid);
        long ms = (System.nanoTime() - t0) / 1_000_000;
        assertThat(r.getStatusCode()).as(r.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(ms).as("lookup time in ms (AC1: under 1 s)").isLessThan(1000);

        Map<String, Object> body = objectMapper.readValue(r.getBody(), Map.class);
        assertThat(body.get("checkInPerson")).isEqualTo(false);
        List<Map<String, Object>> found = (List<Map<String, Object>>) body.get("matches");
        assertThat(found).hasSize(1);
        Map<String, Object> m = found.get(0);
        assertThat(m.keySet()).containsExactlyInAnyOrder("sourceTenantId", "branchLabel", "sourcePatientRef", "displayName", "sex", "birthYear");
        assertThat(m).containsEntry("sourceTenantId", c.a().id().toString()).containsEntry("displayName", "Asha Rao")
                .containsEntry("sex", "female").containsEntry("birthYear", 1988);
        assertThat((String) m.get("sourcePatientRef")).matches("[0-9a-f]{64}");
        // nothing else of the record: not its id, MRN, full date of birth, number, address or national id
        assertThat(r.getBody()).doesNotContain(atA.toString()).doesNotContain(mrn).doesNotContain(DOB).doesNotContain(phone)
                .doesNotContain("Synthetic Road").doesNotContain("SYN-0001");

        Map<String, Object> source = su().queryForMap("SELECT * FROM phi_read_log WHERE request_id = ? AND tenant_id = ?", rid, c.a().id());
        assertThat(source).containsEntry("purpose_code", "brand_directory_lookup").containsEntry("patient_id", atA)
                .containsEntry("resource_id", c.b().id()).containsEntry("actor_id", c.b().desk().id()).containsEntry("actor_type", "system");
        Map<String, Object> searching = su().queryForMap("SELECT * FROM phi_read_log WHERE request_id = ? AND tenant_id = ?", rid, c.b().id());
        assertThat(searching).containsEntry("purpose_code", "brand_directory_lookup").containsEntry("resource_id", c.a().id())
                .containsEntry("actor_id", c.b().desk().id()).containsEntry("actor_type", "staff");
        assertThat(searching.get("patient_id")).as("the searching clinic never stores the source patient's id").isNotEqualTo(atA);
        assertThat(audits(c.b().id(), "patient.brand_directory.searched")).isEqualTo(2);

        // AC5: visible on the owner's Compliance and audit screen of both clinics
        List<Map<String, Object>> atSource = (List<Map<String, Object>>) exchange("/v1/patients/brand-directory/lookups", HttpMethod.GET,
                authed(complianceToken(c.a())), Map.class).getBody().get("items");
        assertThat(atSource).isNotEmpty().allSatisfy(i -> assertThat(i).containsEntry("direction", "incoming").containsEntry("kind", "lookup")
                .containsEntry("patientId", atA.toString()).containsEntry("patientName", "Asha Rao"));
        List<Map<String, Object>> atSearching = (List<Map<String, Object>>) exchange("/v1/patients/brand-directory/lookups", HttpMethod.GET,
                authed(complianceToken(c.b())), Map.class).getBody().get("items");
        assertThat(atSearching).isNotEmpty().allSatisfy(i -> {
            assertThat(i).containsEntry("direction", "outgoing").containsEntry("staffName", "Test Staff");
            assertThat(i.get("patientName")).isNull();
        });
        // the log itself needs compliance:view
        assertThat(exchange("/v1/patients/brand-directory/lookups", HttpMethod.GET, authed(c.a().deskToken()), Map.class).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    private String complianceToken(Clinic c) {
        UUID role = seedRole(c.id(), "Compliance " + tag(), false, fullGrant("compliance"));
        return loginAndGetAccessToken(seedStaff(c.tenant(), role, "cmp-" + tag() + "@plt024.test", "+9196" + SEQ.incrementAndGet(), false));
    }

    // ---- AC4: profile NONE ----------------------------------------------------------------------------------------

    @Test
    void profileNoneAnswers403AndNothingIsReadEvenThroughTheDatabaseFunction() throws Exception {
        Chain c = chain();
        String phone = phone();
        register(c.a(), phone);
        Map<String, Object> m = matches(c.b(), phone).get(0); // a reference obtained while sharing was on
        int readsBefore = directoryReads(c.a().id());
        profile(c.brand(), "NONE");

        ResponseEntity<String> r = lookupRaw(c.b().deskToken(), "phone=" + phone, null);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(r.getBody()).contains("DIRECTORY_DISABLED").doesNotContain("Asha");
        assertThat(exchange("/v1/patients/brand-directory/status", HttpMethod.GET, authed(c.b().deskToken()), Map.class).getBody())
                .containsEntry("enabled", false);
        ResponseEntity<Map> l = link(c.b().deskToken(), m.get("sourceTenantId"), m.get("sourcePatientRef"), phone, DOB);
        assertThat(l.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(String.valueOf(l.getBody())).contains("DIRECTORY_DISABLED");

        // the functions themselves refuse, whatever the application does
        List<Map<String, Object>> direct = inTenantTx(c.b().id(), () -> jdbc.queryForList(
                "SELECT * FROM brand_directory_lookup(?, NULL, ?, 'x', 'x', NULL, NULL)", "+91" + phone, c.b().desk().id()));
        assertThat(direct).isEmpty();
        String outcome = inTenantTx(c.b().id(), () -> jdbc.queryForObject(
                "SELECT outcome FROM brand_link_create(?, ?, ?, ?::date, ?, ?, NULL, NULL)", String.class,
                c.a().id(), m.get("sourcePatientRef"), "+91" + phone, DOB, UUID.randomUUID(), c.b().desk().id()));
        assertThat(outcome).isEqualTo("disabled");
        assertThat(directoryReads(c.a().id())).as("no read of branch A while sharing is off").isEqualTo(readsBefore);
        assertThat(links(c.a().id()) + links(c.b().id())).isZero();

        // a clinic with no brand at all
        Clinic alone = clinic(null);
        assertThat(lookupRaw(alone.deskToken(), "phone=" + phone, null).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    // ---- brand and region boundary --------------------------------------------------------------------------------

    @Test
    void patientsOfAnotherBrandOrAnotherRegionAreNeverFoundOrLinked() throws Exception {
        Chain mine = chain();
        Chain other = chain(); // another owner's brand, sharing on as well
        String phone = phone();
        UUID atMine = register(mine.a(), phone);
        UUID atOther = register(other.a(), phone);
        // a clinic of MY brand in another region, holding the same person
        UUID abroad = UUID.randomUUID(); // region_code is immutable, so the clinic is inserted in its region
        jdbc.update("INSERT INTO tenants (id, slug, name, region_code, status, timezone, data_gate, consent_default_deny, day_start_minute, brand_id) "
                + "VALUES (?,?,?,'AE','active','UTC','live',false,0,?)", abroad, "t" + abroad.toString().replace("-", "").substring(0, 12), "Abroad " + tag(), mine.brand().id());
        UUID atAbroad = inTenantTx(abroad, () -> jdbc.queryForObject(
                "INSERT INTO patients (tenant_id, name, phone, dob, gender) VALUES (?, 'Asha Rao', ?, ?::date, 'female') RETURNING id", UUID.class,
                abroad, "+91" + phone, DOB));

        List<Map<String, Object>> found = matches(mine.b(), phone);
        assertThat(found).hasSize(1);
        assertThat(found.get(0)).containsEntry("sourceTenantId", mine.a().id().toString());
        assertThat(matches(other.b(), phone)).hasSize(1).allSatisfy(m -> assertThat(m).containsEntry("sourceTenantId", other.a().id().toString()));
        assertThat(count("SELECT count(*) FROM phi_read_log WHERE purpose_code = 'brand_directory_lookup' AND patient_id IN (?, ?) AND resource_id = ?",
                atOther, atAbroad, mine.b().id())).as("no read of the other brand's or the other region's patient").isZero();

        // even with a correct reference for the other brand's / region's patient, the link answers like an unknown clinic
        for (Object[] foreign : new Object[][]{{other.a().id(), atOther}, {abroad, atAbroad}}) {
            String ref = su().queryForObject("SELECT brand_pair_ref(?, ?, ?)", String.class, foreign[0], mine.b().id(), foreign[1]);
            assertThat(link(mine.b().deskToken(), foreign[0], ref, phone, DOB).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
            String outcome = inTenantTx(mine.b().id(), () -> jdbc.queryForObject(
                    "SELECT outcome FROM brand_link_create(?, ?, ?, ?::date, ?, ?, NULL, NULL)", String.class,
                    foreign[0], ref, "+91" + phone, DOB, UUID.randomUUID(), mine.b().desk().id()));
            assertThat(outcome).isEqualTo("not_found");
        }
        assertThat(links(other.a().id()) + links(abroad) + links(mine.b().id())).isZero();
        assertThat(atMine).isNotNull();

        // the reference is pairwise: the one branch B got does not work from another clinic of the brand
        Clinic third = clinic(mine.brand());
        assertThat(link(third.deskToken(), mine.a().id(), found.get(0).get("sourcePatientRef"), phone, DOB).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        // and without patients:create there is no directory at all
        UUID viewOnly = seedRole(mine.b().id(), "Viewer", false, new com.nabd.hms.common.ModuleGrant("patients", true, false, false, false, false, false, false));
        String viewer = loginAndGetAccessToken(seedStaff(mine.b().tenant(), viewOnly, "view-" + tag() + "@plt024.test", "+9196" + SEQ.incrementAndGet(), false));
        assertThat(lookupRaw(viewer, "phone=" + phone, null).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(link(viewer, mine.a().id(), found.get(0).get("sourcePatientRef"), phone, DOB).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    // ---- AC3: opt-out and everyone else who is never listed ------------------------------------------------------

    @Test
    void anOptedOutPatientNeverAppearsAndCannotBeLinked() throws Exception {
        Chain c = chain();
        String atRegistration = phone();
        UUID never = register(c.a(), "Asha Rao", atRegistration, DOB, Map.of("brandDirectoryOptOut", true));
        assertThat(matches(c.b(), atRegistration)).isEmpty();
        assertThat(count("SELECT count(*) FROM phi_read_log WHERE patient_id = ? AND purpose_code = 'brand_directory_lookup'", never)).isZero();

        String later = phone();
        UUID p = register(c.a(), later);
        Map<String, Object> m = matches(c.b(), later).get(0);
        assertThat(exchange("/v1/patients/" + p + "/brand-directory-opt-out", HttpMethod.PUT, authedJsonBody(c.a().deskToken(), Map.of("optOut", true)), Void.class)
                .getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(audits(c.a().id(), "patient.brand_directory.opt_out_changed")).isEqualTo(1);
        assertThat(exchange("/v1/patients/" + p + "/brand-links", HttpMethod.GET, authed(c.a().deskToken()), Map.class).getBody()).containsEntry("optedOut", true);
        assertThat(matches(c.b(), later)).isEmpty();
        assertThat(lookup(c.b(), "phone=" + later + "&dateOfBirth=1990-01-01").get("checkInPerson")).as("not even its existence").isEqualTo(false);
        ResponseEntity<Map> l = link(c.b().deskToken(), m.get("sourceTenantId"), m.get("sourcePatientRef"), later, DOB);
        assertThat(l.getStatusCode()).isEqualTo(HttpStatus.GONE);
        assertThat(String.valueOf(l.getBody())).contains("SOURCE_OPTED_OUT");
        assertThat(links(c.a().id()) + links(c.b().id())).isZero();
        // another clinic's desk cannot flip the choice
        assertThat(exchange("/v1/patients/" + p + "/brand-directory-opt-out", HttpMethod.PUT, authedJsonBody(c.b().deskToken(), Map.of("optOut", false)), Void.class)
                .getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void demoMinorAndConsentWithdrawnPatientsAreNeverListed() throws Exception {
        Chain c = chain();
        JdbcTemplate su = su();
        String demo = phone(), minor = phone(), withdrawn = phone(), denied = phone(), granted = phone();
        UUID pDemo = register(c.a(), demo);
        su.update("UPDATE patients SET is_synthetic = true WHERE id = ?", pDemo);
        UUID pMinor = register(c.a(), minor);
        su.update("UPDATE patients SET dob = current_date - interval '10 years' WHERE id = ?", pMinor);
        UUID pWithdrawn = register(c.a(), withdrawn);
        su.update("INSERT INTO consents (tenant_id, patient_id, consent_type, withdrawn_at) VALUES (?, ?, 'data_processing', now())", c.a().id(), pWithdrawn);
        assertThat(matches(c.b(), demo)).isEmpty();
        assertThat(matches(c.b(), minor)).isEmpty();
        assertThat(matches(c.b(), withdrawn)).isEmpty();

        // a default-deny clinic: listed only with a data-processing grant
        UUID pDenied = register(c.a(), denied);
        UUID pGranted = register(c.a(), granted);
        su.update("UPDATE tenants SET consent_default_deny = true WHERE id = ?", c.a().id());
        su.update("INSERT INTO consents (tenant_id, patient_id, consent_type) VALUES (?, ?, 'data_processing')", c.a().id(), pGranted);
        assertThat(matches(c.b(), denied)).isEmpty();
        assertThat(matches(c.b(), granted)).hasSize(1);
        assertThat(count("SELECT count(*) FROM phi_read_log WHERE purpose_code = 'brand_directory_lookup' AND patient_id IN (?, ?, ?, ?)",
                pDemo, pMinor, pWithdrawn, pDenied)).isZero();
    }

    // ---- AC7: date of birth differs --------------------------------------------------------------------------------

    @Test
    void whenTheDateOfBirthDiffersNoSuggestionIsMadeAndTheDeskIsToldToCheckInPerson() throws Exception {
        Chain c = chain();
        String phone = phone();
        register(c.a(), phone);
        Map<String, Object> m = matches(c.b(), phone).get(0);

        ResponseEntity<String> r = lookupRaw(c.b().deskToken(), "phone=" + phone + "&dateOfBirth=1988-02-04", null);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> body = objectMapper.readValue(r.getBody(), Map.class);
        assertThat((List<?>) body.get("matches")).isEmpty();
        assertThat(body.get("checkInPerson")).isEqualTo(true);
        assertThat(r.getBody()).doesNotContain("Asha").doesNotContain("1988").doesNotContain((String) m.get("sourcePatientRef"));
        // the right date of birth is a suggestion
        assertThat((List<?>) lookup(c.b(), "phone=" + phone + "&dateOfBirth=" + DOB).get("matches")).hasSize(1);

        ResponseEntity<Map> l = link(c.b().deskToken(), m.get("sourceTenantId"), m.get("sourcePatientRef"), phone, "1988-02-04");
        assertThat(l.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(String.valueOf(l.getBody())).contains("DOB_MISMATCH").contains("in person");
        // a wrong mobile with the right reference and date of birth is no match either
        assertThat(link(c.b().deskToken(), m.get("sourceTenantId"), m.get("sourcePatientRef"), phone(), DOB).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(links(c.a().id()) + links(c.b().id())).isZero();
        assertThat(count("SELECT count(*) FROM patients WHERE tenant_id = ?", c.b().id())).as("nothing was copied").isZero();
        assertThat(audits(c.b().id(), "patient.brand_link.refused")).isEqualTo(2);
    }

    // ---- AC2 + AC6: link ---------------------------------------------------------------------------------------------

    @Test
    void linkingCopiesDemographicsOnlyWritesALinkInBothClinicsAndShowsNoClinicalRowAtTheSecondBranch() throws Exception {
        Chain c = chain();
        String phone = phone();
        UUID atA = register(c.a(), "Asha Rao", phone, DOB, Map.of("address", "12 Synthetic Road", "nationalId", "SYN-0002"));
        assertThat(exchange("/v1/clinical/patients/" + atA + "/allergies", HttpMethod.POST, authedJsonBody(c.a().ownerToken(),
                Map.of("substance", "Penicillin", "severity", "severe", "reaction", "rash")), Map.class).getStatusCode()).isEqualTo(HttpStatus.CREATED);

        Map<String, Object> m = matches(c.b(), phone).get(0);
        String ref = (String) m.get("sourcePatientRef");
        ResponseEntity<Map> r = link(c.b().deskToken(), m.get("sourceTenantId"), ref, phone, DOB);
        assertThat(r.getStatusCode()).as(String.valueOf(r.getBody())).isEqualTo(HttpStatus.CREATED);
        UUID atB = UUID.fromString((String) r.getBody().get("patientId"));
        assertThat(atB).isNotEqualTo(atA);

        Map<String, Object> copy = su().queryForMap("SELECT * FROM patients WHERE id = ?", atB);
        assertThat(copy).containsEntry("tenant_id", c.b().id()).containsEntry("name", "Asha Rao").containsEntry("phone", "+91" + phone)
                .containsEntry("gender", "female").containsEntry("status", "active");
        assertThat(String.valueOf(copy.get("dob"))).isEqualTo(DOB);
        assertThat(copy.get("address")).isNull();
        assertThat(copy.get("national_id_enc")).isNull();
        assertThat(copy.get("guardian_id")).isNull();

        Map<String, Object> atBLink = su().queryForMap("SELECT * FROM patient_branch_links WHERE tenant_id = ?", c.b().id());
        assertThat(atBLink).containsEntry("patient_id", atB).containsEntry("linked_tenant_id", c.a().id()).containsEntry("linked_patient_ref", ref)
                .containsEntry("linked_by", c.b().desk().id());
        Map<String, Object> atALink = su().queryForMap("SELECT * FROM patient_branch_links WHERE tenant_id = ?", c.a().id());
        assertThat(atALink).containsEntry("patient_id", atA).containsEntry("linked_tenant_id", c.b().id());
        assertThat((String) atALink.get("linked_patient_ref")).as("pairwise references, never raw ids").matches("[0-9a-f]{64}").isNotEqualTo(ref)
                .doesNotContain(atB.toString());
        assertThat(ref).doesNotContain(atA.toString());

        // logged and audited in both clinics; the event carries ids only
        assertThat(audits(c.a().id(), "patient.branch_link.created")).isEqualTo(1);
        assertThat(audits(c.b().id(), "patient.branch_link.created")).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM phi_read_log WHERE tenant_id = ? AND patient_id = ? AND resource_type = 'brand_link_import'", c.a().id(), atA)).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM phi_read_log WHERE tenant_id = ? AND patient_id = ? AND resource_type = 'brand_link_import'", c.b().id(), atB)).isEqualTo(1);
        String event = su().queryForObject("SELECT payload::text FROM outbox_events WHERE tenant_id = ? AND event_type = 'patient.branch_link.created.v1'",
                String.class, c.b().id());
        assertThat(event).contains(atB.toString()).contains(c.a().id().toString()).doesNotContain("Asha").doesNotContain(phone).doesNotContain(atA.toString());
        assertThat(su().queryForObject("SELECT string_agg(COALESCE(before::text, '') || COALESCE(after::text, ''), ' ') FROM audit_log "
                + "WHERE tenant_id IN (?, ?) AND action LIKE 'patient.br%'", String.class, c.a().id(), c.b().id()))
                .as("audit rows hold ids only").doesNotContain("Asha").doesNotContain(phone).doesNotContain(DOB);

        // no clinical row is visible at B: not through the copy, not through A's id
        ResponseEntity<String> allergies = exchange("/v1/clinical/patients/" + atB + "/allergies", HttpMethod.GET, authed(c.b().ownerToken()), String.class);
        assertThat(allergies.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(allergies.getBody()).doesNotContain("Penicillin");
        assertThat(exchange("/v1/clinical/patients/" + atA + "/allergies", HttpMethod.GET, authed(c.b().ownerToken()), String.class).getBody()).doesNotContain("Penicillin");
        assertThat(exchange("/v1/patients/" + atA, HttpMethod.GET, authed(c.b().ownerToken()), Map.class).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(inTenantTx(c.b().id(), () -> jdbc.queryForObject("SELECT count(*) FROM patients WHERE id = ?", Integer.class, atA))).isZero();
        assertThat(inTenantTx(c.b().id(), () -> jdbc.queryForObject("SELECT count(*) FROM patient_branch_links", Integer.class)))
                .as("RLS: a clinic sees its own side of the link only").isEqualTo(1);

        // the badge, and a second link of the same pair
        Map<String, Object> badge = exchange("/v1/patients/" + atB + "/brand-links", HttpMethod.GET, authed(c.b().deskToken()), Map.class).getBody();
        assertThat((List<Map<String, Object>>) badge.get("links")).hasSize(1).allSatisfy(x -> assertThat(x.get("branchLabel")).isNotNull());
        ResponseEntity<Map> again = link(c.b().deskToken(), m.get("sourceTenantId"), ref, phone, DOB);
        assertThat(again.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(String.valueOf(again.getBody())).contains("ALREADY_LINKED");
        assertThat(count("SELECT count(*) FROM patients WHERE tenant_id = ?", c.b().id())).isEqualTo(1);

        // AC6 (the parts a test can hold): the linked walk-in is found at B by phone, so the desk goes straight to check-in
        ResponseEntity<String> search = exchange("/v1/patients?q=" + phone, HttpMethod.GET, authed(c.b().deskToken()), String.class);
        assertThat(search.getBody()).contains(atB.toString());
    }

    @Test
    void linkingAPersonAlreadyRegisteredHereLinksThatRecordInsteadOfCopying() throws Exception {
        Chain c = chain();
        String phone = phone();
        register(c.a(), phone);
        UUID here = register(c.b(), "asha rao", phone, DOB, Map.of());
        assertThat(linkFirst(c.b(), phone)).isEqualTo(here);
        assertThat(count("SELECT count(*) FROM patients WHERE tenant_id = ?", c.b().id())).isEqualTo(1);
        assertThat(links(c.a().id())).isEqualTo(1);
        assertThat(links(c.b().id())).isEqualTo(1);
    }

    // ---- AC8: unlink ---------------------------------------------------------------------------------------------------

    @Test
    void onlyTheOwnerUnlinksAndBothRecordsThenStandAloneAuditedInBothClinics() throws Exception {
        Chain c = chain();
        String phone = phone();
        UUID atA = register(c.a(), phone);
        UUID atB = linkFirst(c.b(), phone);
        String linkId = (String) ((List<Map<String, Object>>) exchange("/v1/patients/" + atB + "/brand-links", HttpMethod.GET, authed(c.b().deskToken()), Map.class)
                .getBody().get("links")).get(0).get("id");

        ResponseEntity<Map> byDesk = exchange("/v1/patients/" + atB + "/brand-links/" + linkId, HttpMethod.DELETE, authed(c.b().deskToken()), Map.class);
        assertThat(byDesk.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(String.valueOf(byDesk.getBody())).contains("OWNER_ONLY");
        // the other clinic's owner cannot end it through this clinic's patient id, nor with a link id that is not the patient's
        assertThat(exchange("/v1/patients/" + atB + "/brand-links/" + linkId, HttpMethod.DELETE, authed(c.a().ownerToken()), Map.class).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(exchange("/v1/patients/" + atB + "/brand-links/" + UUID.randomUUID(), HttpMethod.DELETE, authed(c.b().ownerToken()), Map.class).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(links(c.a().id()) + links(c.b().id())).isEqualTo(2);

        profile(c.brand(), "NONE"); // a link can be ended after sharing was switched off
        assertThat(exchange("/v1/patients/" + atB + "/brand-links/" + linkId, HttpMethod.DELETE, authed(c.b().ownerToken()), Void.class).getStatusCode())
                .isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(links(c.a().id()) + links(c.b().id())).isZero();
        assertThat(count("SELECT count(*) FROM patients WHERE id IN (?, ?) AND status = 'active'", atA, atB)).as("both records stand alone").isEqualTo(2);
        assertThat(audits(c.a().id(), "patient.branch_link.ended")).isEqualTo(1);
        assertThat(audits(c.b().id(), "patient.branch_link.ended")).isEqualTo(1);
        assertThat(su().queryForObject("SELECT entity_id FROM audit_log WHERE tenant_id = ? AND action = 'patient.branch_link.ended'", UUID.class, c.a().id()))
                .isEqualTo(atA);

        // and the pair can be linked again once sharing is back on
        profile(c.brand(), "DEMOGRAPHICS");
        assertThat(linkFirst(c.b(), phone)).isEqualTo(atB);
    }

    @Test
    void thePatientsUnlinkFromChatEndsEveryLinkThroughTheInternalEndpoint() throws Exception {
        Chain c = chain();
        String phone = phone();
        UUID atA = register(c.a(), phone);
        linkFirst(c.b(), phone);
        String ref = inTenantTx(c.a().id(), () -> refs.refFor(atA));
        String path = "/internal/v1/tenants/" + c.a().id() + "/patients/" + ref + "/brand-links/unlink";

        assertThat(exchange(path, HttpMethod.POST, authedJsonBody(c.a().ownerToken(), Map.of()), Map.class).getStatusCode())
                .as("a staff token is not a service identity").isIn(HttpStatus.UNAUTHORIZED, HttpStatus.FORBIDDEN);
        assertThat(exchange(path, HttpMethod.POST, authedJsonBody(InternalTestTokens.mint(encoder, "patient-access-edge", c.b().id()), Map.of()), Map.class)
                .getStatusCode()).as("a token for another clinic").isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(links(c.a().id()) + links(c.b().id())).isEqualTo(2);

        ResponseEntity<Map> r = exchange(path, HttpMethod.POST, authedJsonBody(InternalTestTokens.mint(encoder, "patient-access-edge", c.a().id()), Map.of()), Map.class);
        assertThat(r.getStatusCode()).as(String.valueOf(r.getBody())).isEqualTo(HttpStatus.OK);
        assertThat(r.getBody()).containsEntry("ended", 1);
        assertThat(links(c.a().id()) + links(c.b().id())).isZero();
        assertThat(audits(c.a().id(), "patient.branch_link.ended")).isEqualTo(1);
        assertThat(audits(c.b().id(), "patient.branch_link.ended")).isEqualTo(1);
        assertThat(su().queryForObject("SELECT after->>'endedBy' FROM audit_log WHERE tenant_id = ? AND action = 'patient.branch_link.ended'", String.class, c.b().id()))
                .isEqualTo("patient");
    }

    // ---- the owner's switch ------------------------------------------------------------------------------------------

    @Test
    void onlyTheBrandsOwnerSwitchesSharingWithTheNoticeStatementAuditedInEveryBranch() {
        Chain c = chain();
        profile(c.brand(), "NONE");
        String ownerAccount = (String) exchange("/v1/owners/workspaces/select", HttpMethod.POST,
                authedJsonBody(ownerLoginPendingToken(c.account()), Map.of("clinicId", c.a().id().toString())), Map.class).getBody().get("accessToken");
        String path = "/v1/settings/privacy/brand-directory";

        assertThat(exchange(path, HttpMethod.GET, authed(ownerAccount), Map.class).getBody())
                .containsEntry("profile", "NONE").containsEntry("canEdit", true).containsEntry("siblingBranches", 1);
        // staff on the built-in Owner role who are not the brand's owner account: read, never write
        assertThat(exchange(path, HttpMethod.GET, authed(c.a().ownerToken()), Map.class).getBody()).containsEntry("canEdit", false);
        ResponseEntity<Map> byStaff = exchange(path, HttpMethod.PUT, authedJsonBody(c.a().ownerToken(),
                Map.of("profile", "DEMOGRAPHICS", "noticeDisclosesDirectory", true)), Map.class);
        assertThat(byStaff.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(String.valueOf(byStaff.getBody())).contains("OWNER_ONLY");
        assertThat(exchange(path, HttpMethod.PUT, authedJsonBody(c.a().deskToken(), Map.of("profile", "DEMOGRAPHICS", "noticeDisclosesDirectory", true)), Map.class)
                .getStatusCode()).as("no setup:edit").isEqualTo(HttpStatus.FORBIDDEN);
        // another brand's owner changes their own brand only
        Chain other = chain();
        profile(other.brand(), "NONE");
        String otherOwner = (String) exchange("/v1/owners/workspaces/select", HttpMethod.POST,
                authedJsonBody(ownerLoginPendingToken(other.account()), Map.of("clinicId", other.a().id().toString())), Map.class).getBody().get("accessToken");
        assertThat(exchange(path, HttpMethod.PUT, authedJsonBody(otherOwner, Map.of("profile", "DEMOGRAPHICS", "noticeDisclosesDirectory", true)), Map.class)
                .getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(su().queryForObject("SELECT chain_sharing_profile FROM brands WHERE id = ?", String.class, other.brand().id())).isEqualTo("DEMOGRAPHICS");
        assertThat(su().queryForObject("SELECT chain_sharing_profile FROM brands WHERE id = ?", String.class, c.brand().id())).isEqualTo("NONE");

        ResponseEntity<Map> noStatement = exchange(path, HttpMethod.PUT, authedJsonBody(ownerAccount, Map.of("profile", "DEMOGRAPHICS")), Map.class);
        assertThat(noStatement.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(String.valueOf(noStatement.getBody())).contains("NOTICE_DISCLOSURE_REQUIRED");
        assertThat(exchange(path, HttpMethod.PUT, authedJsonBody(ownerAccount, Map.of("profile", "EVERYTHING", "noticeDisclosesDirectory", true)), Map.class)
                .getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(audits(c.a().id(), "brand.chain_sharing_profile.changed")).isZero();

        ResponseEntity<Map> on = exchange(path, HttpMethod.PUT, authedJsonBody(ownerAccount, Map.of("profile", "DEMOGRAPHICS", "noticeDisclosesDirectory", true)), Map.class);
        assertThat(on.getStatusCode()).as(String.valueOf(on.getBody())).isEqualTo(HttpStatus.OK);
        assertThat(on.getBody()).containsEntry("profile", "DEMOGRAPHICS");
        assertThat(exchange("/v1/patients/brand-directory/status", HttpMethod.GET, authed(c.b().deskToken()), Map.class).getBody()).containsEntry("enabled", true);
        assertThat(audits(c.a().id(), "brand.chain_sharing_profile.changed")).isEqualTo(1);
        assertThat(audits(c.b().id(), "brand.chain_sharing_profile.changed")).as("audited in the sibling branch too").isEqualTo(1);
        assertThat(audits(other.a().id(), "brand.chain_sharing_profile.changed")).as("the other brand: its own change only").isEqualTo(1);
    }

    // ---- 30 a minute per user -----------------------------------------------------------------------------------------

    @Test
    void theThirtyFirstAttemptInAMinuteIsRefusedAndRefusedLinksCountSoADateOfBirthCannotBeGuessed() throws Exception {
        Chain c = chain();
        String phone = phone();
        register(c.a(), phone);
        Map<String, Object> m = matches(c.b(), phone).get(0); // attempt 1
        for (int i = 0; i < 9; i++) { // attempts 2-10: guessing the date of birth by linking
            assertThat(link(c.b().deskToken(), m.get("sourceTenantId"), m.get("sourcePatientRef"), phone, "1988-03-" + String.format("%02d", i + 1)).getStatusCode())
                    .isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        }
        for (int i = 0; i < 20; i++) { // attempts 11-30, misses included
            assertThat(lookupRaw(c.b().deskToken(), "phone=" + phone(), null).getStatusCode()).isEqualTo(HttpStatus.OK);
        }
        int readsBefore = directoryReads(c.a().id());
        assertThat(lookupRaw(c.b().deskToken(), "phone=" + phone, null).getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(link(c.b().deskToken(), m.get("sourceTenantId"), m.get("sourcePatientRef"), phone, DOB).getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(directoryReads(c.a().id())).isEqualTo(readsBefore);
        assertThat(links(c.b().id())).isZero();
        // the limit is per user: a colleague is not locked out
        assertThat(lookupRaw(c.b().ownerToken(), "phone=" + phone, null).getStatusCode()).isEqualTo(HttpStatus.OK);
    }
}
