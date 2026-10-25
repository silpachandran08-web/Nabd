package com.nabd.hms.common;

import com.nabd.hms.support.ApiTestBase;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** PLT-009: who may touch the master schema (operators, sessions, plans, subscriptions, audit of the control plane). */
class MasterSchemaGrantTest extends ApiTestBase {

    private final JdbcTemplate su = new JdbcTemplate(superuserDataSource());

    @Test
    void controlRoleIsNologinNobypassrlsAndOwnsDmlOnEveryMasterTable() {
        var role = su.queryForMap("SELECT rolcanlogin, rolbypassrls, rolsuper FROM pg_roles WHERE rolname = 'nabd_control'");
        assertThat(role).containsEntry("rolcanlogin", false).containsEntry("rolbypassrls", false).containsEntry("rolsuper", false);
        assertThat(su.queryForObject("SELECT has_schema_privilege('nabd_control', 'master', 'USAGE')", Boolean.class)).isTrue();
        List<String> missing = su.queryForList("""
                SELECT c.relname FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
                WHERE n.nspname = 'master' AND c.relkind IN ('r', 'p')
                  AND NOT (has_table_privilege('nabd_control', c.oid, 'SELECT') AND has_table_privilege('nabd_control', c.oid, 'INSERT')
                       AND has_table_privilege('nabd_control', c.oid, 'UPDATE') AND has_table_privilege('nabd_control', c.oid, 'DELETE'))""", String.class);
        assertThat(missing).isEmpty();
        assertThat(su.queryForObject("SELECT count(*) FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace WHERE n.nspname = 'master' AND c.relkind = 'r'", Integer.class))
                .isGreaterThan(5);
        assertThat(su.queryForObject("SELECT has_function_privilege('nabd_control', 'search_audit_log(uuid,text,text,timestamptz,timestamptz,bigint,int)', 'EXECUTE')", Boolean.class)).isTrue();
    }

    /** PLT-009 acceptance "nabd_app has no privileges on schema master": NOT MET yet. It needs the platform package on a nabd_control DataSource first. */
    @Test
    @Tag("known-defect-PLT-009")
    void clinicRuntimeRoleHasNoPrivilegesOnMaster() {
        assertThat(su.queryForObject("SELECT has_schema_privilege('nabd_app', 'master', 'USAGE')", Boolean.class)).isFalse();
        assertThat(su.queryForList("""
                SELECT c.relname FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
                WHERE n.nspname = 'master' AND c.relkind IN ('r', 'p') AND has_any_column_privilege('nabd_app', c.oid, 'SELECT,INSERT,UPDATE')""", String.class)).isEmpty();
    }
}
