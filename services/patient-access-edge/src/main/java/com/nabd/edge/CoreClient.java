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

    // ---- ACC-041 / ACC-044: reminder buttons and 'status' --------------------------------------------------------------

    /** The patientRefs behind a verified phone (empty when Core has no household for it). */
    public java.util.List<String> household(UUID tenantId, String phoneHash) {
        try {
            java.util.List<String> refs = new java.util.ArrayList<>();
            for (JsonNode m : json.readTree(http.get(base + "/internal/v1/tenants/" + tenantId + "/households/" + phoneHash)).path("members")) {
                refs.add(m.path("patientRef").asString(""));
            }
            return refs;
        } catch (Http.UpstreamStatusException e) {
            if (e.status == 404) {
                return java.util.List.of();
            }
            throw e;
        }
    }

    public record Detail(UUID doctorId, java.time.Instant startTime, String status, String zone, long windowHours) {
    }

    /** The patient's own appointment, or null when it is not theirs / not there. */
    public Detail detail(UUID tenantId, UUID appointmentId, String patientRef) {
        try {
            JsonNode n = json.readTree(http.get(base + "/internal/v1/tenants/" + tenantId + "/appointments/" + appointmentId
                    + "?patientRef=" + java.net.URLEncoder.encode(patientRef, java.nio.charset.StandardCharsets.UTF_8)));
            return new Detail(UUID.fromString(n.path("doctorId").asString("")), java.time.Instant.parse(n.path("startTime").asString("")),
                    n.path("status").asString(""), n.path("zone").asString("UTC"), n.path("windowHours").asLong(0));
        } catch (Http.UpstreamStatusException e) {
            if (e.status == 403 || e.status == 404) {
                return null;
            }
            throw e;
        }
    }

    public enum ActStatus { OK, NOT_OWNER, INSIDE_WINDOW, GONE, CONFLICT }

    public record ActResult(ActStatus outcome, long windowHours, UUID appointmentId) {
    }

    /** action: confirm | reschedule | cancel | call-me. The key makes a re-run of the same step a replay at Core. */
    public ActResult act(UUID tenantId, UUID appointmentId, String action, String patientRef, java.util.Map<String, String> extra, String idempotencySeed) {
        java.util.Map<String, Object> m = new java.util.LinkedHashMap<>(extra);
        m.put("patientRef", patientRef);
        try {
            JsonNode n = json.readTree(http.post(base + "/internal/v1/tenants/" + tenantId + "/appointments/" + appointmentId + "/" + action,
                    json.writeValueAsString(m), UUID.nameUUIDFromBytes((idempotencySeed + ":" + action).getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString()));
            String id = n.path("appointmentId").asString("");
            return new ActResult(ActStatus.OK, 0, id.isEmpty() ? appointmentId : UUID.fromString(id));
        } catch (Http.UpstreamStatusException e) {
            return switch (e.status) {
                case 403 -> new ActResult(ActStatus.NOT_OWNER, 0, appointmentId);
                case 404 -> new ActResult(ActStatus.GONE, 0, appointmentId);
                case 409 -> new ActResult(ActStatus.CONFLICT, 0, appointmentId);
                case 422 -> new ActResult(ActStatus.INSIDE_WINDOW, json.readTree(e.body).path("windowHours").asLong(0), appointmentId);
                default -> throw e;
            };
        }
    }

    /** One open visit of a household member today; p50 is null when the display mode hides time. */
    public record VisitStatus(UUID appointmentId, String patientRef, String status, String mode, String text, java.time.Instant p50) {
    }

    public java.util.List<VisitStatus> status(UUID tenantId, String phoneHash) {
        java.util.List<VisitStatus> out = new java.util.ArrayList<>();
        for (JsonNode n : json.readTree(http.get(base + "/internal/v1/tenants/" + tenantId + "/status/" + phoneHash)).path("items")) {
            out.add(new VisitStatus(UUID.fromString(n.path("appointmentId").asString("")), n.path("patientRef").asString(""), n.path("status").asString(""),
                    n.path("mode").asString("hidden"), n.hasNonNull("text") ? n.path("text").asString("") : null,
                    n.hasNonNull("p50") ? java.time.Instant.parse(n.path("p50").asString("")) : null));
        }
        return out;
    }
}
