package com.nabd.hms.clinical.derm;

import com.nabd.hms.common.ModuleGrant;
import com.nabd.hms.support.ApiTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/** S24 QA (t1) CLN-047: consent gate, withdrawal vs already-issued links, tenant and own-patients scope, key reuse, foreign uploads. */
@SuppressWarnings({"unchecked", "rawtypes"})
class S24QaCln047DermPhotosApiTest extends ApiTestBase {

    private static final AtomicInteger SEQ = new AtomicInteger((int) (System.nanoTime() % 300_000) + 600_000);
    private static final String SIGNATURE = "<svg><path d=\"M20 150 L60 60 L100 140 L140 50 L180 150 L220 70\"/><path d=\"M240 120 L300 80 L360 130 L420 60 L480 140\"/></svg>";

    private record Clinic(SeededTenant t, String ownerToken, UUID doctorRole, SeededStaff doctor, String doctorToken, String patientId, String visitId) {
    }

    private static String phone() {
        return "+9194" + (10000000 + SEQ.incrementAndGet());
    }

    private static JdbcTemplate su() {
        return new JdbcTemplate(superuserDataSource());
    }

    private Clinic clinic() {
        SeededTenant t = seedTenant();
        SeededStaff owner = seedStaff(t, seedFullAccessRole(t.id()), "o" + SEQ.incrementAndGet() + "@q47.test", phone(), false);
        UUID role = seedRole(t.id(), "QDoctor", false, new ModuleGrant("queue", true, true, true, false, false, false, false),
                new ModuleGrant("clinical", true, true, true, false, false, false, false),
                new ModuleGrant("patients", true, false, false, false, false, false, false));
        String ownerToken = loginAndGetAccessToken(owner);
        SeededStaff doctor = dermDoctor(t, ownerToken, role);
        String patientId = patient(ownerToken);
        return new Clinic(t, ownerToken, role, doctor, loginAndGetAccessToken(doctor), patientId, checkIn(ownerToken, patientId, doctor.id()));
    }

    private SeededStaff dermDoctor(SeededTenant t, String ownerToken, UUID role) {
        SeededStaff d = seedStaff(t, role, "d" + SEQ.incrementAndGet() + "@q47.test", phone(), false);
        inTenantTx(t.id(), () -> jdbc.update("UPDATE staff SET is_clinician = true WHERE id = ?", d.id()));
        assertThat(exchange("/v1/staff/" + d.id() + "/specialty-profiles", HttpMethod.PUT,
                authedJsonBody(ownerToken, Map.of("primary", "DERM")), Map.class).getStatusCode()).isEqualTo(HttpStatus.OK);
        return d;
    }

    private String patient(String token) {
        int n = SEQ.incrementAndGet();
        ResponseEntity<Map> r = exchange("/v1/patients", HttpMethod.POST, authedJsonBody(token, Map.of(
                "name", "Q47 Patient " + n, "phone", phone(), "dob", "19" + (50 + n % 40) + "-0" + (1 + n % 9) + "-1" + (n % 9), "gender", "female")), Map.class);
        assertThat(r.getStatusCode().is2xxSuccessful()).as("patient %s", r.getBody()).isTrue();
        return (String) r.getBody().get("id");
    }

    private String checkIn(String token, String patientId, UUID doctorId) {
        ResponseEntity<Map> r = exchange("/v1/queue/check-in", HttpMethod.POST, authedJsonBody(token, Map.of(
                "patientId", patientId, "doctorId", doctorId.toString())), Map.class);
        assertThat(r.getStatusCode().is2xxSuccessful()).as("check-in %s", r.getBody()).isTrue();
        return (String) r.getBody().get("id");
    }

    private static String base(String patientId) {
        return "/v1/specialty/derm/patients/" + patientId;
    }

    private ResponseEntity<Map> consent(String token, String patientId) {
        return exchange(base(patientId) + "/photo-consent", HttpMethod.POST, authedJsonBody(token,
                Map.of("scope", "record_only", "signerKind", "patient", "signatureSvg", SIGNATURE)), Map.class);
    }

    private ResponseEntity<Map> addPhoto(String token, String patientId, String uploadId, String visitId) {
        return exchange(base(patientId) + "/photos", HttpMethod.POST, authedJsonBody(token, Map.of("uploadId", uploadId,
                "queueEntryId", visitId, "site", Map.of("region", "cheek", "side", "left"))), Map.class);
    }

    private ResponseEntity<Map> timeline(String token, String patientId) {
        return exchange(base(patientId) + "/photos", HttpMethod.GET, authed(token), Map.class);
    }

