package com.nabd.hms.billing.dto;

import java.util.List;

public record OtcChargesResponse(String currency, List<ChargeResponse> charges) {
}
