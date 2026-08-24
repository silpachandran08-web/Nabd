package com.nabd.hms.nursing;

import com.nabd.hms.common.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static com.nabd.hms.nursing.NursingModels.ActivityRow;
import static com.nabd.hms.nursing.NursingModels.AdministrationOrderRow;
import static com.nabd.hms.nursing.NursingModels.AdministrationRecordRow;
import static com.nabd.hms.nursing.NursingModels.ProcedureOrderRow;

@Repository
class NursingRepository {

    private final JdbcTemplate jdbc;

    NursingRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ── shared narrow lookups (same precedent as CheckoutRepository/PharmacyRepository) ──

    Optional<String> findPatientName(UUID tenantId, UUID patientId) {
        return jdbc.query("SELECT name FROM patients WHERE tenant_id = ? AND id = ?",
                (rs, i) -> rs.getString("name"), tenantId, patientId).stream().findFirst();
    }

    Optional<String> findStaffName(UUID tenantId, UUID staffId) {
        return jdbc.query("SELECT name FROM staff WHERE tenant_id = ? AND id = ?",
                (rs, i) -> rs.getString("name"), tenantId, staffId).stream().findFirst();
    }

    Optional<UUID> findPatientIdForQueueEntry(UUID tenantId, UUID queueEntryId) {
        return jdbc.query("SELECT patient_id FROM queue_entries WHERE tenant_id = ? AND id = ?",
                (rs, i) -> UUID.fromString(rs.getString("patient_id")), tenantId, queueEntryId).stream().findFirst();
    }

    // ── NB-145: administration orders & records ─────────────────────────────

    UUID insertAdministrationOrder(UUID tenantId, UUID queueEntryId, UUID patientId, UUID orderedBy, String drugName,
                                    String dose, String route, String site) {
        return jdbc.queryForObject(
                "INSERT INTO administration_orders (tenant_id, queue_entry_id, patient_id, ordered_by, drug_name, " +
                        "dose, route, site) VALUES (?,?,?,?,?,?,?,?) RETURNING id",
                UUID.class, tenantId, queueEntryId, patientId, orderedBy, drugName, dose, route, site);
    }

    List<AdministrationOrderRow> listAdministrationOrders(UUID tenantId, LocalDate day) {
        return jdbc.query(
                "SELECT o.id, o.queue_entry_id, o.patient_id, o.ordered_by, o.drug_name, o.dose, o.route, o.site, o.created_at " +
                        "FROM administration_orders o JOIN queue_entries q ON q.id = o.queue_entry_id " +
                        "WHERE o.tenant_id = ? AND q.queue_date = ? ORDER BY o.created_at",
                (rs, i) -> new AdministrationOrderRow(UUID.fromString(rs.getString("id")),
                        UUID.fromString(rs.getString("queue_entry_id")), UUID.fromString(rs.getString("patient_id")),
                        UUID.fromString(rs.getString("ordered_by")), rs.getString("drug_name"), rs.getString("dose"),
                        rs.getString("route"), rs.getString("site"), rs.getTimestamp("created_at").toInstant()),
                tenantId, java.sql.Date.valueOf(day));
    }

    Optional<AdministrationOrderRow> findAdministrationOrder(UUID tenantId, UUID id) {
        return jdbc.query(
                "SELECT id, queue_entry_id, patient_id, ordered_by, drug_name, dose, route, site, created_at " +
                        "FROM administration_orders WHERE tenant_id = ? AND id = ?",
                (rs, i) -> new AdministrationOrderRow(UUID.fromString(rs.getString("id")),
                        UUID.fromString(rs.getString("queue_entry_id")), UUID.fromString(rs.getString("patient_id")),
                        UUID.fromString(rs.getString("ordered_by")), rs.getString("drug_name"), rs.getString("dose"),
                        rs.getString("route"), rs.getString("site"), rs.getTimestamp("created_at").toInstant()),
                tenantId, id).stream().findFirst();
    }

