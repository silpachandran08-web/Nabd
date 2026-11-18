package com.nabd.hms.shared.files;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** CLN-050: numbering setting (display only), tooth / whole-case images on the PLT-029 pipeline, chair-side notes with the LA lot,
 * clinic presets with dentist overrides. Lives beside the file-pipeline tests to reuse their upload helpers. */
class Cln050DentalCaseApiTest extends FilesApiTestBase {

    private record Clinic(SeededTenant tenant, SeededStaff owner, String ownerToken, SeededStaff dentist, String dentistToken,
                          String patientId, String visitId) {
    }

    private Clinic clinic(String n, String phoneTail) {
        SeededTenant t = seedTenant();
        SeededStaff owner = seedStaff(t, seedFullAccessRole(t.id()), "cln050" + n + "o@a.com", "+919800350" + phoneTail + "1", false);
        UUID dentistRole = seedRole(t.id(), "Dentist", false, fullGrant("patients"), fullGrant("queue"), fullGrant("clinical"), fullGrant("specialty_dental"));
        SeededStaff dentist = seedStaff(t, dentistRole, "cln050" + n + "d@a.com", "+919800350" + phoneTail + "2", false);
        String ownerToken = loginAndGetAccessToken(owner);
        String p = registerPatient(ownerToken, "Dental " + n, "+919900350" + phoneTail + "3");
        return new Clinic(t, owner, ownerToken, dentist, loginAndGetAccessToken(dentist), p, checkIn(ownerToken, p, owner.id()));
    }

    private String base(Clinic c) {
        return "/v1/specialty/dental/patients/" + c.patientId();
    }

    private ResponseEntity<Map> post(String token, String path, Object body) {
        return exchange(path, HttpMethod.POST, authedJsonBodyWithHeader(token, "Idempotency-Key", UUID.randomUUID().toString(), body), Map.class);
    }

    private ResponseEntity<Map> put(String token, String path, Object body) {
        return exchange(path, HttpMethod.PUT, authedJsonBody(token, body), Map.class);
    }

