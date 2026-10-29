package com.nabd.hms.clinical;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.UUID;

/** CLN-020: drug_usage holds drug ids and counts only (no patient data), per doctor and for the whole clinic (doctor_id NULL). */
@Repository
class DrugUsageRepository {

    private final JdbcTemplate jdbc;

    DrugUsageRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** True the first time a prescription is seen: the event may be delivered more than once. */
    boolean markApplied(UUID tenantId, UUID prescriptionId) {
        return jdbc.update("INSERT INTO drug_usage_applied (tenant_id, prescription_id) VALUES (?, ?) ON CONFLICT DO NOTHING",
                tenantId, prescriptionId) == 1;
    }

    /** One count per coded line of the signed prescription, for the signer and for the clinic. */
    void add(UUID tenantId, UUID prescriptionId) {
        jdbc.update("INSERT INTO drug_usage (tenant_id, doctor_id, drug_id, uses) " +
                        "SELECT ?, u.doc, u.drug_id, count(*) FROM (" +
                        " SELECT i.drug_id, CASE k.n WHEN 0 THEN p.signed_by END AS doc " +
                        "  FROM prescriptions p JOIN prescription_items i ON i.tenant_id = p.tenant_id AND i.prescription_id = p.id " +
                        "  CROSS JOIN (VALUES (0), (1)) k(n) " +
                        "  WHERE p.tenant_id = ? AND p.id = ? AND p.status = 'signed' AND i.drug_id IS NOT NULL) u " +
                        "GROUP BY u.doc, u.drug_id " +
                        "ON CONFLICT (tenant_id, COALESCE(doctor_id, '00000000-0000-0000-0000-000000000000'::uuid), drug_id) " +
                        "DO UPDATE SET uses = drug_usage.uses + EXCLUDED.uses, last_used_at = now()",
                tenantId, tenantId, prescriptionId);
    }
}
