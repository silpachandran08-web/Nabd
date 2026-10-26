package com.nabd.comms.testkit;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/** Reaches the package-private {@code com.nabd.comms.TestInfra} (one Postgres container and one Valkey stand-in per test JVM) from other packages. */
public final class Infra {
    private static final Class<?> INFRA;

    static {
        try {
            INFRA = Class.forName("com.nabd.comms.TestInfra");
        } catch (ClassNotFoundException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private Infra() {}

    private static Object field(String name) {
        try {
            Field f = INFRA.getDeclaredField(name);
            f.setAccessible(true);
            return f.get(null);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Object call(String name, Object... args) {
        try {
            for (Method m : INFRA.getDeclaredMethods()) {
                if (m.getName().equals(name) && m.getParameterCount() == args.length) {
                    m.setAccessible(true);
                    return m.invoke(null, args);
                }
            }
            throw new IllegalStateException(name);
        } catch (java.lang.reflect.InvocationTargetException | IllegalAccessException e) {
            throw new IllegalStateException(e.getCause() == null ? e : e.getCause());
        }
    }

    public static int redisPort() { return (int) field("redisPort"); }

    public static String appPassword() { return (String) field("APP_PW"); }

    public static void freshComms(String db) { call("freshComms", db); }

    public static String url(String db) { return (String) call("url", db); }

    /** Superuser template (bypasses RLS): for seeding and counting only. */
    public static JdbcTemplate superuser(String db) {
        var pg = (PostgreSQLContainer) field("PG");
        return new JdbcTemplate(new DriverManagerDataSource(url(db), pg.getUsername(), pg.getPassword()));
    }

    public static DriverManagerDataSource appDataSource(String db) {
        return new DriverManagerDataSource(url(db), "comms_app", appPassword());
    }
}
