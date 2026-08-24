package com.nabd.hms.platform.ticket.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record RaiseTicketRequest(
        @NotBlank @Size(max = 200) String subject,
        @NotBlank @Size(max = 4000) String description,
        @Pattern(regexp = "low|normal|high|urgent") String priority
) {
    public String priorityOrDefault() {
        return priority == null ? "normal" : priority;
    }
}
