package com.nabd.hms.auth;

import com.nabd.hms.common.ModuleGrant;
import com.nabd.hms.common.OutboxDispatcher;
import com.nabd.hms.support.ApiTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** PLT-013: the owner's break-glass review queue, what was read, the outcome (incident + removal) and the 24 h reminder;
 * the device label header on sessions and grants. */
class Plt013BreakGlassReviewApiTest extends ApiTestBase {

    private static final String REASON = "Collapsed in waiting room, unknown history";

    @Autowired
    private BreakGlassReminderJob reminderJob;

    @Autowired
    private OutboxDispatcher dispatcher;

    private record Clinic(SeededTenant t, SeededStaff owner, String ownerToken, UUID doctorRole) {
    }

    private Clinic clinic(String tag) {
        SeededTenant t = seedTenant();
        UUID ownerRole = seedRole(t.id(), "Owner", true, new ModuleGrant("staff", true, true, true, true, true, true, true),
                new ModuleGrant("compliance", true, false, false, true, true, false, true),
                new ModuleGrant("clinical", true, false, false, false, false, false, false, true));
        SeededStaff owner = seedStaff(t, ownerRole, "own" + tag + "@a.com", phone(tag, 1), false);
        return new Clinic(t, owner, loginAndGetAccessToken(owner), seedBreakGlassDoctorRole(t.id()));
    }

    private static String phone(String tag, int n) {
        return "+91" + String.format("%010d", Math.abs((tag.hashCode() % 100000)) * 100L + n);
    }

    private static HttpHeaders device(String label) {
        HttpHeaders h = new HttpHeaders();
        h.set("X-Device-Label", label);
        return h;
    }

    /** Doctor activates (device label sent), returns the grant id. */
    private UUID activate(String doctorToken, String label) {
        ResponseEntity<Map> r = activateBreakGlass(doctorToken, REASON, label == null ? null : device(label));
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return UUID.fromString((String) r.getBody().get("grantId"));
    }

