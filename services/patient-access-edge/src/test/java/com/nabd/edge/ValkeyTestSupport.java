package com.nabd.edge;

import redis.embedded.RedisServer;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.ServerSocket;

/** Redis 6.2 (embedded-redis) as the Valkey stand-in, as in Core's tests; stop()/restart() simulate an outage on the same port. */
final class ValkeyTestSupport {

    private final int port;
    private final RedisServer server;

    private ValkeyTestSupport(int port) throws IOException {
        this.port = port;
        this.server = RedisServer.newRedisServer().port(port).bind("127.0.0.1").setting("save \"\"").setting("appendonly no").build();
    }

    static ValkeyTestSupport start() {
        try (ServerSocket s = new ServerSocket(0)) {
            int p = s.getLocalPort();
            s.close();
            ValkeyTestSupport v = new ValkeyTestSupport(p);
            v.server.start();
            return v;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    String url() {
        return "redis://127.0.0.1:" + port;
    }

    synchronized void stop() throws IOException {
        if (server.isActive()) {
            server.stop();
        }
    }

    synchronized void restart() throws IOException {
        if (!server.isActive()) {
            server.start();
        }
    }
}
