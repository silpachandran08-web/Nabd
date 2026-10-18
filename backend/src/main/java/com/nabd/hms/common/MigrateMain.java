package com.nabd.hms.common;

import org.flywaydb.core.Flyway;

/**
 * OPS-003: the separate migrate job. Runs Flyway as nabd_migrator; the API itself runs with Flyway off and never holds
 * migrator credentials. Env: MIGRATE_DB_URL, MIGRATE_DB_USER, MIGRATE_DB_PASSWORD, MIGRATE_LOCATIONS (default the image path).
 * Run: java -cp app.jar -Dloader.main=com.nabd.hms.common.MigrateMain org.springframework.boot.loader.launch.PropertiesLauncher
 */
public final class MigrateMain {

    private MigrateMain() {
    }

    public static int migrate(String url, String user, String password, String locations) {
        return Flyway.configure().dataSource(url, user, password).locations(locations).load().migrate().migrationsExecuted;
    }

    public static void main(String[] args) {
        String url = require("MIGRATE_DB_URL");
        String locations = System.getenv().getOrDefault("MIGRATE_LOCATIONS", "filesystem:/app/db/migrations");
        int n = migrate(url, require("MIGRATE_DB_USER"), require("MIGRATE_DB_PASSWORD"), locations);
        System.out.println("migrations executed: " + n);
    }

    private static String require(String name) {
        String v = System.getenv(name);
        if (v == null || v.isBlank()) {
            throw new IllegalStateException(name + " is not set");
        }
        return v;
    }
}
