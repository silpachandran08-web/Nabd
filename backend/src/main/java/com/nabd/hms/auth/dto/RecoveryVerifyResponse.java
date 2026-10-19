package com.nabd.hms.auth.dto;

public record RecoveryVerifyResponse(String accessToken, String refreshToken, long expiresIn, int remainingCodes) {
}
