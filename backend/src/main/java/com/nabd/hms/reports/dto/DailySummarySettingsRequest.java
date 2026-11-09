package com.nabd.hms.reports.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.List;

/** ADM-043 PUT /v1/reports/daily-summary/settings. sendAt: 'close' (default) or a clinic-local HH:mm. */
public record DailySummarySettingsRequest(boolean enabled, boolean combined,
                                          @NotNull @Size(max = 20) List<DailySummarySettingsResponse.DailySummaryRecipient> recipients,
                                          @Pattern(regexp = "close|([01][0-9]|2[0-3]):[0-5][0-9]") String sendAt) {
}
