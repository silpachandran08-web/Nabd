package com.nabd.hms.setup.dto;

public record PolicyResponse(
        String id,
        String policyKey,
        String value,
        int version
) {
}
