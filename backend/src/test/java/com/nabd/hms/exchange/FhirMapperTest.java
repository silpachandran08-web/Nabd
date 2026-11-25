package com.nabd.hms.exchange;

import com.nabd.hms.exchange.api.ConsultFhir.Consult;
import com.nabd.hms.exchange.api.ConsultFhir.Encounter;
import com.nabd.hms.exchange.api.ConsultFhir.Facility;
import com.nabd.hms.exchange.api.ConsultFhir.Note;
import com.nabd.hms.exchange.api.ConsultFhir.Patient;
import com.nabd.hms.exchange.api.ConsultFhir.Practitioner;
import com.nabd.hms.exchange.api.ConsultFhir.Prescription;
import com.nabd.hms.exchange.api.ConsultFhir.RxLine;
import com.nabd.hms.exchange.internal.bundle.fhir.FhirMapper;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

/** IDC-055: the pure mapper (no Spring, no database): identifiers, Aadhaar, free text, missing data, purity and speed. */
class FhirMapperTest {

    private static final Pattern TWELVE_DIGITS = Pattern.compile("(?<!\\d)\\d{4}[ -]?\\d{4}[ -]?\\d{4}(?!\\d)");

    private static Consult consult(Patient p, Practitioner dr, Facility f, Note n, List<RxLine> lines) {
        return new Consult(f, p, new Encounter(FhirFixtures.id("e", 1), "completed", Instant.parse("2026-02-01T05:00:00Z"), dr.id()), n,
                lines == null ? null : new Prescription(dr.id(), Instant.parse("2026-02-01T05:30:00Z"), lines), List.of(dr));
    }

    private static Patient patient(String mrn, String abhaNumber, String abhaAddress) {
        return new Patient(FhirFixtures.id("p", 1), mrn, "Test Patient", "female", LocalDate.of(1990, 1, 1), abhaNumber, abhaAddress);
    }

    private static Note note(String subjective) {
        return new Note(FhirFixtures.id("n", 1), FhirFixtures.id("d", 1), Instant.parse("2026-02-01T05:20:00Z"), subjective, null, null, null, null, List.of());
    }

    private static final Practitioner DOCTOR = new Practitioner(FhirFixtures.id("d", 1), "Dr Test Doctor", "71-0000-0000-0001", "TEST/REG/1");
    private static final Facility CLINIC = new Facility(FhirFixtures.id("f", 1), "Test Clinic", "IN-TEST-HFR-1");

    private static RxLine line(String instructions) {
        return new RxLine(FhirFixtures.id("l", 1), "Test drug", null, "1 unit", "1-0-1", null, null, null, instructions, null, null);
    }

    @Test
    void abhaNumberAndAddressAndTheRegistryIdsCarryThePublishedSystemsAndTypeCodes() {
        List<ObjectNode> out = FhirMapper.map(consult(patient("MRN-000042", "91123456789012", "Test.Patient@ABDM"), DOCTOR, CLINIC, note("Test text"), List.of(line(null))));
        JsonNode ids = FhirFixtures.one(out, "Patient").get("identifier");
        assertThat(ids).hasSize(3);
        // the clinic's record number is scoped to the clinic
        assertThat(ids.get(0).get("system").asString()).isEqualTo("urn:nabd:tenant:" + CLINIC.id() + ":mrn");
        assertThat(ids.get(0).get("value").asString()).isEqualTo("MRN-000042");
        // ABHA number: system and type as in the IG's Patient example, value in the 2-4-4-4 form
        assertThat(ids.get(1).get("system").asString()).isEqualTo("https://healthid.ndhm.gov.in");
        assertThat(ids.get(1).get("value").asString()).isEqualTo("91-1234-5678-9012");
        assertThat(ids.get(1).get("type").get("coding").get(0).get("system").asString()).isEqualTo("http://terminology.hl7.org/CodeSystem/v2-0203");
        assertThat(ids.get(1).get("type").get("coding").get(0).get("code").asString()).isEqualTo("MR");
        // ABHA address: the IG's ABHA type code, lower case, and no system (the IG publishes none)
        assertThat(ids.get(2).get("type").get("coding").get(0).get("system").asString()).isEqualTo("https://nrces.in/ndhm/fhir/r4/CodeSystem/ndhm-identifier-type-code");
        assertThat(ids.get(2).get("type").get("coding").get(0).get("code").asString()).isEqualTo("ABHA");
        assertThat(ids.get(2).get("value").asString()).isEqualTo("test.patient@abdm");
        assertThat(ids.get(2).has("system")).isFalse();

        JsonNode dr = FhirFixtures.one(out, "Practitioner").get("identifier");
        assertThat(dr.get(0).get("system").asString()).isEqualTo("https://doctor.ndhm.gov.in");
        assertThat(dr.get(0).get("value").asString()).isEqualTo("71-0000-0000-0001"); // a 14-digit registry id is not mistaken for an Aadhaar number
        assertThat(dr.get(1).get("value").asString()).isEqualTo("TEST/REG/1");
        assertThat(dr.get(1).has("system")).isFalse();
        JsonNode org = FhirFixtures.one(out, "Organization").get("identifier");
        assertThat(org.get(0).get("system").asString()).isEqualTo("https://facility.ndhm.gov.in");
        assertThat(org.get(0).get("type").get("coding").get(0).get("code").asString()).isEqualTo("PRN");
    }

