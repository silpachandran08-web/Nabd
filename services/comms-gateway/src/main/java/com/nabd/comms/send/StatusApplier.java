package com.nabd.comms.send;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * COM-010: applies a delivery callback (sent / delivered / read / failed) to outbound_messages. Duplicate and reordered callbacks are
 * harmless because a status only moves forward: sent &lt; failed &lt; delivered &lt; read (a late 'delivered' never undoes 'read').
 * The caller binds the tenant (RLS).
 */
public class StatusApplier {

    private static final String RANK = "CASE %s WHEN 'queued' THEN 0 WHEN 'scheduled' THEN 0 WHEN 'sending' THEN 1 WHEN 'sent' THEN 2 "
            + "WHEN 'failed' THEN 3 WHEN 'delivered' THEN 4 WHEN 'read' THEN 5 ELSE 9 END";

    private final JdbcTemplate jdbc;

    public StatusApplier(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** @return true if the row moved forward */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean apply(String providerMessageId, String status) {
        return jdbc.update("UPDATE outbound_messages SET status = ?, updated_at = now() WHERE provider_message_id = ? AND "
                + RANK.formatted("status") + " < " + RANK.formatted("?"), status, providerMessageId, status) == 1;
    }
}
