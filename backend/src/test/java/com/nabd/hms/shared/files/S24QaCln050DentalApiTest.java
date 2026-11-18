package com.nabd.hms.shared.files;

import com.nabd.hms.common.ModuleGrant;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/** S24 QA (t1) CLN-050: roles, LA lots that must never be offered, an unscanned upload, another clinic's X-ray. */
@SuppressWarnings({"unchecked", "rawtypes"})
class S24QaCln050DentalApiTest extends FilesApiTestBase {

    private static final AtomicInteger SEQ = new AtomicInteger((int) (System.nanoTime() % 300_000) + 300_000);

    private record Clinic(SeededTenant tenant, SeededStaff owner, String ownerToken, String dentistToken, String patientId, String visitId) {
    }

    private static String phone() {
        return "+9191" + String.format("%08d", SEQ.incrementAndGet());
    }

    private Clinic clinic() {
        SeededTenant t = seedTenant();
        SeededStaff owner = seedStaff(t, seedFullAccessRole(t.id()), "q50o" + SEQ.incrementAndGet() + "@a.com", phone(), false);
        UUID role = seedRole(t.id(), "QDentist", false, fullGrant("patients"), fullGrant("queue"), fullGrant("clinical"), fullGrant("specialty_dental"));
        String ownerToken = loginAndGetAccessToken(owner);
        String dentist = loginAndGetAccessToken(seedStaff(t, role, "q50d" + SEQ.incrementAndGet() + "@a.com", phone(), false));
        String p = registerPatient(ownerToken, "Q50 Dental " + SEQ.incrementAndGet(), phone());
        return new Clinic(t, owner, ownerToken, dentist, p, checkIn(ownerToken, p, owner.id()));
    }

    private String tokenWith(Clinic c, String name, ModuleGrant... grants) {
        UUID role = seedRole(c.tenant().id(), name, false, grants);
        return loginAndGetAccessToken(seedStaff(c.tenant(), role, "q50" + name + SEQ.incrementAndGet() + "@a.com", phone(), false));
    }

    private String base(Clinic c) {
        return "/v1/specialty/dental/patients/" + c.patientId();
    }

    private ResponseEntity<Map> post(String token, String path, Object body) {
        return exchange(path, HttpMethod.POST, authedJsonBodyWithHeader(token, "Idempotency-Key", UUID.randomUUID().toString(), body), Map.class);
    }

    // C50-A01 + A06: who may do what
    @Test
    void c50a01_a06_roles() {
        Clinic c = clinic();
        String desk = tokenWith(c, "QDesk", fullGrant("queue"), fullGrant("patients"));
        String nurse = tokenWith(c, "QNurse", new ModuleGrant("clinical", true, false, false, false, false, false, false),
                new ModuleGrant("specialty_dental", true, false, false, false, false, false, false), new ModuleGrant("patients", true, false, false, false, false, false, false));
        Map<String, Integer> got = new LinkedHashMap<>();
        got.put("dentist PUT settings", exchange("/v1/specialty/dental/settings", HttpMethod.PUT, authedJsonBody(c.dentistToken(), Map.of("numbering", "UNIVERSAL")), Map.class).getStatusCode().value());
        got.put("desk GET images", exchange(base(c) + "/images", HttpMethod.GET, authed(desk), Map.class).getStatusCode().value());
        got.put("desk GET chair-notes", exchange(base(c) + "/chair-notes", HttpMethod.GET, authed(desk), Map.class).getStatusCode().value());
        got.put("desk GET settings", exchange("/v1/specialty/dental/settings", HttpMethod.GET, authed(desk), Map.class).getStatusCode().value());
        got.put("nurse GET images", exchange(base(c) + "/images", HttpMethod.GET, authed(nurse), Map.class).getStatusCode().value());
        got.put("nurse POST chair-note", post(nurse, base(c) + "/chair-notes", Map.of("queueEntryId", c.visitId(), "tooth", 11, "presetCode", "RCT_STARTED")).getStatusCode().value());
        got.put("nurse GET la-lots", exchange("/v1/specialty/dental/la-lots?q=lig", HttpMethod.GET, authed(nurse), Map.class).getStatusCode().value());
        got.put("nurse PUT my-presets", exchange("/v1/specialty/dental/settings/my-presets", HttpMethod.PUT, authedJsonBody(nurse, Map.of("presets", List.of())), Map.class).getStatusCode().value());
        System.out.println("C50-A01/A06 " + got);
        assertThat(got).containsEntry("dentist PUT settings", 403).containsEntry("desk GET images", 403).containsEntry("desk GET chair-notes", 403)
                .containsEntry("desk GET settings", 403).containsEntry("nurse GET images", 200).containsEntry("nurse POST chair-note", 403)
                .containsEntry("nurse GET la-lots", 403).containsEntry("nurse PUT my-presets", 403);
    }

