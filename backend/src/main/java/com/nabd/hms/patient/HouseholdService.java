package com.nabd.hms.patient;

import com.nabd.hms.common.ClinicClock;
import com.nabd.hms.common.PhoneNumbers;
import com.nabd.hms.patient.dto.HouseholdResponse;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.Period;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/** ACC-029: household lookup by phone. */
@Service
public class HouseholdService {

    private static final int MAX_MEMBERS = 50;

    private final HouseholdRepository households;
    private final PatientRepository patients;
    private final PatientService patientService;
    private final ClinicClock clock;

    HouseholdService(HouseholdRepository households, PatientRepository patients, PatientService patientService, ClinicClock clock) {
        this.households = households;
        this.patients = patients;
        this.patientService = patientService;
        this.clock = clock;
    }

    @Transactional
    public HouseholdResponse byPhone(UUID tenantId, UUID callerStaffId, String phone) {
        UUID scope = patientService.requireVerifiedAndResolveScope(tenantId, callerStaffId);
        String e164 = PhoneNumbers.require(phone, clock.region(tenantId), "phone");
        List<HouseholdRepository.Row> rows = households.find(tenantId, e164, scope, MAX_MEMBERS);
        Set<UUID> ids = rows.stream().map(HouseholdRepository.Row::id).collect(Collectors.toSet());
        Set<UUID> guardians = rows.stream().map(HouseholdRepository.Row::guardianId).filter(ids::contains).collect(Collectors.toSet());
        Map<UUID, Instant> lastVisit = rows.isEmpty() ? Map.of() : patients.findLastVisitAts(tenantId, List.copyOf(ids));
        java.time.LocalDate today = clock.today(tenantId);
        return new HouseholdResponse(rows.stream().map(r -> new HouseholdResponse.Member(r.id(), r.name(),
                Period.between(r.dob(), today).getYears(), r.gender(),
                guardians.contains(r.id()) ? "guardian" : ids.contains(r.guardianId()) ? "child" : "member",
                lastVisit.get(r.id()), r.guardianId())).toList());
    }
}
