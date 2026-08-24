package com.nabd.hms.platform.billing.dto;

import java.util.List;

public record SubscriptionPage(List<SubscriptionResponse> data, PageMeta page) {
}