    Optional<AdministrationRecordRow> findAdministrationRecord(UUID tenantId, UUID orderId) {
        return jdbc.query(
                "SELECT action, recorded_by, witnessed_by, refuse_reason, recorded_at FROM administration_records " +
                        "WHERE tenant_id = ? AND order_id = ?",
                (rs, i) -> new AdministrationRecordRow(rs.getString("action"),
                        UUID.fromString(rs.getString("recorded_by")),
                        rs.getString("witnessed_by") == null ? null : UUID.fromString(rs.getString("witnessed_by")),
                        rs.getString("refuse_reason"), rs.getTimestamp("recorded_at").toInstant()),
                tenantId, orderId).stream().findFirst();
    }

    /** Immutable: this INSERT is the only write administration_records ever gets — no UPDATE, no DELETE.
     * UNIQUE(order_id) turns "already recorded" into a constraint violation, caught here as a 409. */
    void insertAdministrationRecord(UUID tenantId, UUID orderId, String action, UUID recordedBy, UUID witnessedBy, String refuseReason) {
        try {
            jdbc.update("INSERT INTO administration_records (tenant_id, order_id, action, recorded_by, witnessed_by, refuse_reason) " +
                    "VALUES (?,?,?,?,?,?)", tenantId, orderId, action, recordedBy, witnessedBy, refuseReason);
        } catch (org.springframework.dao.DuplicateKeyException e) {
            throw new ApiException(HttpStatus.CONFLICT, "already-recorded", "Already recorded",
                    "This order already has an administration outcome recorded.");
        }
    }

    // ── NB-146: procedure orders ─────────────────────────────────────────────

    record ChargeSnapshot(String code, String name, BigDecimal baseAmount, BigDecimal taxRatePercent) {
    }

    Optional<ChargeSnapshot> findActiveCharge(UUID tenantId, String code) {
        return jdbc.query("SELECT code, name, base_amount, tax_rate_percent FROM charge_catalogue " +
                        "WHERE tenant_id = ? AND code = ? AND active = true",
                (rs, i) -> new ChargeSnapshot(rs.getString("code"), rs.getString("name"),
                        rs.getBigDecimal("base_amount"), rs.getBigDecimal("tax_rate_percent")),
                tenantId, code).stream().findFirst();
    }

    UUID insertProcedureOrder(UUID tenantId, UUID queueEntryId, UUID patientId, UUID orderedBy, ChargeSnapshot charge,
                              String prepNotes, String consentNote) {
        return jdbc.queryForObject(
                "INSERT INTO procedure_orders (tenant_id, queue_entry_id, patient_id, ordered_by, charge_code, " +
                        "charge_name, base_amount, tax_rate_percent, prep_notes, consent_note) " +
                        "VALUES (?,?,?,?,?,?,?,?,?,?) RETURNING id",
                UUID.class, tenantId, queueEntryId, patientId, orderedBy, charge.code(), charge.name(),
                charge.baseAmount(), charge.taxRatePercent(), prepNotes, consentNote);
    }

    List<ProcedureOrderRow> listProcedureOrders(UUID tenantId, LocalDate day) {
        return jdbc.query(
                "SELECT p.id, p.queue_entry_id, p.patient_id, p.ordered_by, p.charge_code, p.charge_name, " +
                        "p.base_amount, p.tax_rate_percent, p.prep_notes, p.consent_note, p.status, p.billed, " +
                        "p.completed_by, p.completed_at, p.created_at, p.consent_signed_name, p.consent_recorded_by, " +
                        "p.consent_signed_at " +
                        "FROM procedure_orders p JOIN queue_entries q ON q.id = p.queue_entry_id " +
                        "WHERE p.tenant_id = ? AND q.queue_date = ? AND p.status != 'cancelled' ORDER BY p.created_at",
                this::mapProcedure, tenantId, java.sql.Date.valueOf(day));
    }

    Optional<ProcedureOrderRow> findProcedureOrder(UUID tenantId, UUID id) {
        return jdbc.query(
                "SELECT id, queue_entry_id, patient_id, ordered_by, charge_code, charge_name, base_amount, " +
                        "tax_rate_percent, prep_notes, consent_note, status, billed, completed_by, completed_at, created_at, " +
                        "consent_signed_name, consent_recorded_by, consent_signed_at " +
                        "FROM procedure_orders WHERE tenant_id = ? AND id = ?",
                this::mapProcedure, tenantId, id).stream().findFirst();
    }

