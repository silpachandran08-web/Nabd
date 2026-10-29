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
        return relayConsentRecord(tenantId, phoneHash, decisions, noticeVersion, language, wamid) != null;
    }

    /** As {@link #relayConsent}, returning the id of the first stored decision (ACC-043 hands it to patient creation); null on a stale notice. */
    public String relayConsentRecord(UUID tenantId, String phoneHash, java.util.Map<String, Boolean> decisions, String noticeVersion, String language, String wamid) {
        java.util.Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("phoneHash", phoneHash);
        m.put("decisions", decisions.entrySet().stream().map(e -> java.util.Map.of("purpose", e.getKey(), "granted", e.getValue())).toList());
        m.put("noticeVersion", noticeVersion);
        m.put("language", language);
        m.put("evidence", java.util.Map.of("wamid", wamid));
        try {
            return json.readTree(http.post(base + "/internal/v1/tenants/" + tenantId + "/patients/consents", json.writeValueAsString(m)))
                    .path("consentIds").path(0).asString("");
        } catch (Http.UpstreamStatusException e) {
            if (e.status == 409) {
                return null;
            }
            throw e;
        }
    }

    /** ACC-043: patientRef when created; otherwise code is POSSIBLE_DUPLICATE, GUARDIAN_REQUIRED or CONSENT_REQUIRED and nothing was stored. */
    public record Created(String patientRef, String code) {
    }

    public Created createPatient(UUID tenantId, java.util.Map<String, Object> body, String idempotencyKey) {
        try {
            return new Created(json.readTree(http.post(base + "/internal/v1/tenants/" + tenantId + "/patients", json.writeValueAsString(body), idempotencyKey))
                    .path("patientRef").asString(""), null);
        } catch (Http.UpstreamStatusException e) {
            String code = e.status == 409 || e.status == 422 ? json.readTree(e.body).path("code").asString("") : "";
            if (code.isEmpty()) {
                throw e;
            }
            return new Created(null, code);
        }
    }

    /** ACC-043: the fields the patient answered in chat (address, email, language) onto the record. */
    public void completePatient(UUID tenantId, String patientRef, java.util.Map<String, Object> fields, String idempotencyKey) {
        http.patch(base + "/internal/v1/tenants/" + tenantId + "/patients/" + patientRef, json.writeValueAsString(fields), idempotencyKey);
    }

    /** ACC-043: the patient the desk asked to finish in chat on this phone, or null (404). Reference and first name only. */
    public record Pending(String patientRef, String firstName) {
    }

    public Pending pendingCompletion(UUID tenantId, String phoneHash) {
        try {
            JsonNode n = json.readTree(http.get(base + "/internal/v1/tenants/" + tenantId + "/patients/pending-completion/" + phoneHash));
            return new Pending(n.path("patientRef").asString(""), n.path("firstName").asString(""));
        } catch (Http.UpstreamStatusException e) {
            if (e.status == 404) {
                return null;
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

    /** ACC-041/044: the patientRefs behind a verified phone (empty when Core has no household for it). */
    public java.util.List<String> householdRefs(UUID tenantId, String phoneHash) {
        return household(tenantId, phoneHash).stream().map(Member::patientRef).toList();
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

    /** IDC-010: Core's answer to "add this family member". */
    public record AddedMember(String memberRef, String proxyStatus) {
    }

    /** IDC-010: creates the member (and the guardian consent for a minor). Idempotent at Core on the caregiver, name and date of birth.
     * Throws Http.UpstreamStatusException: 409 notice-version-stale, 422 guardian-required, anything else is an outage. */
    public AddedMember addMember(UUID tenantId, String caregiverRef, java.util.Map<String, Object> body) {
        JsonNode n = json.readTree(http.post(base + "/internal/v1/tenants/" + tenantId + "/patients/" + caregiverRef + "/members", json.writeValueAsString(body)));
        return new AddedMember(n.path("memberRef").asString(""), n.path("proxyStatus").asString(""));
    }
}
