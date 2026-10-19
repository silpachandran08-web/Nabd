package com.nabd.hms.support;

import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;

/** CLN-005: marks a seeded staff member as a registered clinician (is_clinician + a registration number when none is on
 * file) so tests written before the licence rule keep exercising what they were written for. Superuser connection:
 * staff is RLS-forced. */
public final class ClinicianFixture {

    private ClinicianFixture() {
    }

    public static ApiTestBase.SeededStaff licensed(ApiTestBase.SeededStaff s) {
        license(s.tenantId(), s.id());
        return s;
    }

    public static void license(UUID tenantId, UUID staffId) {
        new JdbcTemplate(ApiTestBase.superuserDataSource()).update(
                "UPDATE staff SET is_clinician = true, professional_registration_no = coalesce(professional_registration_no, "
                        + "'TEST REG ' || left(id::text, 8)) WHERE tenant_id = ? AND id = ?", tenantId, staffId);
    }
}
