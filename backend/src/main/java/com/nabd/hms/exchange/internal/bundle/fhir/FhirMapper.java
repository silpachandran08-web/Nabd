package com.nabd.hms.exchange.internal.bundle.fhir;

import com.nabd.hms.exchange.api.ConsultFhir.Amendment;
import com.nabd.hms.exchange.api.ConsultFhir.Consult;
import com.nabd.hms.exchange.api.ConsultFhir.Encounter;
import com.nabd.hms.exchange.api.ConsultFhir.Facility;
import com.nabd.hms.exchange.api.ConsultFhir.Note;
import com.nabd.hms.exchange.api.ConsultFhir.Patient;
import com.nabd.hms.exchange.api.ConsultFhir.Practitioner;
import com.nabd.hms.exchange.api.ConsultFhir.Prescription;
import com.nabd.hms.exchange.api.ConsultFhir.RxLine;
import org.springframework.web.util.HtmlUtils;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * IDC-055: signed consult -> FHIR R4 resources as plain JSON, shaped for the NRCeS / ABDM profiles (IG ndhm.in 6.5.0).
 * Pure functions: no database, no clock, no randomness. Identifier systems, identifier type codes and the Composition
 * type / Medications section codes are the ones the IG publishes; nothing clinical is coded here, source free text stays text.
 */
public final class FhirMapper {

    static final String PROFILE = "https://nrces.in/ndhm/fhir/r4/StructureDefinition/";
    static final String ID_TYPE_V2 = "http://terminology.hl7.org/CodeSystem/v2-0203";
    static final String ID_TYPE_NDHM = "https://nrces.in/ndhm/fhir/r4/CodeSystem/ndhm-identifier-type-code";
    public static final String ABHA_NUMBER_SYSTEM = "https://healthid.ndhm.gov.in";
    public static final String HPR_SYSTEM = "https://doctor.ndhm.gov.in";
    public static final String HFR_SYSTEM = "https://facility.ndhm.gov.in";
    static final String SNOMED = "http://snomed.info/sct";

    private static final JsonNodeFactory F = JsonNodeFactory.instance;
    /** 12 digits, bare or grouped 4-4-4: the shape of an Aadhaar number. */
    private static final Pattern AADHAAR = Pattern.compile("(?<!\\d)\\d{4}[ -]?\\d{4}[ -]?\\d{4}(?!\\d)");
    private static final Pattern ABHA_ADDRESS = Pattern.compile("[a-z0-9][a-z0-9._-]*@[a-z0-9][a-z0-9.-]*");
    private static final Pattern XML_ILLEGAL = Pattern.compile("[\\x00-\\x08\\x0B\\x0C\\x0E-\\x1F]");
    /** Subtrees the Aadhaar mask leaves alone: ids, references and codes written by this class, and identifiers (checked when built). */
    private static final Set<String> STRUCTURAL = Set.of("resourceType", "id", "meta", "identifier", "coding", "reference", "system");

    private FhirMapper() {
    }

    public static List<ObjectNode> map(Consult c) {
        List<ObjectNode> out = new ArrayList<>();
        List<RxLine> lines = c.prescription() == null ? List.of() : c.prescription().lines();
        out.add(composition(c, lines));
        out.add(patient(c.facility(), c.patient()));
        out.add(encounter(c));
        out.add(organization(c.facility()));
        c.practitioners().forEach(p -> out.add(practitioner(p)));
        lines.forEach(l -> out.add(medicationRequest(c, c.prescription(), l)));
        out.forEach(FhirMapper::maskAadhaar);
        return out;
    }

    private static ObjectNode patient(Facility f, Patient p) {
        ObjectNode r = resource("Patient", p.id(), "Patient");
        ArrayNode ids = F.arrayNode();
        // the clinic's own record number: unique inside one clinic only, so the system names the clinic
        add(ids, identifier(ID_TYPE_V2, "MR", "Medical record number", "urn:nabd:tenant:" + f.id() + ":mrn", p.mrn()));
        String abha = p.abhaNumber() == null ? "" : p.abhaNumber().replaceAll("\\D", "");
        if (abha.length() == 14) { // anything else (a 12-digit Aadhaar included) is not an ABHA number and is not written
            add(ids, identifier(ID_TYPE_V2, "MR", "Medical record number", ABHA_NUMBER_SYSTEM,
                    abha.substring(0, 2) + "-" + abha.substring(2, 6) + "-" + abha.substring(6, 10) + "-" + abha.substring(10)));
        }
        String address = text(p.abhaAddress()) == null ? null : text(p.abhaAddress()).toLowerCase(Locale.ROOT);
        if (address != null && ABHA_ADDRESS.matcher(address).matches() && !aadhaarShaped(address.substring(0, address.indexOf('@')))) {
            // the IG publishes the type code (its definition is the ABHA address) but no system URI for it: none is written
            add(ids, identifier(ID_TYPE_NDHM, "ABHA", "Ayushman Bharat Health Account (ABHA) ID", null, address));
        }
        if (!ids.isEmpty()) r.set("identifier", ids);
        name(r, p.name());
        r.put("gender", p.gender() == null ? "unknown" : switch (p.gender().toLowerCase(Locale.ROOT)) {
            case "male", "female", "other" -> p.gender().toLowerCase(Locale.ROOT);
            default -> "unknown";
        });
        if (p.dob() != null) r.put("birthDate", p.dob().toString());
        return r;
    }

