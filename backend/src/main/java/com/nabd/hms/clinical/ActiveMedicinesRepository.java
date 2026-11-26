package com.nabd.hms.clinical;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** CLN-032: what the active-medicines resolver reads (all clinical-owned tables). */
@Repository
class ActiveMedicinesRepository {

    record SignedLine(String drugId, String name, String duration, String sigPattern, int refills, Instant signedAt) {
    }

    record Reported(String drugId, String name, LocalDate stoppedOn) {
    }

    private final JdbcTemplate jdbc;

    ActiveMedicinesRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Lines of the patient's signed prescriptions, newest first. A revoked prescription (CLN-023) contributes nothing;
     * excludeId (the prescription being checked) and signedBefore (only what was already signed then) are optional. */
    List<SignedLine> signedLines(UUID tenantId, UUID patientId, UUID excludeId, Instant signedBefore) {
        java.sql.Timestamp before = signedBefore == null ? null : java.sql.Timestamp.from(signedBefore);
        return jdbc.query("SELECT i.drug_id, COALESCE(NULLIF(btrim(i.brand_name), ''), i.drug_name) AS name, i.duration, i.sig_pattern, " +
                        "i.refills_allowed, p.signed_at FROM prescriptions p " +
                        "JOIN prescription_items i ON i.tenant_id = p.tenant_id AND i.prescription_id = p.id " +
                        "WHERE p.tenant_id = ? AND p.patient_id = ? AND p.status = 'signed' " +
                        "AND NOT EXISTS (SELECT 1 FROM prescription_rx_tokens t WHERE t.tenant_id = p.tenant_id " +
                        "  AND t.prescription_id = p.id AND t.revoked_at IS NOT NULL) " +
                        "AND (?::uuid IS NULL OR p.id <> ?::uuid) AND (?::timestamptz IS NULL OR p.signed_at < ?::timestamptz) " +
                        "ORDER BY p.signed_at DESC, i.display_order",
                (rs, n) -> new SignedLine(rs.getString("drug_id"), rs.getString("name"), rs.getString("duration"),
                        rs.getString("sig_pattern"), rs.getInt("refills_allowed"), rs.getTimestamp("signed_at").toInstant()),
                tenantId, patientId, excludeId, excludeId, before, before);
    }

    /** Patient-reported medicines current on that day: started (or start unknown) and not stopped before it. */
    List<Reported> reported(UUID tenantId, UUID patientId, LocalDate on) {
        return jdbc.query("SELECT drug_id, text, stopped_on FROM patient_reported_medicines WHERE tenant_id = ? AND patient_id = ? " +
                        "AND (started_on IS NULL OR started_on <= ?) AND (stopped_on IS NULL OR stopped_on >= ?) ORDER BY recorded_at DESC",
                (rs, n) -> new Reported(rs.getString("drug_id"), rs.getString("text"),
                        rs.getDate("stopped_on") == null ? null : rs.getDate("stopped_on").toLocalDate()),
                tenantId, patientId, on, on);
    }
}
