package com.nabd.hms.auth.dto;

public record MfaEnrollResponse(String secretBase32, String otpauthUri) {
}
