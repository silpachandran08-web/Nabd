package com.nabd.hms.clinical;

import java.util.List;
import java.util.UUID;

/**
 * IDC-001: who is reading clinical data, resolved once per request by {@link ClinicalReadScope#resolve}.
 * scopedToDoctorId is the caller's own id for an own_patients_only staff member, null otherwise (whole clinic).
 * fieldGrants: empty = no custom restriction (NB-052), otherwise only the listed field groups are returned.
 */
public record ReadScope(UUID tenantId, UUID staffId, UUID scopedToDoctorId, List<String> fieldGrants) {

    /**
     * The one SQL gate every clinical read appends: consent (NB-053), then own-patients (NB-051) via appointments
     * OR queue_entries, so a walk-in without an appointment stays visible to the doctor who saw her.
     * Args are SQL expressions of the enclosing query (e.g. "q.patient_id", "q.tenant_id").
     */
    public String gate(String patientIdExpr, String tenantIdExpr) {
        String consent = com.nabd.hms.patient.api.ConsentGateSql.gate(patientIdExpr, tenantIdExpr);
        if (scopedToDoctorId == null) {
            return consent;
        }
        // Inlined, not bound: a java.util.UUID's toString is hex and dashes only, so it cannot carry SQL,
        // and callers keep their own positional parameter lists untouched.
        String doctor = "'" + scopedToDoctorId + "'::uuid";
        return consent
                + "AND (EXISTS (SELECT 1 FROM appointments rs_a WHERE rs_a.tenant_id = " + tenantIdExpr
                + " AND rs_a.doctor_id = " + doctor + " AND rs_a.patient_id = " + patientIdExpr + ")"
                + " OR EXISTS (SELECT 1 FROM queue_entries rs_q WHERE rs_q.tenant_id = " + tenantIdExpr
                + " AND rs_q.doctor_id = " + doctor + " AND rs_q.patient_id = " + patientIdExpr + ")) ";
    }

    /** NB-052 field grant check: true when the caller may see this field group (e.g. "diagnosis"). */
    public boolean sees(String fieldGroup) {
        return fieldGrants == null || fieldGrants.isEmpty() || fieldGrants.contains(fieldGroup);
    }
}
