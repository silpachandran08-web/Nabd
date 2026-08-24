package com.nabd.hms.clinical;

import com.nabd.hms.clinical.dto.PrescriptionItemRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static com.nabd.hms.clinical.NoteModels.QueueEntryOwner;
import static com.nabd.hms.clinical.PrescriptionModels.ActorInfo;
import static com.nabd.hms.clinical.PrescriptionModels.FavouriteSetItemRow;
import static com.nabd.hms.clinical.PrescriptionModels.FavouriteSetRow;
import static com.nabd.hms.clinical.PrescriptionModels.PrescriptionItemRow;
import static com.nabd.hms.clinical.PrescriptionModels.PrescriptionRow;

@Repository
class PrescriptionRepository {

    private final JdbcTemplate jdbc;

    PrescriptionRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    Optional<QueueEntryOwner> findQueueEntryOwner(UUID tenantId, UUID queueEntryId) {
        return jdbc.query("SELECT patient_id, doctor_id FROM queue_entries WHERE tenant_id = ? AND id = ?",
                (rs, i) -> new QueueEntryOwner(UUID.fromString(rs.getString("patient_id")), UUID.fromString(rs.getString("doctor_id"))),
                tenantId, queueEntryId).stream().findFirst();
    }

    record PatientProfile(String gender, java.time.LocalDate dob) {
    }

    /** NB-113: pregnancy/lactation warning needs sex + age; NB-111/NB-112 reuse dob for age too. */
    Optional<PatientProfile> findPatientProfile(UUID tenantId, UUID patientId) {
        return jdbc.query("SELECT gender, dob FROM patients WHERE tenant_id = ? AND id = ?",
                (rs, i) -> new PatientProfile(rs.getString("gender"), rs.getDate("dob").toLocalDate()),
                tenantId, patientId).stream().findFirst();
    }

    /** NB-111: India Schedule H/H1 vs KSA SFDA controlled-substance rules apply by tenant region. */
    Optional<String> findTenantRegion(UUID tenantId) {
        return jdbc.query("SELECT region FROM tenants WHERE id = ?", (rs, i) -> rs.getString("region"), tenantId)
                .stream().findFirst();
    }

    /** NB-108: actor_name/actor_role snapshot for the audit row an allergy override writes. */
    Optional<ActorInfo> findActorInfo(UUID tenantId, UUID staffId) {
        return jdbc.query("SELECT s.name, r.name AS role_name FROM staff s JOIN roles r ON r.id = s.role_id " +
                        "WHERE s.tenant_id = ? AND s.id = ?",
                (rs, i) -> new ActorInfo(rs.getString("name"), rs.getString("role_name")),
                tenantId, staffId).stream().findFirst();
    }

    Optional<PrescriptionRow> findByQueueEntry(UUID tenantId, UUID queueEntryId) {
        return jdbc.query("SELECT * FROM prescriptions WHERE tenant_id = ? AND queue_entry_id = ?",
                prescriptionMapper(), tenantId, queueEntryId).stream().findFirst();
    }

    /** NB-105: previous medicines, most recent first — signed only, a draft isn't a real prescription yet. */
    List<PrescriptionRow> findSignedByPatient(UUID tenantId, UUID patientId) {
        return jdbc.query("SELECT * FROM prescriptions WHERE tenant_id = ? AND patient_id = ? AND status = 'signed' " +
                        "ORDER BY signed_at DESC",
                prescriptionMapper(), tenantId, patientId);
    }

    List<PrescriptionItemRow> findItems(UUID tenantId, UUID prescriptionId) {
        return jdbc.query("SELECT * FROM prescription_items WHERE tenant_id = ? AND prescription_id = ? " +
                        "ORDER BY display_order",
                itemMapper(), tenantId, prescriptionId);
    }

