package com.nabd.hms.clinical.derm;

import com.nabd.hms.common.ModuleGrant;
import com.nabd.hms.platform.entitlements.PlanEntitlements;
import com.nabd.hms.support.ApiTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/** CLN-047: consent gate, timeline by site and date, withdrawal hides (keeps) photos, 5-minute URLs, compression, plan gate. */
@SuppressWarnings({"unchecked", "rawtypes"})
class Cln047DermPhotosApiTest extends ApiTestBase {

    private static final AtomicInteger SEQ = new AtomicInteger((int) (System.nanoTime() % 400_000) + 100_000);
    /** A real-looking finger signature: two strokes across the pad (CLN-014 format). */
    private static final String SIGNATURE = "<svg><path d=\"M20 150 L60 60 L100 140 L140 50 L180 150 L220 70\"/><path d=\"M240 120 L300 80 L360 130 L420 60 L480 140\"/></svg>";

    @Autowired
    private PlanEntitlements planEntitlements;

    private record Clinic(SeededTenant t, SeededStaff owner, String ownerToken, SeededStaff doctor, String doctorToken, String patientId,
                          String visitId) {
    }

    private String phone() {
        return "+9195" + (10000000 + SEQ.incrementAndGet());
    }

    /** Owner + a dermatologist (clinical access by role, specialty_derm only through the DERM profile), one patient checked in to them. */
    private Clinic clinic() {
        SeededTenant t = seedTenant();
        SeededStaff owner = seedStaff(t, seedFullAccessRole(t.id()), "o" + SEQ.incrementAndGet() + "@c47.test", phone(), false);
        UUID role = seedRole(t.id(), "Doctor", false, new ModuleGrant("queue", true, true, true, false, false, false, false),
                new ModuleGrant("clinical", true, true, true, false, false, false, false),
                new ModuleGrant("patients", true, false, false, false, false, false, false));
        SeededStaff doctor = seedStaff(t, role, "d" + SEQ.incrementAndGet() + "@c47.test", phone(), false);
        inTenantTx(t.id(), () -> jdbc.update("UPDATE staff SET is_clinician = true WHERE id = ?", doctor.id()));
        String ownerToken = loginAndGetAccessToken(owner);
        assertThat(exchange("/v1/staff/" + doctor.id() + "/specialty-profiles", HttpMethod.PUT,
                authedJsonBody(ownerToken, Map.of("primary", "DERM")), Map.class).getStatusCode()).isEqualTo(HttpStatus.OK);
        String patientId = (String) exchange("/v1/patients", HttpMethod.POST, authedJsonBody(ownerToken, Map.of(
                "name", "C47 Patient", "phone", phone(), "dob", "1990-01-01", "gender", "female")), Map.class).getBody().get("id");
        String visitId = (String) exchange("/v1/queue/check-in", HttpMethod.POST, authedJsonBody(ownerToken, Map.of(
                "patientId", patientId, "doctorId", doctor.id().toString())), Map.class).getBody().get("id");
        return new Clinic(t, owner, ownerToken, doctor, loginAndGetAccessToken(doctor), patientId, visitId);
    }

    /** An earlier visit of the same patient with the same doctor (setup only). */
    private String pastVisit(Clinic c, int daysAgo) {
        UUID id = UUID.randomUUID();
        inTenantTx(c.t().id(), () -> jdbc.update("INSERT INTO queue_entries (id, tenant_id, patient_id, doctor_id, department_id, queue_date, token_number, status) "
                + "VALUES (?,?,?::uuid,?,(SELECT id FROM departments WHERE tenant_id = ? AND is_default),?,?, 'completed')",
                id, c.t().id(), c.patientId(), c.doctor().id(), c.t().id(), LocalDate.now().minusDays(daysAgo), 900 + daysAgo));
        return id.toString();
    }

    private String base(Clinic c) {
        return "/v1/specialty/derm/patients/" + c.patientId();
    }

    private ResponseEntity<Map> consent(Clinic c, String scope, String signature) {
        Map<String, Object> body = new HashMap<>(Map.of("scope", scope, "signerKind", "patient"));
        if (signature != null) body.put("signatureSvg", signature);
        return exchange(base(c) + "/photo-consent", HttpMethod.POST, authedJsonBody(c.doctorToken(), body), Map.class);
    }

