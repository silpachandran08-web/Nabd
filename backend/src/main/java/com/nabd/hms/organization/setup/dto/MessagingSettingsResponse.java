package com.nabd.hms.organization.setup.dto;

import java.math.BigDecimal;

/** COM-024: quiet hours (every clinic) and prayer windows (KSA default on; UAE/Qatar opt in), times HH:mm in the clinic's clock. */
public record MessagingSettingsResponse(MessagingQuietHours quietHours, MessagingPrayerWindows prayerWindows) {

    public record MessagingQuietHours(boolean enabled, String start, String end) {
    }

    /** method: umm_al_qura | qatar | dubai | mwl (NEEDS LOCAL CHECK); bufferMinutes after each adhan; latitude/longitude of the clinic. */
    public record MessagingPrayerWindows(boolean enabled, String method, int bufferMinutes, MessagingFridayBlock fridayBlock, BigDecimal latitude, BigDecimal longitude) {
    }

    public record MessagingFridayBlock(String start, String end) {
    }
}