    UUID ensureDraft(UUID tenantId, UUID queueEntryId, UUID patientId, UUID doctorId) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO prescriptions (id, tenant_id, queue_entry_id, patient_id, doctor_id) " +
                        "VALUES (?,?,?,?,?) ON CONFLICT (queue_entry_id) DO NOTHING",
                id, tenantId, queueEntryId, patientId, doctorId);
        return findByQueueEntry(tenantId, queueEntryId).orElseThrow().id();
    }

    /** Free-text pad: full replace on every save is simpler and just as correct as diffing line items. */
    void replaceItems(UUID tenantId, UUID prescriptionId, List<PrescriptionItemRow> items) {
        jdbc.update("DELETE FROM prescription_items WHERE tenant_id = ? AND prescription_id = ?", tenantId, prescriptionId);
        int order = 0;
        for (PrescriptionItemRow item : items) {
            jdbc.update("INSERT INTO prescription_items (tenant_id, prescription_id, drug_name, dosage, frequency, " +
                            "  duration, instructions, allergy_override_reason, display_order) VALUES (?,?,?,?,?,?,?,?,?)",
                    tenantId, prescriptionId, item.drugName(), item.dosage(), item.frequency(), item.duration(),
                    item.instructions(), item.allergyOverrideReason(), order++);
        }
    }

    /** Idempotent — signing an already-signed prescription is a no-op, not an error. */
    void sign(UUID tenantId, UUID prescriptionId) {
        jdbc.update("UPDATE prescriptions SET status = 'signed', signed_at = now() " +
                "WHERE tenant_id = ? AND id = ? AND status = 'draft'", tenantId, prescriptionId);
    }

    // ── favourite Rx sets (NB-114) ───────────────────────────────────────

    UUID insertSet(UUID tenantId, UUID doctorId, String name) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO favourite_rx_sets (id, tenant_id, doctor_id, name) VALUES (?,?,?,?)",
                id, tenantId, doctorId, name);
        return id;
    }

    void insertSetItems(UUID tenantId, UUID setId, List<PrescriptionItemRequest> items) {
        int order = 0;
        for (PrescriptionItemRequest item : items) {
            jdbc.update("INSERT INTO favourite_rx_set_items (tenant_id, set_id, drug_name, dosage, frequency, " +
                            "instructions, duration, display_order) VALUES (?,?,?,?,?,?,?,?)",
                    tenantId, setId, item.drugName(), item.dosage(), item.frequency(), item.instructions(),
                    item.duration(), order++);
        }
    }

    /** Sets visible to this doctor: their own plus every clinic default (doctor_id is null). */
    List<FavouriteSetRow> listSets(UUID tenantId, UUID doctorId) {
        return jdbc.query("SELECT id, doctor_id, name, created_at FROM favourite_rx_sets " +
                        "WHERE tenant_id = ? AND (doctor_id = ? OR doctor_id IS NULL) ORDER BY name",
                (rs, i) -> {
                    String d = rs.getString("doctor_id");
                    return new FavouriteSetRow(UUID.fromString(rs.getString("id")), d == null ? null : UUID.fromString(d),
                            rs.getString("name"), rs.getTimestamp("created_at").toInstant());
                }, tenantId, doctorId);
    }

    Optional<FavouriteSetRow> findSet(UUID tenantId, UUID setId) {
        return jdbc.query("SELECT id, doctor_id, name, created_at FROM favourite_rx_sets WHERE tenant_id = ? AND id = ?",
                (rs, i) -> {
                    String d = rs.getString("doctor_id");
                    return new FavouriteSetRow(UUID.fromString(rs.getString("id")), d == null ? null : UUID.fromString(d),
                            rs.getString("name"), rs.getTimestamp("created_at").toInstant());
                }, tenantId, setId).stream().findFirst();
    }

    List<FavouriteSetItemRow> findSetItems(UUID tenantId, UUID setId) {
        return jdbc.query("SELECT * FROM favourite_rx_set_items WHERE tenant_id = ? AND set_id = ? ORDER BY display_order",
                (rs, i) -> new FavouriteSetItemRow(UUID.fromString(rs.getString("id")), rs.getString("drug_name"),
                        rs.getString("dosage"), rs.getString("frequency"), rs.getString("duration"),
                        rs.getString("instructions"), rs.getInt("display_order")),
                tenantId, setId);
    }

    private RowMapper<PrescriptionRow> prescriptionMapper() {
        return (rs, i) -> new PrescriptionRow(
                UUID.fromString(rs.getString("id")),
                UUID.fromString(rs.getString("queue_entry_id")),
                UUID.fromString(rs.getString("patient_id")),
                UUID.fromString(rs.getString("doctor_id")),
                rs.getString("status"),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("signed_at") == null ? null : rs.getTimestamp("signed_at").toInstant());
    }

    private RowMapper<PrescriptionItemRow> itemMapper() {
        return (rs, i) -> new PrescriptionItemRow(
                UUID.fromString(rs.getString("id")),
                UUID.fromString(rs.getString("prescription_id")),
                rs.getString("drug_name"),
                rs.getString("dosage"),
                rs.getString("frequency"),
                rs.getString("duration"),
                rs.getString("instructions"),
                rs.getString("allergy_override_reason"),
                rs.getInt("display_order"));
    }
}
