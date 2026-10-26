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

    TerritoryService(TerritoryRepository repo, RegionRules regions) {
        this.repo = repo;
        this.regions = regions;
    }

    public List<RegionSummaryResponse> list() {
        Map<String, ClinicCounts> clinics = repo.clinicCounts();
        Map<String, Long> users = repo.userCounts();
        Map<String, RegionMrr> mrr = repo.mrrByRegion();
        Map<String, List<PlanMixEntry>> planMix = repo.planMixByRegion();

        // every region in the table, so one with zero clinics still shows as a coverage gap
        return regions.all().stream().map(RegionRules.Region::code).map(region -> {
            ClinicCounts c = clinics.getOrDefault(region, new ClinicCounts(0, 0, 0, List.of()));
            RegionMrr m = mrr.get(region);
            List<PlanMixResponse> mix = planMix.getOrDefault(region, List.of()).stream()
                    .map(e -> new PlanMixResponse(e.planCode(), e.tenantCount())).toList();
            return new RegionSummaryResponse(region, c.total(), c.active(), users.getOrDefault(region, 0L),
                    m == null ? 0 : m.mrrCents(), m == null ? defaultCurrency(region) : m.currency(),
                    c.taxIdTypes(), mix, c.newLast30d());
        }).toList();
    }

    private String defaultCurrency(String region) {
        return regions.region(region).currency();
    }
}