    private ResponseEntity<Map> downloadUrl(String token, String fileId) {
        return exchange("/v1/files/" + fileId + "/download-url", HttpMethod.POST, authed(token), Map.class);
    }

    private static byte[] small() throws IOException {
        BufferedImage img = new BufferedImage(64, 48, BufferedImage.TYPE_INT_RGB);
        for (int x = 0; x < 64; x++) img.setRGB(x, x % 48, 0xAA3355);
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        ImageIO.write(img, "jpeg", b);
        return b.toByteArray();
    }

    /** init + PUT + complete + scan; the upload (= file) id. */
    private String upload(String token) throws Exception {
        return upload(token, "clinical_photo");
    }

    private String upload(String token, String purpose) throws Exception {
        byte[] data = small();
        ResponseEntity<Map> s = exchange("/v1/files/uploads", HttpMethod.POST, authedJsonBody(token, Map.of(
                "purpose", purpose, "contentType", "image/jpeg", "sizeBytes", data.length)), Map.class);
        assertThat(s.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_OCTET_STREAM);
        http.exchange(URI.create(url((String) s.getBody().get("putUrl"))), HttpMethod.PUT, new HttpEntity<>(data, h), Void.class);
        String id = (String) s.getBody().get("uploadId");
        exchange("/v1/files/uploads/" + id + "/complete", HttpMethod.POST, authedJsonBody(token, Map.of()), Map.class);
        for (int i = 0; i < 300; i++) {
            if ("available".equals(exchange("/v1/files/" + id, HttpMethod.GET, authed(token), Map.class).getBody().get("status"))) return id;
            Thread.sleep(100);
        }
        throw new AssertionError("scan never finished");
    }

