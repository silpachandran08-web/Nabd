package com.nabd.hms.messaging;

import com.nabd.hms.common.ApiException;
import com.nabd.hms.shared.integration.CircuitOpenException;
import com.nabd.hms.shared.integration.ResilientHttpClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.util.Map;

/**
 * COM-009: POST {gateway}/internal/v1/otp/send. Core keeps code generation and verification; the gateway only delivers.
 * The 3 s budget is the provider's connect 1 s + read 2 s (application.yml, app.integration.providers.comms-gateway).
 * Called with no transaction open (OtpRequestedHandler). Never logs the code or the number.
 * shortcut: the cutover flag is one global property (app.comms.gateway.otp-cutover); switch it to the per-tenant
 * comms_cutover_flags (COM-008/COM-011) once that table is in the tree. No tenantId is sent (the sender port has none).
 */
@Component
class OtpGatewayClient {

    static final String PROVIDER = "comms-gateway";
    private static final Logger log = LoggerFactory.getLogger(OtpGatewayClient.class);

    private final RestClient http;
    private final CommsGatewayProperties props;
    private final boolean configured;

    OtpGatewayClient(CommsGatewayProperties props, ResilientHttpClient integration) {
        this.props = props;
        this.configured = props.baseUrl() != null && !props.baseUrl().isBlank();
        RestClient.Builder b = integration.builder(PROVIDER);
        if (configured) {
            b.baseUrl(props.baseUrl());
        }
        if (props.serviceToken() != null && !props.serviceToken().isBlank()) {
            b.defaultHeader("Authorization", "Bearer " + props.serviceToken());
        }
        this.http = b.build();
    }

    /** True: the gateway accepted the OTP. False: use the in-process sender (no gateway, or it is down and the tenant is not
     * cut over). Throws 429 OTP_RATE_LIMITED (nothing sent, no fallback) or 503 PROVIDER_UNAVAILABLE (cut over and down). */
    boolean send(String phoneE164, String code, String purpose, String language) {
        if (!configured) {
            return false;
        }
        try {
            http.post().uri("/internal/v1/otp/send").contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("phoneE164", phoneE164, "code", code, "purpose", purpose, "language", language))
                    .retrieve().toBodilessEntity();
            return true;
        } catch (RestClientResponseException e) {
            if (e.getStatusCode().value() == 429) {
                throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "OTP_RATE_LIMITED", "Too many codes requested",
                        "Too many codes were requested for this number. Try again later.");
            }
            return unavailable("HTTP " + e.getStatusCode().value());
        } catch (RestClientException | CircuitOpenException e) {
            return unavailable(e.getClass().getSimpleName());
        }
    }

    private boolean unavailable(String why) {
        if (props.otpCutover()) {
            log.warn("comms gateway unavailable for OTP ({}), tenant is cut over: not falling back", why);
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "PROVIDER_UNAVAILABLE", "Code delivery unavailable",
                    "The code could not be delivered right now. Try again in a few seconds.", Map.of("retryAfterSeconds", 5));
        }
        log.warn("comms gateway unavailable for OTP ({}), using the in-process sender", why);
        return false;
    }
}