    @Test
    void anAadhaarShapedNumberIsNeverWritten_whateverFieldItArrivesIn() {
        for (String aadhaar : List.of(FhirFixtures.AADHAAR_SHAPED, FhirFixtures.AADHAAR_SHAPED_GROUPED, "9999-0000-1111")) {
            Patient p = new Patient(FhirFixtures.id("p", 1), aadhaar, "Test Patient " + aadhaar, "female", LocalDate.of(1990, 1, 1), aadhaar, aadhaar.replace(" ", "") + "@abdm");
            Practitioner dr = new Practitioner(FhirFixtures.id("d", 1), "Dr Test " + aadhaar, aadhaar, aadhaar);
            Note n = new Note(FhirFixtures.id("n", 1), dr.id(), Instant.parse("2026-02-01T05:20:00Z"), "Test id " + aadhaar, aadhaar, "x" + aadhaar + "y", "Test " + aadhaar + ".",
                    aadhaar, List.of(new com.nabd.hms.exchange.api.ConsultFhir.Amendment(Instant.parse("2026-02-02T05:20:00Z"), dr.id(), "Test " + aadhaar, null, null, null, null, aadhaar)));
            RxLine l = new RxLine(FhirFixtures.id("l", 1), "Test drug " + aadhaar, aadhaar, aadhaar, aadhaar, aadhaar, aadhaar, aadhaar, "Test " + aadhaar, null, aadhaar);
            List<ObjectNode> out = FhirMapper.map(consult(p, dr, new Facility(FhirFixtures.id("f", 1), "Test Clinic " + aadhaar, aadhaar), n, List.of(l)));

            String json = out.toString();
            assertThat(json).doesNotContain(aadhaar).doesNotContain(FhirFixtures.AADHAAR_SHAPED);
            assertThat(TWELVE_DIGITS.matcher(json.replaceAll("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}", "")).find())
                    .as("no 12-digit number left outside the resource ids: " + json).isFalse();
            // an identifier is dropped, not masked: a masked value would pass for a real record number
            assertThat(FhirFixtures.one(out, "Patient").has("identifier")).isFalse();
            assertThat(FhirFixtures.one(out, "Practitioner").has("identifier")).isFalse();
            assertThat(FhirFixtures.one(out, "Organization").has("identifier")).isFalse();
            // free text keeps its last four digits only
            assertThat(FhirFixtures.one(out, "Composition").get("section").get(1).get("text").get("div").asString()).contains("Test id XXXXXXXX1111");
        }
    }

    @Test
    void abhaNumberIsWrittenOnlyWhenItHas14Digits_andAMalformedAddressIsLeftOut() {
        for (String bad : List.of("1234", "911234567890123", "not-a-number", " ")) {
            JsonNode ids = FhirFixtures.one(FhirMapper.map(consult(patient("MRN-1", bad, "no at sign"), DOCTOR, CLINIC, note("Test text"), null)), "Patient").get("identifier");
            assertThat(ids).as(bad).hasSize(1);
            assertThat(ids.get(0).get("value").asString()).isEqualTo("MRN-1");
        }
    }

