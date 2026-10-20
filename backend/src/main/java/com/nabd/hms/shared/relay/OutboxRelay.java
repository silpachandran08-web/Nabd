package com.nabd.hms.shared.relay;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.postgresql.PGConnection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.data.redis.connection.RedisStreamCommands.XAddOptions;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * OPS-016 (ADR-03): moves committed domain events (outbox rows with ce_type) to Valkey Streams ev:&lt;region&gt;:&lt;domain&gt;.
 * One leader per region database: pg_try_advisory_lock on a dedicated direct connection, which also LISTENs on
 * 'outbox' (V1551); standbys retry the lock every lock-retry-ms, and a dead leader's session ends with its lock.
 * The leader drains on every NOTIFY and at least every poll-ms (fallback when a notification is lost).
 * Each batch: claim (claim_relay_events, leased) -> pipelined XADD MAXLEN ~ n -> finish_relay_events, fenced:
 * done only after every XADD of the batch succeeded, otherwise the batch goes back to pending (Valkey down) and
 * relay_errors_total counts it. Delivery is at-least-once (a failed or partial batch is sent again): consumers
 * dedupe on the CloudEvents id. Never calls an HTTP API (RelayArchitectureTest).
 */
public class OutboxRelay implements SmartLifecycle {

    /** Session advisory lock key, one relay per region database ("NabdRel"). */
    static final long LOCK_KEY = 0x4E616264_52656CL;
    static final byte[] FIELD = "ce".getBytes(StandardCharsets.UTF_8);

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

    /** Opens the direct connection for the lock and LISTEN. */
    public interface DirectConnection {
        Connection open() throws SQLException;
    }

    record Claimed(long id, int attempts, String stream, String envelope) {
    }

    private final JdbcTemplate jdbc;
    private final StringRedisTemplate valkey;
    private final RelayProperties props;
    private final DirectConnection direct;
    private final XAddOptions trim;
    private final AtomicInteger leader = new AtomicInteger();
    private final Counter errors;
    private final Counter published;

    private volatile boolean running;
    private volatile Thread thread;
    private volatile Connection lockConnection;

    public OutboxRelay(JdbcTemplate jdbc, StringRedisTemplate valkey, RelayProperties props, DirectConnection direct, MeterRegistry meters) {
        this.jdbc = jdbc;
        this.valkey = valkey;
        this.props = props;
        this.direct = direct;
        this.trim = XAddOptions.maxlen(props.streamMaxlen()).approximateTrimming(true);
        Gauge.builder("relay.leader", leader, AtomicInteger::get).description("1 while this instance holds the relay lock").register(meters);
        this.errors = meters.counter("relay.errors");
        this.published = meters.counter("relay.events.published");
    }

    public static DirectConnection direct(String url, String username, String password) {
        return () -> {
            Properties p = new Properties();
            p.setProperty("user", username);
            p.setProperty("password", password == null ? "" : password);
            p.setProperty("ApplicationName", "nabd-outbox-relay");
            p.setProperty("tcpKeepAlive", "true");
            return DriverManager.getConnection(url, p);
        };
    }

    public boolean isLeader() {
        return leader.get() == 1;
    }

    @Override
    public synchronized void start() {
        if (running) {
            return;
        }
        running = true;
        Thread t = new Thread(this::run, "outbox-relay");
        t.setDaemon(true);
        thread = t;
        t.start();
    }

