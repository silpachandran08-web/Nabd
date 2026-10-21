package com.nabd.hms.messaging;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Claim-check response (COM-004): what the sender needs to render and address one message. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record NotificationClaim(UUID requestId, UUID tenantId, String purpose, String templateKey, String language,
                                List<String> params, Recipient recipient, String consentClass, Instant notBefore, Instant notAfter) {

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @io.swagger.v3.oas.annotations.media.Schema(name = "NotificationClaimRecipient")
    public record Recipient(String phoneE164, UUID patientId) {
    }
}
