package com.nabd.hms.platform.territory;

import com.nabd.hms.organization.api.RegionRules;
import com.nabd.hms.platform.territory.dto.PlanMixResponse;
import com.nabd.hms.platform.territory.dto.RegionSummaryResponse;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

import static com.nabd.hms.platform.territory.TerritoryModels.PlanMixEntry;
import static com.nabd.hms.platform.territory.TerritoryRepository.ClinicCounts;
import static com.nabd.hms.platform.territory.TerritoryRepository.RegionMrr;

@Service
public class TerritoryService {


    private final TerritoryRepository repo;
    private final RegionRules regions;

    private final com.nabd.hms.common.tenantkey.TenantKeyService tenantKeys;

    TerritoryService(TerritoryRepository repo, RegionRules regions, com.nabd.hms.common.tenantkey.TenantKeyService tenantKeys) {
        this.tenantKeys = tenantKeys;
        this.repo = repo;
        this.regions = regions;
    }

    public List<RegionSummaryResponse> list() {
        Map<String, ClinicCounts> clinics = repo.clinicCounts();
        Map<String, Long> users = repo.userCounts();
        Map<String, RegionMrr> mrr = repo.mrrByRegion();
        Map<String, List<PlanMixEntry>> planMix = repo.planMixByRegion();

        Map<String, com.nabd.hms.common.tenantkey.TenantKeyService.RegionKeyInfo> kms = new java.util.HashMap<>(); // PLT-045
        tenantKeys.regionKeys().forEach(k -> kms.put(k.region(), k));
        // every region in the table, so one with zero clinics still shows as a coverage gap
        return regions.all().stream().map(RegionRules.Region::code).map(region -> {
            ClinicCounts c = clinics.getOrDefault(region, new ClinicCounts(0, 0, 0, List.of()));
            RegionMrr m = mrr.get(region);
            List<PlanMixResponse> mix = planMix.getOrDefault(region, List.of()).stream()
                    .map(e -> new PlanMixResponse(e.planCode(), e.tenantCount())).toList();
            return new RegionSummaryResponse(region, c.total(), c.active(), users.getOrDefault(region, 0L),
                    m == null ? 0 : m.mrrCents(), m == null ? defaultCurrency(region) : m.currency(),
                    c.taxIdTypes(), mix, c.newLast30d(),
                    kms.containsKey(region) ? kms.get(region).createdAt() : null,
                    kms.containsKey(region) ? kms.get(region).lastRotatedAt() : null);
        }).toList();
    }

    private String defaultCurrency(String region) {
        return regions.region(region).currency();
    }
}
