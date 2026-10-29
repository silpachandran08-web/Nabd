package com.nabd.hms.identity.integration;

import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * IDC-018: Scan & Share answer over the ABDM v3 gateway (same session token, headers, 401 retry and breaker as every other call).
 * The body carries the token number or a fixed "please see the front desk" message, never a patient value.
 * shortcut: body shape follows the published v3 on-share contract, not a recording of the sandbox (Needs external action, DEP-05).
 */
@Component
class HttpAbdmShareGateway implements AbdmShareGateway {

    private final HttpAbdmGateway base;
    private final AbdmShareProperties paths;

    HttpAbdmShareGateway(HttpAbdmGateway base, AbdmShareProperties paths) {
        this.base = base;
        this.paths = paths;
    }

    @Override
    public void respond(String requestId, Integer tokenNumber) {
        Map<String, Object> body = new LinkedHashMap<>();
        if (tokenNumber != null) {
            body.put("acknowledgement", Map.of("status", "SUCCESS"));
            body.put("profile", Map.of("context", "queue-token", "tokenNumber", String.valueOf(tokenNumber)));
        } else {
            body.put("acknowledgement", Map.of("status", "FAILURE"));
            body.put("error", Map.of("code", 1000, "message", "Please see the front desk."));
        }
        body.put("response", Map.of("requestId", requestId));
        base.call(paths.onSharePath(), body, true);
    }
}
