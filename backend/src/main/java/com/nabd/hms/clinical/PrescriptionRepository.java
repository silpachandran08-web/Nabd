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
import static com.nabd.hms.clinical.PrescriptionModels.DrugSuggestionRow;
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

    /** pregnancyStatus: the recorded DOC-021 status (pregnant | lactating | not_pregnant | unknown) or null when nobody has documented it. */
    record PatientProfile(String gender, java.time.LocalDate dob, String pregnancyStatus) {
    }

    /** NB-113: pregnancy/lactation warning needs sex + age; NB-111/NB-112 reuse dob for age too. */
    Optional<PatientProfile> findPatientProfile(UUID tenantId, UUID patientId) {
        return jdbc.query("SELECT p.gender, p.dob, ps.status AS pregnancy_status FROM patients p " +
                        "LEFT JOIN patient_pregnancy_status ps ON ps.tenant_id = p.tenant_id AND ps.patient_id = p.id " +
                        "WHERE p.tenant_id = ? AND p.id = ?",
                (rs, i) -> new PatientProfile(rs.getString("gender"), rs.getDate("dob").toLocalDate(), rs.getString("pregnancy_status")),
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
        return jdbc.query("SELECT *, allergy_snapshot::text AS allergy_snapshot_json FROM prescriptions WHERE tenant_id = ? AND queue_entry_id = ?",
                prescriptionMapper(), tenantId, queueEntryId).stream().findFirst();
    }

    /** V1-004: row lock (held to the end of the transaction) that serialises upsert/sign on one prescription. */
    Optional<PrescriptionRow> lockByQueueEntry(UUID tenantId, UUID queueEntryId) {
        return jdbc.query("SELECT *, allergy_snapshot::text AS allergy_snapshot_json FROM prescriptions WHERE tenant_id = ? AND queue_entry_id = ? FOR UPDATE",
                prescriptionMapper(), tenantId, queueEntryId).stream().findFirst();
    }

    /** NB-105: previous medicines, most recent first — signed only, a draft isn't a real prescription yet. */
    List<PrescriptionRow> findSignedByPatient(UUID tenantId, UUID patientId) {
        return jdbc.query("SELECT *, allergy_snapshot::text AS allergy_snapshot_json FROM prescriptions WHERE tenant_id = ? AND patient_id = ? AND status = 'signed' " +
                        "ORDER BY signed_at DESC",
                prescriptionMapper(), tenantId, patientId);
    }

    List<PrescriptionItemRow> findItems(UUID tenantId, UUID prescriptionId) {
        return jdbc.query("SELECT *, overrides::text AS overrides_json FROM prescription_items WHERE tenant_id = ? AND prescription_id = ? " +
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
                            "  duration, instructions, allergy_override_reason, display_order, overrides, brand_name, sig_pattern, " +
                            "  route, quantity, quantity_unit) " +
                            "VALUES (?,?,?,?,?,?,?,?,?,?::jsonb,?,?,?,?,?)",
                    tenantId, prescriptionId, item.drugName(), item.dosage(), item.frequency(), item.duration(),
                    item.instructions(), item.allergyOverrideReason(), order++,
                    item.overridesJson() == null ? "[]" : item.overridesJson(),
                    item.brandName(), item.sigPattern(), item.route(), item.quantity(), item.quantityUnit());
        }
    }

    /** Returns affected rows (0 = it was not a draft); the caller audits only when a row actually changed.
     * DOC-R3: the allergy snapshot is written in the same UPDATE as the status flip. V51's trigger refuses any later
     * UPDATE of a signed row, so this is the only moment it can be written. */
    int sign(UUID tenantId, UUID prescriptionId, String allergySnapshotJson, UUID signedBy, String signedByRegistrationNo) {
        // CLN-005: signer and the licence number it signed under are stored with the status flip (V1221 CHECK).
        return jdbc.update("UPDATE prescriptions SET status = 'signed', signed_at = now(), allergy_snapshot = ?::jsonb, " +
                "signed_by = ?, signed_by_registration_no = ? WHERE tenant_id = ? AND id = ? AND status = 'draft'",
                allergySnapshotJson, signedBy, signedByRegistrationNo, tenantId, prescriptionId);
    }

    /** DOC-033: type-ahead source. Pharmacy item master (active stocked items) first, then distinct names this tenant
     * has prescribed before that the master does not already list. `like` is an already-escaped, lower-cased pattern.
     * Names only: no patient, doctor or prescription identifiers leave this query. */
    List<DrugSuggestionRow> searchDrugs(UUID tenantId, String likePattern, String prefixPattern, int limit) {
        return jdbc.query(
                "SELECT name, source, code, in_stock, rx_only FROM (" +
                        " SELECT c.name AS name, 'pharmacy' AS source, c.code AS code, (c.stock_qty > 0) AS in_stock, c.is_rx AS rx_only, " +
                        "   0 AS src_rank FROM charge_catalogue c " +
                        "  WHERE c.tenant_id = ? AND c.stock_qty IS NOT NULL AND c.active AND lower(c.name) LIKE ? ESCAPE '\\' " +
                        " UNION ALL " +
                        " SELECT min(i.drug_name) AS name, 'history' AS source, NULL AS code, NULL::boolean AS in_stock, NULL::boolean AS rx_only, " +
                        "   1 AS src_rank FROM prescription_items i " +
                        "  WHERE i.tenant_id = ? AND lower(i.drug_name) LIKE ? ESCAPE '\\' " +
                        "    AND NOT EXISTS (SELECT 1 FROM charge_catalogue c2 WHERE c2.tenant_id = i.tenant_id " +
                        "      AND c2.stock_qty IS NOT NULL AND c2.active AND lower(c2.name) = lower(i.drug_name)) " +
                        "  GROUP BY lower(i.drug_name) " +
                        ") s ORDER BY (CASE WHEN lower(name) LIKE ? ESCAPE '\\' THEN 0 ELSE 1 END), src_rank, lower(name) LIMIT ?",
                (rs, i) -> new DrugSuggestionRow(rs.getString("name"), rs.getString("source"), rs.getString("code"),
                        (Boolean) rs.getObject("in_stock"), (Boolean) rs.getObject("rx_only")),
                tenantId, likePattern, tenantId, likePattern, prefixPattern, limit);
    }

    // ── favourite Rx sets (NB-114) ───────────────────────────────────────

    UUID insertSet(UUID tenantId, UUID doctorId, String name, boolean shared) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO favourite_rx_sets (id, tenant_id, doctor_id, name, shared) VALUES (?,?,?,?,?)",
                id, tenantId, doctorId, name, shared);
        return id;
    }

    void insertSetItems(UUID tenantId, UUID setId, List<PrescriptionItemRequest> items) {
        int order = 0;
        for (PrescriptionItemRequest item : items) {
            jdbc.update("INSERT INTO favourite_rx_set_items (tenant_id, set_id, drug_name, dosage, frequency, " +
                            "instructions, duration, display_order, brand_name, sig_pattern, route, quantity, quantity_unit) " +
                            "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)",
                    tenantId, setId, item.drugName(), item.dosage(), item.frequency(), item.instructions(),
                    item.duration(), order++, blankToNull(item.brandName()), blankToNull(item.sigPattern()),
                    blankToNull(item.route()), item.quantity(), blankToNull(item.quantityUnit()));
        }
    }

    void deleteSetItems(UUID tenantId, UUID setId) {
        jdbc.update("DELETE FROM favourite_rx_set_items WHERE tenant_id = ? AND set_id = ?", tenantId, setId);
    }

    void updateSet(UUID tenantId, UUID setId, String name, UUID doctorId, boolean shared) {
        jdbc.update("UPDATE favourite_rx_sets SET name = ?, doctor_id = ?, shared = ?, updated_at = now() " +
                "WHERE tenant_id = ? AND id = ?", name, doctorId, shared, tenantId, setId);
    }

    void deleteSet(UUID tenantId, UUID setId) {
        jdbc.update("DELETE FROM favourite_rx_sets WHERE tenant_id = ? AND id = ?", tenantId, setId);
    }

    void markSetUsed(UUID tenantId, UUID setId) {
        jdbc.update("UPDATE favourite_rx_sets SET last_used_at = now() WHERE tenant_id = ? AND id = ?", tenantId, setId);
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private static final String SET_SELECT = "SELECT s.id, s.doctor_id, s.name, s.created_at, s.shared, s.updated_at, " +
            "s.last_used_at, st.name AS owner_name FROM favourite_rx_sets s LEFT JOIN staff st ON st.id = s.doctor_id ";

    private static FavouriteSetRow mapSet(java.sql.ResultSet rs) throws java.sql.SQLException {
        String d = rs.getString("doctor_id");
        java.sql.Timestamp used = rs.getTimestamp("last_used_at");
        return new FavouriteSetRow(UUID.fromString(rs.getString("id")), d == null ? null : UUID.fromString(d),
                rs.getString("name"), rs.getTimestamp("created_at").toInstant(), rs.getBoolean("shared"),
                rs.getTimestamp("updated_at").toInstant(), used == null ? null : used.toInstant(), rs.getString("owner_name"));
    }

    /** Sets visible to this doctor: their own, other doctors' shared sets, and every clinic standard (doctor_id is null). */
    List<FavouriteSetRow> listSets(UUID tenantId, UUID doctorId) {
        return jdbc.query(SET_SELECT + "WHERE s.tenant_id = ? AND (s.doctor_id = ? OR s.doctor_id IS NULL OR s.shared) " +
                        "ORDER BY s.name, s.created_at",
                (rs, i) -> mapSet(rs), tenantId, doctorId);
    }

    Optional<FavouriteSetRow> findSet(UUID tenantId, UUID setId) {
        return jdbc.query(SET_SELECT + "WHERE s.tenant_id = ? AND s.id = ?", (rs, i) -> mapSet(rs), tenantId, setId)
                .stream().findFirst();
    }

    List<FavouriteSetItemRow> findSetItems(UUID tenantId, UUID setId) {
        return jdbc.query("SELECT * FROM favourite_rx_set_items WHERE tenant_id = ? AND set_id = ? ORDER BY display_order",
                (rs, i) -> new FavouriteSetItemRow(UUID.fromString(rs.getString("id")), rs.getString("drug_name"),
                        rs.getString("dosage"), rs.getString("frequency"), rs.getString("duration"),
                        rs.getString("instructions"), rs.getInt("display_order"), rs.getString("brand_name"),
                        rs.getString("sig_pattern"), rs.getString("route"), rs.getBigDecimal("quantity"),
                        rs.getString("quantity_unit")),
                tenantId, setId);
    }

    /** DOC-035: lower-cased names of the tenant's active stocked pharmacy items (the item master the type-ahead uses).
     * Empty means the tenant has no master, in which case staleness cannot be judged. */
    java.util.Set<String> activePharmacyNames(UUID tenantId) {
        return new java.util.HashSet<>(jdbc.queryForList(
                "SELECT lower(btrim(name)) FROM charge_catalogue WHERE tenant_id = ? AND stock_qty IS NOT NULL AND active",
                String.class, tenantId));
    }

    private RowMapper<PrescriptionRow> prescriptionMapper() {
        return (rs, i) -> new PrescriptionRow(
                UUID.fromString(rs.getString("id")),
                UUID.fromString(rs.getString("queue_entry_id")),
                UUID.fromString(rs.getString("patient_id")),
                UUID.fromString(rs.getString("doctor_id")),
                rs.getString("status"),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("signed_at") == null ? null : rs.getTimestamp("signed_at").toInstant(),
                rs.getString("allergy_snapshot_json"),
                rs.getString("signed_by") == null ? null : UUID.fromString(rs.getString("signed_by")),
                rs.getString("signed_by_registration_no"));
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
                rs.getInt("display_order"),
                rs.getString("overrides_json"),
                rs.getString("brand_name"),
                rs.getString("sig_pattern"),
                rs.getString("route"),
                rs.getBigDecimal("quantity"),
                rs.getString("quantity_unit"));
    }
}
