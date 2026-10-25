package com.nabd.comms;

import org.flywaydb.core.Flyway;
import org.testcontainers.postgresql.PostgreSQLContainer;
import redis.embedded.RedisServer;

import java.net.ServerSocket;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;

/** One Postgres container and one Redis 6.2 stand-in for the whole test JVM. Passwords are generated per run. */
final class TestInfra {
    static final PostgreSQLContainer PG = new PostgreSQLContainer("postgres:16-alpine");
    static final String APP_PW = "pw" + java.util.UUID.randomUUID();
    static int redisPort;
    static RedisServer redis;

    static {
        PG.start();
        try (ServerSocket s = new ServerSocket(0)) {
            redisPort = s.getLocalPort();
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
        try {
            redis = RedisServer.newRedisServer().port(redisPort).bind("127.0.0.1").setting("save \"\"").setting("appendonly no").build();
            redis.start();
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }

    static String url(String db) {
        return "jdbc:postgresql://" + PG.getHost() + ":" + PG.getMappedPort(5432) + "/" + db;
    }

    static void exec(String db, String sql) throws Exception {
        try (Connection c = DriverManager.getConnection(url(db), PG.getUsername(), PG.getPassword()); Statement s = c.createStatement()) {
            s.execute(sql);
        }
    }

    /** Creates the database and the two roles (as the bootstrap would, with passwords), then runs the service migrations as the superuser. */
    static void freshComms(String db) throws Exception {
        exec("postgres", "CREATE DATABASE " + db);
        exec("postgres", "DO $$ BEGIN IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='comms_app') THEN CREATE ROLE comms_app LOGIN PASSWORD '" + APP_PW + "'; END IF; END $$");
        Flyway.configure().dataSource(url(db), PG.getUsername(), PG.getPassword()).locations("classpath:db/migration").load().migrate();
    }
}
