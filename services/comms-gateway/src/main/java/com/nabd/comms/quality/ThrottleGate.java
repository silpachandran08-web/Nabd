package com.nabd.comms.quality;

import com.nabd.comms.send.Lane;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/** COM-021: the lanes a sender may send on, for {@code SenderLoop}. Read from sender_accounts and cached 10 s (a pause takes effect within 1 min). */
public class ThrottleGate implements Function<UUID, Set<Lane>> {

    static final Duration TTL = Duration.ofSeconds(10);

    private record Entry(Set<Lane> lanes, Instant at) {}

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final Clock clock;
    private final Map<UUID, Entry> cache = new ConcurrentHashMap<>();

    public ThrottleGate(JdbcTemplate jdbc, TransactionTemplate tx, Clock clock) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.clock = clock;
    }

    @Override
    public Set<Lane> apply(UUID senderId) {
        Instant now = clock.instant();
        Entry e = cache.get(senderId);
        if (e != null && e.at().plus(TTL).isAfter(now)) {
            return e.lanes();
        }
        String mode = tx.execute(st -> {
            jdbc.queryForObject("SELECT set_config('app.sender_admin', 'on', true)", String.class);
            return jdbc.queryForObject("SELECT throttle_mode FROM sender_accounts WHERE id = ?", String.class, senderId);
        });
        Set<Lane> lanes = QualityPolicy.Mode.valueOf(mode).lanes();
        cache.put(senderId, new Entry(lanes, now));
        return lanes;
    }
}
