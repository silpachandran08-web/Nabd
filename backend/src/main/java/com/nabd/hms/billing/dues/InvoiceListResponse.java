package com.nabd.hms.billing.dues;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

@Schema(name = "InvoiceListResponse")
public record InvoiceListResponse(List<InvoiceListItem> items, String nextCursor) {
}
