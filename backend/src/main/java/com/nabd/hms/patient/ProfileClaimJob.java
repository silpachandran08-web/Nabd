package com.nabd.hms.patient;

import com.nabd.hms.common.TenantScope;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * IDC-012: one {@link ProfileClaimService#runDaily()} per clinic on that clinic's own date (ClinicClock). It runs hourly so a
 * clinic's midnight is picked up within the hour in every time zone; the pass is idempotent (one claim per patient and birthday,
 * an ended claim is not ended again). A failing clinic (say, no approved profile_claim_invite template) does not stop the others.
 */
@Component
public class ProfileClaimJob {

    private static final Logger log = LoggerFactory.getLogger(ProfileClaimJob.class);

    private final JdbcTemplate jdbc;
    private final TenantScope tenantScope;
    private final ProfileClaimService service;

    ProfileClaimJob(JdbcTemplate jdbc, TenantScope tenantScope, ProfileClaimService service) {
        this.jdbc = jdbc;
        this.tenantScope = tenantScope;
        this.service = service;
    }

    @Scheduled(fixedDelayString = "${app.profile-claims.interval-ms:3600000}", initialDelayString = "${app.profile-claims.interval-ms:3600000}")
    @SchedulerLock(name = "profile-claims", lockAtMostFor = "PT50M")
    public void scheduled() {
        runOnce();
    }

    /** One pass over every clinic; returns the clinics processed. */
    public int runOnce() {
        int n = 0;
        for (UUID tenantId : jdbc.queryForList("SELECT id FROM tenants", UUID.class)) {
            try {
                tenantScope.run(tenantId, () -> service.runDaily());
                n++;
            } catch (RuntimeException e) {
                log.warn("profile claim pass failed for tenant {} ({}); the next run retries", tenantId, e.getClass().getSimpleName());
            }
        }
        return n;
    }
}