    private Map<?, ?> settings(String token) {
        return exchange("/v1/specialty/dental/settings", HttpMethod.GET, authed(token), Map.class).getBody();
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> items(String token, String path) {
        ResponseEntity<Map> r = exchange(path, HttpMethod.GET, authed(token), Map.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        return (List<Map<String, Object>>) r.getBody().get("items");
    }

    private static List<Object> field(Map<?, ?> settings, String list, String field) {
        return ((List<?>) settings.get(list)).stream().map(p -> (Object) ((Map<?, ?>) p).get(field)).toList();
    }

    private static List<Object> codes(Map<?, ?> settings, String list) {
        return field(settings, list, "code");
    }

    private String cleanJpeg(String token) throws InterruptedException {
        String id = uploadAndComplete(token, "xray", "image/jpeg", jpegWithGps());
        assertThat(awaitStatus(token, id, "available", "infected", "rejected_type")).isEqualTo("available");
        return id;
    }

    @Test
    void ac1_universalNumberingIsDisplayOnly_storedTeethStayFdi_andOnlySetupEditCanChangeIt() {
        Clinic c = clinic("num", "01");
        assertThat(settings(c.dentistToken()).get("numbering")).isEqualTo("FDI");
        assertThat(exchange(base(c) + "/chart/36", HttpMethod.PATCH, authedJsonBody(c.dentistToken(), Map.of("status", "decayed")), Map.class)
                .getStatusCode()).isEqualTo(HttpStatus.OK);

        assertThat(put(c.ownerToken(), "/v1/specialty/dental/settings", Map.of("numbering", "UNIVERSAL")).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(settings(c.dentistToken()).get("numbering")).isEqualTo("UNIVERSAL");
        List<?> chart = exchange(base(c) + "/chart", HttpMethod.GET, authed(c.dentistToken()), List.class).getBody();
        assertThat(((Map<?, ?>) chart.get(0)).get("toothNumber")).isEqualTo(36); // storage stays FDI

        assertThat(put(c.ownerToken(), "/v1/specialty/dental/settings", Map.of("numbering", "FDI")).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(exchange(base(c) + "/chart", HttpMethod.GET, authed(c.dentistToken()), List.class).getBody()).isEqualTo(chart);
        assertThat(inTenantTx(c.tenant().id(), () -> jdbc.queryForObject("SELECT tooth_number FROM dental_chart_entries", Integer.class))).isEqualTo(36);

        // a dentist without setup:edit cannot change the clinic setting; a bad value is 400
        assertThat(put(c.dentistToken(), "/v1/specialty/dental/settings", Map.of("numbering", "UNIVERSAL")).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(put(c.ownerToken(), "/v1/specialty/dental/settings", Map.of("numbering", "PALMER")).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(su().queryForObject("SELECT count(*) FROM audit_log WHERE tenant_id = ? AND action = 'dental_settings.update'", Integer.class, c.tenant().id())).isEqualTo(2);
    }

    @Test
    void ac2_ac4_iopaOnTooth36ShowsInItsHistory_opgGoesToTheCaseGallery_replayIsIdempotent_andRefusals() throws Exception {
        Clinic c = clinic("img", "02");
        String iopa = cleanJpeg(c.dentistToken());
        ResponseEntity<Map> r = post(c.dentistToken(), base(c) + "/images", Map.of("uploadId", iopa, "queueEntryId", c.visitId(), "kind", "iopa", "teeth", List.of(36)));
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String imageId = (String) r.getBody().get("imageId");
        String opg = cleanJpeg(c.dentistToken());
        assertThat(post(c.dentistToken(), base(c) + "/images", Map.of("uploadId", opg, "queueEntryId", c.visitId(), "kind", "opg")).getStatusCode())
                .isEqualTo(HttpStatus.CREATED);

        List<Map<String, Object>> tooth36 = items(c.dentistToken(), base(c) + "/images?tooth=36");
        assertThat(tooth36).extracting(m -> m.get("imageId")).containsExactly(imageId);
        assertThat(tooth36.get(0).get("teeth")).isEqualTo(List.of(36));
        assertThat(tooth36.get(0).get("fileId")).isEqualTo(iopa);
        assertThat(items(c.dentistToken(), base(c) + "/images?tooth=46")).isEmpty();
        List<Map<String, Object>> all = items(c.dentistToken(), base(c) + "/images");
        assertThat(all).hasSize(2);
        assertThat(all).filteredOn(m -> ((List<?>) m.get("teeth")).isEmpty()).extracting(m -> m.get("kind")).containsExactly("opg"); // case gallery
        // the image opens through the signed-URL pipeline (bound to the patient)
        assertThat(exchange("/v1/files/" + iopa + "/download-url", HttpMethod.POST, authed(c.dentistToken()), Map.class).getStatusCode()).isEqualTo(HttpStatus.OK);

        // attaching the same upload again returns the same image, no second row
        assertThat(post(c.dentistToken(), base(c) + "/images", Map.of("uploadId", iopa, "queueEntryId", c.visitId(), "kind", "iopa", "teeth", List.of(36)))
                .getBody().get("imageId")).isEqualTo(imageId);
        assertThat(su().queryForObject("SELECT count(*) FROM audit_log WHERE tenant_id = ? AND action = 'dental_image.attach'", Integer.class, c.tenant().id())).isEqualTo(2);

        String infected = uploadAndComplete(c.dentistToken(), "xray", "application/pdf", ("%PDF-1.4\n" + EICAR).getBytes(StandardCharsets.ISO_8859_1));
        awaitStatus(c.dentistToken(), infected, "infected");
        ResponseEntity<Map> bad = post(c.dentistToken(), base(c) + "/images", Map.of("uploadId", infected, "queueEntryId", c.visitId(), "kind", "iopa"));
        assertThat(bad.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(bad.getBody().get("code")).isEqualTo("UPLOAD_NOT_CLEAN");
        String pdf = uploadAndComplete(c.dentistToken(), "xray", "application/pdf", pdf(900));
        awaitStatus(c.dentistToken(), pdf, "available");
        assertThat(post(c.dentistToken(), base(c) + "/images", Map.of("uploadId", pdf, "queueEntryId", c.visitId(), "kind", "iopa")).getStatusCode())
                .isEqualTo(HttpStatus.UNSUPPORTED_MEDIA_TYPE);
        String other = cleanJpeg(c.dentistToken());
        assertThat(post(c.dentistToken(), base(c) + "/images", Map.of("uploadId", other, "queueEntryId", c.visitId(), "kind", "iopa", "teeth", List.of(19)))
                .getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(post(c.dentistToken(), base(c) + "/images", Map.of("uploadId", other, "queueEntryId", c.visitId(), "kind", "xray")).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);

        // a visit of another patient, or the image already bound to this patient used for another one: refused
        String p2 = registerPatient(c.ownerToken(), "Second patient", "+919900302999");
        String visit2 = checkIn(c.ownerToken(), p2, c.owner().id());
        assertThat(post(c.dentistToken(), base(c) + "/images", Map.of("uploadId", other, "queueEntryId", visit2, "kind", "iopa")).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(post(c.dentistToken(), "/v1/specialty/dental/patients/" + p2 + "/images", Map.of("uploadId", iopa, "queueEntryId", visit2, "kind", "iopa"))
                .getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void imagesAndNotesAreTenantIsolated() throws Exception {
        Clinic a = clinic("isoa", "03");
        Clinic b = clinic("isob", "04");
        String id = cleanJpeg(a.dentistToken());
        post(a.dentistToken(), base(a) + "/images", Map.of("uploadId", id, "queueEntryId", a.visitId(), "kind", "intraoral_photo", "teeth", List.of(11, 21)));
        post(a.dentistToken(), base(a) + "/chair-notes", Map.of("queueEntryId", a.visitId(), "tooth", 11, "presetCode", "RCT_STARTED"));
        assertThat(exchange(base(a) + "/images", HttpMethod.GET, authed(b.dentistToken()), Map.class).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(exchange(base(a) + "/chair-notes", HttpMethod.GET, authed(b.dentistToken()), Map.class).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(post(b.dentistToken(), base(a) + "/images", Map.of("uploadId", id, "queueEntryId", a.visitId(), "kind", "iopa")).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        for (String table : List.of("dental_images", "dental_chair_notes")) {
            assertThat(inTenantTx(b.tenant().id(), () -> jdbc.queryForObject("SELECT count(*) FROM " + table, Integer.class))).isZero();
            assertThat(inTenantTx(a.tenant().id(), () -> jdbc.queryForObject("SELECT count(*) FROM " + table, Integer.class))).isEqualTo(1);
        }
    }

    @Test
    void ac3_laGivenThenTheLignocaineLot_savesDrugAndLotAgainstToothAndVisit() {
        Clinic c = clinic("la", "05");
        UUID item = su().queryForObject("INSERT INTO charge_catalogue (tenant_id, code, name, category, base_amount, stock_qty, active) "
                + "VALUES (?, 'LA-1', 'Lignocaine 2% cartridge', 'Pharmacy', 10, 15, true) RETURNING id", UUID.class, c.tenant().id());
        su().update("INSERT INTO stock_batches (tenant_id, item_id, batch_no, expiry_date, qty_on_hand, purchase_rate) VALUES "
                + "(?, ?, 'LOT-OK', current_date + 200, 10, 5), (?, ?, 'LOT-OLD', current_date - 400, 5, 5)", c.tenant().id(), item, c.tenant().id(), item);

        // tap 1 'LA given' lists the usable lots (expired ones never), tap 2 saves the note
        List<?> lots = exchange("/v1/specialty/dental/la-lots?q=lignocaine", HttpMethod.GET, authed(c.dentistToken()), List.class).getBody();
        assertThat(lots.stream().map(l -> (Object) ((Map<?, ?>) l).get("lot")).toList()).containsExactly("LOT-OK");
        assertThat(((Map<?, ?>) lots.get(0)).get("drugId")).isEqualTo(item.toString());
        ResponseEntity<Map> r = post(c.dentistToken(), base(c) + "/chair-notes", Map.of("queueEntryId", c.visitId(), "tooth", 46, "presetCode", "LA_GIVEN",
                "laBatch", Map.of("drugId", item.toString(), "lot", "LOT-OK")));
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        List<Map<String, Object>> notes = items(c.dentistToken(), base(c) + "/chair-notes?tooth=46");
        assertThat(notes).hasSize(1);
        assertThat(notes.get(0)).containsEntry("noteId", r.getBody().get("noteId")).containsEntry("queueEntryId", c.visitId())
                .containsEntry("tooth", 46).containsEntry("presetCode", "LA_GIVEN").containsEntry("laDrugId", item.toString()).containsEntry("laLot", "LOT-OK");
        assertThat((String) notes.get(0).get("text")).contains("LA given", "Lignocaine 2% cartridge", "LOT-OK");
        assertThat(items(c.dentistToken(), base(c) + "/chair-notes?tooth=36")).isEmpty();
        assertThat(su().queryForObject("SELECT count(*) FROM audit_log WHERE tenant_id = ? AND action = 'dental_chair_note.create'", Integer.class, c.tenant().id())).isEqualTo(1);

        ResponseEntity<Map> expired = post(c.dentistToken(), base(c) + "/chair-notes", Map.of("queueEntryId", c.visitId(), "tooth", 46, "presetCode", "LA_GIVEN",
                "laBatch", Map.of("drugId", item.toString(), "lot", "LOT-OLD")));
        assertThat(expired.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(expired.getBody().get("code")).isEqualTo("LA_LOT_UNKNOWN");
        assertThat(post(c.dentistToken(), base(c) + "/chair-notes", Map.of("queueEntryId", c.visitId(), "tooth", 46, "presetCode", "RCT_STARTED",
                "laBatch", Map.of("drugId", item.toString(), "lot", "LOT-OK"))).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(post(c.dentistToken(), base(c) + "/chair-notes", Map.of("queueEntryId", c.visitId(), "tooth", 46, "presetCode", "NOPE"))
                .getBody().get("code")).isEqualTo("UNKNOWN_PRESET");
        assertThat(post(c.dentistToken(), base(c) + "/chair-notes", Map.of("queueEntryId", c.visitId(), "tooth", 50, "presetCode", "RCT_STARTED"))
                .getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void ac5_clinicPresetsReachEveryDentist_andADentistsOwnPresetOverridesByCode() {
        Clinic c = clinic("pre", "06");
        SeededStaff d2 = seedStaff(c.tenant(), c.dentist().roleId(), "cln050pre2d@a.com", "+919800306009", false);
        String d2Token = loginAndGetAccessToken(d2);
        assertThat(codes(settings(c.dentistToken()), "presets")).containsExactly("RCT_STARTED", "LA_GIVEN", "NEXT_OBTURATION");

        Map<String, Object> clinicX = Map.of("code", "X", "label", "Clinic X", "la", false);
        assertThat(put(c.ownerToken(), "/v1/specialty/dental/settings", Map.of("numbering", "FDI", "presets",
                List.of(Map.of("code", "A", "label", "Clinic A", "la", false), clinicX))).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(put(c.dentistToken(), "/v1/specialty/dental/settings/my-presets", Map.of("presets",
                List.of(Map.of("code", "X", "label", "My X", "la", false), Map.of("code", "C", "label", "Mine only", "la", false)))).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        // a later clinic edit (new preset D) reaches both dentists; the dentist's X still overrides
        assertThat(put(c.ownerToken(), "/v1/specialty/dental/settings", Map.of("numbering", "FDI", "presets",
                List.of(Map.of("code", "A", "label", "Clinic A", "la", false), clinicX, Map.of("code", "D", "label", "Clinic D", "la", false)))).getStatusCode())
                .isEqualTo(HttpStatus.OK);

        Map<?, ?> mine = settings(c.dentistToken());
        assertThat(codes(mine, "presets")).containsExactly("A", "X", "D", "C");
        assertThat(field(mine, "presets", "label")).containsExactly("Clinic A", "My X", "Clinic D", "Mine only");
        Map<?, ?> theirs = settings(d2Token);
        assertThat(field(theirs, "presets", "label")).containsExactly("Clinic A", "Clinic X", "Clinic D");

        assertThat(post(c.dentistToken(), base(c) + "/chair-notes", Map.of("queueEntryId", c.visitId(), "tooth", 11, "presetCode", "C")).getStatusCode())
                .isEqualTo(HttpStatus.CREATED);
        assertThat(post(d2Token, base(c) + "/chair-notes", Map.of("queueEntryId", c.visitId(), "tooth", 11, "presetCode", "C")).getStatusCode())
                .isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(put(c.dentistToken(), "/v1/specialty/dental/settings/my-presets", Map.of("presets",
                List.of(Map.of("code", "X", "label", "a", "la", false), Map.of("code", "X", "label", "b", "la", false)))).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        // an empty list goes back to the clinic list
        put(c.dentistToken(), "/v1/specialty/dental/settings/my-presets", Map.of("presets", List.of()));
        assertThat(codes(settings(c.dentistToken()), "presets")).containsExactly("A", "X", "D");
    }
}
