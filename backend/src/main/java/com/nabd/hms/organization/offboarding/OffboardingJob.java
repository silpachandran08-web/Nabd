package com.nabd.hms.organization.offboarding;

import com.nabd.hms.common.EmailSender;
import com.nabd.hms.common.TenantScope;
import com.nabd.hms.common.tenantkey.TenantKeyService;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * ADM-016: the erasure worker. Each pass looks at every clinic that is leaving, in that clinic's own tenant scope, and takes
 * its request through: claim (cooling-off ended: 'erasing', clinic 'offboarded') -> stored files deleted -> data key destroyed
 * -> rows erased -> certificate signed and stored -> certificate link emailed. Every step is its own transaction and safe to
 * repeat, so a pass that fails or a crashed instance is picked up by the next pass where it stopped. Nothing is erased when
 * the signing key is missing (prod without DELETION_CERT_KEYS_*): the pass stops before the claim.
 */
@Component
public class OffboardingJob {

    private static final Logger log = LoggerFactory.getLogger(OffboardingJob.class);

    private final JdbcTemplate jdbc;
    private final TenantScope tenantScope;
    private final OffboardingService service;
    private final TenantKeyService tenantKeys;
    private final DeletionCertificateSigner signer;
    private final EmailSender email;

    OffboardingJob(JdbcTemplate jdbc, TenantScope tenantScope, OffboardingService service, TenantKeyService tenantKeys, DeletionCertificateSigner signer, EmailSender email) {
        this.jdbc = jdbc;
        this.tenantScope = tenantScope;
        this.service = service;
        this.tenantKeys = tenantKeys;
        this.signer = signer;
        this.email = email;
    }

    @Scheduled(fixedDelayString = "${app.offboarding.interval-ms:300000}", initialDelayString = "${app.offboarding.interval-ms:300000}")
    @SchedulerLock(name = "adm-offboarding-erasure", lockAtMostFor = "PT1H")
    public void scheduled() {
        runOnce();
    }

    /** One pass; returns the certificates issued. */
    public int runOnce() {
        try {
            signer.keys();
        } catch (IllegalStateException e) {
            log.error("ALERT offboarding erasure is paused: the deletion-certificate signing key is not configured");
            return 0;
        }
        int issued = 0;
        // shortcut: every leaving or left clinic is asked each pass (one indexed query each); claim across clinics through a definer function when they pass a few hundred
        for (UUID tenant : jdbc.queryForList("SELECT id FROM tenants WHERE status IN ('offboarding', 'offboarded')", UUID.class)) {
            try {
                if (process(tenant)) {
                    issued++;
                }
            } catch (RuntimeException e) {
                log.error("offboarding erasure of tenant {} stopped and will be retried ({})", tenant, e.getClass().getSimpleName()); // log-phi-ok: tenant id and exception class
            }
        }
        return issued;
    }

    private boolean process(UUID tenant) {
        OffboardingService.Due due = tenantScope.run(tenant, service::claim);
        if (due == null) {
            return false;
        }
        if (!due.erased()) {
            tenantScope.run(tenant, service::deleteStoredObjects);
            tenantKeys.shredOffboarded(tenant, due.id());
            tenantScope.run(tenant, service::erase);
        }
        OffboardingService.Issued cert = tenantScope.run(tenant, () -> service.certify(due.id()));
        if (cert.email() != null && !cert.email().isBlank()) { // after the commit; the body holds the link only
            email.send(cert.email(), "Your Nabd data deletion certificate",
                    "Your clinic's data on Nabd has been erased and its encryption key destroyed. The signed certificate (add /pdf for a printable copy):\n\n"
                            + service.certificateUrl(cert));
        }
        tenantScope.run(tenant, () -> service.notified(due.id()));
        return due.certificateId() == null;
    }
}
