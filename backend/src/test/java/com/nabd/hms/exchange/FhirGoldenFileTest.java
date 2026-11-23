package com.nabd.hms.exchange;

import com.nabd.hms.exchange.internal.bundle.fhir.FhirMapper;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * IDC-055 golden files: 32 signed consults, each mapped and compared byte for byte with its committed Bundle
 * (src/test/resources/exchange/fhir/golden). The same files are what scripts/ci/fhir_validate.sh hands to the HL7 validator.
 * After an intended mapping change, rewrite them and review the diff: run this class alone with -Dfhir.golden.write=true.
 */
class FhirGoldenFileTest {

    static final Path DIR = Path.of("src/test/resources/exchange/fhir/golden");
    private static final ObjectMapper JSON = new ObjectMapper();

    static String file(int n) {
        return String.format("consult-%02d.json", n);
    }

    static String render(int n) {
        return JSON.writerWithDefaultPrettyPrinter().writeValueAsString(FhirFixtures.bundle(FhirMapper.map(FhirFixtures.golden(n)))).replace("\r\n", "\n") + "\n";
    }

    @Test
    void everyGoldenConsultMapsToExactlyItsCommittedBundle() throws IOException {
        assertThat(FhirFixtures.GOLDEN_CASES).isGreaterThanOrEqualTo(30);
        boolean write = Boolean.getBoolean("fhir.golden.write");
        Set<String> expectedFiles = new TreeSet<>();
        for (int n = 0; n < FhirFixtures.GOLDEN_CASES; n++) {
            expectedFiles.add(file(n));
            Path f = DIR.resolve(file(n));
            if (write) {
                Files.createDirectories(DIR);
                Files.writeString(f, render(n));
            }
            assertThat(f).as("golden file %s (rewrite with -Dfhir.golden.write=true)", f).exists();
            assertThat(render(n)).as(file(n)).isEqualTo(Files.readString(f, StandardCharsets.UTF_8).replace("\r\n", "\n"));
        }
        try (Stream<Path> files = Files.list(DIR)) {
            assertThat(files.map(p -> p.getFileName().toString()).filter(x -> x.endsWith(".json")).toList()).as("no stale golden file")
                    .containsExactlyInAnyOrderElementsOf(expectedFiles);
        }
    }

    /**
     * Stands in for the profile validator on the points read from the published IG (ndhm.in 6.5.0): the mandatory elements
     * of each profile and that every reference resolves inside the bundle. It is NOT the HL7 validator.
     */
    @Test
    void everyGoldenBundleHasTheSixResourceTypesTheProfilesMandatoryElementsAndResolvableReferences() throws IOException {
        for (int n = 0; n < FhirFixtures.GOLDEN_CASES; n++) {
            JsonNode bundle = JSON.readTree(Files.readString(DIR.resolve(file(n)), StandardCharsets.UTF_8));
            List<JsonNode> rs = new ArrayList<>();
            bundle.get("entry").forEach(e -> rs.add(e.get("resource")));
            String c = file(n);
            Set<String> ids = new TreeSet<>();
            for (JsonNode r : rs) {
                assertThat(r.get("meta").get("profile").get(0).asString()).as(c).startsWith("https://nrces.in/ndhm/fhir/r4/StructureDefinition/");
                assertThat(ids.add(r.get("resourceType").asString() + "/" + r.get("id").asString())).as(c + " duplicate id").isTrue();
            }
            assertThat(rs.stream().map(r -> r.get("resourceType").asString()).distinct().toList()).as(c)
                    .containsExactly("Composition", "Patient", "Encounter", "Organization", "Practitioner", "MedicationRequest");
            for (JsonNode r : rs) {
                r.findValues("reference").forEach(ref -> assertThat(ids).as(c + " dangling reference").contains(ref.asString()));
            }

            JsonNode patient = FhirFixtures.one(rs, "Patient");
            identifiers(c, patient, false);
            assertThat(patient.get("name").get(0).get("text").asString()).as(c).isNotBlank();
            assertThat(patient.get("gender").asString()).as(c).isIn("male", "female", "other", "unknown");

            for (JsonNode p : FhirFixtures.ofType(rs, "Practitioner")) {
                identifiers(c, p, false);
                assertThat(p.get("name").get(0).get("text").asString()).as(c).isNotBlank();
            }
            JsonNode org = FhirFixtures.one(rs, "Organization");
            identifiers(c, org, true);
            assertThat(org.get("name").asString()).as(c).isNotBlank();

            JsonNode enc = FhirFixtures.one(rs, "Encounter");
            assertThat(enc.get("status").asString()).as(c).isIn("finished", "in-progress", "cancelled");
            assertThat(enc.get("class").get("code").asString()).as(c).isEqualTo("AMB");
            assertThat(enc.get("subject").get("reference").asString()).as(c).isEqualTo("Patient/" + patient.get("id").asString());

            JsonNode comp = FhirFixtures.one(rs, "Composition");
            assertThat(comp.get("meta").get("profile").get(0).asString()).as(c).endsWith("/OPConsultRecord");
            assertThat(comp.get("status").asString()).as(c).isIn("final", "amended");
            JsonNode type = comp.get("type").get("coding").get(0);
            assertThat(List.of(type.get("system").asString(), type.get("code").asString(), type.get("display").asString())).as(c)
                    .containsExactly("http://snomed.info/sct", "371530004", "Clinical consultation report");
            for (String k : List.of("subject", "encounter", "date", "author", "title", "section")) {
                assertThat(comp.has(k)).as(c + " Composition." + k).isTrue();
            }
            JsonNode meds = comp.get("section").get(0); // the profile's slice comes first: unsliced sections are allowed only after it
            assertThat(meds.get("code").get("coding").get(0).get("code").asString()).as(c).isEqualTo("721912009");
            assertThat(meds.get("entry")).as(c).hasSize(FhirFixtures.ofType(rs, "MedicationRequest").size());
            for (int i = 1; i < comp.get("section").size(); i++) { // free text: narrative only, never a code
                JsonNode s = comp.get("section").get(i);
                assertThat(s.has("code")).as(c).isFalse();
                assertThat(s.get("text").get("div").asString()).as(c).startsWith("<div xmlns=\"http://www.w3.org/1999/xhtml\">").endsWith("</div>");
            }

            for (JsonNode m : FhirFixtures.ofType(rs, "MedicationRequest")) {
                for (String k : List.of("status", "intent", "medicationCodeableConcept", "subject", "authoredOn", "requester", "dosageInstruction")) {
                    assertThat(m.has(k)).as(c + " MedicationRequest." + k).isTrue();
                }
                assertThat(m.get("medicationCodeableConcept").has("coding")).as(c + ": no code is derived for a medicine").isFalse();
                assertThat(m.get("medicationCodeableConcept").get("text").asString()).as(c).isNotBlank();
            }
        }
    }

    private static void identifiers(String c, JsonNode resource, boolean systemRequired) {
        assertThat(resource.get("identifier")).as(c + " " + resource.get("resourceType").asString() + ".identifier").isNotEmpty();
        for (JsonNode id : resource.get("identifier")) {
            JsonNode coding = id.get("type").get("coding").get(0);
            assertThat(coding.has("system") && coding.has("code") && coding.has("display")).as(c).isTrue();
            assertThat(id.get("value").asString()).as(c).isNotBlank();
            if (systemRequired) assertThat(id.get("system").asString()).as(c).isNotBlank();
        }
    }
}
