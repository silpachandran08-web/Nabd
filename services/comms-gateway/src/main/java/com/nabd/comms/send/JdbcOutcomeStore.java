package com.nabd.comms.send;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;

/**
 * COM-020 / COM-010: outcomes land in outbound_messages (RLS: the caller binds the item's tenant). A dead letter is status 'failed' with
 * its error_code, so it is visible to the same queries as any failed message. Statuses only move forward (webhooks may already be ahead).
 */
public class JdbcOutcomeStore implements SenderLoop.Outcomes {

    private final JdbcTemplate jdbc;

    public JdbcOutcomeStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void sent(LaneScheduler.Item it, String providerMessageId, Instant at) {
        jdbc.update("UPDATE outbound_messages SET status = 'sent', provider_message_id = ?, attempts = ?, last_attempt_at = ?, updated_at = ? "
                + "WHERE id = ? AND status IN ('queued','scheduled','sending')", providerMessageId, it.attempts() + 1, Timestamp.from(at), Timestamp.from(at), it.messageId());
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void deadLettered(LaneScheduler.Item it, String errorCode, Instant at) {
        jdbc.update("UPDATE outbound_messages SET status = 'failed', error_code = ?, attempts = ?, last_attempt_at = ?, updated_at = ? "
                + "WHERE id = ? AND status IN ('queued','scheduled','sending')", errorCode, it.attempts() + 1, Timestamp.from(at), Timestamp.from(at), it.messageId());
    }
}
