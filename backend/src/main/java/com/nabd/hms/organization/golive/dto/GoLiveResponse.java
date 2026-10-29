package com.nabd.hms.organization.golive.dto;

import java.time.Instant;

public record GoLiveResponse(Instant liveAt, String bookingUrl, String qrPosterUrl) {
}
