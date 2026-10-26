package com.nabd.comms;

import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** COM-007 acceptance 1: the Comms role has no SELECT on any Core table (and cannot reach a Core database at all). */
class CommsGrantTest {

    @Test
    void commsRoleCannotReadAnyCoreTable() throws Exception {
        TestInfra.freshComms("comms_grants");
        // a Core stand-in: a separate database in the same cluster, default grants (Core's tables are owned by nabd_migrator, nabd_app has DML)
        TestInfra.exec("postgres", "CREATE DATABASE core_decoy");
        TestInfra.exec("core_decoy", "CREATE TABLE patients (id uuid primary key, name text); INSERT INTO patients VALUES (gen_random_uuid(), 'x')");

        assertThatThrownBy(() -> {
            try (Connection c = DriverManager.getConnection(TestInfra.url("core_decoy"), "comms_app", TestInfra.APP_PW);
                 Statement s = c.createStatement()) {
                s.executeQuery("SELECT * FROM patients");
            }
        }).isInstanceOf(SQLException.class).hasMessageContaining("permission denied");

        // and inside its own database it may read only the tables of the Comms schema (nothing else exists there)
        try (Connection c = DriverManager.getConnection(TestInfra.url("comms_grants"), TestInfra.PG.getUsername(), TestInfra.PG.getPassword());
             Statement s = c.createStatement()) {
            s.execute("CREATE ROLE other_owner NOLOGIN; GRANT USAGE, CREATE ON SCHEMA public TO other_owner; SET ROLE other_owner; CREATE TABLE core_like_patients (id int); RESET ROLE"); // a table owned by someone else: no default privilege applies
            ResultSet rs = s.executeQuery("SELECT table_name FROM information_schema.role_table_grants WHERE grantee='comms_app' AND privilege_type='SELECT' ORDER BY 1");
            List<String> granted = new ArrayList<>();
            while (rs.next()) {
                granted.add(rs.getString(1));
            }
            // exactly the tables the Comms migrations created (COM-007..COM-011 added tables after the first two), never the foreign one
            List<String> own = new ArrayList<>();
            ResultSet t = s.executeQuery("SELECT tablename FROM pg_tables WHERE schemaname = 'public' AND tableowner = current_user AND tablename <> 'flyway_schema_history' ORDER BY 1");
            while (t.next()) {
                own.add(t.getString(1));
            }
            assertThat(own).contains("processed_events", "shedlock");
            assertThat(granted).containsExactlyElementsOf(own).doesNotContain("core_like_patients");
        }
        try (Connection c = DriverManager.getConnection(TestInfra.url("comms_grants"), "comms_app", TestInfra.APP_PW); Statement s = c.createStatement()) {
            assertThatThrownBy(() -> s.executeQuery("SELECT * FROM core_like_patients")).hasMessageContaining("permission denied");
            ResultSet r = s.executeQuery("SELECT rolbypassrls, rolsuper FROM pg_roles WHERE rolname = current_user");
            r.next();
            assertThat(r.getBoolean(1)).isFalse();
            assertThat(r.getBoolean(2)).isFalse();
            r = s.executeQuery("SHOW statement_timeout");
            r.next();
            assertThat(r.getString(1)).isEqualTo("5s");
        }
    }
}
