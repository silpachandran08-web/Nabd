package com.nabd.hms.organization.export;

import com.nabd.hms.common.EmailSender;
import com.nabd.hms.common.TenantScope;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * ADM-015: the export worker. Each pass looks at every clinic in its own tenant scope (no RLS bypass), claims at most one started
 * export per clinic, writes one dataset per transaction (the drawer polls the progress), then stores the archive and sends the
 * links: WhatsApp inside the last transaction (outbox), email after it commits. A failure marks the job failed with a generic
 * message (no row data in logs) so the owner can start again. Jobs left half-done by a crashed instance are failed after 2 hours.
 */
@Component
public class DataExportJob {

    private static final Logger log = LoggerFactory.getLogger(DataExportJob.class);

    private final JdbcTemplate jdbc;
    private final TenantScope tenantScope;
    private final DataExportService service;
    private final EmailSender email;

    DataExportJob(JdbcTemplate jdbc, TenantScope tenantScope, DataExportService service, EmailSender email) {
        this.jdbc = jdbc;
        this.tenantScope = tenantScope;
        this.service = service;
        this.email = email;
    }

    @Scheduled(fixedDelayString = "${app.data-export.interval-ms:30000}", initialDelayString = "${app.data-export.interval-ms:30000}")
    @SchedulerLock(name = "data-export", lockAtMostFor = "PT2H")
    public void scheduled() {
        runOnce();
    }

    /** One pass; returns the exports completed. */
    public int runOnce() {
        int done = 0;
        for (UUID tenant : jdbc.queryForList("SELECT id FROM tenants", UUID.class)) {
            tenantScope.run(tenant, () -> jdbc.update("UPDATE data_export_jobs SET status = 'failed', error_message = 'The export was interrupted. Start it again.' "
                    + "WHERE tenant_id = ? AND status = 'running' AND started_at < now() - interval '2 hours'", tenant));
            DataExportService.Claimed job = tenantScope.run(tenant, service::claimNext);
            if (job != null && process(tenant, job)) {
                done++;
            }
        }
        return done;
    }

    private boolean process(UUID tenant, DataExportService.Claimed job) {
        DataExportService.Ready ready;
        try {
            ExportArchive zip = new ExportArchive();
            Map<String, Map<String, Long>> counts = new LinkedHashMap<>();
            for (String dataset : job.datasets()) {
                tenantScope.run(tenant, () -> service.writeDataset(job, dataset, zip, counts));
            }
            ready = tenantScope.run(tenant, () -> service.finish(job, zip, counts));
        } catch (RuntimeException e) {
            log.warn("export {} failed for tenant {} ({})", job.id(), tenant, e.getClass().getSimpleName());
            tenantScope.run(tenant, () -> service.fail(job.id()));
            return false;
        }
        if (ready.email() != null && !ready.email().isBlank()) {
            try { // the body names the clinic's export and the link only: no patient data
                email.send(ready.email(), "Your Nabd data export is ready",
                        "Your clinic data export is ready. Open this link and confirm with the code sent to your mobile. It works for 72 hours and 3 downloads:\n\n"
                                + service.downloadUrl(ready.token()));
            } catch (RuntimeException e) {
                log.warn("export {} email not sent ({})", job.id(), e.getClass().getSimpleName());
            }
        }
        return true;
    }
}
