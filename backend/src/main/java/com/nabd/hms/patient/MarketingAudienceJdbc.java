package com.nabd.hms.patient;

import com.nabd.hms.common.ClinicClock;
import com.nabd.hms.common.TenantContext;
import com.nabd.hms.patient.api.MarketingAudience;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@Component
class MarketingAudienceJdbc implements MarketingAudience {

    private final JdbcTemplate jdbc;
    private final TenantContext tenantContext;
    private final ClinicClock clock;

    MarketingAudienceJdbc(JdbcTemplate jdbc, TenantContext tenantContext, ClinicClock clock) {
        this.jdbc = jdbc;
        this.tenantContext = tenantContext;
        this.clock = clock;
    }

    @Override
    public List<UUID> adults(UUID afterId, int limit) {
        // born on or before today minus 18 years (clinic day): a 29 February birthday turns 18 on 1 March in a common year
        LocalDate cutoff = clock.today(tenantContext.current()).minusYears(18);
        return jdbc.queryForList("SELECT p.id FROM patients p WHERE p.status = 'active' AND p.dob IS NOT NULL AND p.dob <= ? "
                + "AND (?::uuid IS NULL OR p.id > ?::uuid) "
                + "AND COALESCE((SELECT m.withdrawn_at IS NULL FROM consents m WHERE m.patient_id = p.id AND m.consent_type = 'marketing' ORDER BY m.created_at DESC, m.id DESC LIMIT 1), false) "
                + "AND COALESCE((SELECT d.withdrawn_at IS NULL FROM consents d WHERE d.patient_id = p.id AND d.consent_type = 'data_processing' ORDER BY d.created_at DESC, d.id DESC LIMIT 1), false) "
                + "ORDER BY p.id LIMIT ?", UUID.class, cutoff, afterId, afterId, Math.min(Math.max(limit, 1), 1000));
    }
}
