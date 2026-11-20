package com.nabd.hms.platform.tenant;

import com.nabd.hms.common.TenantSettingsPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.UUID;

/** PLT-036: the clinic-side writes on tenants, moved unchanged from organization (setup, data gate) and queue (day start). */
@Repository
class TenantSettingsRepository implements TenantSettingsPort {

    private final JdbcTemplate jdbc;

    TenantSettingsRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void updateProfile(UUID tenantId, String name, String timezone, String taxId, String taxIdType, String whatsappNumber, String[] specialties) {
        jdbc.update("UPDATE tenants SET name = ?, timezone = ?, tax_id = ?, tax_id_type = ?, whatsapp_number = ?, specialties = ?::text[], updated_at = now() WHERE id = ?",
                name, timezone, taxId, taxIdType, whatsappNumber, specialties, tenantId);
    }

    @Override
    public void markSetupCompleted(UUID tenantId) {
        jdbc.update("UPDATE tenants SET setup_completed_at = now(), updated_at = now() WHERE id = ?", tenantId);
    }

    @Override
    public void updateConsentContact(UUID tenantId, String name, String email, String phone) {
        jdbc.update("UPDATE tenants SET consent_contact_name = ?, consent_contact_email = ?, consent_contact_phone = ?, updated_at = now() WHERE id = ?",
                name, email, phone, tenantId);
    }

    @Override
    public void setDayStart(UUID tenantId, int minute) {
        jdbc.update("UPDATE tenants SET day_start_minute = ?, updated_at = now() WHERE id = ?", minute, tenantId);
    }

    @Override
    public void openDataGate(UUID tenantId) {
        jdbc.update("UPDATE tenants SET data_gate = 'live', updated_at = now() WHERE id = ? AND data_gate = 'demo_only'", tenantId);
    }
}
