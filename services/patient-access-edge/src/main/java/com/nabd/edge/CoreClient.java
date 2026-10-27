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

    /** ACC-033: a household member with an appointment today. */
    public record Candidate(String patientRef, String firstName, String appointmentId, boolean checkedIn) {
    }

    /** ACC-033: the facility code, the geofence policy and who can check in. */
    public record CheckInContext(String facilityCode, boolean geofenceRequired, boolean walkinAllowed, java.util.List<Candidate> candidates) {
    }

    public CheckInContext checkInContext(UUID tenantId, String phoneHash) {
        JsonNode n = json.readTree(http.get(base + "/internal/v1/tenants/" + tenantId + "/check-ins/context?phoneHash=" + phoneHash));
        java.util.List<Candidate> out = new java.util.ArrayList<>();
        for (JsonNode c : n.path("candidates")) {
            out.add(new Candidate(c.path("patientRef").asString(""), c.path("firstName").asString(""), c.path("appointmentId").asString(""), c.path("checkedIn").asBoolean(false)));
        }
        return new CheckInContext(n.path("facilityCode").asString(""), n.path("geofenceRequired").asBoolean(false), n.path("walkinAllowed").asBoolean(false), out);
    }

    /** ACC-033: what Core decided. status: CHECKED_IN, ALREADY (token = the existing one), OUTSIDE (the desk was told), NOT_TODAY (the desk was told), NEEDS_LOCATION. */
    public record CheckInResult(String status, Integer token, Integer position) {
    }

    /** location = {lat, lng, accuracyM} or null; it goes to Core for one distance check and is not kept here. */
    public CheckInResult checkIn(UUID tenantId, String patientRef, String appointmentId, String channel, double[] location, String idempotencyKey) {
        java.util.Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("patientRef", patientRef);
        m.put("appointmentId", appointmentId);
        m.put("channel", channel);
        if (location != null) {
            m.put("location", java.util.Map.of("lat", location[0], "lng", location[1], "accuracyM", location[2]));
        }
        try {
            JsonNode n = json.readTree(http.post(base + "/internal/v1/tenants/" + tenantId + "/check-ins", json.writeValueAsString(m), idempotencyKey));
            return new CheckInResult("CHECKED_IN", n.path("token").asInt(0), n.has("position") && !n.path("position").isNull() ? n.path("position").asInt() : null);
        } catch (Http.UpstreamStatusException e) {
            String code = e.status == 409 || e.status == 422 ? json.readTree(e.body).path("code").asString("") : "";
            return switch (code) {
                case "ALREADY_CHECKED_IN" -> new CheckInResult("ALREADY", json.readTree(e.body).path("token").asInt(0), null);
                case "OUTSIDE_GEOFENCE" -> new CheckInResult("OUTSIDE", null, null);
                case "APPOINTMENT_NOT_TODAY" -> new CheckInResult("NOT_TODAY", null, null);
                case "LOCATION_REQUIRED" -> new CheckInResult("NEEDS_LOCATION", null, null);
                default -> throw e; // an outage or an unexpected refusal: the step fails and the event is retried
            };
        }
    }
}
