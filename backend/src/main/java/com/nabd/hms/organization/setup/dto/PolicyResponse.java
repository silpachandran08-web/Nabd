package com.nabd.hms.organization.setup.dto;

public record PolicyResponse(
        String id,
        String policyKey,
        String value,
        int version
) {
}
