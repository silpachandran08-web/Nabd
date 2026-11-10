package com.nabd.hms.patient.proxy;

import com.nabd.hms.common.ClinicClock;
import com.nabd.hms.common.TenantContext;
import com.nabd.hms.patient.api.CareRecipients;
import com.nabd.hms.patient.api.ConsentRegistry;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** IDC-014: {@link CareRecipients} over patients.guardian_id, patient_proxies and the consent registry. Read live, never cached. */
@Service
@Transactional(propagation = Propagation.MANDATORY)
class CareRecipientsJdbc implements CareRecipients {

    private static final Set<String> RECORD_SCOPES = Set.of("BOOK_AND_RECORDS", "FULL");

    private final JdbcTemplate jdbc;
    private final ConsentRegistry consents;
    private final ClinicClock clock;
    private final TenantContext tenantContext;

    CareRecipientsJdbc(JdbcTemplate jdbc, ConsentRegistry consents, ClinicClock clock, TenantContext tenantContext) {
        this.jdbc = jdbc;
        this.consents = consents;
        this.clock = clock;
        this.tenantContext = tenantContext;
    }

    @Override
    public List<Recipient> recipients(UUID subject, MessageClass c) {
        Map<String, Object> s = jdbc.queryForList("SELECT phone_digits, guardian_id FROM patients WHERE id = ? AND status = 'active'", subject)
                .stream().findFirst().orElse(null);
        ConsentRegistry.Purpose purpose = c == MessageClass.MARKETING ? ConsentRegistry.Purpose.MARKETING : ConsentRegistry.Purpose.MESSAGING;
        if (s == null || !consents.isGranted(subject, purpose)) {
            return List.of();
        }
        List<Recipient> out = new ArrayList<>();
        out.add(new Recipient(subject, "self"));
        if (c == MessageClass.MARKETING) {
            return out;
        }
        Object own = s.get("phone_digits");
        if (s.get("guardian_id") != null) {
            add(out, (UUID) s.get("guardian_id"), "guardian", own);
        }
        for (Map<String, Object> p : jdbc.queryForList("SELECT proxy_patient_id, relation, scope, status FROM patient_proxies WHERE subject_patient_id = ? AND status <> 'revoked' ORDER BY invited_at", subject)) {
            boolean records = "active".equals(p.get("status")) && RECORD_SCOPES.contains(p.get("scope"));
            if (c == MessageClass.LOGISTICS || records) {
                add(out, (UUID) p.get("proxy_patient_id"), (String) p.get("relation"), own);
            }
        }
        return out;
    }

    private void add(List<Recipient> out, UUID caregiver, String relation, Object subjectDigits) {
        if (out.stream().anyMatch(r -> r.patientId().equals(caregiver))) {
            return;
        }
        String digits = jdbc.queryForList("SELECT phone_digits FROM patients WHERE id = ? AND status = 'active'", String.class, caregiver).stream().findFirst().orElse(null);
        if (digits != null && !Objects.equals(digits, subjectDigits) && consents.isGranted(caregiver, ConsentRegistry.Purpose.MESSAGING)) {
            out.add(new Recipient(caregiver, relation));
        }
    }

    @Override
    public boolean elderly(UUID subject) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM patients WHERE id = ? AND dob <= ?::date - INTERVAL '60 years')",
                Boolean.class, subject, java.sql.Date.valueOf(clock.today(tenantContext.current()))));
    }
}