    private static ObjectNode practitioner(Practitioner p) {
        ObjectNode r = resource("Practitioner", p.id(), "Practitioner");
        ArrayNode ids = F.arrayNode();
        add(ids, identifier(ID_TYPE_V2, "MD", "Medical License number", HPR_SYSTEM, p.hprId()));
        // the council that issued the number is not recorded, so there is no system to name
        add(ids, identifier(ID_TYPE_V2, "MD", "Medical License number", null, p.registrationNo()));
        if (!ids.isEmpty()) r.set("identifier", ids);
        name(r, p.name());
        return r;
    }

    private static ObjectNode organization(Facility f) {
        ObjectNode r = resource("Organization", f.id(), "Organization");
        ArrayNode ids = F.arrayNode();
        add(ids, identifier(ID_TYPE_V2, "PRN", "Provider number", HFR_SYSTEM, f.hfrId()));
        if (!ids.isEmpty()) r.set("identifier", ids);
        if (text(f.name()) != null) r.put("name", text(f.name()));
        return r;
    }

    private static ObjectNode encounter(Consult c) {
        Encounter e = c.encounter();
        ObjectNode r = resource("Encounter", e.id(), "Encounter");
        r.put("status", "completed".equals(e.status()) ? "finished" : "no_show".equals(e.status()) ? "cancelled" : "in-progress");
        // shortcut: every visit is ambulatory today (queue_entries.class, V43); map the class when another one is first written
        r.set("class", F.objectNode().put("system", "http://terminology.hl7.org/CodeSystem/v3-ActCode").put("code", "AMB").put("display", "ambulatory"));
        r.set("subject", ref("Patient", c.patient().id()));
        if (e.practitionerId() != null) r.putArray("participant").addObject().set("individual", ref("Practitioner", e.practitionerId()));
        if (e.start() != null) r.putObject("period").put("start", instant(e.start()));
        r.set("serviceProvider", ref("Organization", c.facility().id()));
        return r;
    }

    private static ObjectNode medicationRequest(Consult c, Prescription rx, RxLine l) {
        ObjectNode r = resource("MedicationRequest", l.id(), "MedicationRequest");
        r.put("status", "active");
        r.put("intent", "order");
        String brand = text(l.brandName());
        String drug = text(l.drugName());
        // text only: the NRCeS profile accepts SNOMED CT codings alone and the drug master carries none
        String medicine = brand == null || brand.equalsIgnoreCase(drug) ? drug : drug == null ? brand : drug + " (" + brand + ")";
        if (medicine != null) r.putObject("medicationCodeableConcept").put("text", medicine);
        r.set("subject", ref("Patient", c.patient().id()));
        r.set("encounter", ref("Encounter", c.encounter().id()));
        if (rx.signedAt() != null) r.put("authoredOn", instant(rx.signedAt()));
        if (rx.prescriberId() != null) r.set("requester", ref("Practitioner", rx.prescriberId()));
        ObjectNode dosage = F.objectNode();
        String sig = join(", ", l.dosage(), l.sigPattern(), l.frequency(), l.duration());
        if (sig != null) dosage.put("text", sig);
        if (text(l.instructions()) != null) dosage.put("patientInstruction", text(l.instructions()));
        if (text(l.route()) != null) dosage.putObject("route").put("text", text(l.route()));
        if (!dosage.isEmpty()) r.putArray("dosageInstruction").add(dosage);
        if (l.quantity() != null) {
            ObjectNode q = r.putObject("dispenseRequest").putObject("quantity").put("value", l.quantity());
            if (text(l.quantityUnit()) != null) q.put("unit", text(l.quantityUnit()));
        }
        return r;
    }

