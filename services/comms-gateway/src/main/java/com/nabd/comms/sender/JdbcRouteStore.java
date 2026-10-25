package com.nabd.comms.sender;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Repository
public class JdbcRouteStore implements RouteStore {

    static final Duration WINDOW = Duration.ofHours(24);
    private final JdbcTemplate jdbc;

    public JdbcRouteStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void recordOutbound(UUID tenantId, UUID senderAccountId, byte[] phoneHash, Instant at) {
        jdbc.queryForObject("SELECT set_config('app.tenant_id', ?, true)", String.class, tenantId.toString());
        jdbc.update("""
                INSERT INTO phone_routes (phone_hash, sender_account_id, tenant_id, last_outbound_at, window_expires_at) VALUES (?,?,?,?,?)
                ON CONFLICT (phone_hash, sender_account_id, tenant_id) DO UPDATE
                  SET last_outbound_at = GREATEST(phone_routes.last_outbound_at, EXCLUDED.last_outbound_at),
                      window_expires_at = GREATEST(phone_routes.window_expires_at, EXCLUDED.window_expires_at)
                """, phoneHash, senderAccountId, tenantId, Timestamp.from(at), Timestamp.from(at.plus(WINDOW)));
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public List<Route> candidates(UUID senderAccountId, byte[] phoneHash) {
        jdbc.queryForObject("SELECT set_config('app.route_lookup', 'on', true)", String.class); // the one cross-tenant read, see V5
        return jdbc.query("""
                SELECT tenant_id, last_outbound_at, window_expires_at FROM phone_routes
                WHERE phone_hash = ? AND sender_account_id = ? ORDER BY last_outbound_at DESC
                """, (rs, i) -> new Route(rs.getObject(1, UUID.class), rs.getTimestamp(2).toInstant(), rs.getTimestamp(3).toInstant()),
                phoneHash, senderAccountId);
    }
}
