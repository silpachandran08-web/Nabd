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

    // ---- ACC-040 / ACC-042 ------------------------------------------------------------------------------------------

    public record Doctor(UUID id, String name) {
    }

    public record Profile(String clinicName, String timeZone, java.util.List<Doctor> doctors) {
    }

    /** Clinic name, clinic time zone and the bookable doctors (staff data). */
    public Profile profile(UUID tenantId) {
        JsonNode n = json.readTree(http.get(base + "/internal/v1/tenants/" + tenantId + "/booking-profile"));
        java.util.List<Doctor> docs = new java.util.ArrayList<>();
        for (JsonNode d : n.path("doctors")) {
            docs.add(new Doctor(UUID.fromString(d.path("doctorId").asString("")), d.path("name").asString("")));
        }
        return new Profile(n.path("clinicName").asString(""), n.path("timeZone").asString("UTC"), docs);
    }

    /** relation: guardian | dependent | self. years is the age in whole years. */
    public record Member(String patientRef, String firstName, int years, String relation) {
    }

    /** The household of a WhatsApp-verified phone; empty when Core knows nobody on it (404). Read on every booking start, never cached. */
    public java.util.List<Member> household(UUID tenantId, String phoneHash) {
        String body;
        try {
            body = http.get(base + "/internal/v1/tenants/" + tenantId + "/households/" + phoneHash);
        } catch (Http.UpstreamStatusException e) {
            if (e.status == 404) {
                return java.util.List.of();
            }
            throw e;
        }
        java.util.List<Member> out = new java.util.ArrayList<>();
        for (JsonNode m : json.readTree(body).path("members")) {
            out.add(new Member(m.path("patientRef").asString(""), m.path("firstName").asString(""), m.path("years").asInt(0), m.path("relation").asString("self")));
        }
        return out;
    }

    /** One of: the booking, or a refusal Core explains with a code. */
    public record Booking(String status, java.time.Instant slaDueAt) {
    }

    public static final class SlotTaken extends RuntimeException {
        SlotTaken() {
            super("slot taken", null, false, false);
        }
    }

    /** ACC-009 route. Same Idempotency-Key = same answer, so a retry after a timeout cannot book twice.
     * @throws SlotTaken on 409 SLOT_TAKEN / SESSION_FULL; any other failure (outage, timeout, in-progress 409) is an IllegalStateException. */
    public Booking book(UUID tenantId, String idempotencyKey, String patientRef, UUID doctorId, java.time.Instant slot, java.util.List<String> triageFlags) {
        java.util.Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("patientRef", patientRef);
        m.put("doctorId", doctorId.toString());
        m.put("slotStart", slot.toString());
        m.put("channel", "whatsapp");
        if (!triageFlags.isEmpty()) {
            m.put("triageFlags", triageFlags);
        }
        try {
            JsonNode n = json.readTree(http.post(base + "/internal/v1/tenants/" + tenantId + "/booking-requests", json.writeValueAsString(m), idempotencyKey));
            String sla = n.path("slaDueAt").asString("");
            return new Booking(n.path("status").asString(""), sla.isEmpty() ? null : java.time.Instant.parse(sla));
        } catch (Http.UpstreamStatusException e) {
            if (e.status == 409 && (e.body.contains("SLOT_TAKEN") || e.body.contains("SESSION_FULL"))) {
                throw new SlotTaken();
            }
            throw e;
        }
    }

    public static final class NotAMinor extends RuntimeException {
        NotAMinor() {
            super("not a minor", null, false, false);
        }
    }

    /** ACC-042. A 409 POSSIBLE_DUPLICATE answers with the existing child's ref, which is what the parent wants anyway.
     * @throws NotAMinor on 400 NOT_A_MINOR */
    public String addDependant(UUID tenantId, String phoneHash, String idempotencyKey, String firstName, java.time.LocalDate dob, String sex) {
        try {
            String body = http.post(base + "/internal/v1/tenants/" + tenantId + "/households/" + phoneHash + "/dependants",
                    json.writeValueAsString(java.util.Map.of("firstName", firstName, "dob", dob.toString(), "sex", sex)), idempotencyKey);
            return json.readTree(body).path("patientRef").asString("");
        } catch (Http.UpstreamStatusException e) {
            if (e.status == 400 && e.body.contains("NOT_A_MINOR")) {
                throw new NotAMinor();
            }
            if (e.status == 409 && e.body.contains("POSSIBLE_DUPLICATE")) {
                return json.readTree(e.body).path("patientRef").asString("");
            }
            throw e;
        }
    }

    /** The clinic days (ascending) on which this doctor has at least one free slot, within [from, to]. Token sessions are not counted: they cannot be booked by time. */
    public java.util.List<LocalDate> freeDays(UUID tenantId, UUID doctorId, LocalDate from, LocalDate to) {
        String body = http.get(base + "/internal/v1/tenants/" + tenantId + "/availability?doctorId=" + doctorId + "&from=" + from + "&to=" + to);
        java.util.TreeSet<LocalDate> days = new java.util.TreeSet<>();
        for (JsonNode d : json.readTree(body).path("doctors")) {
            if (doctorId.toString().equals(d.path("doctorId").asString(""))) {
                for (JsonNode s : d.path("sessions")) {
                    if (s.path("slots").size() > 0 && s.hasNonNull("date")) {
                        days.add(LocalDate.parse(s.path("date").asString("")));
                    }
                }
            }
        }
        return new java.util.ArrayList<>(days);
    }
}
