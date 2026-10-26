package com.nabd.hms.clinical;

import com.nabd.hms.clinical.api.ImportedAllergies;
import com.nabd.hms.common.TenantContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Component
class ImportedAllergiesJdbc implements ImportedAllergies {

    private final JdbcTemplate jdbc;
    private final TenantContext tenant;

    ImportedAllergiesJdbc(JdbcTemplate jdbc, TenantContext tenant) {
        this.jdbc = jdbc;
        this.tenant = tenant;
    }

    /** shortcut: severity is the column's required value 'moderate' because the old list has none; clinical sign-off on that default is open. */
    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public int addUnconfirmed(UUID patientId, List<String> substances, UUID recordedBy) {
        int added = 0;
        for (String s : substances) {
            added += jdbc.update("INSERT INTO patient_allergies (tenant_id, patient_id, substance, severity, source, unconfirmed, recorded_by) " +
                            "SELECT ?, ?, ?, 'moderate', 'outside_record', true, ? WHERE NOT EXISTS " +
                            "(SELECT 1 FROM patient_allergies WHERE tenant_id = ? AND patient_id = ? AND active AND lower(substance) = lower(?))",
                    tenant.current(), patientId, s, recordedBy, tenant.current(), patientId, s);
        }
        return added;
    }
}
