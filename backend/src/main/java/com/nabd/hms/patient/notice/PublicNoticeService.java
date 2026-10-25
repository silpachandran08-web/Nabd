package com.nabd.hms.patient.notice;

import com.nabd.hms.common.TenantScope;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.Optional;
import java.util.UUID;

/** IDC-003: anonymous lookup of one notice by clinic slug. Not transactional: the clinic row (tenants, not tenant data) is read first, then the notice inside that clinic's tenant transaction. */
@Service
class PublicNoticeService {

    private final JdbcTemplate jdbc;
    private final TenantScope tenants;
    private final NoticeService notices;

    PublicNoticeService(JdbcTemplate jdbc, TenantScope tenants, NoticeService notices) {
        this.jdbc = jdbc;
        this.tenants = tenants;
        this.notices = notices;
    }

    Optional<NoticeRepository.Notice> find(String tenantSlug, String version, String language) {
        Optional<UUID> tenant = jdbc.query("SELECT id FROM tenants WHERE slug = ?::citext", (rs, i) -> rs.getObject(1, UUID.class), tenantSlug).stream().findFirst();
        return tenant.flatMap(t -> tenants.run(t, () -> notices.find(version, language)));
    }
}
