package com.nabd.hms.shared.files;

import com.nabd.hms.support.ApiTestBase;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** PLT-029 / PLT-030 test helpers: fixtures are built at test time, bytes are synthetic. */
abstract class FilesApiTestBase extends ApiTestBase {

    static final String EICAR = "X5O!P%@AP[4\\PZX54(P^)7CC)7}$EICAR-STANDARD-ANTIVIRUS-TEST-FILE!$H+H*";

    record Upload(String uploadId, String putUrl) {
    }

    static JdbcTemplate su() {
        return new JdbcTemplate(superuserDataSource());
    }

    static byte[] pdf(int size) {
        byte[] b = new byte[size];
        byte[] head = "%PDF-1.4\n".getBytes(StandardCharsets.ISO_8859_1);
        System.arraycopy(head, 0, b, 0, head.length);
        for (int i = head.length; i < size; i++) {
            b[i] = (byte) ('a' + i % 26);
        }
        return b;
    }

    /** JFIF + an APP1 Exif segment that carries a GPS marker string + a one-byte scan. */
    static byte[] jpegWithGps() {
        byte[] exif = "Exif\0\0GPSLatitude=25.2854;GPSLongitude=51.5310".getBytes(StandardCharsets.ISO_8859_1);
        byte[] jfif = {'J', 'F', 'I', 'F', 0, 1, 1, 0, 0, 1, 0, 1, 0, 0};
        java.io.ByteArrayOutputStream o = new java.io.ByteArrayOutputStream();
        o.write(0xFF); o.write(0xD8);
        o.write(0xFF); o.write(0xE0); o.write(0); o.write(jfif.length + 2); o.writeBytes(jfif);
        o.write(0xFF); o.write(0xE1); o.write((exif.length + 2) >> 8); o.write((exif.length + 2) & 0xFF); o.writeBytes(exif);
        o.write(0xFF); o.write(0xDA); o.write(0); o.write(2); o.write(1); o.write(2); o.write(3);
        o.write(0xFF); o.write(0xD9);
        return o.toByteArray();
    }

    ResponseEntity<Map> init(String token, String purpose, String contentType, long size) {
        return exchange("/v1/files/uploads", HttpMethod.POST, authedJsonBody(token, Map.of(
                "purpose", purpose, "contentType", contentType, "sizeBytes", size)), Map.class);
    }

    ResponseEntity<Void> put(String putUrl, byte[] data) {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_OCTET_STREAM);
        return http.exchange(java.net.URI.create(url(putUrl)), HttpMethod.PUT, new HttpEntity<>(data, h), Void.class); // a URI: the template must not re-encode %2F
    }

    <T> ResponseEntity<T> raw(HttpMethod m, String signedUrl, Class<T> type) {
        return http.exchange(java.net.URI.create(url(signedUrl)), m, null, type);
    }

    ResponseEntity<Map> complete(String token, String uploadId, Map<String, Object> body) {
        return exchange("/v1/files/uploads/" + uploadId + "/complete", HttpMethod.POST, authedJsonBody(token, body), Map.class);
    }

    /** init + PUT + complete; returns the file id. */
    String uploadAndComplete(String token, String purpose, String declaredType, byte[] data) {
        ResponseEntity<Map> slot = init(token, purpose, declaredType, data.length);
        assertThat(slot.getStatusCode().value()).isEqualTo(201);
        assertThat(put((String) slot.getBody().get("putUrl"), data).getStatusCode().value()).isEqualTo(200);
        ResponseEntity<Map> done = complete(token, (String) slot.getBody().get("uploadId"), Map.of());
        assertThat(done.getStatusCode().value()).isEqualTo(202);
        assertThat(done.getBody().get("status")).isEqualTo("pending_scan");
        return (String) done.getBody().get("fileId");
    }

    String awaitStatus(String token, String fileId, String... terminal) throws InterruptedException {
        for (int i = 0; i < 300; i++) {
            String s = (String) exchange("/v1/files/" + fileId, HttpMethod.GET, authed(token), Map.class).getBody().get("status");
            if (java.util.Arrays.asList(terminal).contains(s)) {
                return s;
            }
            Thread.sleep(100);
        }
        throw new AssertionError("file " + fileId + " never reached " + java.util.Arrays.toString(terminal));
    }

    String registerPatient(String token, String name, String phone) {
        ResponseEntity<Map> r = exchange("/v1/patients", HttpMethod.POST, authedJsonBody(token, Map.of(
                "name", name, "phone", phone, "dob", "1990-01-01", "gender", "male")), Map.class);
        assertThat(r.getStatusCode().value()).as("register patient: %s", r.getBody()).isEqualTo(201);
        return (String) r.getBody().get("id");
    }

    String checkIn(String token, String patientId, UUID doctorId) {
        return (String) exchange("/v1/queue/check-in", HttpMethod.POST, authedJsonBody(token, Map.of(
                "patientId", patientId, "doctorId", doctorId.toString())), Map.class).getBody().get("id");
    }
}