    private List<Map<String, Object>> photos(Map<String, Object> timeline) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> s : (List<Map<String, Object>>) timeline.get("sites")) out.addAll((List<Map<String, Object>>) s.get("photos"));
        return out;
    }

    // C47-A01: no consent = 409 PHOTO_CONSENT_REQUIRED and nothing stored
    @Test
    void c47a01_noConsentBlocksThePhoto() throws Exception {
        Clinic c = clinic();
        ResponseEntity<Map> r = addPhoto(c.doctorToken(), c.patientId(), upload(c.doctorToken()), c.visitId());
        int rows = inTenantTx(c.t().id(), () -> jdbc.queryForObject("SELECT count(*) FROM clinical_photos", Integer.class));
        System.out.println("C47-A01 photo without consent=" + r.getStatusCode() + " " + r.getBody() + " rows=" + rows);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(r.getBody()).containsEntry("code", "PHOTO_CONSENT_REQUIRED");
        assertThat(rows).isZero();
    }

    // C47-A02 + A03: withdrawal hides the photos; download-url 404; and what the already-issued signed links do
    @Test
    void c47a02_withdrawalHidesPhotos_downloadUrl404_andEarlierIssuedLinks() throws Exception {
        Clinic c = clinic();
        assertThat(consent(c.doctorToken(), c.patientId()).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String up1 = upload(c.doctorToken()), up2 = upload(c.doctorToken());
        assertThat(addPhoto(c.doctorToken(), c.patientId(), up1, c.visitId()).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(addPhoto(c.doctorToken(), c.patientId(), up2, c.visitId()).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        List<Map<String, Object>> before = photos(timeline(c.doctorToken(), c.patientId()).getBody());
        Instant now = Instant.now();
        for (Map<String, Object> p : before) {
            assertThat(Instant.parse((String) p.get("urlExpiresAt"))).as("C47-A03 url expiry <= 5 min").isBefore(now.plusSeconds(306));
        }
        String issuedTimelineUrl = (String) before.get(0).get("url");
        String issuedDownloadUrl = (String) downloadUrl(c.doctorToken(), up2).getBody().get("url");

        assertThat(exchange(base(c.patientId()) + "/photo-consent/withdraw", HttpMethod.POST, authed(c.doctorToken()), Map.class).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        Map<String, Object> after = timeline(c.doctorToken(), c.patientId()).getBody();
        int d1 = downloadUrl(c.doctorToken(), up1).getStatusCode().value();
        int d2 = downloadUrl(c.doctorToken(), up2).getStatusCode().value();
        int ownerD = downloadUrl(c.ownerToken(), up1).getStatusCode().value();
        int early1 = http.exchange(URI.create(url(issuedTimelineUrl)), HttpMethod.GET, null, byte[].class).getStatusCode().value();
        int early2 = http.exchange(URI.create(url(issuedDownloadUrl)), HttpMethod.GET, null, byte[].class).getStatusCode().value();
        System.out.println("C47-A02 hiddenCount=" + after.get("hiddenCount") + " photosShown=" + photos(after).size() + " downloadUrl=" + d1 + "," + d2
                + " ownerDownloadUrl=" + ownerD + " earlierTimelineUrl=" + early1 + " earlierDownloadUrl=" + early2);
        assertThat(after).containsEntry("hiddenCount", 2);
        assertThat(photos(after)).isEmpty();
        assertThat(List.of(d1, d2, ownerD)).containsOnly(404);
        assertThat(List.of(early1, early2)).as("links of hidden photos issued before the withdrawal still serve the image").doesNotContain(200);
    }

    // C47-A04: another clinic never reads or writes this patient's photos
    @Test
    void c47a04_otherTenantGets404Everywhere() throws Exception {
        Clinic a = clinic();
        consent(a.doctorToken(), a.patientId());
        String up = upload(a.doctorToken());
        assertThat(addPhoto(a.doctorToken(), a.patientId(), up, a.visitId()).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        Clinic b = clinic();
        int tl = timeline(b.doctorToken(), a.patientId()).getStatusCode().value();
        int dl = downloadUrl(b.doctorToken(), up).getStatusCode().value();
        int cons = consent(b.doctorToken(), a.patientId()).getStatusCode().value();
        int wd = exchange(base(a.patientId()) + "/photo-consent/withdraw", HttpMethod.POST, authed(b.doctorToken()), Map.class).getStatusCode().value();
        int add = addPhoto(b.doctorToken(), a.patientId(), upload(b.doctorToken()), a.visitId()).getStatusCode().value();
        System.out.println("C47-A04 otherTenant timeline=" + tl + " downloadUrl=" + dl + " consent=" + cons + " withdraw=" + wd + " addPhoto=" + add);
        assertThat(List.of(tl, dl, cons, wd, add)).containsOnly(404);
        assertThat(photos(timeline(a.doctorToken(), a.patientId()).getBody())).hasSize(1);
    }

    // C47-A05: a DERM doctor limited to own patients cannot read, add, or withdraw (hide) another doctor's patient's photos
    @Test
    void c47a05_ownPatientsOnlyDermDoctorIsOutOfScope() throws Exception {
        Clinic c = clinic();
        consent(c.doctorToken(), c.patientId());
        String up = upload(c.doctorToken());
        assertThat(addPhoto(c.doctorToken(), c.patientId(), up, c.visitId()).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        SeededStaff own = dermDoctor(c.t(), c.ownerToken(), c.doctorRole());
        su().update("UPDATE staff SET scope = 'own_patients_only' WHERE id = ?", own.id());
        String ownToken = loginAndGetAccessToken(own);
        int tl = timeline(ownToken, c.patientId()).getStatusCode().value();
        int dl = downloadUrl(ownToken, up).getStatusCode().value();
        int add = addPhoto(ownToken, c.patientId(), upload(ownToken), c.visitId()).getStatusCode().value();
        int wd = exchange(base(c.patientId()) + "/photo-consent/withdraw", HttpMethod.POST, authed(ownToken), Map.class).getStatusCode().value();
        Integer hidden = inTenantTx(c.t().id(), () -> jdbc.queryForObject("SELECT count(*) FROM clinical_photos WHERE hidden_at IS NOT NULL", Integer.class));
        System.out.println("C47-A05 ownScope timeline=" + tl + " downloadUrl=" + dl + " addPhoto=" + add + " withdraw=" + wd + " hiddenAfter=" + hidden);
        assertThat(List.of(tl, dl, add, wd)).containsOnly(404);
        assertThat(hidden).isZero();
    }

    // C47-A06: a doctor without the DERM profile: timeline 403; the generic download-url of a derm photo is recorded
    @Test
    void c47a06_doctorWithoutDermProfile() throws Exception {
        Clinic c = clinic();
        consent(c.doctorToken(), c.patientId());
        String up = upload(c.doctorToken());
        assertThat(addPhoto(c.doctorToken(), c.patientId(), up, c.visitId()).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String gp = loginAndGetAccessToken(seedStaff(c.t(), c.doctorRole(), "gp" + SEQ.incrementAndGet() + "@q47.test", phone(), false));
        int tl = timeline(gp, c.patientId()).getStatusCode().value();
        int dl = downloadUrl(gp, up).getStatusCode().value();
        System.out.println("C47-A06 noDermProfile timeline=" + tl + " genericDownloadUrlOfDermPhoto=" + dl + " (observation: needs the file id)");
        assertThat(tl).isEqualTo(403);
    }

    // C47-A08: the same Idempotency-Key with another body never records a second consent
    @Test
    void c47a08_idempotencyKeyReuseOnConsent() {
        Clinic c = clinic();
        String key = UUID.randomUUID().toString();
        ResponseEntity<Map> a = exchange(base(c.patientId()) + "/photo-consent", HttpMethod.POST, authedJsonBodyWithHeader(c.doctorToken(), "Idempotency-Key", key,
                Map.of("scope", "record_only", "signerKind", "patient", "signatureSvg", SIGNATURE)), Map.class);
        ResponseEntity<Map> b = exchange(base(c.patientId()) + "/photo-consent", HttpMethod.POST, authedJsonBodyWithHeader(c.doctorToken(), "Idempotency-Key", key,
                Map.of("scope", "record_and_teaching", "signerKind", "guardian", "signatureSvg", SIGNATURE)), Map.class);
        Integer n = inTenantTx(c.t().id(), () -> jdbc.queryForObject("SELECT count(*) FROM photo_consents", Integer.class));
        String scope = inTenantTx(c.t().id(), () -> jdbc.queryForObject("SELECT scope FROM photo_consents WHERE status = 'active'", String.class));
        System.out.println("C47-A08 first=" + a.getStatusCode() + " sameKeyOtherBody=" + b.getStatusCode() + " " + b.getBody() + " consents=" + n + " activeScope=" + scope);
        assertThat(a.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(b.getStatusCode().is2xxSuccessful() && !a.getBody().get("consentId").equals(b.getBody().get("consentId"))).isFalse();
        assertThat(n).isEqualTo(1);
        assertThat(scope).isEqualTo("record_only");
    }

    // C47-A09: patient A's photo upload can never be registered on patient B
    @Test
    void c47a09_anotherPatientsUploadIsRefused() throws Exception {
        Clinic c = clinic();
        consent(c.doctorToken(), c.patientId());
        String up = upload(c.doctorToken());
        assertThat(addPhoto(c.doctorToken(), c.patientId(), up, c.visitId()).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String p2 = patient(c.ownerToken());
        String v2 = checkIn(c.ownerToken(), p2, c.doctor().id());
        consent(c.doctorToken(), p2);
        ResponseEntity<Map> r = addPhoto(c.doctorToken(), p2, up, v2);
        Integer onB = inTenantTx(c.t().id(), () -> jdbc.queryForObject("SELECT count(*) FROM clinical_photos WHERE patient_id = ?::uuid", Integer.class, p2));
        System.out.println("C47-A09 A's upload on B=" + r.getStatusCode() + " " + r.getBody() + " rowsOnB=" + onB);
        assertThat(r.getStatusCode().is4xxClientError()).isTrue();
        assertThat(onB).isZero();
        // and a visit of patient B cannot carry a photo of patient A
        Map<String, Object> body = new HashMap<>(Map.of("uploadId", upload(c.doctorToken()), "queueEntryId", v2, "site", Map.of("region", "cheek", "side", "left")));
        assertThat(exchange(base(c.patientId()) + "/photos", HttpMethod.POST, authedJsonBody(c.doctorToken(), body), Map.class).getStatusCode().value()).isEqualTo(404);
    }

    private int get(String signedUrl) {
        return http.exchange(URI.create(url(signedUrl)), HttpMethod.GET, null, byte[].class).getStatusCode().value();
    }

    // C47-A10 (round 2, T1-BUG-4 fix): after a withdrawal, a NEW consent and a NEW photo: the new link serves, the old links stay dead
    @Test
    void c47a10_newConsentAndNewPhotoServe_oldLinksStayHidden() throws Exception {
        Clinic c = clinic();
        consent(c.doctorToken(), c.patientId());
        String old = upload(c.doctorToken());
        assertThat(addPhoto(c.doctorToken(), c.patientId(), old, c.visitId()).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        List<Map<String, Object>> first = photos(timeline(c.doctorToken(), c.patientId()).getBody());
        String oldTimelineUrl = (String) first.get(0).get("url");
        String oldDownloadUrl = (String) downloadUrl(c.doctorToken(), old).getBody().get("url");
        int before = get(oldTimelineUrl);
        assertThat(exchange(base(c.patientId()) + "/photo-consent/withdraw", HttpMethod.POST, authed(c.doctorToken()), Map.class).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(consent(c.doctorToken(), c.patientId()).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String fresh = upload(c.doctorToken());
        assertThat(addPhoto(c.doctorToken(), c.patientId(), fresh, c.visitId()).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        Map<String, Object> tl = timeline(c.doctorToken(), c.patientId()).getBody();
        List<Map<String, Object>> shown = photos(tl);
        int newUrl = shown.isEmpty() ? -1 : get((String) shown.get(0).get("url"));
        int oldT = get(oldTimelineUrl), oldD = get(oldDownloadUrl), oldAsk = downloadUrl(c.doctorToken(), old).getStatusCode().value();
        int reRegister = addPhoto(c.doctorToken(), c.patientId(), old, c.visitId()).getStatusCode().value(); // the hidden upload again: must not come back
        int oldAfterReRegister = get(oldTimelineUrl);
        System.out.println("C47-A10 beforeWithdrawal=" + before + " newPhotoUrl=" + newUrl + " shown=" + shown.size() + " hiddenCount=" + tl.get("hiddenCount")
                + " oldTimelineUrl=" + oldT + " oldDownloadUrl=" + oldD + " newDownloadUrlForOld=" + oldAsk + " reRegisterHiddenUpload=" + reRegister + " oldUrlAfter=" + oldAfterReRegister);
        assertThat(before).isEqualTo(200);
        assertThat(shown).hasSize(1);
        assertThat(newUrl).isEqualTo(200);
        assertThat(tl).containsEntry("hiddenCount", 1);
        assertThat(List.of(oldT, oldD, oldAsk, oldAfterReRegister)).containsOnly(404);
    }

    // C47-A11 (round 2): withdrawing photo consent hides derm photos only: a dental image and a visit attachment of the same patient still open
    @Test
    void c47a11_dentalImageAndVisitAttachmentStillOpenAfterTheDermWithdrawal() throws Exception {
        Clinic c = clinic();
        assertThat(exchange("/v1/staff/" + c.doctor().id() + "/specialty-profiles", HttpMethod.PUT,
                authedJsonBody(c.ownerToken(), Map.of("primary", "DERM", "secondary", "DENT")), Map.class).getStatusCode()).isEqualTo(HttpStatus.OK);
        String doctor = loginAndGetAccessToken(c.doctor()); // the new token carries specialty_dental:*
        consent(doctor, c.patientId());
        String photo = upload(doctor);
        assertThat(addPhoto(doctor, c.patientId(), photo, c.visitId()).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String xray = upload(doctor, "xray");
        ResponseEntity<Map> img = exchange("/v1/specialty/dental/patients/" + c.patientId() + "/images", HttpMethod.POST, authedJsonBody(doctor,
                Map.of("uploadId", xray, "queueEntryId", c.visitId(), "kind", "iopa", "teeth", List.of(36))), Map.class);
        assertThat(img.getStatusCode()).as("%s", img.getBody()).isEqualTo(HttpStatus.CREATED);
        String report = upload(doctor); // a clinical_photo upload that is NOT a derm timeline photo
        ResponseEntity<Map> att = exchange("/v1/clinical/visits/" + c.visitId() + "/attachments", HttpMethod.POST,
                authedJsonBody(doctor, Map.of("fileId", report, "kind", "photo")), Map.class);
        assertThat(att.getStatusCode()).as("%s", att.getBody()).isEqualTo(HttpStatus.CREATED);
        String photoUrl = (String) downloadUrl(doctor, photo).getBody().get("url");
        String xrayUrl = (String) downloadUrl(doctor, xray).getBody().get("url");
        String reportUrl = (String) downloadUrl(doctor, report).getBody().get("url");
        assertThat(exchange(base(c.patientId()) + "/photo-consent/withdraw", HttpMethod.POST, authed(doctor), Map.class).getStatusCode()).isEqualTo(HttpStatus.OK);
        int p = get(photoUrl), x = get(xrayUrl), r = get(reportUrl);
        int xNew = downloadUrl(doctor, xray).getStatusCode().value(), rNew = downloadUrl(doctor, report).getStatusCode().value();
        System.out.println("C47-A11 after derm withdrawal: photoLink=" + p + " dentalXrayLink=" + x + " visitAttachmentLink=" + r + " newXrayUrl=" + xNew + " newAttachmentUrl=" + rNew);
        assertThat(p).isEqualTo(404);
        assertThat(List.of(x, r, xNew, rNew)).containsOnly(200);
    }
}
