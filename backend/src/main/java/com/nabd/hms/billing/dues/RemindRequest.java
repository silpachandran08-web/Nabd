package com.nabd.hms.billing.dues;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

@Schema(name = "DuesRemindRequest")
public record RemindRequest(@NotNull @Pattern(regexp = "whatsapp|sms") String channel, boolean includePaymentLink) {
}
