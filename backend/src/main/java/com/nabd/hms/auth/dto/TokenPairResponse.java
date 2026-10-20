package com.nabd.hms.auth.dto;

// FEF-009: refreshExpiresIn (seconds) lets the BFF give the refresh cookie a Max-Age equal to the session's real expiry.
public record TokenPairResponse(String accessToken, String refreshToken, long expiresIn, long refreshExpiresIn) {
}
