package com.nabd.hms.platform.fleet.dto;

import java.util.List;

public record FleetPage(List<TenantSummaryResponse> data, PageMeta page) {
}
