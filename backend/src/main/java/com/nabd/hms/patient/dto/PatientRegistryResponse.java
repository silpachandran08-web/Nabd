package com.nabd.hms.patient.dto;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Patient Registry (DESIGN.md): rows for the selected filter/search plus a count per filter.
 * "all" excludes archived (merged) records, as in the wireframe.
 */
public record PatientRegistryResponse(int total, Map<String, Integer> counts, List<Row> rows) {

    /**
     * tags: which filters this patient falls under (recent, followup, package, balance, duplicate,
     * archived). nextAppointmentToday/Id/DoctorId let Check in attach to today's booking.
     */
    public record Row(UUID id, String mrn, String name, String phone, int age, String gender, boolean minor,
                      LocalDate lastVisit, Instant nextAppointment, String nextDoctorName, UUID nextDoctorId,
                      UUID nextAppointmentId, boolean nextAppointmentToday,
                      Integer packageSessionsUsed, Integer packageSessionsTotal, String condition, boolean followUpDue,
                      String allergy, boolean balanceDue, boolean duplicate, boolean archived,
                      Integer queueToken, String queueStatus, List<String> tags) {
    }
}