    private void end(String ownerToken, UUID grantId) {
        assertThat(exchange("/v1/auth/break-glass/" + grantId + "/deactivate", HttpMethod.POST, authedJsonBody(ownerToken, Map.of()), Void.class)
                .getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
    }

    private UUID patient(UUID tenantId, String name, String mrn, int n) {
        return inTenantTx(tenantId, () -> jdbc.queryForObject(
                "INSERT INTO patients (tenant_id, name, phone, dob, gender, mrn) VALUES (?,?,?,'1990-01-01','other',?) RETURNING id",
                UUID.class, tenantId, name, String.format("+9198%08d", Math.abs(tenantId.hashCode() % 100000) * 100 + n), mrn));
    }

    /** What PhiReadCapture writes for a break-glass token's read. */
    private void read(UUID tenantId, UUID actorId, UUID patientId, String resourceType, UUID grantId) {
        inTenantTx(tenantId, () -> jdbc.update("INSERT INTO phi_read_log (tenant_id, actor_type, actor_id, patient_id, resource_type, purpose_code, "
                        + "break_glass_grant_id) VALUES (?, 'staff', ?, ?, ?, ?, ?)", tenantId, actorId, patientId, resourceType,
                grantId == null ? "treatment" : "break_glass", grantId));
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> items(ResponseEntity<Map> r) {
        return (List<Map<String, Object>>) r.getBody().get("items");
    }

    private ResponseEntity<Map> get(String path, String token) {
        return exchange(path, HttpMethod.GET, authed(token), Map.class);
    }

    private ResponseEntity<Map> review(String token, UUID grantId, Map<String, Object> body) {
        return exchange("/v1/break-glass/grants/" + grantId + "/review", HttpMethod.POST, authedJsonBody(token, body), Map.class);
    }

    @SuppressWarnings("unchecked")
    private List<String> permissions(String token) {
        try {
            Map<String, Object> c = objectMapper.readValue(java.util.Base64.getUrlDecoder().decode(token.split("\\.")[1]), Map.class);
            return (List<String>) c.get("permissions");
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void deviceLabelHeaderIsStoredOnSessionAndGrantInsteadOfTheUserAgent() {
        Clinic c = clinic("dev");
        SeededStaff doc = seedStaff(c.t(), c.doctorRole(), "docdev@a.com", phone("dev", 2), false);

        var body = jsonBody(Map.of("tenantSlug", doc.slug(), "email", doc.email(), "pin", STAFF_PIN));
        HttpHeaders h = device("  Front desk PC 2  <script>x</script> " + "y".repeat(80));
        h.addAll(body.getHeaders());
        h.set("User-Agent", "Mozilla/5.0 (Macintosh) TestBrowser/1.0");
        ResponseEntity<Map> login = exchange("/v1/auth/login", HttpMethod.POST, new org.springframework.http.HttpEntity<>(body.getBody(), h), Map.class);
        assertThat(login.getStatusCode()).isEqualTo(HttpStatus.OK);
        String token = (String) login.getBody().get("accessToken");
        String stored = inTenantTx(c.t().id(), () -> jdbc.queryForObject(
                "SELECT device_label FROM sessions WHERE staff_id = ? ORDER BY created_at DESC LIMIT 1", String.class, doc.id()));
        assertThat(stored).startsWith("Front desk PC 2 script x /script yyy").doesNotContain("<").doesNotContain("Mozilla").hasSize(64);

        UUID grantId = activate(token, "ER tablet 1");
        assertThat(inTenantTx(c.t().id(), () -> jdbc.queryForObject("SELECT device_label FROM break_glass_grants WHERE id = ?", String.class, grantId)))
                .isEqualTo("ER tablet 1");
        assertThat(inTenantTx(c.t().id(), () -> jdbc.queryForObject(
                "SELECT s.device_label FROM sessions s JOIN break_glass_grants g ON g.session_id = s.id WHERE g.id = ?", String.class, grantId)))
                .isEqualTo("ER tablet 1");

        // no header: a fixed placeholder, never the User-Agent
        end(c.ownerToken(), grantId);
        UUID second = activate(token, null);
        assertThat(inTenantTx(c.t().id(), () -> jdbc.queryForObject("SELECT device_label FROM break_glass_grants WHERE id = ?", String.class, second)))
                .isEqualTo("Unknown device");
    }

    @Test
    void ownerSeesEndedGrantsWithReasonDeviceWindowAndWhatWasRead() {
        Clinic c = clinic("q");
        SeededStaff doc = seedStaff(c.t(), c.doctorRole(), "docq@a.com", phone("q", 2), false);
        String token = loginAndGetAccessToken(doc);
        UUID p1 = patient(c.t().id(), "Asha Rao", "MRN-Q1", 1);
        UUID p2 = patient(c.t().id(), "Ravi Kumar", "MRN-Q2", 2);

        UUID first = activate(token, "Ward PC");
        read(c.t().id(), doc.id(), p1, "timeline", first);
        read(c.t().id(), doc.id(), p2, "note", first);
        read(c.t().id(), doc.id(), p2, "note", null); // a normal read by the same person: not part of the grant
        end(c.ownerToken(), first);
        UUID live = activate(token, "Ward PC");    // still active: not in the queue yet

        ResponseEntity<Map> q = get("/v1/break-glass/grants", c.ownerToken());
        assertThat(q.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(items(q)).hasSize(1);
        Map<String, Object> item = items(q).get(0);
        assertThat(item).containsEntry("grantId", first.toString()).containsEntry("reasonCode", "unconscious_patient")
                .containsEntry("reasonText", REASON).containsEntry("deviceLabel", "Ward PC").containsEntry("readsCount", 2)
                .containsKeys("startedAt", "endedAt");
        assertThat(item.get("outcome")).isNull();
        assertThat((Map<String, Object>) item.get("staff")).containsEntry("id", doc.id().toString()).containsEntry("name", "Test Staff");
        assertThat(q.getBody().get("nextCursor")).isNull();

        ResponseEntity<Map> reads = get("/v1/break-glass/grants/" + first + "/reads", c.ownerToken());
        assertThat(reads.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(items(reads)).extracting(m -> m.get("patientName") + "/" + m.get("patientRef") + "/" + m.get("resourceType"))
                .containsExactly("Asha Rao/MRN-Q1/timeline", "Ravi Kumar/MRN-Q2/note");

        // keyset pagination over two ended grants
        end(c.ownerToken(), live);
        ResponseEntity<Map> page1 = get("/v1/break-glass/grants?limit=1", c.ownerToken());
        assertThat(items(page1)).extracting(m -> m.get("grantId")).containsExactly(live.toString());
        String cursor = (String) page1.getBody().get("nextCursor");
        assertThat(cursor).isNotNull();
        ResponseEntity<Map> page2 = get("/v1/break-glass/grants?limit=1&cursor=" + cursor, c.ownerToken());
        assertThat(items(page2)).extracting(m -> m.get("grantId")).containsExactly(first.toString());
        assertThat(page2.getBody().get("nextCursor")).isNull();
        assertThat(get("/v1/break-glass/grants?cursor=not-a-cursor", c.ownerToken()).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(get("/v1/break-glass/grants?status=open", c.ownerToken()).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void receptionistAndOtherClinicsAreRefused() {
        Clinic c = clinic("r");
        String token = loginAndGetAccessToken(seedStaff(c.t(), c.doctorRole(), "docr@a.com", phone("r", 2), false));
        UUID grantId = activate(token, "PC");
        end(c.ownerToken(), grantId);

        UUID recRole = seedRole(c.t().id(), "Receptionist", false, new ModuleGrant("queue", true, true, true, false, false, false, false),
                new ModuleGrant("staff", true, false, false, false, false, false, false));
        String rec = loginAndGetAccessToken(seedStaff(c.t(), recRole, "recr@a.com", phone("r", 3), false));
        assertThat(get("/v1/break-glass/grants", rec).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(get("/v1/break-glass/grants/" + grantId + "/reads", rec).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(review(rec, grantId, Map.of("outcome", "justified")).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        // staff:edit alone may see the queue, not the reads
        UUID adminRole = seedRole(c.t().id(), "Admin", false, new ModuleGrant("staff", true, false, true, false, false, false, false));
        String admin = loginAndGetAccessToken(seedStaff(c.t(), adminRole, "admr@a.com", phone("r", 4), false));
        assertThat(get("/v1/break-glass/grants", admin).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(get("/v1/break-glass/grants/" + grantId + "/reads", admin).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        Clinic other = clinic("r2");
        assertThat(items(get("/v1/break-glass/grants", other.ownerToken()))).isEmpty();
        assertThat(get("/v1/break-glass/grants/" + grantId + "/reads", other.ownerToken()).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(review(other.ownerToken(), grantId, Map.of("outcome", "justified")).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(inTenantTx(other.t().id(), () -> jdbc.queryForObject("SELECT count(*) FROM break_glass_grants WHERE id = ?", Integer.class, grantId))).isZero();
    }

    @Test
    void notJustifiedOpensAnIncidentAndRemovesBreakGlassFromThatPersonOnly() {
        Clinic c = clinic("nj");
        SeededStaff doc = seedStaff(c.t(), c.doctorRole(), "docnj@a.com", phone("nj", 2), false);
        SeededStaff colleague = seedStaff(c.t(), c.doctorRole(), "docnj2@a.com", phone("nj", 3), false);
        String token = loginAndGetAccessToken(doc);
        UUID active = activate(token, "PC");
        assertThat(review(c.ownerToken(), active, Map.of("outcome", "justified")).getStatusCode()).isEqualTo(HttpStatus.CONFLICT); // still live
        end(c.ownerToken(), active);
        UUID grantId = active;

        assertThat(review(c.ownerToken(), grantId, Map.of("outcome", "not_justified")).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(review(c.ownerToken(), grantId, Map.of("outcome", "justified", "revokePermission", true)).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(review(c.ownerToken(), grantId, Map.of("outcome", "maybe")).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        String note = "Opened two unrelated charts; patient was stable";
        ResponseEntity<Map> r = review(c.ownerToken(), grantId, Map.of("outcome", "not_justified", "note", note, "revokePermission", true));
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(r.getBody()).containsKey("reviewedAt").containsEntry("permissionRevoked", true);

        // single use: a second review (any outcome) is refused
        ResponseEntity<Map> again = review(c.ownerToken(), grantId, Map.of("outcome", "justified"));
        assertThat(again.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(again.getBody().toString()).contains("ALREADY_REVIEWED");

        Map<String, Object> g = inTenantTx(c.t().id(), () -> jdbc.queryForMap(
                "SELECT review_outcome, review_note, reviewed_by FROM break_glass_grants WHERE id = ?", grantId));
        assertThat(g).containsEntry("review_outcome", "not_justified").containsEntry("review_note", note).containsEntry("reviewed_by", c.owner().id());
        List<String> audits = inTenantTx(c.t().id(), () -> jdbc.queryForList(
                "SELECT action || ' ' || coalesce(after::text, '') FROM audit_log WHERE tenant_id = ? AND action IN "
                        + "('auth.break_glass_reviewed','auth.break_glass_incident_opened','staff.break_glass_removed') ORDER BY id", String.class, c.t().id()));
        assertThat(audits).hasSize(3).noneMatch(a -> a.contains("unrelated charts"));
        assertThat(audits).anyMatch(a -> a.startsWith("auth.break_glass_reviewed") && a.contains("not_justified"));
        String event = inTenantTx(c.t().id(), () -> jdbc.queryForObject(
                "SELECT payload::text FROM outbox_events WHERE event_type = 'iam.break_glass.reviewed.v1' AND aggregate_id = ?", String.class, grantId));
        assertThat(event).contains("not_justified").contains(grantId.toString()).doesNotContain("unrelated");

        // removed from the holder: the old token cannot activate, a new token lacks it; the colleague on the same role keeps it
        assertThat(activateBreakGlass(token, REASON, null).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(permissions(loginAndGetAccessToken(doc))).doesNotContain("clinical:breakGlass");
        assertThat(permissions(loginAndGetAccessToken(colleague))).contains("clinical:breakGlass");

        // reviewed grants move to the reviewed list
        assertThat(items(get("/v1/break-glass/grants", c.ownerToken()))).isEmpty();
        assertThat(items(get("/v1/break-glass/grants?status=reviewed", c.ownerToken()))).singleElement()
                .satisfies(m -> assertThat(m).containsEntry("outcome", "not_justified").containsKey("reviewedAt"));
    }

    @Test
    void removingBreakGlassNeedsStaffEdit() {
        Clinic c = clinic("se");
        String token = loginAndGetAccessToken(seedStaff(c.t(), c.doctorRole(), "docse@a.com", phone("se", 2), false));
        UUID grantId = activate(token, "PC");
        end(c.ownerToken(), grantId);
        UUID dpoRole = seedRole(c.t().id(), "Compliance", false, new ModuleGrant("compliance", true, false, false, false, true, false, false));
        String dpo = loginAndGetAccessToken(seedStaff(c.t(), dpoRole, "dpose@a.com", phone("se", 3), false));
        assertThat(review(dpo, grantId, Map.of("outcome", "not_justified", "note", "Not an emergency", "revokePermission", true)).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(review(dpo, grantId, Map.of("outcome", "justified")).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void unreviewedFor24HoursGetsOneReminderInAppAndOnWhatsApp() {
        Clinic c = clinic("rm");
        inTenantTx(c.t().id(), () -> jdbc.update("""
                INSERT INTO whatsapp_templates (tenant_id, name, meta_name, language, category, header_text, body, param_count, status)
                VALUES (?, 'emergency_access_used', ?, 'en', 'UTILITY', 'Clinic', 'Emergency access used by {{1}} at {{2}}. Review in Nabd.', 2, 'approved')
                """, c.t().id(), c.t().slug() + "_emergency_access_used"));
        String token = loginAndGetAccessToken(seedStaff(c.t(), c.doctorRole(), "docrm@a.com", phone("rm", 2), false));
        UUID due = activate(token, "PC");
        end(c.ownerToken(), due);
        UUID fresh = activate(token, "PC");
        end(c.ownerToken(), fresh);
        UUID reviewed = activate(token, "PC");
        end(c.ownerToken(), reviewed);
        for (UUID id : List.of(due, reviewed)) { // ended 25 h ago
            inTenantTx(c.t().id(), () -> jdbc.update("UPDATE break_glass_grants SET activated_at = now() - interval '26 hours', "
                    + "expires_at = now() - interval '25 hours', deactivated_at = now() - interval '25 hours' WHERE id = ?", id));
        }
        assertThat(review(c.ownerToken(), reviewed, Map.of("outcome", "justified")).getStatusCode()).isEqualTo(HttpStatus.OK);

        reminderJob.sweep();
        reminderJob.sweep(); // deduped: one reminder per grant
        dispatcher.poll();
        dispatcher.poll();

        List<String> kinds = inTenantTx(c.t().id(), () -> jdbc.queryForList(
                "SELECT grant_id::text FROM break_glass_alerts WHERE owner_staff_id = ? AND kind = 'review_reminder'", String.class, c.owner().id()));
        assertThat(kinds).containsExactly(due.toString());
        ResponseEntity<Map> alerts = get("/v1/auth/break-glass/alerts", c.ownerToken());
        assertThat(items(alerts)).anySatisfy(a -> assertThat(a).containsEntry("kind", "review_reminder").containsEntry("grantId", due.toString()))
                .allSatisfy(a -> assertThat((String) a.get("message")).doesNotContain(REASON));
        Integer wa = inTenantTx(c.t().id(), () -> jdbc.queryForObject(
                "SELECT count(*) FROM notification_requests WHERE dedupe_key LIKE 'break-glass-reminder:%'", Integer.class));
        assertThat(wa).isEqualTo(1);
        assertThat(inTenantTx(c.t().id(), () -> jdbc.queryForObject(
                "SELECT count(*) FROM notification_requests WHERE dedupe_key = ?", Integer.class,
                "break-glass-reminder:" + due + ":" + c.owner().id()))).isEqualTo(1);
    }
}
