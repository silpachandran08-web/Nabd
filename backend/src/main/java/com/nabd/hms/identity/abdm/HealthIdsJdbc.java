package com.nabd.hms.identity.abdm;

import com.nabd.hms.common.FieldCipher;
import com.nabd.hms.common.TenantContext;
import com.nabd.hms.identity.api.HealthIds;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Component
class HealthIdsJdbc implements HealthIds {

    private final JdbcTemplate jdbc;
    private final TenantContext tenantContext;
    private final FieldCipher cipher;

    HealthIdsJdbc(JdbcTemplate jdbc, TenantContext tenantContext, FieldCipher cipher) {
        this.jdbc = jdbc;
        this.tenantContext = tenantContext;
        this.cipher = cipher;
    }

    @Override
    public PatientIds ofPatient(UUID patientId) {
        UUID tenant = tenantContext.current();
        Map<String, String> ids = new HashMap<>();
        // a value that cannot be unsealed fails the read: a Patient silently missing its ABHA would be matched to the wrong record downstream
        jdbc.query("SELECT id, id_type, value_enc FROM patient_identifiers WHERE tenant_id = ? AND patient_id = ? AND id_type IN (?, ?)",
                rs -> {
                    ids.put(rs.getString("id_type"), new String(cipher.decrypt(rs.getBytes("value_enc"),
                            FieldCipher.Aad.of(tenant, "patient_identifiers", "value_enc", rs.getObject("id", UUID.class))), StandardCharsets.UTF_8));
                }, tenant, patientId, AbhaIdentifiers.NUMBER, AbhaIdentifiers.ADDRESS);
        return new PatientIds(ids.get(AbhaIdentifiers.NUMBER), ids.get(AbhaIdentifiers.ADDRESS));
    }

    @Override
    public Optional<String> facilityHfrId() {
        return jdbc.queryForList("SELECT hfr_id FROM tenant_integrations_abdm WHERE tenant_id = ? AND hfr_id IS NOT NULL AND verified_at IS NOT NULL",
                String.class, tenantContext.current()).stream().findFirst();
    }
}
