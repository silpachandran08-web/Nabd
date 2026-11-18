package com.nabd.hms.clinical.dental;

import com.nabd.hms.clinical.dental.dto.DentalCaseDtos.ChairNoteItem;
import com.nabd.hms.clinical.dental.dto.DentalCaseDtos.ImageItem;
import com.nabd.hms.clinical.dental.dto.DentalCaseDtos.LaLot;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Date;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

/** CLN-050: dental_settings, dental_preset_overrides, dental_images, dental_chair_notes. */
@Repository
@Transactional(propagation = Propagation.MANDATORY)
class DentalCaseRepository {

    record SettingsRow(String numbering, String presetsJson) {
    }

    private final JdbcTemplate jdbc;

    DentalCaseRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    Optional<SettingsRow> settings(UUID tenantId) {
        return jdbc.query("SELECT numbering, presets::text FROM dental_settings WHERE tenant_id = ?",
                (rs, i) -> new SettingsRow(rs.getString(1), rs.getString(2)), tenantId).stream().findFirst();
    }

    /** presetsJson null keeps the stored clinic list. */
    void upsertSettings(UUID tenantId, String numbering, String presetsJson, UUID by) {
        jdbc.update("INSERT INTO dental_settings (tenant_id, numbering, presets, updated_by) VALUES (?,?,?::jsonb,?) "
                + "ON CONFLICT (tenant_id) DO UPDATE SET numbering = EXCLUDED.numbering, "
                + "presets = COALESCE(EXCLUDED.presets, dental_settings.presets), updated_by = EXCLUDED.updated_by, updated_at = now()",
                tenantId, numbering, presetsJson, by);
    }

    Optional<String> myPresets(UUID tenantId, UUID staffId) {
        return jdbc.query("SELECT presets::text FROM dental_preset_overrides WHERE tenant_id = ? AND staff_id = ?",
                (rs, i) -> rs.getString(1), tenantId, staffId).stream().findFirst();
    }

    void saveMyPresets(UUID tenantId, UUID staffId, String presetsJson) {
        jdbc.update("INSERT INTO dental_preset_overrides (tenant_id, staff_id, presets) VALUES (?,?,?::jsonb) "
                + "ON CONFLICT (tenant_id, staff_id) DO UPDATE SET presets = EXCLUDED.presets, updated_at = now()", tenantId, staffId, presetsJson);
    }

    void clearMyPresets(UUID tenantId, UUID staffId) {
        jdbc.update("DELETE FROM dental_preset_overrides WHERE tenant_id = ? AND staff_id = ?", tenantId, staffId);
    }

    boolean visitOfPatient(UUID tenantId, UUID queueEntryId, UUID patientId) {
        return !jdbc.queryForList("SELECT 1 FROM queue_entries WHERE tenant_id = ? AND id = ? AND patient_id = ?",
                tenantId, queueEntryId, patientId).isEmpty();
    }

    /** Empty when this upload is already in the library (the caller returns the existing row: the attach is idempotent). */
    Optional<UUID> insertImage(UUID tenantId, UUID patientId, UUID queueEntryId, UUID uploadId, String kind, List<Integer> teeth, UUID by) {
        return jdbc.query("INSERT INTO dental_images (tenant_id, patient_id, queue_entry_id, upload_id, kind, teeth, created_by) "
                        + "VALUES (?,?,?,?,?,?::int[],?) ON CONFLICT (tenant_id, upload_id) DO NOTHING RETURNING id",
                (rs, i) -> rs.getObject(1, UUID.class), tenantId, patientId, queueEntryId, uploadId, kind, pgArray(teeth), by).stream().findFirst();
    }

    Optional<UUID> imageByUpload(UUID tenantId, UUID patientId, UUID uploadId) {
        return jdbc.query("SELECT id FROM dental_images WHERE tenant_id = ? AND patient_id = ? AND upload_id = ?",
                (rs, i) -> rs.getObject(1, UUID.class), tenantId, patientId, uploadId).stream().findFirst();
    }

