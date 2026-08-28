package com.nabd.hms.platform.fleet;

import com.nabd.hms.common.Cursor;
import com.nabd.hms.platform.fleet.dto.FleetPage;
import com.nabd.hms.platform.fleet.dto.FleetSummaryResponse;
import com.nabd.hms.platform.fleet.dto.PageMeta;
import com.nabd.hms.platform.fleet.dto.TenantSummaryResponse;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

import static com.nabd.hms.platform.fleet.FleetModels.TenantSummary;

@Service
public class FleetService {

    private final FleetRepository repo;

    FleetService(FleetRepository repo) {
        this.repo = repo;
    }

    public FleetPage list(int limit, String cursor) {
        Cursor after = cursor == null ? null : Cursor.decode(cursor);
        List<TenantSummary> rows = repo.listPage(limit + 1,
                after == null ? null : after.createdAt(), after == null ? null : after.id());

        boolean hasMore = rows.size() > limit;
        List<TenantSummary> page = hasMore ? rows.subList(0, limit) : rows;
        String nextCursor = hasMore
                ? new Cursor(page.get(page.size() - 1).createdAt(), page.get(page.size() - 1).id()).encode()
                : null;

        return new FleetPage(page.stream().map(this::toResponse).toList(), new PageMeta(nextCursor, limit));
    }

    public FleetSummaryResponse summary() {
        List<String[]> rows = repo.listStatusesAndRegions();
        Map<String, Integer> byStatus = new LinkedHashMap<>();
        TreeSet<String> regions = new TreeSet<>();
        for (String[] row : rows) {
            byStatus.merge(row[0], 1, Integer::sum);
            regions.add(row[1]);
        }
        return new FleetSummaryResponse(rows.size(), byStatus, List.copyOf(regions));
    }

    private TenantSummaryResponse toResponse(TenantSummary t) {
        return new TenantSummaryResponse(t.id(), t.slug(), t.name(), t.region(), t.status(),
                t.brandName(), t.ownerName(), t.ownerEmail(), t.createdAt());
    }
}
