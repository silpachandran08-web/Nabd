package com.nabd.hms.auth.dto;

public record MfaChallengeResponse(String challengeId, String method, long expiresIn) {
}