    @Test
    void whatTheRecordDoesNotHoldIsLeftOut_nothingIsFilledIn() {
        Practitioner bare = new Practitioner(FhirFixtures.id("d", 1), "Dr Test Doctor", null, " ");
        RxLine bareLine = new RxLine(FhirFixtures.id("l", 1), "Test drug", null, null, null, null, null, null, null, null, null);
        List<ObjectNode> out = FhirMapper.map(consult(new Patient(FhirFixtures.id("p", 1), "MRN-1", "Test Patient", "X", null, null, null), bare,
                new Facility(FhirFixtures.id("f", 1), "Test Clinic", null), note("Test text"), List.of(bareLine)));
        assertThat(FhirFixtures.one(out, "Practitioner").has("identifier")).isFalse();
        assertThat(FhirFixtures.one(out, "Organization").has("identifier")).isFalse();
        JsonNode p = FhirFixtures.one(out, "Patient");
        assertThat(p.has("birthDate")).isFalse();
        assertThat(p.has("telecom") || p.has("address")).as("phone and address are never mapped").isFalse();
        assertThat(p.get("gender").asString()).isEqualTo("unknown");
        JsonNode m = FhirFixtures.one(out, "MedicationRequest");
        assertThat(m.has("dosageInstruction") || m.has("dispenseRequest")).isFalse();
        assertThat(m.get("medicationCodeableConcept").toString()).isEqualTo("{\"text\":\"Test drug\"}");

        // no prescription and an empty note: no MedicationRequest, no Medications section, no section at all
        List<ObjectNode> empty = FhirMapper.map(consult(patient("MRN-1", null, null), DOCTOR, CLINIC, note("  "), null));
        assertThat(FhirFixtures.ofType(empty, "MedicationRequest")).isEmpty();
        assertThat(FhirFixtures.one(empty, "Composition").has("section")).isFalse();
        assertThat(out.toString() + empty).doesNotContain("null");
    }

    @Test
    void freeTextStaysTextAndIsSafeXhtml() {
        Note n = new Note(FhirFixtures.id("n", 1), DOCTOR.id(), Instant.parse("2026-02-01T05:20:00.987Z"), "Test <script>alert(1)</script> & \"q\" 'a'\nTest line 2\u0007", null, null, null,
                "Test diagnosis words", List.of());
        RxLine l = new RxLine(FhirFixtures.id("l", 1), "Test drug", "Test brand", "1 unit", "1-0-1", "twice daily", "5 days", "oral", "Test after food", new java.math.BigDecimal("10.00"), "tablet");
        List<ObjectNode> out = FhirMapper.map(consult(patient("MRN-1", null, null), DOCTOR, CLINIC, n, List.of(l)));
        JsonNode comp = FhirFixtures.one(out, "Composition");
        assertThat(comp.get("date").asString()).isEqualTo("2026-02-01T05:20:00Z");
        JsonNode subjective = comp.get("section").get(1);
        assertThat(subjective.get("title").asString()).isEqualTo("Subjective");
        assertThat(subjective.get("text").get("div").asString()).isEqualTo("<div xmlns=\"http://www.w3.org/1999/xhtml\">"
                + "Test &lt;script&gt;alert(1)&lt;/script&gt; &amp; &quot;q&quot; &#39;a&#39;<br/>Test line 2</div>");
        JsonNode diagnosis = comp.get("section").get(2);
        assertThat(diagnosis.get("title").asString()).isEqualTo("Diagnosis");
        assertThat(diagnosis.has("code") || diagnosis.has("entry")).as("a free-text diagnosis gets no code and no Condition").isFalse();
        assertThat(out.stream().map(r -> r.get("resourceType").asString())).doesNotContain("Condition", "Observation");

        JsonNode m = FhirFixtures.one(out, "MedicationRequest");
        assertThat(m.get("medicationCodeableConcept").toString()).isEqualTo("{\"text\":\"Test drug (Test brand)\"}");
        assertThat(m.get("dosageInstruction").toString()).isEqualTo("[{\"text\":\"1 unit, 1-0-1, twice daily, 5 days\",\"patientInstruction\":\"Test after food\",\"route\":{\"text\":\"oral\"}}]");
        assertThat(m.get("dispenseRequest").toString()).isEqualTo("{\"quantity\":{\"value\":10.00,\"unit\":\"tablet\"}}");
        assertThat(m.get("requester").get("reference").asString()).isEqualTo("Practitioner/" + DOCTOR.id());
    }

