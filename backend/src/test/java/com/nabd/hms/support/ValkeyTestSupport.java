package com.nabd.hms.support;

import redis.embedded.RedisServer;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.ServerSocket;

/**
 * PLT-008: a real Redis 6.2 server (embedded-redis, the Valkey stand-in: no Valkey image here) on a free local port.
 * {@link #stop()} / {@link #restart()} simulate a Valkey outage mid-test on the same port. Usage (docs/architecture/valkey.md):
 * <pre>
 * static final ValkeyTestSupport VALKEY = ValkeyTestSupport.start();
 * &#64;DynamicPropertySource static void valkey(DynamicPropertyRegistry r) { r.add("app.valkey.url", VALKEY::url); }
 * &#64;AfterAll static void stopValkey() { VALKEY.close(); }
 * </pre>
 * Nothing is persisted (save "" / no AOF); the JVM shutdown hook of embedded-redis also stops it if a test forgets.
 */
public final class ValkeyTestSupport implements AutoCloseable {

    private final int port;
    private final RedisServer server;

    private ValkeyTestSupport(int port) throws IOException {
        this.port = port;
        this.server = RedisServer.newRedisServer().port(port).bind("127.0.0.1")
                .setting("save \"\"").setting("appendonly no").build();
    }

    public static ValkeyTestSupport start() {
        try (ServerSocket s = new ServerSocket(0)) {
            ValkeyTestSupport v = new ValkeyTestSupport(s.getLocalPort());
            s.close();
            v.server.start();
            return v;
        } catch (IOException e) {
            throw new UncheckedIOException("embedded Valkey stand-in did not start", e);
        }
    }

    public int port() {
        return port;
    }

    public String url() {
        return "redis://127.0.0.1:" + port;
    }

    public boolean running() {
        return server.isActive();
    }

    /** Simulates Valkey going down: the process stops, the port refuses connections. */
    public synchronized void stop() {
        try {
            if (server.isActive()) {
                server.stop();
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Brings it back on the same port, empty (as after a managed failover without persistence). */
    public synchronized void restart() {
        try {
            if (!server.isActive()) {
                server.start();
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public void close() {
        stop();
    }
}
