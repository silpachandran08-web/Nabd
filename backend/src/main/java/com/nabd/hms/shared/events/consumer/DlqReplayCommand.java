package com.nabd.hms.shared.events.consumer;

import com.nabd.hms.common.AuditService;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * OPS-017: the DLQ replay CLI for platform on-call, a one-off run of the worker image (docs/architecture/events.md 7.4):
 * <pre>
 * java -jar app.jar --spring.main.web-application-type=none --app.events.consumer.enabled=false \
 *   --app.events.dlq.region=in --app.events.dlq.replay=&lt;dlq entry id&gt;[,&lt;id&gt;...] --app.events.dlq.replayed-by=&lt;operator&gt;
 * </pre>
 * No HTTP surface: only someone who can start the worker with its DB and Valkey credentials can replay. Every replay
 * (and every refused one) is audited. Exit code 0 when all replayed, 1 when any was not, 2 on bad arguments.
 */
@Component
class DlqReplayCommand implements ApplicationRunner {

    private final ConsumerGroups groups;
    private final AuditService audit;
    private final Environment env;
    private final ConfigurableApplicationContext ctx;

    DlqReplayCommand(ConsumerGroups groups, AuditService audit, Environment env, ConfigurableApplicationContext ctx) {
        this.groups = groups;
        this.audit = audit;
        this.env = env;
        this.ctx = ctx;
    }

    @Override
    public void run(ApplicationArguments args) {
        String ids = env.getProperty("app.events.dlq.replay", "");
        if (ids.isBlank()) {
            return; // a normal API or worker start
        }
        int code = replay(env.getProperty("app.events.dlq.region", "in"), Arrays.asList(ids.split(",")),
                env.getProperty("app.events.dlq.replayed-by", ""));
        System.exit(SpringApplication.exit(ctx, () -> code));
    }

    int replay(String region, List<String> ids, String replayedBy) {
        if (replayedBy == null || !replayedBy.matches("[A-Za-z0-9._@-]{2,64}")) {
            System.out.println("app.events.dlq.replayed-by must name the operator (2-64 of A-Z a-z 0-9 . _ @ -)");
            return 2;
        }
        String actor = "dlq-replay-cli:" + replayedBy;
        int failed = 0;
        for (String raw : ids) {
            String id = raw.trim();
            try {
                ConsumerGroups.Replayed r = groups.replay(region, id);
                audit.record(r.tenantId(), "system", null, actor, "Platform CLI", null, "events.dlq.replayed", "event", null, null,
                        Map.of("dlqEntryId", id, "consumer", r.group(), "stream", r.stream(), "entryId", r.entryId(),
                                "eventId", r.eventId(), "outcome", r.outcome().name()));
                System.out.println("replayed " + id + ": " + r.outcome());
            } catch (RuntimeException e) {
                failed++;
                String reason = e.getClass().getSimpleName(); // never the message of a handler error (may carry PHI)
                audit.recordPlatform("system", null, actor, "Platform CLI", null, "events.dlq.replay_failed", "event", null, null,
                        Map.of("region", region, "dlqEntryId", id.length() > 45 ? id.substring(0, 45) : id, "reason", reason));
                System.out.println("not replayed " + id + ": " + reason);
            }
        }
        return failed == 0 ? 0 : 1;
    }
}
