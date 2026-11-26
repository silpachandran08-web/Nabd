package com.nabd.hms.exchange;

import com.nabd.hms.clinical.api.SignedConsults;
import com.nabd.hms.common.TenantContext;
import com.nabd.hms.exchange.api.ConsultFhir;
import com.nabd.hms.exchange.internal.bundle.fhir.FhirMapper;
import com.nabd.hms.iam.api.PractitionerRecords;
import com.nabd.hms.identity.api.HealthIds;
import com.nabd.hms.patient.api.PatientDemographics;
import com.nabd.hms.queue.api.VisitEncounters;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.node.ObjectNode;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** IDC-055: gathers one signed consult through the owning modules' APIs; the mapping itself is {@link FhirMapper} (pure). */
@Service
class ConsultFhirService implements ConsultFhir {

    private final SignedConsults consults;
    private final VisitEncounters encounters;
    private final PatientDemographics patients;
    private final PractitionerRecords practitioners;
    private final HealthIds healthIds;
    private final TenantContext tenantContext;
    private final JdbcTemplate jdbc;

    ConsultFhirService(SignedConsults consults, VisitEncounters encounters, PatientDemographics patients, PractitionerRecords practitioners,
                       HealthIds healthIds, TenantContext tenantContext, JdbcTemplate jdbc) {
        this.consults = consults;
        this.encounters = encounters;
        this.patients = patients;
        this.practitioners = practitioners;
        this.healthIds = healthIds;
        this.tenantContext = tenantContext;
        this.jdbc = jdbc;
    }

    @Override
    public List<ObjectNode> map(Consult consult) {
        return FhirMapper.map(consult);
    }

    @Override
    @Transactional(readOnly = true, propagation = Propagation.MANDATORY)
    public Optional<List<ObjectNode>> forVisit(UUID queueEntryId) {
        return load(queueEntryId).map(FhirMapper::map);
    }

    private Optional<Consult> load(UUID queueEntryId) {
        UUID tenant = tenantContext.current();
        Optional<VisitEncounters.Encounter> visit = encounters.forVisit(queueEntryId);
        Optional<SignedConsults.Consult> signed = visit.flatMap(v -> consults.forVisit(queueEntryId));
        Optional<PatientDemographics.Demographics> demographics = signed.flatMap(s -> patients.find(s.note().patientId()));
        if (demographics.isEmpty()) {
            return Optional.empty();
        }
        VisitEncounters.Encounter v = visit.get();
        SignedConsults.Note n = signed.get().note();
        SignedConsults.Prescription rx = signed.get().prescription();
        PatientDemographics.Demographics d = demographics.get();
        HealthIds.PatientIds ids = healthIds.ofPatient(d.id());

        Set<UUID> staff = new LinkedHashSet<>();
        staff.add(v.doctorId());
        staff.add(n.authorId());
        n.amendments().forEach(a -> staff.add(a.authorId()));
        if (rx != null) staff.add(rx.prescriberId());

        return Optional.of(new Consult(
                new Facility(tenant, jdbc.queryForObject("SELECT name FROM tenants WHERE id = ?", String.class, tenant), healthIds.facilityHfrId().orElse(null)),
                new Patient(d.id(), d.mrn(), d.name(), d.gender(), d.dob(), ids.abhaNumber(), ids.abhaAddress()),
                new Encounter(v.encounterId(), v.status(), v.startedAt(), v.doctorId()),
                new Note(n.id(), n.authorId(), n.signedAt(), n.subjective(), n.objective(), n.assessment(), n.plan(), n.diagnosis(),
                        n.amendments().stream().map(a -> new Amendment(a.at(), a.authorId(), a.reason(), a.subjective(), a.objective(), a.assessment(),
                                a.plan(), a.diagnosis())).toList()),
                rx == null ? null : new Prescription(rx.prescriberId(), rx.signedAt(), rx.lines().stream().map(l -> new RxLine(l.id(), l.drugName(),
                        l.brandName(), l.dosage(), l.sigPattern(), l.frequency(), l.duration(), l.route(), l.instructions(), l.quantity(),
                        l.quantityUnit())).toList()),
                practitioners.find(staff).stream().map(p -> new Practitioner(p.id(), p.name(), p.hprId(), p.registrationNo())).toList()));
    }
}
