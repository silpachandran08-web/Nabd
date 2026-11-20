package com.nabd.hms.clinical;

import com.nabd.hms.clinical.api.ImportedHistory;
import com.nabd.hms.common.TenantContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** ADM-013: imported visit history as issued clinical_documents rows (V3400). Read through the patient timeline only. */
@Component
class ImportedHistoryJdbc implements ImportedHistory {

    private final JdbcTemplate jdbc;
    private final TenantContext tenant;
    private final ObjectMapper json;

    ImportedHistoryJdbc(JdbcTemplate jdbc, TenantContext tenant, ObjectMapper json) {
        this.jdbc = jdbc;
        this.tenant = tenant;
        this.json = json;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean exists(UUID patientId, Visit v) {
        return !jdbc.queryForList("SELECT 1 FROM clinical_documents WHERE tenant_id = ? AND patient_id = ? AND doc_type = 'imported_history' " +
                "AND body = ?::jsonb LIMIT 1", tenant.current(), patientId, body(v)).isEmpty();
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public UUID add(UUID patientId, Visit v, String source, UUID importJobId, UUID importedBy) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO clinical_documents (id, tenant_id, patient_id, doc_type, status, body, header, created_by, issued_by, issued_at) " +
                        "VALUES (?,?,?,'imported_history','issued',?::jsonb,?::jsonb,?,?,now())",
                id, tenant.current(), patientId, body(v), json.writeValueAsString(Map.of("source", source, "importJobId", importJobId.toString())),
                importedBy, importedBy);
        return id;
    }

    private String body(Visit v) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("visitDate", v.visitDate().toString());
        m.put("doctorName", v.doctorName());
        put(m, "complaint", v.complaint());
        put(m, "diagnosis", v.diagnosis());
        put(m, "notes", v.notes());
        put(m, "medications", v.medications());
        put(m, "billNo", v.billNo());
        put(m, "billAmount", v.billAmount());
        return json.writeValueAsString(m);
    }

    private static void put(Map<String, String> m, String k, String v) {
        if (v != null && !v.isBlank()) m.put(k, v);
    }
}