    /** tooth null = every image (tooth-linked and whole-case); else only images linked to that FDI tooth. */
    List<ImageItem> images(UUID tenantId, UUID patientId, Integer tooth) {
        List<Object> args = new ArrayList<>(List.of(tenantId, patientId));
        if (tooth != null) args.add(tooth);
        return jdbc.query("SELECT id, patient_id, upload_id, queue_entry_id, kind, teeth, created_at FROM dental_images "
                        + "WHERE tenant_id = ? AND patient_id = ?" + (tooth != null ? " AND teeth @> ARRAY[?::int]" : "") + " ORDER BY created_at DESC, id",
                (rs, i) -> new ImageItem(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getObject(3, UUID.class),
                        rs.getObject(4, UUID.class), rs.getString(5), Arrays.asList((Integer[]) rs.getArray(6).getArray()),
                        rs.getTimestamp(7).toInstant()),
                args.toArray());
    }

    UUID insertNote(UUID tenantId, UUID patientId, UUID queueEntryId, int tooth, String presetCode, String text, UUID laDrugId,
                    String laLot, UUID by) {
        return jdbc.queryForObject("INSERT INTO dental_chair_notes (tenant_id, patient_id, queue_entry_id, tooth, preset_code, text, la_drug_id, la_lot, created_by) "
                + "VALUES (?,?,?,?,?,?,?,?,?) RETURNING id", UUID.class, tenantId, patientId, queueEntryId, tooth, presetCode, text, laDrugId, laLot, by);
    }

    List<ChairNoteItem> notes(UUID tenantId, UUID patientId, Integer tooth) {
        List<Object> args = new ArrayList<>(List.of(tenantId, patientId));
        if (tooth != null) args.add(tooth);
        return jdbc.query("SELECT id, patient_id, queue_entry_id, tooth, preset_code, text, la_drug_id, la_lot, created_by, created_at "
                        + "FROM dental_chair_notes WHERE tenant_id = ? AND patient_id = ?" + (tooth != null ? " AND tooth = ?" : "")
                        + " ORDER BY created_at DESC, id",
                (rs, i) -> new ChairNoteItem(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getObject(3, UUID.class),
                        rs.getInt(4), rs.getString(5), rs.getString(6), rs.getObject(7, UUID.class), rs.getString(8),
                        rs.getObject(9, UUID.class), rs.getTimestamp(10).toInstant()),
                args.toArray());
    }

    // shortcut: clinical reads pharmacy's stock_batches and billing's charge_catalogue directly (sql-ownership debt, same as
    // PrescriptionRepository); a clinical -> pharmacy.api call would close a module cycle (pharmacy already uses clinical.api).
    // Upgrade to a pharmacy read API once the cycle is broken.
    private static final String USABLE_LOTS = "SELECT b.item_id, c.name, b.batch_no, b.expiry_date FROM stock_batches b "
            + "JOIN charge_catalogue c ON c.tenant_id = b.tenant_id AND c.id = b.item_id "
            + "WHERE b.tenant_id = ? AND b.status = 'active' AND b.qty_on_hand > 0 AND c.active AND (b.expiry_date IS NULL OR b.expiry_date >= ?) ";

    /** Active, in-stock, in-date lots of the pharmacy items whose name contains {@code query}, earliest expiry first. */
    List<LaLot> usableLots(UUID tenantId, LocalDate today, String query, int limit) {
        return jdbc.query(USABLE_LOTS + "AND position(lower(?) in lower(c.name)) > 0 ORDER BY b.expiry_date NULLS LAST, c.name, b.id LIMIT ?",
                (rs, i) -> lot(rs), tenantId, Date.valueOf(today), query, limit);
    }

    Optional<LaLot> usableLot(UUID tenantId, LocalDate today, UUID itemId, String lot) {
        return jdbc.query(USABLE_LOTS + "AND b.item_id = ? AND b.batch_no = ? LIMIT 1", (rs, i) -> lot(rs),
                tenantId, Date.valueOf(today), itemId, lot).stream().findFirst();
    }

    private static LaLot lot(java.sql.ResultSet rs) throws java.sql.SQLException {
        Date d = rs.getDate(4);
        return new LaLot(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), d == null ? null : d.toLocalDate());
    }

    private static String pgArray(List<Integer> teeth) {
        return teeth.stream().map(String::valueOf).collect(Collectors.joining(",", "{", "}"));
    }
}
