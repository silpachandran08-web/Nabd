package com.nabd.hms.auth.api;

import java.time.Instant;
import java.util.UUID;

/**
 * ADM-002: a one-time code sent to a staff member's registered mobile (WhatsApp, through the OTP outbox) to confirm one
 * sensitive action. Runs in the caller's tenant transaction; reads the bound tenant. Each challenge allows 5 wrong codes.
 */
public interface StepUpOtp {

    record Challenge(UUID id, Instant expiresAt) {
    }

    /** Sends a fresh code for the purpose (only 'dpa' today). 400 NO_MOBILE without a number on file, 429 when rate limited. */
    Challenge issue(UUID staffId, String purpose);

    /** True once, for the right code of an unexpired challenge of this staff member and purpose; false otherwise. */
    boolean verify(UUID staffId, UUID challengeId, String purpose, String code);
}