    /** Also what a killed pod looks like to the database: the lock connection closes and the lock is released. */
    @Override
    public synchronized void stop() {
        running = false;
        Thread t = thread;
        closeQuietly(lockConnection);
        if (t != null) {
            t.interrupt();
            try {
                t.join(5000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        thread = null;
        leader.set(0);
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    private void run() {
        while (running) {
            try (Connection c = direct.open()) {
                lockConnection = c;
                c.setAutoCommit(true);
                while (running && !tryLock(c)) {
                    Thread.sleep(props.lockRetryMs());
                }
                if (!running) {
                    return;
                }
                leader.set(1);
                log.info("outbox relay: this instance is the leader");
                try (Statement st = c.createStatement()) {
                    st.execute("LISTEN outbox");
                }
                PGConnection pg = c.unwrap(PGConnection.class);
                while (running) {
                    if (drain()) {
                        pg.getNotifications(props.pollMs()); // wakes on NOTIFY, or after poll-ms when one was lost
                    } else {
                        Thread.sleep(props.errorBackoffMs());
                        pg.getNotifications(1); // still notices a lost lock connection while Valkey is down
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception e) {
                if (running) {
                    errors.increment();
                    log.warn("relay_errors_total: outbox relay lost its lock connection ({}); retrying", e.toString());
                    sleepQuietly(props.lockRetryMs());
                }
            } finally {
                leader.set(0);
                lockConnection = null;
            }
        }
    }

    private static boolean tryLock(Connection c) throws SQLException {
        try (var ps = c.prepareStatement("SELECT pg_try_advisory_lock(?)")) {
            ps.setLong(1, LOCK_KEY);
            try (var rs = ps.executeQuery()) {
                return rs.next() && rs.getBoolean(1);
            }
        }
    }

    /** Relays batches until one comes back short. False when a batch failed (it is back to pending). */
    boolean drain() {
        while (running) {
            List<Claimed> batch;
            try {
                batch = jdbc.query("SELECT * FROM claim_relay_events(?, ?)", (rs, i) -> new Claimed(
                        rs.getLong("id"), rs.getInt("attempts"), rs.getString("stream"), rs.getString("envelope")),
                        props.batchSize(), props.leaseSeconds());
            } catch (RuntimeException e) {
                errors.increment();
                log.warn("relay_errors_total: outbox relay claim failed ({})", e.toString());
                return false;
            }
            if (batch.isEmpty()) {
                return true;
            }
            try {
                xadd(batch);
            } catch (RuntimeException e) {
                errors.increment();
                log.warn("relay_errors_total: XADD of {} events failed, they stay pending in Postgres ({})", batch.size(), e.toString());
                finish(batch, false, e.getClass().getSimpleName());
                return false;
            }
            int done = finish(batch, true, null);
            published.increment(done);
            if (done < batch.size()) {
                log.warn("outbox relay: {} of {} events were reclaimed by another relay before finishing (fenced)", batch.size() - done, batch.size());
            }
            if (batch.size() < props.batchSize()) {
                return true;
            }
        }
        return true;
    }

    private void xadd(List<Claimed> batch) {
        // one round trip per batch; any failed entry fails the whole batch (RedisPipelineException)
        List<Object> ids = valkey.executePipelined((RedisCallback<Object>) conn -> {
            for (Claimed e : batch) {
                conn.streamCommands().xAdd(StreamRecords.rawBytes(Map.of(FIELD, e.envelope().getBytes(StandardCharsets.UTF_8)))
                        .withStreamKey(e.stream().getBytes(StandardCharsets.UTF_8)), trim);
            }
            return null;
        });
        if (ids.size() != batch.size() || ids.contains(null)) {
            throw new IllegalStateException("XADD returned " + ids.size() + " ids for " + batch.size() + " events");
        }
    }

    private int finish(List<Claimed> batch, boolean done, String error) {
        Long[] ids = batch.stream().map(Claimed::id).toArray(Long[]::new);
        Integer[] attempts = batch.stream().map(Claimed::attempts).toArray(Integer[]::new);
        try {
            return jdbc.query(con -> {
                var ps = con.prepareStatement("SELECT * FROM finish_relay_events(?, ?, ?, ?)");
                ps.setArray(1, con.createArrayOf("bigint", ids));
                ps.setArray(2, con.createArrayOf("integer", attempts));
                ps.setBoolean(3, done);
                ps.setString(4, error);
                return ps;
            }, (rs, i) -> rs.getLong(1)).size();
        } catch (RuntimeException e) {
            // rows stay 'processing' until their lease ends, then the next claim takes them again
            errors.increment();
            log.warn("relay_errors_total: finishing {} relayed events failed ({}); they are reclaimed after the lease", batch.size(), e.toString());
            return 0;
        }
    }

    private static void closeQuietly(Connection c) {
        if (c != null) {
            try {
                c.close();
            } catch (SQLException ignored) {
                // closing anyway
            }
        }
    }

    private static void sleepQuietly(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
