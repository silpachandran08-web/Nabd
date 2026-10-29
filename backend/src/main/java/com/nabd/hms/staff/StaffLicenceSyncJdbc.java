package com.nabd.hms.staff;

import com.nabd.hms.iam.api.StaffLicenceSync;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Date;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** ADM-032: {@link StaffLicenceSync} over the staff table (iam is its only writer). Every method needs the caller's tenant transaction. */
@Component
class StaffLicenceSyncJdbc implements StaffLicenceSync {

    private final JdbcTemplate jdbc;

    StaffLicenceSyncJdbc(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void apply(UUID staffId, boolean bookableOnline, LocalDate licenceExpiresOn) {
        jdbc.update("UPDATE staff SET bookable_online = ?, licence_expires_on = COALESCE(?, licence_expires_on) WHERE tenant_id = app_tenant_id() AND id = ?",
                bookableOnline, licenceExpiresOn == null ? null : Date.valueOf(licenceExpiresOn), staffId);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public Set<UUID> hiddenFromOnlineBooking() {
        return new HashSet<>(jdbc.queryForList("SELECT id FROM staff WHERE tenant_id = app_tenant_id() AND NOT bookable_online", UUID.class));
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<Contact> contact(UUID staffId) {
        return jdbc.query("SELECT id, name, mobile_phone FROM staff WHERE tenant_id = app_tenant_id() AND id = ? AND status = 'active'",
                (rs, i) -> new Contact(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3)), staffId).stream().findFirst();
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public List<Contact> owners() {
        return jdbc.query("SELECT s.id, s.name, s.mobile_phone FROM staff s JOIN roles r ON r.id = s.role_id " +
                        "WHERE s.tenant_id = app_tenant_id() AND r.built_in = true AND s.status = 'active' ORDER BY s.created_at",
                (rs, i) -> new Contact(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3)));
    }
}
