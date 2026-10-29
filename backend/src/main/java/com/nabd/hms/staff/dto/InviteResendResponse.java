package com.nabd.hms.staff.dto;

import java.time.Instant;
import java.util.UUID;

/** ADM-018: same hand-relay shape as {@link StaffInviteResponse} (no invite email service yet). */
public record InviteResendResponse(UUID inviteId, Instant expiresAt, String inviteToken) {
}
