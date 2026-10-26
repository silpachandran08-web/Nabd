package com.nabd.hms.shared.files;

import com.nabd.hms.support.MutableClock;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class Plt029FilesApiTest extends FilesApiTestBase {

    @Autowired
    ObjectStorage storage;

    private record Ctx(SeededTenant tenant, String token, SeededStaff staff) {
    }

    private Ctx ctx(String n) {
        SeededTenant t = seedTenant();
        SeededStaff s = seedStaff(t, seedFullAccessRole(t.id()), n + "@a.com", "+9198" + (10000000 + Math.abs(n.hashCode()) % 80000000), false);
        return new Ctx(t, loginAndGetAccessToken(s), s);
    }

    @Test
    void ac1_threeMbPdf_becomesAvailableWithStoredSha256_underTheTenantPrefix() throws Exception {
        Ctx c = ctx("f1");
        byte[] data = pdf(3 * 1024 * 1024);
        String fileId = uploadAndComplete(c.token(), "outside_report", "application/pdf", data);

        long t0 = System.currentTimeMillis();
        assertThat(awaitStatus(c.token(), fileId, "available", "infected", "rejected_type")).isEqualTo("available");
        assertThat(System.currentTimeMillis() - t0).isLessThan(30_000);

        Map<String, Object> row = su().queryForMap("SELECT sha256, storage_key, detected_type, scan_engine FROM file_objects WHERE id = ?::uuid", fileId);
        assertThat(HexFormat.of().formatHex((byte[]) row.get("sha256"))).isEqualTo(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data)));
        // AC5: every object lives under tenant/{tenantId}/ and nothing stays in quarantine
        assertThat((String) row.get("storage_key")).isEqualTo("tenant/" + c.tenant().id() + "/" + fileId);
        assertThat(storage.get(ObjectStorage.Bucket.CLEAN, (String) row.get("storage_key"))).hasValueSatisfying(b -> assertThat(b).isEqualTo(data));
        assertThat(storage.get(ObjectStorage.Bucket.QUARANTINE, "quarantine/" + c.tenant().id() + "/" + fileId)).isEmpty();
    }

    @Test
    void ac2_executableDeclaredAsPdf_isRejectedType_andNeverLeavesQuarantine() throws Exception {
        Ctx c = ctx("f2");
        byte[] exe = new byte[2048];
        exe[0] = 'M';
        exe[1] = 'Z';
        String fileId = uploadAndComplete(c.token(), "outside_report", "application/pdf", exe);
        assertThat(awaitStatus(c.token(), fileId, "available", "infected", "rejected_type")).isEqualTo("rejected_type");
        assertThat(storage.get(ObjectStorage.Bucket.CLEAN, "tenant/" + c.tenant().id() + "/" + fileId)).isEmpty();
        assertThat(storage.get(ObjectStorage.Bucket.QUARANTINE, "quarantine/" + c.tenant().id() + "/" + fileId)).isPresent();
        assertThat(su().queryForObject("SELECT count(*) FROM file_blocked_alerts WHERE file_id = ?::uuid AND reason = 'rejected_type'", Integer.class, fileId)).isEqualTo(1);
    }

    @Test
    void ac2_boundary_aPngDeclaredAsPdf_isAlsoRejected() throws Exception {
        Ctx c = ctx("f2b");
        byte[] png = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 0};
        String fileId = uploadAndComplete(c.token(), "clinical_photo", "application/pdf", png);
        assertThat(awaitStatus(c.token(), fileId, "available", "infected", "rejected_type")).isEqualTo("rejected_type");
    }

    @Test
    void ac3_eicar_isInfected_stayedInQuarantine_andTheOwnerGetsAnInAppAlert() throws Exception {
        Ctx c = ctx("f3");
        byte[] data = ("%PDF-1.4\n" + EICAR).getBytes(StandardCharsets.ISO_8859_1);
        String fileId = uploadAndComplete(c.token(), "outside_report", "application/pdf", data);
        assertThat(awaitStatus(c.token(), fileId, "available", "infected", "rejected_type")).isEqualTo("infected");
        assertThat(storage.get(ObjectStorage.Bucket.CLEAN, "tenant/" + c.tenant().id() + "/" + fileId)).isEmpty();
        assertThat(su().queryForObject("SELECT count(*) FROM file_blocked_alerts WHERE file_id = ?::uuid AND owner_staff_id = ? AND reason = 'infected'",
                Integer.class, fileId, c.staff().id())).isEqualTo(1);
        assertThat(su().queryForObject("SELECT count(*) FROM outbox_events WHERE tenant_id = ? AND event_type = 'shared.attachment.scanned.v1' AND payload->>'status' = 'infected'",
                Integer.class, c.tenant().id())).isEqualTo(1);
    }

    @Test
    void ac4_jpegWithGps_isStoredWithoutLocationMetadata() throws Exception {
        Ctx c = ctx("f4");
        byte[] jpeg = jpegWithGps();
        assertThat(new String(jpeg, StandardCharsets.ISO_8859_1)).contains("GPSLatitude");
        String fileId = uploadAndComplete(c.token(), "clinical_photo", "image/jpeg", jpeg);
        assertThat(awaitStatus(c.token(), fileId, "available", "infected", "rejected_type")).isEqualTo("available");
        byte[] stored = storage.get(ObjectStorage.Bucket.CLEAN, "tenant/" + c.tenant().id() + "/" + fileId).orElseThrow();
        String s = new String(stored, StandardCharsets.ISO_8859_1);
        assertThat(s).doesNotContain("GPS").doesNotContain("Exif").contains("JFIF");
        assertThat(stored[0] & 0xFF).isEqualTo(0xFF);
        assertThat(stored[1] & 0xFF).isEqualTo(0xD8);
        assertThat(su().queryForObject("SELECT size_bytes FROM file_objects WHERE id = ?::uuid", Long.class, fileId)).isEqualTo((long) stored.length);
    }

    @Test
    void ac6_initRefuses25Mb_with413_andUnknownTypeWith415_andBoundaryAtTheLimitPasses() {
        Ctx c = ctx("f6");
        ResponseEntity<Map> big = init(c.token(), "outside_report", "application/pdf", 25L * 1024 * 1024);
        assertThat(big).extracting(r -> r.getStatusCode().value()).isEqualTo(413);
        assertThat(big.getBody().get("type").toString()).contains("FILE_TOO_LARGE");
        ResponseEntity<Map> html = init(c.token(), "outside_report", "text/html", 100);
        assertThat(html.getStatusCode()).isEqualTo(HttpStatus.UNSUPPORTED_MEDIA_TYPE);
        assertThat(html.getBody().get("type").toString()).contains("TYPE_NOT_ALLOWED");
        assertThat(init(c.token(), "outside_report", "application/pdf", 20L * 1024 * 1024).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(init(c.token(), "outside_report", "application/pdf", 0).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void completeTwiceIs409_wrongChecksumIs400_unknownIs404_missingBytesIs409() throws Exception {
        Ctx c = ctx("f7");
        byte[] data = pdf(1000);
        ResponseEntity<Map> slot = init(c.token(), "outside_report", "application/pdf", data.length);
        String id = (String) slot.getBody().get("uploadId");
        assertThat(complete(c.token(), id, Map.of()).getStatusCode()).isEqualTo(HttpStatus.CONFLICT); // nothing uploaded yet
        put((String) slot.getBody().get("putUrl"), data);
        assertThat(complete(c.token(), id, Map.of("sha256", "0".repeat(64))).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        String good = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        assertThat(complete(c.token(), id, Map.of("sha256", good)).getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        ResponseEntity<Map> again = complete(c.token(), id, Map.of());
        assertThat(again.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(again.getBody().get("type").toString()).contains("ALREADY_COMPLETED");
        assertThat(complete(c.token(), UUID.randomUUID().toString(), Map.of()).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void presignedUrl_cannotWriteOutsideItsKey_orBeUsedForAnotherMethod_orBeTampered() {
        Ctx c = ctx("f8");
        ResponseEntity<Map> slot = init(c.token(), "outside_report", "application/pdf", 100);
        String putUrl = (String) slot.getBody().get("putUrl");
        String otherKey = "quarantine/" + c.tenant().id() + "/" + UUID.randomUUID();
        String swapped = putUrl.replaceAll("k=[^&]*", "k=" + java.net.URLEncoder.encode(otherKey, StandardCharsets.UTF_8));
        assertThat(put(swapped, pdf(100)).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(storage.get(ObjectStorage.Bucket.QUARANTINE, otherKey)).isEmpty();
        assertThat(put(putUrl.replaceAll("x=\\d+", "x=999999999"), pdf(100)).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(raw(HttpMethod.GET, putUrl.replace("m=PUT", "m=GET"), byte[].class).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(raw(HttpMethod.GET, putUrl, byte[].class).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(put(putUrl, pdf(100)).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void anotherTenantCannotSeeTheFile_andRowsAreRlsIsolated() throws Exception {
        Ctx a = ctx("f9a");
        Ctx b = ctx("f9b");
        String fileId = uploadAndComplete(a.token(), "outside_report", "application/pdf", pdf(500));
        awaitStatus(a.token(), fileId, "available");
        assertThat(exchange("/v1/files/" + fileId, HttpMethod.GET, authed(b.token()), Map.class).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(inTenantTx(b.tenant().id(), () -> jdbc.queryForObject("SELECT count(*) FROM file_objects", Integer.class))).isZero();
        assertThat(inTenantTx(a.tenant().id(), () -> jdbc.queryForObject("SELECT count(*) FROM file_objects", Integer.class))).isEqualTo(1);
        assertThat(complete(b.token(), fileId, Map.of()).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void signedUrlsExpire_putAfterTenMinutes_andGetAfterFive() {
        MutableClock clock = new MutableClock();
        LocalObjectStorage local = new LocalObjectStorage(System.getProperty("java.io.tmpdir") + "/nabd-files-unit", "unit-key", clock);
        String url = local.presign(false, ObjectStorage.Bucket.CLEAN, "tenant/x/y", Duration.ofMinutes(5), 0);
        long exp = Long.parseLong(url.replaceAll(".*[?&]e=(\\d+).*", "$1"));
        assertThat(local.expired(exp)).isFalse();
        clock.advance(Duration.ofMinutes(5).plusSeconds(1));
        assertThat(local.expired(exp)).isTrue();
        assertThat(local.validSignature("GET", "CLEAN", "tenant/x/y", exp, 0, url.replaceAll(".*[?&]s=([0-9a-f]+).*", "$1"))).isTrue();
    }

    @Test
    void sniffer_allowsPdfJpegPng_only_andStripKeepsImageData() {
        assertThat(FileSniffer.detect(pdf(20))).isEqualTo("application/pdf");
        assertThat(FileSniffer.detect(jpegWithGps())).isEqualTo("image/jpeg");
        assertThat(FileSniffer.detect(new byte[]{'M', 'Z', 0, 0, 0, 0, 0, 0})).isNull();
        assertThat(FileSniffer.detect(new byte[]{0x7F, 'E', 'L', 'F', 0, 0, 0, 0})).isNull();
        assertThat(FileSniffer.detect(new byte[]{1})).isNull();
        byte[] out = FileSniffer.stripJpegMetadata(jpegWithGps());
        assertThat(out[out.length - 1] & 0xFF).isEqualTo(0xD9);
    }
}
