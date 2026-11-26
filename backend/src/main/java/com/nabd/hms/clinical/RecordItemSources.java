package com.nabd.hms.clinical;

import com.nabd.hms.common.TenantContext;
import com.nabd.hms.patient.api.RecordItemSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * COM-050: what the clinical module offers the patient's 'My records' chat menu. Ids and times only leave this file; who may ask is
 * decided by the patient module before it calls (RecordItemSource). There is no visit source: a visit has no server-rendered document
 * to link to (the visit summary is printed by the browser).
 */
final class RecordItemSources {

    private RecordItemSources() {
    }

    private static final RowMapper<RecordItemSource.Item> ITEM = (rs, i) -> new RecordItemSource.Item(rs.getObject(1, UUID.class), rs.getTimestamp(2).toInstant());

    /** Signed prescriptions that were not revoked (CLN-023); the link opens the stored A4 PDF. */
    @Component
    static class Prescriptions implements RecordItemSource {

        private static final String SHAREABLE = """
                FROM prescriptions p WHERE p.tenant_id = app_tenant_id() AND p.status = 'signed' AND p.signed_at IS NOT NULL
                  AND NOT EXISTS (SELECT 1 FROM prescription_rx_tokens t WHERE t.tenant_id = p.tenant_id AND t.prescription_id = p.id AND t.revoked_at IS NOT NULL)
                """;

        private final JdbcTemplate jdbc;
        private final TenantContext tenantContext;
        private final RxDocumentService documents;
        private final PrescriptionPrintRepository repo;

        Prescriptions(JdbcTemplate jdbc, TenantContext tenantContext, RxDocumentService documents, PrescriptionPrintRepository repo) {
            this.jdbc = jdbc;
            this.tenantContext = tenantContext;
            this.documents = documents;
            this.repo = repo;
        }

        @Override
        public String type() {
            return "prescription";
        }

        @Override
        public List<Item> latest(UUID patientId, int limit) {
            return jdbc.query("SELECT p.id, p.signed_at " + SHAREABLE + " AND p.patient_id = ? ORDER BY p.signed_at DESC, p.id DESC LIMIT ?", ITEM, patientId, limit);
        }

        @Override
        public Optional<Found> find(UUID itemId) {
            return jdbc.query("SELECT p.patient_id, p.signed_at " + SHAREABLE + " AND p.id = ?",
                    (rs, i) -> new Found(rs.getObject(1, UUID.class), rs.getTimestamp(2).toInstant()), itemId).stream().findFirst();
        }

        @Override
        @Transactional // joins the caller's tenant transaction
        public UUID file(UUID itemId) {
            UUID tenant = tenantContext.current();
            UUID visit = jdbc.queryForObject("SELECT p.queue_entry_id " + SHAREABLE + " AND p.id = ?", UUID.class, itemId);
            documents.pdf(tenant, null, visit, false, null); // stored on the first request, integrity-checked on every later one
            return repo.lockToken(tenant, itemId).orElseThrow().a4FileId();
        }
    }

    /** Outside reports attached to a visit (PLT-030); the link opens the attached file. */
    @Component
    static class Reports implements RecordItemSource {

        private static final String REPORTS = "FROM visit_attachments a WHERE a.tenant_id = app_tenant_id() AND a.kind = 'outside_report' ";

        private final JdbcTemplate jdbc;

        Reports(JdbcTemplate jdbc) {
            this.jdbc = jdbc;
        }

        @Override
        public String type() {
            return "report";
        }

        // shortcut: no index on visit_attachments (tenant_id, patient_id); add one when clinics attach reports at volume (the attachments panel is not mounted yet)
        @Override
        public List<Item> latest(UUID patientId, int limit) {
            return jdbc.query("SELECT a.id, a.created_at " + REPORTS + "AND a.patient_id = ? ORDER BY a.created_at DESC, a.id DESC LIMIT ?", ITEM, patientId, limit);
        }

        @Override
        public Optional<Found> find(UUID itemId) {
            return jdbc.query("SELECT a.patient_id, a.created_at " + REPORTS + "AND a.id = ?",
                    (rs, i) -> new Found(rs.getObject(1, UUID.class), rs.getTimestamp(2).toInstant()), itemId).stream().findFirst();
        }

        @Override
        public UUID file(UUID itemId) {
            return jdbc.queryForObject("SELECT a.file_id " + REPORTS + "AND a.id = ?", UUID.class, itemId);
        }
    }
}
