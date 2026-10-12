package com.nabd.hms.clinical;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

import static com.nabd.hms.clinical.VitalsModels.QueueEntrySnapshot;
import static com.nabd.hms.clinical.VitalsModels.VitalsRow;

@Repository
class VitalsRepository {

    private final JdbcTemplate jdbc;

    VitalsRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    Optional<QueueEntrySnapshot> findQueueEntrySnapshot(UUID tenantId, UUID queueEntryId) {
        return jdbc.query("SELECT patient_id, status FROM queue_entries WHERE tenant_id = ? AND id = ?",
                (rs, i) -> new QueueEntrySnapshot(UUID.fromString(rs.getString("patient_id")), rs.getString("status")),
                tenantId, queueEntryId).stream().findFirst();
    }

    Optional<VitalsRow> findByQueueEntry(UUID tenantId, UUID queueEntryId) {
        return jdbc.query("SELECT * FROM vitals WHERE tenant_id = ? AND queue_entry_id = ?",
                mapper(), tenantId, queueEntryId).stream().findFirst();
    }

    Optional<LocalDate> findPatientDob(UUID tenantId, UUID patientId) {
        return jdbc.query("SELECT dob FROM patients WHERE tenant_id = ? AND id = ?",
                (rs, i) -> rs.getDate("dob").toLocalDate(), tenantId, patientId).stream().findFirst();
    }

    /** NB-112: the paediatric dose calculator sources weight from here — the most recent vitals row for the patient, across any visit. */
    Optional<BigDecimal> findLatestWeightKg(UUID tenantId, UUID patientId) {
        return jdbc.query("SELECT weight_kg FROM vitals WHERE tenant_id = ? AND patient_id = ? AND weight_kg IS NOT NULL " +
                        "ORDER BY recorded_at DESC LIMIT 1",
                (rs, i) -> rs.getBigDecimal("weight_kg"), tenantId, patientId).stream().findFirst();
    }

    /** Freely correctable — no signed/locked concept for vitals. A null argument keeps the stored value (V2-008),
     * so a second save carrying only a pulse can no longer wipe the height, weight, BP, temperature and SpO2. */
    void upsert(UUID tenantId, UUID queueEntryId, UUID patientId, BigDecimal heightCm, BigDecimal weightKg,
                Integer bpSystolic, Integer bpDiastolic, Integer pulseBpm, BigDecimal tempCelsius,
                Integer spo2Percent, UUID recordedBy) {
        jdbc.update(
                "INSERT INTO vitals (tenant_id, queue_entry_id, patient_id, height_cm, weight_kg, bp_systolic, " +
                        "  bp_diastolic, pulse_bpm, temp_celsius, spo2_percent, recorded_by) " +
                        "VALUES (?,?,?,?,?,?,?,?,?,?,?) " +
                        "ON CONFLICT (queue_entry_id) DO UPDATE SET " +
                        "  height_cm = COALESCE(EXCLUDED.height_cm, vitals.height_cm), " +
                        "  weight_kg = COALESCE(EXCLUDED.weight_kg, vitals.weight_kg), " +
                        "  bp_systolic = COALESCE(EXCLUDED.bp_systolic, vitals.bp_systolic), " +
                        "  bp_diastolic = COALESCE(EXCLUDED.bp_diastolic, vitals.bp_diastolic), " +
                        "  pulse_bpm = COALESCE(EXCLUDED.pulse_bpm, vitals.pulse_bpm), " +
                        "  temp_celsius = COALESCE(EXCLUDED.temp_celsius, vitals.temp_celsius), " +
                        "  spo2_percent = COALESCE(EXCLUDED.spo2_percent, vitals.spo2_percent), " +
                        "  recorded_by = EXCLUDED.recorded_by, recorded_at = now()",
                tenantId, queueEntryId, patientId, heightCm, weightKg, bpSystolic, bpDiastolic, pulseBpm,
                tempCelsius, spo2Percent, recordedBy);
    }

    Optional<VitalsModels.ActorInfo> findActorInfo(UUID tenantId, UUID staffId) {
        return jdbc.query("SELECT s.name, r.name AS role_name FROM staff s JOIN roles r ON r.id = s.role_id " +
                        "WHERE s.tenant_id = ? AND s.id = ?",
                (rs, i) -> new VitalsModels.ActorInfo(rs.getString("name"), rs.getString("role_name")),
                tenantId, staffId).stream().findFirst();
    }

    private RowMapper<VitalsRow> mapper() {
        return (rs, i) -> new VitalsRow(
                UUID.fromString(rs.getString("id")),
                UUID.fromString(rs.getString("queue_entry_id")),
                UUID.fromString(rs.getString("patient_id")),
                rs.getBigDecimal("height_cm"),
                rs.getBigDecimal("weight_kg"),
                (Integer) rs.getObject("bp_systolic"),
                (Integer) rs.getObject("bp_diastolic"),
                (Integer) rs.getObject("pulse_bpm"),
                rs.getBigDecimal("temp_celsius"),
                (Integer) rs.getObject("spo2_percent"),
                UUID.fromString(rs.getString("recorded_by")),
                rs.getTimestamp("recorded_at").toInstant());
    }
}
