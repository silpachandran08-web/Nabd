package com.nabd.edge;

import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.util.Map;
import java.util.UUID;

/** PLT-047: Core's POST /internal/v1/tenants/{tid}/privacy/requests. Core decides who may ask for whom; the Edge only relays and maps the refusal. */
@Component
public class PrivacyClient {

    private final Http http;
    private final String base;
    private final ObjectMapper json;

    PrivacyClient(EdgeProperties p, ObjectMapper json) {
        this.http = new Http(p.coreTimeoutMs(), p.coreToken());
        this.base = p.coreUrl();
        this.json = json;
    }

    /** @return the request id. Throws Http.UpstreamStatusException: 403 PROXY_NOT_APPROVED / PHONE_NOT_VERIFIED, 404 PATIENT_NOT_FOUND, 409 OPEN_REQUEST_EXISTS (anything 5xx is an outage). */
    public String request(UUID tenantId, String personRef, String phoneHash, String type, String idempotencyKey) {
        return json.readTree(http.post(base + "/internal/v1/tenants/" + tenantId + "/privacy/requests",
                json.writeValueAsString(Map.of("personRef", personRef, "phoneHash", phoneHash, "type", type)), idempotencyKey)).path("requestId").asString("");
    }
}
