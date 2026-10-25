package com.nabd.hms.platform.plans.dto;

import java.util.List;

public record PlanCatalogueEntry(String code, String name, int priceMinor, String currency, String pricingBasis,
                                 List<String> modules, Limits limits) {
    public record Limits(int seats, Integer branches, Integer visitsPerMonth) {
    }
}
