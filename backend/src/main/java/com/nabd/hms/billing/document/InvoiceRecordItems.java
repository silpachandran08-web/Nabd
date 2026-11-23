package com.nabd.hms.billing.document;

import com.nabd.hms.patient.api.RecordItemSource;
import com.nabd.hms.shared.FileStore;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * COM-050: the patient's invoices for the 'My records' chat menu: ids and dates only, void and cancelled bills left out. The link
 * opens the stored A4 invoice (FIN-012: one PDF per invoice version), copied once per version to the file store, where the
 * secure-link pipeline serves files from.
 */
@Component
class InvoiceRecordItems implements RecordItemSource {

    private static final String BILLS = "FROM invoices i WHERE i.tenant_id = app_tenant_id() AND i.patient_id IS NOT NULL AND i.status NOT IN ('void', 'cancelled') ";
    private static final String STORED = "WHERE tenant_id = app_tenant_id() AND invoice_id = ? AND kind = 'invoice' AND layout = 'a4' AND version = ?";

    private final JdbcTemplate jdbc;
    private final InvoiceDocumentService documents;
    private final FileStore files;

    InvoiceRecordItems(JdbcTemplate jdbc, InvoiceDocumentService documents, FileStore files) {
        this.jdbc = jdbc;
        this.documents = documents;
        this.files = files;
    }

    @Override
    public String type() {
        return "invoice";
    }

    @Override
    public List<Item> latest(UUID patientId, int limit) {
        return jdbc.query("SELECT i.id, i.created_at " + BILLS + "AND i.patient_id = ? ORDER BY i.created_at DESC, i.id DESC LIMIT ?",
                (rs, n) -> new Item(rs.getObject(1, UUID.class), rs.getTimestamp(2).toInstant()), patientId, limit);
    }

    @Override
    public Optional<Found> find(UUID itemId) {
        return jdbc.query("SELECT i.patient_id, i.created_at " + BILLS + "AND i.id = ?",
                (rs, n) -> new Found(rs.getObject(1, UUID.class), rs.getTimestamp(2).toInstant()), itemId).stream().findFirst();
    }

    @Override
    public UUID file(UUID itemId) {
        byte[] pdf = documents.document(itemId, "a4", "invoice", null); // renders and stores the current version if it never was
        Map<String, Object> inv = jdbc.queryForMap("SELECT i.patient_id, i.queue_entry_id, i.version " + BILLS + "AND i.id = ?", itemId);
        UUID copy = jdbc.queryForObject("SELECT file_id FROM billing_documents " + STORED, UUID.class, itemId, inv.get("version"));
        if (copy == null) {
            // shortcut: stored under the file purpose 'claim_pack' (billing's generated documents); give invoices their own purpose with the next file_objects purpose migration
            copy = files.storeGenerated(null, "claim_pack", "application/pdf", pdf, (UUID) inv.get("patient_id"), (UUID) inv.get("queue_entry_id"));
            jdbc.update("UPDATE billing_documents SET file_id = ? " + STORED, copy, itemId, inv.get("version"));
        }
        return copy;
    }
}
