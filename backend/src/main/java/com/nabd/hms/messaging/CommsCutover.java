package com.nabd.hms.messaging;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.UUID;

/** COM-008: per-tenant comms.cutover flag. True = the remote Comms Gateway sends for the tenant and Core's in-process sender must not. */
@Component
public class CommsCutover {

    private final JdbcTemplate jdbc;

    CommsCutover(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Runs inside the tenant transaction of the caller (RLS limits the read to the bound tenant). */
    @org.springframework.transaction.annotation.Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
    public boolean remote(UUID tenantId) {
        return Boolean.TRUE.equals(jdbc.query("SELECT remote FROM comms_cutover_flags WHERE tenant_id = ?",
                rs -> rs.next() ? rs.getBoolean(1) : Boolean.FALSE, tenantId));
    }
}
