package com.nabd.hms.patient;

import com.nabd.hms.patient.api.ConsentRegistry;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.UUID;

@Component
class ConsentRegistryJdbc implements ConsentRegistry {

    private final JdbcTemplate jdbc;

    ConsentRegistryJdbc(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public boolean isGranted(UUID patientId, Purpose purpose) {
        // consents are append-only: a withdrawal is its own newer row, so the newest row decides (same rule as messaging)
        return jdbc.query("SELECT withdrawn_at IS NULL FROM consents WHERE patient_id = ? AND consent_type = ? " +
                        "ORDER BY created_at DESC, id DESC LIMIT 1",
                (rs, i) -> rs.getBoolean(1), patientId, purpose.dbValue()).stream().findFirst().orElse(false);
    }

    @Override
    public Optional<NoticeRef> currentNotice(String language) {
        return jdbc.query("SELECT version, language, template_key FROM privacy_notices WHERE language = ? AND effective_from <= now() " +
                        "ORDER BY effective_from DESC, created_at DESC LIMIT 1",
                (rs, i) -> new NoticeRef(rs.getString(1), rs.getString(2), rs.getString(3)), language).stream().findFirst();
    }
}
