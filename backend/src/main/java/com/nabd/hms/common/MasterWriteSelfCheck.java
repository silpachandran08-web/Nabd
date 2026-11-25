package com.nabd.hms.common;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * PLT-061: boot guard for after the Control Plane cut-over. OFF by default (the platform package still writes master.* from
 * Core today); switched on with app.master-cutover-check.enabled=true once the writers have moved. Core then refuses to
 * start, naming the offenders, while its database role could still change a master table: directly, through a role it can
 * SET ROLE to, or through a SECURITY DEFINER function. Passes trivially once the master schema is dropped.
 */
@Component
@ConditionalOnProperty(name = "app.master-cutover-check.enabled", havingValue = "true")
public class MasterWriteSelfCheck implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(MasterWriteSelfCheck.class);

    private final JdbcTemplate jdbc;

    public MasterWriteSelfCheck(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void run(ApplicationArguments args) {
        List<String> problems = violations(jdbc);
        if (!problems.isEmpty()) {
            throw new IllegalStateException("master write self-check failed, refusing to start: " + String.join("; ", problems));
        }
        log.info("master write self-check passed: this role cannot change master.*");
    }

    /** Package-visible so a test can run it as a role of its choice. */
    static List<String> violations(JdbcTemplate jdbc) {
        List<String> out = new ArrayList<>();
        // MEMBER, not USAGE: a NOINHERIT membership (nabd_app in nabd_platform) still allows SET ROLE
        List<String> tables = jdbc.queryForList(
                "SELECT DISTINCT 'master.' || c.relname || ' (as ' || r.rolname || ')' FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace "
                        + "CROSS JOIN pg_roles r WHERE n.nspname = 'master' AND c.relkind IN ('r','p') AND pg_has_role(current_user, r.oid, 'MEMBER') "
                        + "AND has_table_privilege(r.oid, c.oid, 'INSERT,UPDATE,DELETE,TRUNCATE') ORDER BY 1", String.class);
        if (!tables.isEmpty()) {
            out.add("the app role can still write: " + tables);
        }
        // shortcut: a text match on the function body, not a SQL parser; it can only over-report (a comment), which fails closed
        List<String> functions = jdbc.queryForList(
                "SELECT p.oid::regprocedure::text FROM pg_proc p WHERE p.prosecdef AND has_function_privilege(current_user, p.oid, 'EXECUTE') "
                        + "AND p.prosrc ~* '(insert\\s+into|update|delete\\s+from)\\s+(only\\s+)?master\\.' ORDER BY 1", String.class);
        if (!functions.isEmpty()) {
            out.add("SECURITY DEFINER functions the app role may run still write master.*: " + functions);
        }
        return out;
    }
}
