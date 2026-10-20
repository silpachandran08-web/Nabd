package com.nabd.hms.shared.relay;

import com.nabd.hms.common.AuditService;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Map;

/**
 * OPS-016 (QA-7 D2): redrive CLI for platform on-call when Valkey lost stream entries the relay had already XADDed
 * (docs/architecture/events.md section 8, "Redrive"). A one-off run of the worker image:
 * <pre>
 * java -jar app.jar --spring.main.web-application-type=none --app.events.consumer.enabled=false \
 *   --app.relay.redrive-from=2026-10-20T08:00:00Z --app.relay.redrive-to=2026-10-20T09:00:00Z --app.relay.redrive-by=&lt;operator&gt;
 * </pre>
 * Puts the domain rows the relay finished in [from, to) back to pending (redrive_relay_events, V1701); the running relay
 * XADDs them again and consumers dedupe on the CloudEvents id. Done rows are kept 7 days (OutboxPurgeJob), so that is
 * the longest window. Audited in the platform chain. Exit code 0 = redriven, 2 = bad arguments.
 */
@Component
public class RelayRedriveCommand implements ApplicationRunner {

    private final JdbcTemplate jdbc;
    private final AuditService audit;
    private final Environment env;
    private final ConfigurableApplicationContext ctx;

    RelayRedriveCommand(JdbcTemplate jdbc, AuditService audit, Environment env, ConfigurableApplicationContext ctx) {
        this.jdbc = jdbc;
        this.audit = audit;
        this.env = env;
        this.ctx = ctx;
    }

    @Override
    public void run(ApplicationArguments args) {
        String from = env.getProperty("app.relay.redrive-from", "");
        if (from.isBlank()) {
            return; // a normal API or worker start
        }
        int code = redrive(from, env.getProperty("app.relay.redrive-to", ""), env.getProperty("app.relay.redrive-by", ""));
        System.exit(SpringApplication.exit(ctx, () -> code));
    }

    public int redrive(String from, String to, String redrivenBy) {
        if (redrivenBy == null || !redrivenBy.matches("[A-Za-z0-9._@-]{2,64}")) {
            System.out.println("app.relay.redrive-by must name the operator (2-64 of A-Z a-z 0-9 . _ @ -)");
            return 2;
        }
        Instant f;
        Instant t;
        try {
            f = Instant.parse(from);
            t = Instant.parse(to);
        } catch (DateTimeParseException | NullPointerException e) {
            System.out.println("app.relay.redrive-from / -to must be ISO-8601 instants, e.g. 2026-10-20T08:00:00Z");
            return 2;
        }
        if (!t.isAfter(f) || Duration.between(f, t).compareTo(Duration.ofDays(7)) > 0) {
            System.out.println("the redrive window must be non-empty and at most 7 days (done rows are purged after 7)");
            return 2;
        }
        Integer n = jdbc.queryForObject("SELECT redrive_relay_events(?, ?)", Integer.class, Timestamp.from(f), Timestamp.from(t));
        audit.recordPlatform("system", null, "relay-redrive-cli:" + redrivenBy, "Platform CLI", null, "events.relay.redriven", "outbox",
                null, null, Map.of("from", f.toString(), "to", t.toString(), "rows", n == null ? 0 : n));
        System.out.println("redriven " + n + " events finished in [" + f + ", " + t + ")");
        return 0;
    }
}
