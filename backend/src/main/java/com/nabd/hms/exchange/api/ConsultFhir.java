package com.nabd.hms.exchange.api;

import tools.jackson.databind.node.ObjectNode;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * IDC-055: one signed consult as FHIR R4 resources (NRCeS / ABDM profiles, IG ndhm.in 6.5.0), as plain JSON: Composition
 * (OPConsultRecord) first, then Patient, Encounter, Organization, Practitioner(s) and one MedicationRequest per signed Rx line.
 * No web request is needed. Only what the source record holds is written: free text stays text (no clinical code is derived),
 * a missing value is left out, and no Aadhaar number is ever written (an Aadhaar-shaped identifier is dropped, an
 * Aadhaar-shaped number inside free text is masked).
 *
 * <p>This is a system read: it applies no consent, read-scope or field-grant check and writes no audit or phi_read_log row.
 * The flow that calls it must have authorised the disclosure (consent, purpose) and must record it.
 */
public interface ConsultFhir {

    /** @param hfrId the verified Health Facility Registry id, null when the clinic has none */
    record Facility(UUID id, String name, String hfrId) {
    }

    /** @param abhaNumber 14 digits (any grouping), null when not linked; @param abhaAddress null when not linked */
    record Patient(UUID id, String mrn, String name, String gender, LocalDate dob, String abhaNumber, String abhaAddress) {
    }

    /** @param hprId the verified Healthcare Professionals Registry id; @param registrationNo the council registration number as entered */
    record Practitioner(UUID id, String name, String hprId, String registrationNo) {
    }

    /** @param id queue_entries.encounter_id; @param status the visit status (completed, in_consult, no_show, ...) */
    record Encounter(UUID id, String status, Instant start, UUID practitionerId) {
    }

    record Amendment(Instant at, UUID authorId, String reason, String subjective, String objective, String assessment, String plan, String diagnosis) {
    }

    record Note(UUID id, UUID authorId, Instant signedAt, String subjective, String objective, String assessment, String plan, String diagnosis,
                List<Amendment> amendments) {
    }

    record RxLine(UUID id, String drugName, String brandName, String dosage, String sigPattern, String frequency, String duration, String route,
                  String instructions, BigDecimal quantity, String quantityUnit) {
    }

    record Prescription(UUID prescriberId, Instant signedAt, List<RxLine> lines) {
    }

    /** @param prescription null when the visit has no signed prescription; @param practitioners everyone the other records name */
    record Consult(Facility facility, Patient patient, Encounter encounter, Note note, Prescription prescription, List<Practitioner> practitioners) {
    }

    /** Pure mapping of already-loaded records: no database, no clock, the same input always gives the same output. */
    List<ObjectNode> map(Consult consult);

    /**
     * Loads the visit's signed consult through the owning modules' APIs and maps it. Empty when the visit is unknown in the
     * bound tenant or its note is not signed. Call inside a tenant transaction (a worker: TenantScope.run).
     */
    Optional<List<ObjectNode>> forVisit(UUID queueEntryId);
}