    private ResponseEntity<Map> addPhoto(Clinic c, String uploadId, String visitId, String region, String side) {
        return exchange(base(c) + "/photos", HttpMethod.POST, authedJsonBody(c.doctorToken(), Map.of("uploadId", uploadId,
                "queueEntryId", visitId, "site", Map.of("region", region, "side", side), "note", "Follow-up photo")), Map.class);
    }

    private Map<String, Object> timeline(Clinic c, String query) {
        ResponseEntity<Map> r = exchange(base(c) + "/photos" + query, HttpMethod.GET, authed(c.doctorToken()), Map.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        return r.getBody();
    }

    // ---- upload pipeline helpers (PLT-029), bytes are synthetic ----

    private Map<String, Object> slot(String token, long size) {
        ResponseEntity<Map> s = exchange("/v1/files/uploads", HttpMethod.POST, authedJsonBody(token, Map.of(
                "purpose", "clinical_photo", "contentType", "image/jpeg", "sizeBytes", size)), Map.class);
        assertThat(s.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return s.getBody();
    }

    private void putBytes(String putUrl, byte[] data) {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_OCTET_STREAM);
        assertThat(http.exchange(URI.create(url(putUrl)), HttpMethod.PUT, new HttpEntity<>(data, h), Void.class).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    /** init + PUT + complete + wait for the scan; the upload id. */
    private String upload(String token, byte[] data) throws InterruptedException {
        Map<String, Object> s = slot(token, data.length);
        putBytes((String) s.get("putUrl"), data);
        String id = (String) s.get("uploadId");
        assertThat(exchange("/v1/files/uploads/" + id + "/complete", HttpMethod.POST, authedJsonBody(token, Map.of()), Map.class)
                .getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        for (int i = 0; i < 300; i++) {
            String st = (String) exchange("/v1/files/" + id, HttpMethod.GET, authed(token), Map.class).getBody().get("status");
            if (!"pending_scan".equals(st)) {
                assertThat(st).isEqualTo("available");
                return id;
            }
            Thread.sleep(100);
        }
        throw new AssertionError("scan never finished");
    }

    private static byte[] jpeg(int w, int h, float q, boolean noise) throws IOException {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Random r = new Random(w);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int n = noise ? r.nextInt(64) : 0;
                img.setRGB(x, y, (Math.min(255, x * 255 / w + n) << 16) | (Math.min(255, y * 255 / h + n) << 8) | Math.min(255, 120 + n));
            }
        }
        ImageWriter wr = ImageIO.getImageWritersByFormatName("jpeg").next();
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        try (ImageOutputStream o = ImageIO.createImageOutputStream(b)) {
            wr.setOutput(o);
            ImageWriteParam p = wr.getDefaultWriteParam();
            p.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            p.setCompressionQuality(q);
            wr.write(null, new IIOImage(img, null, null), p);
        }
        wr.dispose();
        byte[] plain = b.toByteArray();
        byte[] exif = "Exif\0\0GPSLatitude=25.2854;GPSLongitude=51.5310".getBytes(StandardCharsets.ISO_8859_1);
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        o.write(0xFF); o.write(0xD8);
        o.write(0xFF); o.write(0xE1); o.write((exif.length + 2) >> 8); o.write((exif.length + 2) & 0xFF); o.writeBytes(exif);
        o.write(plain, 2, plain.length - 2);
        return o.toByteArray();
    }

    private static byte[] small() throws IOException {
        return jpeg(64, 48, 0.9f, false);
    }

    private List<Map<String, Object>> sites(Map<String, Object> timeline) {
        return (List<Map<String, Object>>) timeline.get("sites");
    }

    @Test
    void ac1_withoutPhotoConsentTheCameraIsBlocked_untilConsentIsRecorded() throws Exception {
        Clinic c = clinic();
        Map<String, Object> empty = timeline(c, "");
        assertThat(empty.get("consent")).isNull();
        assertThat(sites(empty)).isEmpty();

        String up = upload(c.doctorToken(), small());
        ResponseEntity<Map> blocked = addPhoto(c, up, c.visitId(), "cheek", "left");
        assertThat(blocked.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(blocked.getBody()).containsEntry("code", "PHOTO_CONSENT_REQUIRED");
        assertThat(inTenantTx(c.t().id(), () -> jdbc.queryForObject("SELECT count(*) FROM clinical_photos", Integer.class))).isZero();

        // a tap is not a signature; unknown scope is 400
        assertThat(consent(c, "record_only", "<svg><path d=\"M10 10\"/></svg>").getBody()).containsEntry("code", "SIGNATURE_REQUIRED");
        assertThat(consent(c, "everything", SIGNATURE).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        ResponseEntity<Map> rec = consent(c, "record_only", SIGNATURE);
        assertThat(rec.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String consentId = (String) rec.getBody().get("consentId");
        assertThat(consent(c, "record_only", SIGNATURE).getBody()).containsEntry("consentId", consentId); // idempotent repeat
        assertThat(consent(c, "record_and_teaching", null).getBody()).containsEntry("code", "PHOTO_CONSENT_ACTIVE");
        Map<String, Object> stored = inTenantTx(c.t().id(), () -> jdbc.queryForMap(
                "SELECT c.scope, c.status, c.signer_kind, f.declared_type FROM photo_consents c JOIN file_objects f ON f.id = c.signature_file_id WHERE c.id = ?::uuid", consentId));
        assertThat(stored).containsEntry("scope", "record_only").containsEntry("status", "active").containsEntry("declared_type", "image/svg+xml");

        ResponseEntity<Map> added = addPhoto(c, up, c.visitId(), "cheek", "left");
        assertThat(added.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(addPhoto(c, up, c.visitId(), "cheek", "left").getBody()).containsEntry("photoId", added.getBody().get("photoId")); // repeat
        assertThat(((Map<String, Object>) timeline(c, "").get("consent"))).containsEntry("consentId", consentId).containsEntry("scope", "record_only");

        // audit + event: ids only (no note, no image metadata); the event names the patient by its opaque ref
        String after = inTenantTx(c.t().id(), () -> jdbc.queryForObject("SELECT after::text FROM audit_log WHERE action = 'derm_photo.added'", String.class));
        assertThat(after).contains("cheek").doesNotContain("Follow-up photo").doesNotContain("GPS");
        String event = inTenantTx(c.t().id(), () -> jdbc.queryForObject(
                "SELECT payload::text FROM outbox_events WHERE event_type = 'specialty.photo.added.v1'", String.class));
        assertThat(event).contains((String) added.getBody().get("photoId")).contains("patientRef").doesNotContain(c.patientId());
        assertThat(inTenantTx(c.t().id(), () -> jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE action = 'derm_photo_consent.recorded'", Integer.class))).isEqualTo(1);
    }

    @Test
    void ac2_ac4_fourVisitsOfTheLeftCheekCompareSideBySide_withDateSiteVisitBadge_andFiveMinuteUrls() throws Exception {
        Clinic c = clinic();
        assertThat(consent(c, "record_and_teaching", SIGNATURE).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        List<String> visits = List.of(pastVisit(c, 90), pastVisit(c, 60), pastVisit(c, 30), c.visitId());
        for (String v : visits) {
            assertThat(addPhoto(c, upload(c.doctorToken(), small()), v, "cheek", "left").getStatusCode()).isEqualTo(HttpStatus.CREATED);
        }
        assertThat(addPhoto(c, upload(c.doctorToken(), small()), c.visitId(), "forearm", "right").getStatusCode()).isEqualTo(HttpStatus.CREATED);

        long t0 = System.currentTimeMillis();
        ResponseEntity<Map> r = exchange(base(c) + "/photos", HttpMethod.GET, authed(c.doctorToken()), Map.class);
        List<Map<String, Object>> sites = sites(r.getBody());
        Map<String, Object> cheek = sites.stream().filter(s -> ((Map<String, Object>) s.get("site")).get("region").equals("cheek")).findFirst().orElseThrow();
        List<Map<String, Object>> photos = (List<Map<String, Object>>) cheek.get("photos");
        // compare: any two dates (first and last) open side by side
        byte[] a = http.exchange(URI.create(url((String) photos.get(0).get("url"))), HttpMethod.GET, null, byte[].class).getBody();
        byte[] b = http.exchange(URI.create(url((String) photos.get(3).get("url"))), HttpMethod.GET, null, byte[].class).getBody();
        assertThat(System.currentTimeMillis() - t0).as("timeline + two images").isLessThan(2000);
        assertThat(a).isNotEmpty();
        assertThat(b).isNotEmpty();

        assertThat(sites).hasSize(2);
        assertThat(photos).hasSize(4);
        assertThat(photos).extracting(p -> p.get("queueEntryId")).containsExactlyElementsOf(visits);
        String today = inTenantTx(c.t().id(), () -> jdbc.queryForObject("SELECT queue_date::text FROM queue_entries WHERE id = ?::uuid", String.class, c.visitId()));
        assertThat(photos).extracting(p -> p.get("visitDate")).containsExactly(LocalDate.now().minusDays(90).toString(),
                LocalDate.now().minusDays(60).toString(), LocalDate.now().minusDays(30).toString(), today);
        Instant now = Instant.now();
        for (Map<String, Object> p : photos) {
            assertThat(p).containsEntry("consentBadge", "record_and_teaching").containsKeys("takenAt", "id");
            assertThat((Map<String, Object>) p.get("site")).containsEntry("region", "cheek").containsEntry("side", "left");
            Instant exp = Instant.parse((String) p.get("urlExpiresAt"));
            assertThat(exp).isBefore(now.plusSeconds(305)).isAfter(now.plusSeconds(200));
            String url = (String) p.get("url");
            assertThat(url).contains("e=" + exp.getEpochSecond());
            // the expiry is signed: pushing it out is refused (expiry itself: Plt029FilesApiTest.signedUrlsExpire...)
            String forged = url.replace("e=" + exp.getEpochSecond(), "e=" + (exp.getEpochSecond() + 3600));
            assertThat(http.exchange(URI.create(url(forged)), HttpMethod.GET, null, byte[].class).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        }
        assertThat(r.getHeaders().getCacheControl()).contains("no-store"); // never in a shared cache
        assertThat(http.exchange(URI.create(url((String) photos.get(0).get("url"))), HttpMethod.GET, null, byte[].class)
                .getHeaders().getCacheControl()).contains("no-store");

        assertThat(sites(timeline(c, "?site=forearm"))).hasSize(1);
        // another clinic cannot see this patient's photos
        Clinic other = clinic();
        assertThat(exchange(base(c) + "/photos", HttpMethod.GET, authed(other.doctorToken()), Map.class).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        // a visit of another patient cannot carry this patient's photo
        assertThat(addPhoto(c, upload(c.doctorToken(), small()), other.visitId(), "cheek", "left").getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void ac3_withdrawnConsentHidesPhotos_keepsThem_andBlocksNewOnes() throws Exception {
        Clinic c = clinic();
        consent(c, "record_only", SIGNATURE);
        String up = upload(c.doctorToken(), small());
        assertThat(addPhoto(c, up, c.visitId(), "cheek", "left").getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(exchange("/v1/files/" + up + "/download-url", HttpMethod.POST, authed(c.doctorToken()), Map.class).getStatusCode()).isEqualTo(HttpStatus.OK);

        ResponseEntity<Map> w = exchange(base(c) + "/photo-consent/withdraw", HttpMethod.POST, authed(c.doctorToken()), Map.class);
        assertThat(w.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(w.getBody()).containsEntry("photosHidden", 1);

        Map<String, Object> t = timeline(c, "");
        assertThat(t.get("consent")).isNull();
        assertThat(t).containsEntry("hiddenCount", 1);
        assertThat(sites(t)).isEmpty();
        assertThat(exchange("/v1/files/" + up + "/download-url", HttpMethod.POST, authed(c.doctorToken()), Map.class).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        // retained: the row and the bytes are still there
        assertThat(inTenantTx(c.t().id(), () -> jdbc.queryForObject("SELECT count(*) FROM clinical_photos WHERE hidden_at IS NOT NULL", Integer.class))).isEqualTo(1);
        assertThat(new JdbcTemplate(superuserDataSource()).queryForObject("SELECT status FROM file_objects WHERE id = ?::uuid", String.class, up)).isEqualTo("available");

        ResponseEntity<Map> blocked = addPhoto(c, upload(c.doctorToken(), small()), c.visitId(), "cheek", "left");
        assertThat(blocked.getBody()).containsEntry("code", "PHOTO_CONSENT_REQUIRED");
        assertThat(exchange(base(c) + "/photo-consent/withdraw", HttpMethod.POST, authed(c.doctorToken()), Map.class).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

        // a new consent allows new photos, but photos hidden by the withdrawal stay hidden
        consent(c, "record_only", SIGNATURE);
        assertThat(addPhoto(c, upload(c.doctorToken(), small()), c.visitId(), "cheek", "left").getStatusCode()).isEqualTo(HttpStatus.CREATED);
        Map<String, Object> again = timeline(c, "");
        assertThat(again).containsEntry("hiddenCount", 1);
        assertThat((List<?>) sites(again).get(0).get("photos")).hasSize(1);
        assertThat(inTenantTx(c.t().id(), () -> jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE action = 'derm_photo_consent.withdrawn'", Integer.class))).isEqualTo(1);
    }

    @Test
    void ac5_aLargePhonePhotoIsCompressedTo1_5MbWithoutLocation_andAnUnscannedUploadIsRefused() throws Exception {
        Clinic c = clinic();
        consent(c, "record_only", SIGNATURE);
        byte[] phone = jpeg(2400, 1800, 0.98f, true);
        assertThat(phone.length).as("precondition").isGreaterThan(1_500_000);
        String up = upload(c.doctorToken(), phone);
        Map<String, Object> f = new JdbcTemplate(superuserDataSource()).queryForMap("SELECT size_bytes, detected_type FROM file_objects WHERE id = ?::uuid", up);
        assertThat((Long) f.get("size_bytes")).isLessThanOrEqualTo(1_500_000L);
        assertThat(f.get("detected_type")).isEqualTo("image/jpeg");
        assertThat(addPhoto(c, up, c.visitId(), "cheek", "left").getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String url = (String) ((List<Map<String, Object>>) sites(timeline(c, "")).get(0).get("photos")).get(0).get("url");
        byte[] stored = http.exchange(URI.create(url(url)), HttpMethod.GET, null, byte[].class).getBody();
        assertThat(stored.length).isLessThanOrEqualTo(1_500_000);
        assertThat(new String(stored, StandardCharsets.ISO_8859_1)).doesNotContain("GPS").doesNotContain("Exif");

        // AV pending: uploaded but never completed (not scanned) -> 422, never viewable
        Map<String, Object> s = slot(c.doctorToken(), 1000);
        putBytes((String) s.get("putUrl"), small());
        ResponseEntity<Map> pending = addPhoto(c, (String) s.get("uploadId"), c.visitId(), "cheek", "left");
        assertThat(pending.getStatusCode().value()).isEqualTo(422);
        assertThat(pending.getBody()).containsEntry("code", "UPLOAD_NOT_CLEAN");
    }

    @Test
    void writesNeedTheDermPlan_readsAndWithdrawalDoNot_andOtherRolesCannotWrite() throws Exception {
        Clinic c = clinic();
        consent(c, "record_only", SIGNATURE);
        String up = upload(c.doctorToken(), small());
        assertThat(addPhoto(c, up, c.visitId(), "cheek", "left").getStatusCode()).isEqualTo(HttpStatus.CREATED);

        // the clinic moves to a plan without dermatology
        String op = platformLoginAndGetAccessToken(seedOperator("sa-" + UUID.randomUUID() + "@nabd.internal", "super_admin", false));
        ResponseEntity<Map> plan = exchange("/v1/platform/plans", HttpMethod.POST, authedJsonBody(op, Map.of(
                "code", "c47-" + UUID.randomUUID(), "name", "P", "monthlyPriceCents", 100000, "currency", "INR",
                "active", true, "modules", List.of("billing", "specialty_gp"), "limits", Map.of("seats", 20, "branches", 2))), Map.class);
        assertThat(exchange("/v1/platform/billing/subscriptions/" + c.t().id(), HttpMethod.POST, authedJsonBody(op, Map.of("planId",
                plan.getBody().get("id"), "mrrCents", 100000, "renewalDate", LocalDate.now().plusDays(30).toString())), Map.class).getStatusCode()).isEqualTo(HttpStatus.OK);
        planEntitlements.evict(c.t().id());

        ResponseEntity<Map> refused = addPhoto(c, upload(c.doctorToken(), small()), c.visitId(), "cheek", "left");
        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(refused.getBody()).containsEntry("code", "ENTITLEMENT_EXPIRED");
        assertThat(sites(timeline(c, ""))).hasSize(1); // history stays readable
        assertThat(exchange(base(c) + "/photo-consent/withdraw", HttpMethod.POST, authed(c.doctorToken()), Map.class).getStatusCode()).isEqualTo(HttpStatus.OK);

        // a receptionist-like role (no derm profile) has no derm access at all
        UUID deskRole = seedRole(c.t().id(), "Desk", false, new ModuleGrant("patients", true, true, false, false, false, false, false));
        String desk = loginAndGetAccessToken(seedStaff(c.t(), deskRole, "r" + SEQ.incrementAndGet() + "@c47.test", phone(), false));
        assertThat(exchange(base(c) + "/photos", HttpMethod.GET, authed(desk), Map.class).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(exchange(base(c) + "/photo-consent", HttpMethod.POST, authedJsonBody(desk, Map.of("scope", "record_only", "signerKind", "patient")),
                Map.class).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }
}
