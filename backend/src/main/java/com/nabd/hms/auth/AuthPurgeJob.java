package com.nabd.hms.auth;

import com.nabd.hms.shared.BatchedPurge;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * OPS-013: nightly retention purge of sessions (30 days after expiry) and login_attempts (90 days), in batches of
 * app.purge.batch-size through the V1541 SECURITY DEFINER functions (SKIP LOCKED, lock_timeout 1 s). Retention, batch,
 * pause, cron and zone are per-deployment (= per-region cell) config; runs 03:15 cell-local by default (outside OPD).
 * Failures propagate so JobRegistry counts them (the 3-nights alert, infra/terraform/modules/cell/aws/monitoring.tf).
 */
@Component
public class AuthPurgeJob {

    private static final Logger log = LoggerFactory.getLogger(AuthPurgeJob.class);
    private final JdbcTemplate jdbc;
    private final int sessionDays;
    private final int loginAttemptDays;
    private final int batch;
    private final long pauseMs;
    private final int maxBatches;

    AuthPurgeJob(JdbcTemplate jdbc,
                 @Value("${app.purge.sessions-days:30}") int sessionDays,
                 @Value("${app.purge.login-attempts-days:90}") int loginAttemptDays,
                 @Value("${app.purge.batch-size:10000}") int batch,
                 @Value("${app.purge.pause-ms:200}") long pauseMs,
                 @Value("${app.purge.max-batches:500}") int maxBatches) {
        this.jdbc = jdbc;
        this.sessionDays = sessionDays;
        this.loginAttemptDays = loginAttemptDays;
        this.batch = batch;
        this.pauseMs = pauseMs;
        this.maxBatches = maxBatches;
    }

    @Scheduled(cron = "${app.purge.cron:0 15 3 * * *}", zone = "${app.purge.zone:Asia/Kolkata}")
    @SchedulerLock(name = "sessions-purge", lockAtMostFor = "PT2H")
    public void purgeSessions() {
        int n = BatchedPurge.run(b -> jdbc.queryForObject("SELECT purge_sessions(?, ?)", Integer.class, sessionDays, b), batch, pauseMs, maxBatches);
        log.info("sessions purge removed {} rows", n);
    }

    @Scheduled(cron = "${app.purge.cron:0 15 3 * * *}", zone = "${app.purge.zone:Asia/Kolkata}")
    @SchedulerLock(name = "login-attempts-purge", lockAtMostFor = "PT2H")
    public void purgeLoginAttempts() {
        int n = BatchedPurge.run(b -> jdbc.queryForObject("SELECT purge_login_attempts(?, ?)", Integer.class, loginAttemptDays, b), batch, pauseMs, maxBatches);
        log.info("login_attempts purge removed {} rows", n);
    }
}