    // C50-A03: zero stock, quarantined, inactive item, another clinic's lot: never offered, never accepted; 'Save without lot' works
    @Test
    void c50a03_unusableLotsAreNeverOfferedOrAccepted() {
        Clinic c = clinic();
        Clinic other = clinic();
        UUID item = su().queryForObject("INSERT INTO charge_catalogue (tenant_id, code, name, category, base_amount, stock_qty, active) "
                + "VALUES (?, 'QLA-1', 'Lignocaine QA cartridge', 'Pharmacy', 10, 20, true) RETURNING id", UUID.class, c.tenant().id());
        UUID inactive = su().queryForObject("INSERT INTO charge_catalogue (tenant_id, code, name, category, base_amount, stock_qty, active) "
                + "VALUES (?, 'QLA-2', 'Lignocaine QA retired', 'Pharmacy', 10, 10, false) RETURNING id", UUID.class, c.tenant().id());
        UUID foreign = su().queryForObject("INSERT INTO charge_catalogue (tenant_id, code, name, category, base_amount, stock_qty, active) "
                + "VALUES (?, 'QLA-3', 'Lignocaine QA other clinic', 'Pharmacy', 10, 10, true) RETURNING id", UUID.class, other.tenant().id());
        su().update("INSERT INTO stock_batches (tenant_id, item_id, batch_no, expiry_date, qty_on_hand, purchase_rate, status) VALUES "
                + "(?, ?, 'LOT-GOOD', current_date + 200, 10, 5, 'active'), (?, ?, 'LOT-EMPTY', current_date + 200, 0, 5, 'active'), "
                + "(?, ?, 'LOT-QUAR', current_date + 200, 10, 5, 'quarantined'), (?, ?, 'LOT-INACT', current_date + 200, 10, 5, 'active')",
                c.tenant().id(), item, c.tenant().id(), item, c.tenant().id(), item, c.tenant().id(), inactive);
        su().update("INSERT INTO stock_batches (tenant_id, item_id, batch_no, expiry_date, qty_on_hand, purchase_rate) VALUES (?, ?, 'LOT-FOREIGN', current_date + 200, 10, 5)",
                other.tenant().id(), foreign);

        List<?> lots = exchange("/v1/specialty/dental/la-lots?q=lignocaine", HttpMethod.GET, authed(c.dentistToken()), List.class).getBody();
        List<Object> offered = lots.stream().map(l -> (Object) ((Map<?, ?>) l).get("lot")).toList();
        Map<String, Integer> notes = new LinkedHashMap<>();
        for (Object[] x : new Object[][]{{item, "LOT-EMPTY"}, {item, "LOT-QUAR"}, {inactive, "LOT-INACT"}, {foreign, "LOT-FOREIGN"}, {item, "LOT-GOOD"}}) {
            notes.put((String) x[1], post(c.dentistToken(), base(c) + "/chair-notes", Map.of("queueEntryId", c.visitId(), "tooth", 46, "presetCode", "LA_GIVEN",
                    "laBatch", Map.of("drugId", x[0].toString(), "lot", x[1]))).getStatusCode().value());
        }
        int noLot = post(c.dentistToken(), base(c) + "/chair-notes", Map.of("queueEntryId", c.visitId(), "tooth", 46, "presetCode", "LA_GIVEN")).getStatusCode().value();
        System.out.println("C50-A03 offered=" + offered + " chairNote=" + notes + " withoutLot=" + noLot);
        assertThat(offered).containsExactly("LOT-GOOD");
        assertThat(notes).containsEntry("LOT-EMPTY", 422).containsEntry("LOT-QUAR", 422).containsEntry("LOT-INACT", 422)
                .containsEntry("LOT-FOREIGN", 422).containsEntry("LOT-GOOD", 201);
        assertThat(noLot).isEqualTo(201);
    }

    // C50-A04: an upload that was never scanned clean is refused
    @Test
    void c50a04_unscannedUploadRefused() {
        Clinic c = clinic();
        ResponseEntity<Map> slot = init(c.dentistToken(), "xray", "image/jpeg", jpegWithGps().length);
        put((String) slot.getBody().get("putUrl"), jpegWithGps());
        ResponseEntity<Map> r = post(c.dentistToken(), base(c) + "/images", Map.of("uploadId", slot.getBody().get("uploadId"), "queueEntryId", c.visitId(), "kind", "iopa", "teeth", List.of(36)));
        System.out.println("C50-A04 not completed/scanned=" + r.getStatusCode() + " " + r.getBody());
        assertThat(r.getStatusCode().value()).isEqualTo(422);
        assertThat(r.getBody()).containsEntry("code", "UPLOAD_NOT_CLEAN");
    }

    // C50-A05: another clinic cannot open this clinic's X-ray
    @Test
    void c50a05_otherClinicCannotOpenTheImage() throws Exception {
        Clinic a = clinic();
        Clinic b = clinic();
        String id = uploadAndComplete(a.dentistToken(), "xray", "image/jpeg", jpegWithGps());
        assertThat(awaitStatus(a.dentistToken(), id, "available", "infected", "rejected_type")).isEqualTo("available");
        assertThat(post(a.dentistToken(), base(a) + "/images", Map.of("uploadId", id, "queueEntryId", a.visitId(), "kind", "iopa", "teeth", List.of(36))).getStatusCode())
                .isEqualTo(HttpStatus.CREATED);
        int dl = exchange("/v1/files/" + id + "/download-url", HttpMethod.POST, authed(b.dentistToken()), Map.class).getStatusCode().value();
        int meta = exchange("/v1/files/" + id, HttpMethod.GET, authed(b.dentistToken()), Map.class).getStatusCode().value();
        System.out.println("C50-A05 other clinic downloadUrl=" + dl + " fileMeta=" + meta);
        assertThat(dl).isEqualTo(404);
        assertThat(meta).isEqualTo(404);
    }
}
