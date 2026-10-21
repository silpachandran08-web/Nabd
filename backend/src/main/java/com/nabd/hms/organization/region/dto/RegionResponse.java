package com.nabd.hms.organization.region.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.util.List;

/** One row of the regions reference table (non-PHI). */
public record RegionResponse(String code, String name, String currency, int minorUnits, String taxRegime,
                             BigDecimal standardTaxRate, String regulator, String defaultTimezone,
                             RegionPhoneRules phoneRules, String cell) {

    @Schema(name = "RegionPhoneRules")
    public record RegionPhoneRules(String countryCode, List<Integer> nsnLength) {
    }
}
