package com.nabd.hms.staff.dto;

/**
 * ponytail: no transactional-email service yet (NB-128) to deliver the invite, so the raw link
 * comes back to the inviter (already staff:create-trusted) to relay by hand. Drop this field once
 * NB-128 lands and invites are emailed directly instead.
 */
public record StaffInviteResponse(StaffResponse staff, String inviteToken) {
}
