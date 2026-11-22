package com.nabd.hms.packages.dto;

import java.math.BigDecimal;

/** FIN-025: gstOpinionRequired is true where the clinic's tax regime needs the GST opinion decision id before the split can be on. */
public record PackageSettingsResponse(BigDecimal priceFloorPercent, boolean taxSplitEnabled, String gstOpinionDecisionId,
                                      boolean gstOpinionRequired) {
}