    private static ObjectNode composition(Consult c, List<RxLine> lines) {
        Note n = c.note();
        List<Amendment> amendments = n.amendments() == null ? List.of() : n.amendments();
        ObjectNode r = resource("Composition", n.id(), "OPConsultRecord");
        r.put("status", amendments.isEmpty() ? "final" : "amended");
        r.set("type", concept("371530004", "Clinical consultation report"));
        r.set("subject", ref("Patient", c.patient().id()));
        r.set("encounter", ref("Encounter", c.encounter().id()));
        Instant date = amendments.isEmpty() ? n.signedAt() : amendments.get(amendments.size() - 1).at();
        if (date != null) r.put("date", instant(date));
        if (n.authorId() != null) r.putArray("author").add(ref("Practitioner", n.authorId()));
        r.put("title", "Consultation Report");
        r.set("custodian", ref("Organization", c.facility().id()));
        ArrayNode sections = F.arrayNode();
        if (!lines.isEmpty()) { // the profile's Medications slice; slices come before the free-text sections (slicing is open at end)
            ObjectNode s = sections.addObject().put("title", "Medications");
            s.set("code", concept("721912009", "Medication summary document"));
            ArrayNode entries = s.putArray("entry");
            lines.forEach(l -> entries.add(ref("MedicationRequest", l.id())));
        }
        // SOAP fields and the diagnosis are free text in the record: sections with a title and narrative, no code
        section(sections, "Subjective", n.subjective());
        section(sections, "Objective", n.objective());
        section(sections, "Assessment", n.assessment());
        section(sections, "Plan", n.plan());
        section(sections, "Diagnosis", n.diagnosis());
        for (Amendment a : amendments) { // a signed note is never rewritten: each amendment follows the original, oldest first
            ObjectNode s = section(sections, "Amendment", join("\n", label("Reason", a.reason()), label("Subjective", a.subjective()),
                    label("Objective", a.objective()), label("Assessment", a.assessment()), label("Plan", a.plan()), label("Diagnosis", a.diagnosis())));
            if (s != null && a.authorId() != null) s.putArray("author").add(ref("Practitioner", a.authorId()));
        }
        if (!sections.isEmpty()) r.set("section", sections);
        return r;
    }

    private static ObjectNode section(ArrayNode sections, String title, String body) {
        if (text(body) == null) return null;
        String xhtml = HtmlUtils.htmlEscape(XML_ILLEGAL.matcher(text(body)).replaceAll(""), "UTF-8").replaceAll("\\r\\n?|\\n", "<br/>");
        ObjectNode s = sections.addObject().put("title", title);
        s.putObject("text").put("status", "additional").put("div", "<div xmlns=\"http://www.w3.org/1999/xhtml\">" + xhtml + "</div>");
        return s;
    }

    private static ObjectNode resource(String type, UUID id, String profile) {
        ObjectNode r = F.objectNode().put("resourceType", type).put("id", id.toString());
        r.putObject("meta").putArray("profile").add(PROFILE + profile);
        return r;
    }

    private static ObjectNode ref(String type, UUID id) {
        return F.objectNode().put("reference", type + "/" + id);
    }

    private static ObjectNode concept(String snomedCode, String display) {
        ObjectNode c = F.objectNode();
        c.putArray("coding").addObject().put("system", SNOMED).put("code", snomedCode).put("display", display);
        return c;
    }

    /** Null (nothing written) for a blank value or one shaped like an Aadhaar number. */
    private static ObjectNode identifier(String typeSystem, String typeCode, String typeDisplay, String system, String value) {
        String v = text(value);
        if (v == null || aadhaarShaped(v)) return null;
        ObjectNode id = F.objectNode();
        id.putObject("type").putArray("coding").addObject().put("system", typeSystem).put("code", typeCode).put("display", typeDisplay);
        if (system != null) id.put("system", system);
        return id.put("value", v);
    }

    /** The whole value is 12 digits once spaces and hyphens are removed (a 14-digit ABHA or HPR id is not). */
    private static boolean aadhaarShaped(String value) {
        return value.replaceAll("[\\s-]", "").matches("\\d{12}");
    }

    private static void add(ArrayNode to, ObjectNode node) {
        if (node != null) to.add(node);
    }

    private static void name(ObjectNode r, String name) {
        if (text(name) != null) r.putArray("name").addObject().put("text", text(name));
    }

    private static String label(String label, String value) {
        return text(value) == null ? null : label + ": " + text(value);
    }

    private static String join(String separator, String... parts) {
        List<String> kept = new ArrayList<>();
        for (String p : parts) {
            if (text(p) != null) kept.add(text(p));
        }
        return kept.isEmpty() ? null : String.join(separator, kept);
    }

    private static String text(String s) {
        return s == null || s.isBlank() ? null : s.strip();
    }

    private static String instant(Instant i) {
        return i.truncatedTo(ChronoUnit.SECONDS).toString();
    }

    /** Last line of defence: an Aadhaar-shaped number in any text value keeps only its last four digits. */
    private static void maskAadhaar(JsonNode node) {
        if (node instanceof ObjectNode o) {
            for (String key : List.copyOf(o.propertyNames())) {
                JsonNode v = o.get(key);
                if (STRUCTURAL.contains(key)) continue;
                if (v.isString()) o.put(key, mask(v.stringValue()));
                else maskAadhaar(v);
            }
        } else if (node instanceof ArrayNode a) {
            a.forEach(FhirMapper::maskAadhaar);
        }
    }

    private static String mask(String s) {
        return AADHAAR.matcher(s).replaceAll(m -> "XXXXXXXX" + m.group().substring(m.group().length() - 4));
    }
}
