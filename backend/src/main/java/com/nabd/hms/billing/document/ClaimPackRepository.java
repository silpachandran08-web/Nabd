package com.nabd.hms.billing.document;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** FIN-035: claim pack formats (reference rows have tenant_id NULL) and generated packs. Runs in the caller's tenant transaction. */
@Repository
class ClaimPackRepository {

    record Format(String code, String jurisdiction, String insurerName, String sectionsJson, String csvColumnsJson) {
    }

    record Pack(UUID id, UUID visitId, UUID invoiceId, UUID patientId, String formatCode, UUID documentId, UUID csvDocumentId) {
    }

    private static final String FORMAT_COLS = "code, jurisdiction, insurer_name, sections::text AS sections, csv_columns::text AS csv_columns";

    private final JdbcTemplate jdbc;

    ClaimPackRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Active formats; a clinic's own format replaces the reference one of the same code. */
    List<Format> formats(UUID tenantId, String jurisdiction) {
        return jdbc.query("SELECT DISTINCT ON (code) " + FORMAT_COLS + " FROM claim_pack_formats " +
                        "WHERE (tenant_id = ? OR tenant_id IS NULL) AND active AND (?::text IS NULL OR jurisdiction IS NULL OR jurisdiction = ?::text) " +
                        "ORDER BY code, (tenant_id IS NULL)",
                this::format, tenantId, jurisdiction, jurisdiction);
    }

    Optional<Format> format(UUID tenantId, String code) {
        return jdbc.query("SELECT " + FORMAT_COLS + " FROM claim_pack_formats WHERE (tenant_id = ? OR tenant_id IS NULL) AND active AND code = ? " +
                "ORDER BY (tenant_id IS NULL) LIMIT 1", this::format, tenantId, code).stream().findFirst();
    }

    /** The visit's current invoice (a voided one does not count). */
    Optional<UUID> invoiceForVisit(UUID tenantId, UUID visitId) {
        return jdbc.query("SELECT id FROM invoices WHERE tenant_id = ? AND queue_entry_id = ? AND status <> 'void' ORDER BY created_at DESC LIMIT 1",
                (rs, i) -> rs.getObject(1, UUID.class), tenantId, visitId).stream().findFirst();
    }

    Optional<String> registrationNo(UUID tenantId, UUID staffId) {
        return jdbc.query("SELECT professional_registration_no FROM staff WHERE tenant_id = ? AND id = ?",
                (rs, i) -> rs.getString(1), tenantId, staffId).stream().filter(v -> v != null && !v.isBlank()).findFirst();
    }

    /** The clinic's facility licence number, if one is on the licence register and not expired. */
    Optional<String> facilityLicence(UUID tenantId, LocalDate today) {
        return jdbc.query("SELECT number FROM licence_registry WHERE tenant_id = ? AND licence_type = 'facility' " +
                        "AND (expiry_date IS NULL OR expiry_date >= ?) ORDER BY expiry_date DESC NULLS FIRST, created_at DESC LIMIT 1",
                (rs, i) -> rs.getString(1), tenantId, java.sql.Date.valueOf(today)).stream().filter(v -> v != null && !v.isBlank()).findFirst();
    }

    UUID insert(UUID tenantId, UUID visitId, UUID invoiceId, UUID patientId, String formatCode, UUID documentId, UUID csvDocumentId,
                String sha256, UUID generatedBy) {
        return jdbc.queryForObject("INSERT INTO claim_packs (tenant_id, visit_id, invoice_id, patient_id, format_code, document_id, csv_document_id, " +
                        "pdf_sha256, generated_by) VALUES (?,?,?,?,?,?,?,?,?) RETURNING id", UUID.class,
                tenantId, visitId, invoiceId, patientId, formatCode, documentId, csvDocumentId, sha256, generatedBy);
    }

    Optional<Pack> find(UUID tenantId, UUID id) {
        return jdbc.query("SELECT id, visit_id, invoice_id, patient_id, format_code, document_id, csv_document_id FROM claim_packs WHERE tenant_id = ? AND id = ?",
                (rs, i) -> new Pack(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getObject(3, UUID.class), rs.getObject(4, UUID.class),
                        rs.getString(5), rs.getObject(6, UUID.class), rs.getObject(7, UUID.class)), tenantId, id).stream().findFirst();
    }

    private Format format(java.sql.ResultSet rs, int i) throws java.sql.SQLException {
        return new Format(rs.getString("code"), rs.getString("jurisdiction"), rs.getString("insurer_name"), rs.getString("sections"), rs.getString("csv_columns"));
    }
}
