package com.nabd.hms.organization.series.dto;

import java.util.UUID;

public record InvoiceSeriesResponse(UUID id, String kind, String prefix, String fiscalYear, long nextNumber,
                                    UUID taxRegistrationId, String prefixPattern, int fyStartMonth, int pad) {
}
