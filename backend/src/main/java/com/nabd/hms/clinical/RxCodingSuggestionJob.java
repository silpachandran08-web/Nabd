package com.nabd.hms.clinical;

import com.nabd.hms.common.TenantScope;
import com.nabd.hms.organization.api.RegionRules;
import com.nabd.hms.platform.tenant.TenantRegionService;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.UUID;

/** CLN-021: nightly batch of coding suggestions per tenant (500 lines each); one failing tenant does not stop the rest. */
@Component
class RxCodingSuggestionJob {

    private static final Logger log = LoggerFactory.getLogger(RxCodingSuggestionJob.class);

    private final RxCodingSuggestionService service;
    private final TenantScope tenantScope;
    private final TenantRegionService tenants;
    private final RegionRules regions;

    RxCodingSuggestionJob(RxCodingSuggestionService service, TenantScope tenantScope, TenantRegionService tenants, RegionRules regions) {
        this.service = service;
        this.tenantScope = tenantScope;
        this.tenants = tenants;
        this.regions = regions;
    }

    @Scheduled(cron = "${app.rx-coding.cron:0 40 2 * * *}")
    @SchedulerLock(name = "rx-coding-suggestions", lockAtMostFor = "PT30M")
    public void nightly() {
        for (RegionRules.Region region : regions.all()) {
            for (UUID tenant : tenants.tenantIdsIn(region.code())) {
                try {
                    tenantScope.run(tenant, () -> service.run(500));
                } catch (RuntimeException e) {
                    log.warn("rx coding suggestions failed for tenant {}", tenant);
                }
            }
        }
    }
}
