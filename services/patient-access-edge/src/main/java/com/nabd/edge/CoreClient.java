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

    /** IDC-005: the privacy notice in force for a language (version echoed back on capture, path of the public page). */
    public record Notice(String version, String language, String path) {
    }

    public Notice currentNotice(UUID tenantId, String language) {
        JsonNode n = json.readTree(http.get(base + "/internal/v1/tenants/" + tenantId + "/privacy-notice/current?language=" + language));
        return new Notice(n.path("version").asString(""), n.path("language").asString(language), n.path("path").asString(""));
    }

    /** IDC-005: relays the taps. Idempotent on the wamid at Core, so a re-run after a version conflict stores nothing twice.
     * @return false when Core says the notice version is stale (409): the caller shows the current notice again. */
    public boolean relayConsent(UUID tenantId, String phoneHash, java.util.Map<String, Boolean> decisions, String noticeVersion, String language, String wamid) {
        java.util.Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("phoneHash", phoneHash);
        m.put("decisions", decisions.entrySet().stream().map(e -> java.util.Map.of("purpose", e.getKey(), "granted", e.getValue())).toList());
        m.put("noticeVersion", noticeVersion);
        m.put("language", language);
        m.put("evidence", java.util.Map.of("wamid", wamid));
        try {
            http.post(base + "/internal/v1/tenants/" + tenantId + "/patients/consents", json.writeValueAsString(m));
            return true;
        } catch (Http.UpstreamStatusException e) {
            if (e.status == 409) {
                return false;
            }
            throw e;
        }
    }

    /** IDC-010: Core's answer to "add this family member". */
    public record Member(String memberRef, String proxyStatus) {
    }

    /** IDC-010: creates the member (and the guardian consent for a minor). Idempotent at Core on the caregiver, name and date of birth.
     * Throws Http.UpstreamStatusException: 409 notice-version-stale, 422 guardian-required, anything else is an outage. */
    public Member addMember(UUID tenantId, String caregiverRef, java.util.Map<String, Object> body) {
        JsonNode n = json.readTree(http.post(base + "/internal/v1/tenants/" + tenantId + "/patients/" + caregiverRef + "/members", json.writeValueAsString(body)));
        return new Member(n.path("memberRef").asString(""), n.path("proxyStatus").asString(""));
    }
}
