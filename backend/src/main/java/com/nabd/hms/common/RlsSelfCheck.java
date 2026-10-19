package com.nabd.hms.common;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * OPS-003: boot guard. Tenant isolation is only real when the connecting role cannot sidestep RLS, so the app refuses
 * to start (naming the offenders) if its role owns an RLS table, bypasses RLS, or any tenant_id table is not FORCEd.
 * Tables that legitimately skip RLS are listed, with a reason, in rls-exemptions.txt.
 */
@Component
public class RlsSelfCheck implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(RlsSelfCheck.class);

    private final JdbcTemplate jdbc;

    public RlsSelfCheck(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void run(ApplicationArguments args) {
        List<String> problems = violations(jdbc, exemptions());
        if (!problems.isEmpty()) {
            throw new IllegalStateException("RLS self-check failed, refusing to start: " + String.join("; ", problems));
        }
        log.info("RLS self-check passed");
    }

    /** Package-visible so a test can run it against a deliberately broken catalog. */
    static List<String> violations(JdbcTemplate jdbc, Set<String> exempt) {
        List<String> out = new ArrayList<>();
        List<String> role = jdbc.query(
                "SELECT rolname, rolsuper, rolbypassrls FROM pg_roles WHERE rolname = current_user",
                (rs, i) -> rs.getBoolean("rolsuper") || rs.getBoolean("rolbypassrls") ? rs.getString("rolname") : null)
                .stream().filter(java.util.Objects::nonNull).toList();
        role.forEach(r -> out.add("role " + r + " is superuser or has BYPASSRLS"));
        List<String> owned = jdbc.queryForList(
                "SELECT n.nspname || '.' || c.relname FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace " +
                        "WHERE c.relkind IN ('r','p') AND c.relowner = (SELECT oid FROM pg_roles WHERE rolname = current_user) " +
                        "AND n.nspname IN ('public','master') ORDER BY 1", String.class);
        if (!owned.isEmpty()) {
            out.add("the app role owns tables (owners ignore RLS unless forced; run Flyway as nabd_migrator): " + owned);
        }
        List<String> unforced = jdbc.queryForList(
                "SELECT n.nspname || '.' || c.relname FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace " +
                        "JOIN pg_attribute a ON a.attrelid = c.oid AND a.attname = 'tenant_id' AND NOT a.attisdropped " +
                        "WHERE c.relkind IN ('r','p') AND n.nspname IN ('public','master') " +
                        "AND NOT (c.relrowsecurity AND c.relforcerowsecurity) ORDER BY 1", String.class)
                .stream().filter(t -> !exempt.contains(t)).collect(Collectors.toList());
        if (!unforced.isEmpty()) {
            out.add("tenant_id tables without FORCE ROW LEVEL SECURITY: " + unforced);
        }
        return out;
    }

    static Set<String> exemptions() {
        try {
            String text = new String(new ClassPathResource("rls-exemptions.txt").getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            return text.lines().map(l -> l.replaceFirst("#.*", "").trim()).filter(l -> !l.isEmpty()).collect(Collectors.toSet());
        } catch (IOException e) {
            throw new IllegalStateException("rls-exemptions.txt missing from the classpath", e);
        }
    }
}