    @Test
    void anAmendedNoteKeepsTheOriginalAndAddsEachAmendmentInOrder() {
        List<ObjectNode> out = FhirMapper.map(FhirFixtures.golden(15));
        JsonNode comp = FhirFixtures.one(out, "Composition");
        assertThat(comp.get("status").asString()).isEqualTo("amended");
        List<String> titles = new ArrayList<>();
        comp.get("section").forEach(s -> titles.add(s.get("title").asString()));
        assertThat(titles).containsExactly("Medications", "Subjective", "Assessment", "Plan", "Diagnosis", "Amendment", "Amendment");
        JsonNode last = comp.get("section").get(6);
        assertThat(last.get("text").get("div").asString()).contains("Reason: Test second amendment reason 15<br/>Plan: Test amended plan 15");
        assertThat(last.get("author").get(0).get("reference").asString()).startsWith("Practitioner/");
        assertThat(comp.get("date").asString()).as("the date of the last amendment").isGreaterThan(FhirFixtures.one(out, "MedicationRequest").get("authoredOn").asString());
        assertThat(FhirFixtures.one(FhirMapper.map(FhirFixtures.golden(1)), "Composition").get("status").asString()).isEqualTo("final");
    }

    @Test
    void encounterStatusFollowsTheVisit() {
        for (String[] c : new String[][] {{"completed", "finished"}, {"no_show", "cancelled"}, {"in_consult", "in-progress"}, {"transferred_out", "in-progress"}}) {
            Consult base = FhirFixtures.golden(1);
            Consult k = new Consult(base.facility(), base.patient(), new Encounter(base.encounter().id(), c[0], base.encounter().start(), base.encounter().practitionerId()),
                    base.note(), base.prescription(), base.practitioners());
            assertThat(FhirFixtures.one(FhirMapper.map(k), "Encounter").get("status").asString()).as(c[0]).isEqualTo(c[1]);
        }
    }

    @Test
    void mappingIsPure_sameInputSameOutput_andTheMapperPackageCannotReachADatabase() {
        assertThat(FhirMapper.map(FhirFixtures.golden(3)).toString()).isEqualTo(FhirMapper.map(FhirFixtures.golden(3)).toString());
        noClasses().that().resideInAPackage("com.nabd.hms.exchange.internal..")
                .should().dependOnClassesThat().resideInAnyPackage("org.springframework.jdbc..", "java.sql..", "javax.sql..", "org.springframework.transaction..",
                        "org.springframework.web.client..", "java.net.http..")
                .check(new ClassFileImporter().importPackages("com.nabd.hms.exchange.internal"));
    }

    @Test
    void threeHundredResourcesMapInUnderHalfASecond() {
        List<Consult> consults = new ArrayList<>();
        for (int i = 0; i < 60; i++) consults.add(FhirFixtures.golden(i % FhirFixtures.GOLDEN_CASES));
        consults.forEach(FhirMapper::map); // class loading and JIT are not what the criterion measures
        long best = Long.MAX_VALUE;
        int resources = 0;
        for (int round = 0; round < 3; round++) { // best of three: the build machine is shared
            long t0 = System.nanoTime();
            resources = 0;
            for (Consult c : consults) resources += FhirMapper.map(c).size();
            best = Math.min(best, System.nanoTime() - t0);
        }
        assertThat(resources).isGreaterThanOrEqualTo(300);
        assertThat(best / 1_000_000).as("ms to map " + resources + " resources").isLessThan(500);
    }
}
