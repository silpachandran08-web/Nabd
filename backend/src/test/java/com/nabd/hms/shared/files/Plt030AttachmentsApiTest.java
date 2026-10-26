package com.nabd.hms.shared.files;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class Plt030AttachmentsApiTest extends FilesApiTestBase {

    private record Visit(SeededTenant tenant, SeededStaff staff, String token, String patientId, String visitId) {
    }

    private Visit visit(String n, String phone) {
        SeededTenant t = seedTenant();
        SeededStaff s = seedStaff(t, seedFullAccessRole(t.id()), n + "@a.com", phone, false);
        String token = loginAndGetAccessToken(s);
        String p = registerPatient(token, n, phone.replace("+9198", "+9199"));
        return new Visit(t, s, token, p, checkIn(token, p, s.id()));
    }

    private String attach(Visit v, String fileId) {
        ResponseEntity<Map> r = exchange("/v1/clinical/visits/" + v.visitId() + "/attachments", HttpMethod.POST,
                authedJsonBody(v.token(), Map.of("fileId", fileId, "kind", "outside_report", "note", "CBC from outside lab")), Map.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return (String) r.getBody().get("attachmentId");
    }

    private int phiRows(Visit v, String type) throws InterruptedException {
        Thread.sleep(1500); // the PLT-039 writer is asynchronous
        return su().queryForObject("SELECT count(*) FROM phi_read_log WHERE tenant_id = ? AND patient_id = ?::uuid AND resource_type = ?",
                Integer.class, v.tenant().id(), v.patientId(), type);
    }

    @Test
    void ac1_ac2_attachListAndOpen_withinFiveMinutes_andEachIssuanceIsLogged() throws Exception {
        Visit v = visit("att1", "+919800300001");
        byte[] data = pdf(200_000);
        String fileId = uploadAndComplete(v.token(), "outside_report", "application/pdf", data);
        assertThat(awaitStatus(v.token(), fileId, "available", "infected")).isEqualTo("available");
        String attachmentId = attach(v, fileId);

        long t0 = System.currentTimeMillis();
        Map<?, ?> list = exchange("/v1/clinical/visits/" + v.visitId() + "/attachments", HttpMethod.GET, authed(v.token()), Map.class).getBody();
        assertThat(System.currentTimeMillis() - t0).isLessThan(2000);
        Map<?, ?> item = (Map<?, ?>) ((List<?>) list.get("items")).get(0);
        assertThat(item.get("attachmentId")).isEqualTo(attachmentId);
        assertThat(item.get("kind")).isEqualTo("outside_report");
        assertThat(item.get("note")).isEqualTo("CBC from outside lab");
        assertThat(item.get("contentType")).isEqualTo("application/pdf");

        Instant before = Instant.now();
        ResponseEntity<Map> u1 = exchange("/v1/files/" + fileId + "/download-url", HttpMethod.POST, authed(v.token()), Map.class);
        assertThat(u1.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(Instant.parse((String) u1.getBody().get("expiresAt"))).isBefore(before.plus(Duration.ofMinutes(5)).plusSeconds(5));
        ResponseEntity<byte[]> got = raw(HttpMethod.GET, (String) u1.getBody().get("url"), byte[].class);
        assertThat(got.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(got.getBody()).isEqualTo(data);
        exchange("/v1/files/" + fileId + "/download-url", HttpMethod.POST, authed(v.token()), Map.class);
        assertThat(phiRows(v, "attachment")).isEqualTo(2); // one phi_read_log row per issuance
        assertThat(su().queryForObject("SELECT count(*) FROM audit_log WHERE tenant_id = ? AND action = 'attachment.added'", Integer.class, v.tenant().id())).isEqualTo(1);
    }

    @Test
    void ac4_withdrawnDataProcessingConsent_makesListAndUrlIssuance404() throws Exception {
        Visit v = visit("att2", "+919800300002");
        String fileId = uploadAndComplete(v.token(), "outside_report", "application/pdf", pdf(1000));
        awaitStatus(v.token(), fileId, "available");
        attach(v, fileId);
        assertThat(exchange("/v1/patients/" + v.patientId() + "/consent/withdraw", HttpMethod.POST,
                authedJsonBody(v.token(), Map.of("consentType", "data_processing")), Void.class).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(exchange("/v1/clinical/visits/" + v.visitId() + "/attachments", HttpMethod.GET, authed(v.token()), Map.class).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(exchange("/v1/files/" + fileId + "/download-url", HttpMethod.POST, authed(v.token()), Map.class).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(phiRows(v, "attachment")).isZero();
    }

    @Test
    void attachRefusals_blockedOrPendingFile409_unknownVisitOrFile404_secondPatientCannotReuseTheFile() throws Exception {
        Visit v = visit("att3", "+919800300003");
        String infected = uploadAndComplete(v.token(), "outside_report", "application/pdf", ("%PDF-1.4\n" + EICAR).getBytes(StandardCharsets.ISO_8859_1));
        awaitStatus(v.token(), infected, "infected");
        ResponseEntity<Map> r = exchange("/v1/clinical/visits/" + v.visitId() + "/attachments", HttpMethod.POST,
                authedJsonBody(v.token(), Map.of("fileId", infected, "kind", "photo")), Map.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(r.getBody().get("type").toString()).contains("FILE_NOT_AVAILABLE");

        assertThat(exchange("/v1/clinical/visits/" + UUID.randomUUID() + "/attachments", HttpMethod.POST,
                authedJsonBody(v.token(), Map.of("fileId", infected, "kind", "photo")), Map.class).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(exchange("/v1/clinical/visits/" + v.visitId() + "/attachments", HttpMethod.POST,
                authedJsonBody(v.token(), Map.of("fileId", UUID.randomUUID().toString(), "kind", "photo")), Map.class).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(exchange("/v1/clinical/visits/" + v.visitId() + "/attachments", HttpMethod.POST,
                authedJsonBody(v.token(), Map.of("fileId", infected, "kind", "audio")), Map.class).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        String clean = uploadAndComplete(v.token(), "outside_report", "application/pdf", pdf(800));
        awaitStatus(v.token(), clean, "available");
        attach(v, clean);
        assertThat(exchange("/v1/clinical/visits/" + v.visitId() + "/attachments", HttpMethod.POST,
                authedJsonBody(v.token(), Map.of("fileId", clean, "kind", "photo")), Map.class).getStatusCode()).isEqualTo(HttpStatus.CONFLICT); // already attached

        String p2 = registerPatient(v.token(), "Zubair Khan", "+919900300033");
        String visit2 = checkIn(v.token(), p2, v.staff().id());
        assertThat(exchange("/v1/clinical/visits/" + visit2 + "/attachments", HttpMethod.POST,
                authedJsonBody(v.token(), Map.of("fileId", clean, "kind", "photo")), Map.class).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void downloadUrlForAnUnattachedFile_orAnotherTenantsFile_is404_andRowsAreRlsIsolated() throws Exception {
        Visit a = visit("att4a", "+919800300004");
        Visit b = visit("att4b", "+919800300005");
        String fileId = uploadAndComplete(a.token(), "outside_report", "application/pdf", pdf(800));
        awaitStatus(a.token(), fileId, "available");
        assertThat(exchange("/v1/files/" + fileId + "/download-url", HttpMethod.POST, authed(a.token()), Map.class).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND); // no patient yet
        attach(a, fileId);
        assertThat(exchange("/v1/files/" + fileId + "/download-url", HttpMethod.POST, authed(b.token()), Map.class).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(exchange("/v1/clinical/visits/" + a.visitId() + "/attachments", HttpMethod.GET, authed(b.token()), Map.class).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(inTenantTx(b.tenant().id(), () -> jdbc.queryForObject("SELECT count(*) FROM visit_attachments", Integer.class))).isZero();
        assertThat(inTenantTx(a.tenant().id(), () -> jdbc.queryForObject("SELECT count(*) FROM visit_attachments", Integer.class))).isEqualTo(1);
    }
}
