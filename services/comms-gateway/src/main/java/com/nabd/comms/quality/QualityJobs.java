package com.nabd.comms.quality;

import com.nabd.comms.provider.Provider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

/**
 * COM-021: hourly block-rate signal (errors 131049/131050 over the sends of the last 24 h; the STOP rate is not in this signal yet) and the
 * daily poll of every number's quality rating and tier. Without a Provider bean (the Meta client is COM-007's) the poll is skipped and logged.
 */
@Component
public class QualityJobs {

    private static final Logger log = LoggerFactory.getLogger(QualityJobs.class);

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final SenderQualityService service;
    private final ObjectProvider<Provider> provider;
    private final Clock clock;

    QualityJobs(JdbcTemplate jdbc, TransactionTemplate tx, SenderQualityService service, ObjectProvider<Provider> provider, Clock clock) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.service = service;
        this.provider = provider;
        this.clock = clock;
    }

    record Sender(UUID id, String phoneNumberId) {}

    List<Sender> senders() {
        return tx.execute(st -> {
            jdbc.queryForObject("SELECT set_config('app.sender_admin', 'on', true)", String.class);
            return jdbc.query("SELECT id, phone_number_id FROM sender_accounts WHERE status <> 'disabled'", (rs, i) -> new Sender(rs.getObject(1, UUID.class), rs.getString(2)));
        });
    }

    // OPS-013: no @SchedulerLock on purpose: the gateway has no ShedLock; the job is idempotent (same inputs, same state, history only on change)
    @Scheduled(cron = "${comms.quality.block-rate-cron:0 5 * * * *}")
    public void hourly() {
        for (Sender s : senders()) {
            try {
                blockRate(s);
            } catch (RuntimeException e) { // one sender must not stop the others
                log.warn("block rate failed for sender {}: {}", s.id(), e.getClass());
            }
        }
        service.publishPending();
    }

    void blockRate(Sender s) {
        Timestamp since = Timestamp.from(clock.instant().minus(Duration.ofHours(24)));
        long[] c = tx.execute(st -> {
            jdbc.queryForObject("SELECT set_config('app.sender_admin', 'on', true)", String.class);
            return jdbc.queryForObject("SELECT count(*) FILTER (WHERE error_code IN ('131049','131050')), count(*) FROM outbound_messages "
                    + "WHERE sender_account_id = ? AND updated_at >= ? AND status IN ('sent','delivered','read','failed','blocked')",
                    (rs, i) -> new long[] {rs.getLong(1), rs.getLong(2)}, s.id(), since);
        });
        service.observeBlockRate(s.id(), QualityPolicy.blockRate(c[0], c[1]));
    }

    /** phone_number_quality_update / account_update webhook: the provider's current answer is authoritative, so ask it now (pause within 1 min). */
    public void onQualityWebhook(String phoneNumberId) {
        Provider p = provider.getIfAvailable();
        if (p == null) {
            log.warn("quality webhook ignored: no provider client configured");
            return;
        }
        senders().stream().filter(s -> s.phoneNumberId().equals(phoneNumberId)).forEach(s -> service.refresh(s.id(), s.phoneNumberId(), p));
    }

    // OPS-013: no @SchedulerLock on purpose: see hourly()
    @Scheduled(cron = "${comms.quality.poll-cron:0 30 3 * * *}")
    public void dailyPoll() {
        Provider p = provider.getIfAvailable();
        if (p == null) {
            log.warn("quality poll skipped: no provider client configured");
            return;
        }
        for (Sender s : senders()) {
            try {
                service.refresh(s.id(), s.phoneNumberId(), p);
            } catch (RuntimeException e) {
                log.warn("quality poll failed for sender {}: {}", s.id(), e.getClass());
            }
        }
    }
}
