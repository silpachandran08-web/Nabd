package com.nabd.hms.auth.dto;

public record TokenPairResponse(String accessToken, String refreshToken, long expiresIn) {
}
