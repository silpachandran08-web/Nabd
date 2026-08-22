package com.nabd.hms.packages.dto;

import java.math.BigDecimal;

public record RefundPreviewItem(String name, int quantityConsumed, BigDecimal listValue) {
}
