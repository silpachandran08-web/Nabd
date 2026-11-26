package com.nabd.hms.exchange;

import com.nabd.hms.exchange.api.ConsultFhir.Amendment;
import com.nabd.hms.exchange.api.ConsultFhir.Consult;
import com.nabd.hms.exchange.api.ConsultFhir.Encounter;
import com.nabd.hms.exchange.api.ConsultFhir.Facility;
import com.nabd.hms.exchange.api.ConsultFhir.Note;
import com.nabd.hms.exchange.api.ConsultFhir.Patient;
import com.nabd.hms.exchange.api.ConsultFhir.Practitioner;
import com.nabd.hms.exchange.api.ConsultFhir.Prescription;
import com.nabd.hms.exchange.api.ConsultFhir.RxLine;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * IDC-055 test data. TEST DATA: every name, medicine, finding and identifier below is synthetic ("Test ..."); nothing is a
 * real person, clinic, product or clinical statement. Deterministic: the same case number always gives the same consult.
 */
final class FhirFixtures {

    /** Size of the golden suite (acceptance: at least 30 encounters). */
    static final int GOLDEN_CASES = 32;
    /** 12 digits, the shape of an Aadhaar number; not a real one. */
    static final String AADHAAR_SHAPED = "999900001111";
    static final String AADHAAR_SHAPED_GROUPED = "9999 0000 1111";
    private static final Instant T0 = Instant.parse("2026-01-05T04:30:00Z");
    private static final String[] STATUSES = {"completed", "in_consult", "checkout_pending", "transferred_out"};
    private static final String[] GENDERS = {"male", "female", "other"};

    private FhirFixtures() {
    }

    static UUID id(String kind, int n) {
        return UUID.nameUUIDFromBytes((kind + "-" + n).getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Golden case n (0-based): a complete signed consult with one to three Rx lines. The cases vary the identifiers on file
     * (ABHA number / address / none), one or two practitioners, HPR id or council number, visit status, which note fields
     * are filled, amendments, scripts (Devanagari, Arabic), markup and control characters, and Aadhaar-shaped input (case 10).
     */
    static Consult golden(int n) {
        String tag = String.format("%02d", n);
        UUID doctor = id("doctor", n);
        UUID second = id("second", n);
        boolean twoDoctors = n % 5 == 0;
        Instant start = T0.plus(Duration.ofDays(n)).plusSeconds(n * 61L);
        Instant signedAt = start.plusSeconds(20 * 60);

        String name = switch (n) {
            case 7 -> "परीक्षण रोगी 07";
            case 8 -> "مريض اختبار 08";
            case 9 -> "Test O'Patient <09> & Co";
            default -> "Test Patient " + tag;
        };
        String abhaNumber = n == 10 ? AADHAAR_SHAPED_GROUPED : n % 4 == 1 || n % 4 == 2 ? String.format("91%012d", n) : null;
        String abhaAddress = n == 10 ? AADHAAR_SHAPED + "@abdm" : n % 4 >= 2 ? "test.patient" + tag + "@abdm" : null;
        Patient patient = new Patient(id("patient", n), "MRN-TEST-" + tag, name, n == 11 ? "M" : GENDERS[n % 3],
                LocalDate.of(1950 + n, 1 + n % 12, 1 + n % 28), abhaNumber, abhaAddress);

        List<Practitioner> practitioners = new ArrayList<>();
        practitioners.add(new Practitioner(doctor, "Dr Test Doctor " + tag, n % 2 == 0 ? "71-0000-0000-00" + tag : null,
                n % 2 == 1 || n % 3 == 0 ? "TEST/REG/" + tag : null));
        if (twoDoctors) practitioners.add(new Practitioner(second, "Dr Test Second " + tag, null, "TEST/REG/S" + tag));

        List<Amendment> amendments = new ArrayList<>();
        if (n % 6 == 3) {
            amendments.add(new Amendment(signedAt.plusSeconds(3600), doctor, "Test amendment reason " + tag, null, null, null, null, "Test amended diagnosis " + tag));
        }
        if (n == 15) {
            amendments.add(new Amendment(signedAt.plusSeconds(7200), doctor, "Test second amendment reason " + tag, null, null, null, "Test amended plan " + tag, null));
        }
        String subjective = switch (n) {
            case 5 -> "Test <b>markup</b> & \"quotes\" 'single' 05";
            case 10 -> "Test note quoting ids " + AADHAAR_SHAPED_GROUPED + " and " + AADHAAR_SHAPED + " 10";
            default -> "Test subjective text " + tag;
        };
        String objective = n == 9 ? "Test control\u0007char 09" : n % 2 == 0 ? "Test objective text " + tag : null;
        String plan = n == 6 ? "Test line one 06\nTest line two 06\r\nTest line three 06" : n % 4 != 1 ? "Test plan text " + tag : null;
        Note note = new Note(id("note", n), doctor, signedAt, subjective, objective, n % 3 == 0 ? "Test assessment text " + tag : null, plan,
                n % 5 != 4 ? "Test diagnosis text " + tag : null, amendments);

        List<RxLine> lines = new ArrayList<>();
        for (int k = 0; k <= n % 3; k++) {
            String drug = "Test drug " + tag + "-" + k;
            lines.add(new RxLine(id("line", n * 10 + k), drug, k == 1 ? "Test brand " + tag : k == 2 ? drug.toUpperCase() : null, "1 unit",
                    k % 2 == 0 ? "1-0-1" : null, k % 2 == 1 ? "twice daily" : null, n % 2 == 0 ? "5 days" : null, k == 0 ? "oral" : null,
                    n % 3 == 1 ? "Test instruction " + tag : null, k == 0 ? new BigDecimal("10.00") : k == 1 ? new BigDecimal("2.50") : null,
                    k == 0 ? "tablet" : null));
        }
        return new Consult(new Facility(id("facility", n), "Test Clinic " + tag, "IN-TEST-HFR-" + tag), patient,
                new Encounter(id("encounter", n), n == 12 ? "no_show" : STATUSES[n % 4], start, doctor), note,
                new Prescription(twoDoctors ? second : doctor, start.plusSeconds(25 * 60), lines), practitioners);
    }

    /** The resources as one collection Bundle (what a golden file holds and the HL7 validator reads); relative references resolve against fullUrl. */
    static ObjectNode bundle(List<ObjectNode> resources) {
        ObjectNode b = JsonNodeFactory.instance.objectNode().put("resourceType", "Bundle").put("type", "collection");
        ArrayNode entries = b.putArray("entry");
        for (ObjectNode r : resources) {
            entries.addObject().put("fullUrl", "https://nabd.example/fhir/" + r.get("resourceType").asString() + "/" + r.get("id").asString()).set("resource", r);
        }
        return b;
    }

    static List<JsonNode> ofType(List<? extends JsonNode> resources, String type) {
        return resources.stream().filter(r -> type.equals(r.get("resourceType").asString())).map(r -> (JsonNode) r).toList();
    }

    static JsonNode one(List<? extends JsonNode> resources, String type) {
        List<JsonNode> found = ofType(resources, type);
        if (found.size() != 1) throw new AssertionError("expected one " + type + ", got " + found.size());
        return found.get(0);
    }
}
