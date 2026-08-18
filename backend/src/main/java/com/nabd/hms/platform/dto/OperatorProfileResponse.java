package com.nabd.hms.platform.dto;

import java.util.List;
import java.util.UUID;

public record OperatorProfileResponse(
        UUID id,
        String name,
        String email,
        String role,
        List<String> permissions
) {
}
