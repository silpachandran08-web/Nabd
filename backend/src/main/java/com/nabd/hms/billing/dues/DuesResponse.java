package com.nabd.hms.billing.dues;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

@Schema(name = "DuesResponse")
public record DuesResponse(List<DuesRow> items, String nextCursor) {
}
