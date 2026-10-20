package com.nabd.hms.platform.access.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.UUID;

/** PLT-056: ticketId and scope are checked in SupportAccessService (TICKET_REQUIRED / SCOPE_INVALID codes);
 * minutes defaults to 60 and may not exceed it. */
public record RequestGrantRequest(
        @NotNull UUID tenantId,
        UUID ticketId,
        String scope,
        @NotBlank @Size(max = 500) String reason,
        Integer minutes
) {
}
