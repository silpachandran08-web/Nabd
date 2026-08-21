package com.nabd.hms.platform.billing.dto;

import java.util.List;

public record DiscountPage(List<DiscountResponse> data, PageMeta page) {
}
