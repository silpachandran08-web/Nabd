package com.nabd.hms.pharmacy;

import com.nabd.hms.pharmacy.dto.PaperRxDtos;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.stereotype.Repository;

import java.sql.Date;
import java.time.LocalDate;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** PHL-020: paper_prescriptions and their lines. What a line has already dispensed is read from the bills (RxGateRepository). */
@Repository
class PaperRxRepository {

    /** What the item's schedule class asks of a prescription in this market (pharmacy_schedule_rules, data not code). */
    record ItemRule(boolean duplicateRxCopy, Integer validityDays) {
    }

    record LineRow(UUID id, UUID itemId, int qty, LocalDate rxDate, LocalDate validUntil, UUID documentId, boolean duplicateCopyRetained) {
    }

    private final JdbcTemplate jdbc;

    PaperRxRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Empty when the item is not in this clinic's price list. An item without a profile follows the OTC rule, as the worklist does. */
    Optional<ItemRule> itemRule(UUID tenantId, String market, UUID itemId) {
        return jdbc.query("SELECT COALESCE(r.duplicate_rx_copy, false) AS copy, r.rx_validity_days FROM charge_catalogue c " +
                        "LEFT JOIN pharmacy_item_profiles p ON p.item_id = c.id AND p.tenant_id = c.tenant_id " +
                        "LEFT JOIN pharmacy_schedule_rules r ON r.market = ? AND r.schedule_class = COALESCE(p.schedule_class, 'OTC') " +
                        "WHERE c.tenant_id = ? AND c.id = ?",
                (rs, i) -> new ItemRule(rs.getBoolean("copy"), (Integer) rs.getObject("rx_validity_days")), market, tenantId, itemId).stream().findFirst();
    }

    UUID insert(UUID tenantId, PaperRxDtos.CreateRequest req, LocalDate validUntil, UUID staffId) {
        return jdbc.queryForObject("INSERT INTO paper_prescriptions (tenant_id, prescriber_name, prescriber_reg_no, rx_date, customer_name, " +
                        "customer_phone, document_id, duplicate_copy_retained, valid_until, created_by) VALUES (?,?,?,?,?,?,?,?,?,?) RETURNING id", UUID.class,
                tenantId, req.prescriberName().trim(), req.prescriberRegNo().trim(), Date.valueOf(req.rxDate()), blankToNull(req.patientName()),
                blankToNull(req.patientPhone()), req.documentId(), req.duplicateCopyRetained(), validUntil == null ? null : Date.valueOf(validUntil), staffId);
    }

    UUID insertLine(UUID tenantId, UUID paperRxId, PaperRxDtos.LineRequest l) {
        return jdbc.queryForObject("INSERT INTO paper_prescription_lines (tenant_id, paper_rx_id, item_id, drug_text, qty) VALUES (?,?,?,?,?) RETURNING id",
                UUID.class, tenantId, paperRxId, l.itemId(), blankToNull(l.drugText()), l.qty());
    }

    /** Row-locks the lines in id order, so two bills racing on one paper line run one after the other (as PHL-001 does). */
    Map<UUID, LineRow> lockLines(UUID tenantId, Collection<UUID> ids) {
        Map<UUID, LineRow> out = new HashMap<>();
        jdbc.query("SELECT l.id, l.item_id, l.qty, p.rx_date, p.valid_until, p.document_id, p.duplicate_copy_retained " +
                        "FROM paper_prescription_lines l JOIN paper_prescriptions p ON p.id = l.paper_rx_id AND p.tenant_id = l.tenant_id " +
                        "WHERE l.tenant_id = ? AND l.id = ANY (?) ORDER BY l.id FOR UPDATE OF l",
                (RowCallbackHandler) rs -> out.put(rs.getObject("id", UUID.class), new LineRow(rs.getObject("id", UUID.class),
                        rs.getObject("item_id", UUID.class), rs.getInt("qty"), rs.getDate("rx_date").toLocalDate(),
                        rs.getDate("valid_until") == null ? null : rs.getDate("valid_until").toLocalDate(),
                        rs.getObject("document_id", UUID.class), rs.getBoolean("duplicate_copy_retained"))),
                tenantId, ids.toArray(new UUID[0]));
        return out;
    }

    /** The photo of this clinic's paper prescription; empty when there is no such record or it has no photo. */
    Optional<UUID> documentOf(UUID tenantId, UUID paperRxId) {
        return jdbc.query("SELECT document_id FROM paper_prescriptions WHERE tenant_id = ? AND id = ? AND document_id IS NOT NULL",
                (rs, i) -> rs.getObject(1, UUID.class), tenantId, paperRxId).stream().findFirst();
    }

    /** The prescribers this clinic recorded most recently, one row each (the dialog's autocomplete). */
    List<PaperRxDtos.Prescriber> recentPrescribers(UUID tenantId) {
        return jdbc.query("SELECT prescriber_name, prescriber_reg_no FROM (SELECT DISTINCT ON (lower(prescriber_name), prescriber_reg_no) " +
                        "prescriber_name, prescriber_reg_no, created_at FROM paper_prescriptions WHERE tenant_id = ? " +
                        "ORDER BY lower(prescriber_name), prescriber_reg_no, created_at DESC) x ORDER BY created_at DESC LIMIT 30",
                (rs, i) -> new PaperRxDtos.Prescriber(rs.getString(1), rs.getString(2)), tenantId);
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
