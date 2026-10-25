package com.nabd.edge;

import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDate;
import java.util.UUID;

/** Core's ACC-038 contract: GET /internal/v1/tenants/{tid}/availability. The Edge never imports Core code. */
@Component
public class CoreClient {

    private final Http http;
    private final String base;
    private final ObjectMapper json;

    CoreClient(EdgeProperties p, ObjectMapper json) {
        this.http = new Http(p.coreTimeoutMs(), p.coreToken());
        this.base = p.coreUrl();
        this.json = json;
    }

    /** The sessions of one doctor on one day, exactly as Core returns them (possibly empty). */
    public JsonNode doctorDay(UUID tenantId, UUID doctorId, LocalDate day) {
        String body = http.get(base + "/internal/v1/tenants/" + tenantId + "/availability?doctorId=" + doctorId
                + "&from=" + day + "&to=" + day);
        for (JsonNode d : json.readTree(body).path("doctors")) {
            if (doctorId.toString().equals(d.path("doctorId").asString(""))) {
                return d.path("sessions");
            }
        }
        return json.createArrayNode();
    }
}
