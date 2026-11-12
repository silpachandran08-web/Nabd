package com.nabd.hms.clinical;

import com.nabd.hms.clinical.api.ClaimSummary;
import com.nabd.hms.common.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

@Component
class ClaimSummaryJdbc implements ClaimSummary {

    private final JdbcTemplate jdbc;
    private final ClinicalReadScope scopes;

    ClaimSummaryJdbc(JdbcTemplate jdbc, ClinicalReadScope scopes) {
        this.jdbc = jdbc;
        this.scopes = scopes;
    }

    @Override
    public Summary forVisit(Jwt caller, UUID queueEntryId) {
        List<String> perms = caller.getClaimAsStringList("permissions");
        if (perms == null || !perms.contains("clinical:view")) {
            throw new ApiException(HttpStatus.FORBIDDEN, "clinical-summary-grant-required", "Clinical summary access required",
                    "A claim pack includes the consultation summary: it needs the clinical view permission.");
        }
        ReadScope scope = scopes.resolve(caller);
        scopes.requireVisit(scope, queueEntryId);
        UUID tenantId = scope.tenantId();
        var note = jdbc.query("SELECT diagnosis FROM clinical_notes WHERE tenant_id = ? AND queue_entry_id = ? AND status = 'signed'",
                (rs, i) -> rs.getString(1), tenantId, queueEntryId).stream().findFirst();
        String diagnosis = note.isPresent() && scope.sees(FieldGrantMasker.DIAGNOSIS) ? note.get() : null;
        List<String> meds = jdbc.query("SELECT pi.drug_name, pi.dosage, pi.frequency, pi.duration FROM prescription_items pi " +
                        "JOIN prescriptions p ON p.tenant_id = pi.tenant_id AND p.id = pi.prescription_id " +
                        "WHERE p.tenant_id = ? AND p.queue_entry_id = ? AND p.status = 'signed' ORDER BY pi.display_order",
                (rs, i) -> join(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4)), tenantId, queueEntryId);
        boolean rxSigned = Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM prescriptions WHERE tenant_id = ? AND queue_entry_id = ? AND status = 'signed')", Boolean.class, tenantId, queueEntryId));
        return new Summary(note.isPresent(), diagnosis, rxSigned, meds);
    }

    private static String join(String... parts) {
        StringBuilder b = new StringBuilder();
        for (String p : parts) {
            if (p != null && !p.isBlank()) b.append(b.length() > 0 ? " · " : "").append(p.strip());
        }
        return b.toString();
    }
}
