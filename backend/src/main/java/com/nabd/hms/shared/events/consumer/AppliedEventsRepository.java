package com.nabd.hms.shared.events.consumer;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** OPS-017: projector_applied_events (V1690). Runs inside the consumer's tenant transaction (RLS). */
@Repository
class AppliedEventsRepository {

    private final JdbcTemplate jdbc;

    AppliedEventsRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Records the event for this group; APPLIED means the handler should run now, in this transaction. */
    ConsumerGroups.Outcome claim(String group, EventConsumer.Event e, boolean versionGated) {
        Long stored = null;
        if (versionGated && e.subject() != null) {
            // serialises two replicas applying versions of the same subject, so the max() below cannot go stale
            jdbc.queryForObject("SELECT pg_advisory_xact_lock(hashtextextended(?, 17))::text", String.class,
                    group + ":" + e.subject());
            stored = jdbc.queryForObject("""
                    SELECT max(version) FROM projector_applied_events
                    WHERE tenant_id = ? AND consumer_group = ? AND subject = ?""", Long.class, e.tenantId(), group, e.subject());
        }
        int inserted = jdbc.update("""
                INSERT INTO projector_applied_events (tenant_id, consumer_group, event_id, subject, version)
                VALUES (?,?,?,?,?) ON CONFLICT DO NOTHING""", e.tenantId(), group, e.id(), e.subject(), e.version());
        if (inserted == 0) {
            return ConsumerGroups.Outcome.DUPLICATE;
        }
        return stored != null && e.version() <= stored ? ConsumerGroups.Outcome.STALE : ConsumerGroups.Outcome.APPLIED;
    }
}
