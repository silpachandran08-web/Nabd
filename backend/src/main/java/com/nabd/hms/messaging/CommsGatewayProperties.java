package com.nabd.hms.messaging;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** COM-009: the Comms Gateway as Core sees it. Blank baseUrl = no gateway yet, OTPs go through the in-process sender.
 * otpCutover true = OTPs must go through the gateway (no in-process fallback). */
@ConfigurationProperties(prefix = "app.comms.gateway")
public record CommsGatewayProperties(String baseUrl, String serviceToken, boolean otpCutover) {
}