    void updateProcedureStatus(UUID tenantId, UUID id, String status, UUID completedBy) {
        if ("completed".equals(status)) {
            jdbc.update("UPDATE procedure_orders SET status = ?, completed_by = ?, completed_at = now() " +
                    "WHERE tenant_id = ? AND id = ?", status, completedBy, tenantId, id);
        } else {
            jdbc.update("UPDATE procedure_orders SET status = ? WHERE tenant_id = ? AND id = ?", status, tenantId, id);
        }
    }

    void updateProcedureNotes(UUID tenantId, UUID id, String prepNotes, String consentNote) {
        jdbc.update("UPDATE procedure_orders SET prep_notes = ?, consent_note = ? WHERE tenant_id = ? AND id = ?",
                prepNotes, consentNote, tenantId, id);
    }

    /** NB-119: a typed name stands in for a signature — no canvas/stylus capture exists in this app. */
    void recordConsent(UUID tenantId, UUID id, String signedName, UUID recordedBy) {
        jdbc.update("UPDATE procedure_orders SET consent_signed_name = ?, consent_recorded_by = ?, " +
                        "consent_signed_at = now() WHERE tenant_id = ? AND id = ?",
                signedName, recordedBy, tenantId, id);
    }

    private ProcedureOrderRow mapProcedure(java.sql.ResultSet rs, int i) throws java.sql.SQLException {
        String completedBy = rs.getString("completed_by");
        java.sql.Timestamp completedAt = rs.getTimestamp("completed_at");
        String consentRecordedBy = rs.getString("consent_recorded_by");
        java.sql.Timestamp consentSignedAt = rs.getTimestamp("consent_signed_at");
        return new ProcedureOrderRow(UUID.fromString(rs.getString("id")), UUID.fromString(rs.getString("queue_entry_id")),
                UUID.fromString(rs.getString("patient_id")), UUID.fromString(rs.getString("ordered_by")),
                rs.getString("charge_code"), rs.getString("charge_name"), rs.getBigDecimal("base_amount"),
                rs.getBigDecimal("tax_rate_percent"), rs.getString("prep_notes"), rs.getString("consent_note"),
                rs.getString("status"), rs.getBoolean("billed"), completedBy == null ? null : UUID.fromString(completedBy),
                completedAt == null ? null : completedAt.toInstant(), rs.getTimestamp("created_at").toInstant(),
                rs.getString("consent_signed_name"), consentRecordedBy == null ? null : UUID.fromString(consentRecordedBy),
                consentSignedAt == null ? null : consentSignedAt.toInstant());
    }

    // ── NB-148: completed activity — a read across three existing write paths, no new table ──

    List<ActivityRow> listActivityForStaffToday(UUID tenantId, UUID staffId, LocalDate day) {
        java.sql.Date sqlDay = java.sql.Date.valueOf(day);
        return jdbc.query("""
                SELECT 'vitals' AS kind, 'Vitals recorded' AS activity, patient_id, recorded_by AS staff_id, recorded_at AS occurred_at
                FROM vitals WHERE tenant_id = ? AND recorded_by = ? AND recorded_at::date = ?
                UNION ALL
                SELECT 'administration', CASE WHEN r.action = 'administered' THEN o.drug_name || ' administered'
                                               ELSE o.drug_name || ' refused' END,
                       o.patient_id, r.recorded_by, r.recorded_at
                FROM administration_records r JOIN administration_orders o ON o.id = r.order_id
                WHERE r.tenant_id = ? AND (r.recorded_by = ? OR r.witnessed_by = ?) AND r.recorded_at::date = ?
                UNION ALL
                SELECT 'priority', 'Urgent priority flagged', patient_id, priority_flagged_by, priority_flagged_at
                FROM queue_entries WHERE tenant_id = ? AND priority_flagged_by = ? AND priority_flagged_at::date = ?
                ORDER BY occurred_at
                """,
                (rs, i) -> new ActivityRow(rs.getString("kind"), rs.getString("activity"),
                        UUID.fromString(rs.getString("patient_id")), UUID.fromString(rs.getString("staff_id")),
                        rs.getTimestamp("occurred_at").toInstant()),
                tenantId, staffId, sqlDay,
                tenantId, staffId, staffId, sqlDay,
                tenantId, staffId, sqlDay);
    }
}
