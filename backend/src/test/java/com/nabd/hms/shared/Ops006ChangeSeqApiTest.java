package com.nabd.hms.shared;

import com.nabd.hms.support.ApiTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

/** OPS-006: change_seq/updated_at on synced tables, ETag/304, UUID v7 ids, no SELECT *, note autosave without audit. */
class Ops006ChangeSeqApiTest extends ApiTestBase {

    // signing a note needs a registered clinician (CLN-005)
    @Override
    protected SeededStaff seedStaff(SeededTenant tenant, UUID roleId, String email, String mobilePhone, boolean mfaEnabled,
                                    boolean emailVerified, boolean mobileVerified) {
        return com.nabd.hms.support.ClinicianFixture.licensed(
                super.seedStaff(tenant, roleId, email, mobilePhone, mfaEnabled, emailVerified, mobileVerified));
    }

    private static final List<String> SYNCED = List.of("queue_entries", "appointments", "invoices", "prescriptions", "vitals",
            "waitlist_entries", "procedure_orders", "administration_orders", "patients");

    private org.springframework.jdbc.core.JdbcTemplate su() {
        return new org.springframework.jdbc.core.JdbcTemplate(superuserDataSource());
    }

    private UUID patient(String token, String phone) {
        ResponseEntity<Map> r = exchange("/v1/patients", HttpMethod.POST, authedJsonBody(token, Map.of(
                "name", "Seq " + UUID.randomUUID().toString().replace("-", "x").substring(0, 12), "phone", phone,
                "dob", (1950 + Math.abs(phone.hashCode() % 50)) + "-0" + (1 + Math.abs(phone.hashCode() % 9)) + "-1" + Math.abs(phone.hashCode() % 9), "gender", "male")), Map.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return UUID.fromString((String) r.getBody().get("id"));
    }

    private record Clinic(SeededTenant tenant, SeededStaff doctor, String token) {
    }

    private Clinic clinic(String tag, String phone) {
        SeededTenant t = seedTenant();
        SeededStaff d = seedStaff(t, seedFullAccessRole(t.id()), tag + "@seq.test", phone, false);
        return new Clinic(t, d, loginAndGetAccessToken(d));
    }

    private HttpEntity<Void> ifNoneMatch(String token, String etag) {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(token);
        if (etag != null) h.set(HttpHeaders.IF_NONE_MATCH, etag);
        return new HttpEntity<>(h);
    }

    @Test
    void everySyncedTableHasTheColumnsTheIndexAndTheTrigger() {
        for (String t : SYNCED) {
            assertThat(su().queryForList("SELECT column_name FROM information_schema.columns WHERE table_name = ? AND column_name IN ('change_seq','updated_at')", String.class, t))
                    .as(t).containsExactlyInAnyOrder("change_seq", "updated_at");
            assertThat(su().queryForObject("SELECT count(*) FROM pg_trigger WHERE tgrelid = ?::regclass AND tgname = ?", Integer.class, t, "trg_" + t + "_change_seq"))
                    .as(t + " trigger").isEqualTo(1);
            assertThat(su().queryForObject("SELECT count(*) FROM pg_indexes WHERE indexname = ?", Integer.class, "idx_" + t + "_tenant_change_seq"))
                    .as(t + " index").isEqualTo(1);
        }
    }

    @Test
    void changeSeqIncreasesOnEveryInsertAndUpdateAndUpdatedAtFollowsNow() throws Exception {
        Clinic c = clinic("seq-a", "+919500000001");
        UUID p = patient(c.token(), "+919500000101");
        long first = inTenantTx(c.tenant().id(), () -> jdbc.queryForObject("SELECT change_seq FROM patients WHERE id = ?", Long.class, p));
        Instant before = Instant.now();
        Thread.sleep(20);
        long second = inTenantTx(c.tenant().id(), () -> {
            jdbc.update("UPDATE patients SET name = 'Seq Patient 2' WHERE id = ?", p);
            return jdbc.queryForObject("SELECT change_seq FROM patients WHERE id = ?", Long.class, p);
        });
        Instant updatedAt = inTenantTx(c.tenant().id(), () -> jdbc.queryForObject("SELECT updated_at FROM patients WHERE id = ?", java.sql.Timestamp.class, p).toInstant());
        assertThat(second).isGreaterThan(first);
        assertThat(updatedAt).isAfter(before);
    }

    @Test
    void changeSeqStaysMonotonicUnderConcurrentUpdates() throws Exception {
        Clinic c = clinic("seq-b", "+919500000002");
        List<UUID> ids = new ArrayList<>();
        for (int i = 0; i < 6; i++) ids.add(patient(c.token(), "+91950000020" + i));
        var pool = Executors.newFixedThreadPool(6);
        try {
            List<Future<List<Long>>> runs = new ArrayList<>();
            for (UUID id : ids) {
                Callable<List<Long>> work = () -> {
                    List<Long> seen = new ArrayList<>();
                    for (int i = 0; i < 15; i++) {
                        seen.add(inTenantTx(c.tenant().id(), () -> {
                            jdbc.update("UPDATE patients SET name = ? WHERE id = ?", "n" + UUID.randomUUID(), id);
                            return jdbc.queryForObject("SELECT change_seq FROM patients WHERE id = ?", Long.class, id);
                        }));
                    }
                    return seen;
                };
                runs.add(pool.submit(work));
            }
            Set<Long> all = new HashSet<>();
            for (Future<List<Long>> f : runs) {
                List<Long> seen = f.get();
                for (int i = 1; i < seen.size(); i++) assertThat(seen.get(i)).isGreaterThan(seen.get(i - 1));
                all.addAll(seen);
            }
            assertThat(all).hasSize(6 * 15); // one global sequence: no two writes share a value
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void anUnchangedAppointmentAnswers304AndAChangedOneAnswers200WithTheNewTag() {
        Clinic c = clinic("seq-c", "+919500000003");
        Clinic other = clinic("seq-d", "+919500000004");
        UUID p = patient(c.token(), "+919500000103");
        Instant start = Instant.now().plus(5, ChronoUnit.DAYS).truncatedTo(ChronoUnit.HOURS);
        UUID apt = inTenantTx(c.tenant().id(), () -> jdbc.queryForObject(
                "INSERT INTO appointments (tenant_id, patient_id, doctor_id, start_time, end_time) VALUES (?,?,?,?,?) RETURNING id", UUID.class,
                c.tenant().id(), p, c.doctor().id(), java.sql.Timestamp.from(start), java.sql.Timestamp.from(start.plus(15, ChronoUnit.MINUTES))));
        String path = "/v1/appointments/" + apt;

        ResponseEntity<Map> first = exchange(path, HttpMethod.GET, ifNoneMatch(c.token(), null), Map.class);
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.OK);
        String etag = first.getHeaders().getETag();
        assertThat(etag).matches("W/\"\\d+\"");
        assertThat(first.getHeaders().getCacheControl()).contains("no-cache").contains("private");

        ResponseEntity<String> same = exchange(path, HttpMethod.GET, ifNoneMatch(c.token(), etag), String.class);
        assertThat(same.getStatusCode()).isEqualTo(HttpStatus.NOT_MODIFIED);
        assertThat(same.getBody()).isNull();
        assertThat(same.getHeaders().getCacheControl()).contains("no-cache").contains("private");
        assertThat(same.getHeaders().getETag()).isEqualTo(etag);

        inTenantTx(c.tenant().id(), () -> jdbc.update("UPDATE appointments SET is_follow_up = NOT is_follow_up WHERE id = ?", apt));
        ResponseEntity<Map> changed = exchange(path, HttpMethod.GET, ifNoneMatch(c.token(), etag), Map.class);
        assertThat(changed.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(changed.getHeaders().getETag()).isNotEqualTo(etag);

        // another clinic's appointment is 404 even with a tag; an unknown id too
        assertThat(exchange(path, HttpMethod.GET, ifNoneMatch(other.token(), "*"), Map.class).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(exchange("/v1/appointments/" + UUID.randomUUID(), HttpMethod.GET, ifNoneMatch(c.token(), etag), Map.class).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void anInvoiceTagChangesWhenAPaymentIsRecorded() {
        Clinic c = clinic("seq-e", "+919500000005");
        exchange("/v1/pharmacy/settings", HttpMethod.PATCH, authedJsonBody(c.token(), Map.of("mode", "hybrid", "reason", "Test setup")), Map.class);
        inTenantTx(c.tenant().id(), () -> jdbc.update("INSERT INTO charge_catalogue (tenant_id, code, name, category, base_amount) VALUES (?, 'X', 'X', 'Service', 100)", c.tenant().id()));
        ResponseEntity<Map> inv = exchange("/v1/billing/otc-checkout", HttpMethod.POST, authedJsonBody(c.token(), Map.of(
                "lineItems", List.of(Map.of("chargeCode", "X", "chargeName", "X", "category", "Service", "quantity", 1, "unitPrice", 100.00, "taxRatePercent", 0)),
                "discount", 0)), Map.class);
        String path = "/v1/billing/invoices/" + inv.getBody().get("id");
        String etag = exchange(path, HttpMethod.GET, ifNoneMatch(c.token(), null), Map.class).getHeaders().getETag();
        assertThat(exchange(path, HttpMethod.GET, ifNoneMatch(c.token(), etag), String.class).getStatusCode()).isEqualTo(HttpStatus.NOT_MODIFIED);
        exchange(path + "/payments", HttpMethod.POST, authedJsonBody(c.token(), Map.of("method", "cash", "amount", 40)), Map.class);
        ResponseEntity<Map> after = exchange(path, HttpMethod.GET, ifNoneMatch(c.token(), etag), Map.class);
        assertThat(after.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(after.getHeaders().getETag()).isNotEqualTo(etag);
    }

    private static void assertV7(String id) {
        assertThat(id.charAt(14)).as(id).isEqualTo('7');
        assertThat("89ab").contains(String.valueOf(id.charAt(19)));
    }

    @Test
    void newRowsOfTheAppendHeavyTablesGetTimeOrderedUuidV7Ids() {
        Clinic c = clinic("seq-f", "+919500000006");
        UUID p = patient(c.token(), "+919500000106");
        // queue_entries (Java generator), sessions (login above), appointments (Java insert path is booking; DB default covers raw inserts)
        exchange("/v1/doctors/" + c.doctor().id() + "/working-hours", HttpMethod.POST, authedJsonBody(c.token(), Map.of(
                "dayOfWeek", java.time.LocalDate.now(java.time.ZoneOffset.UTC).getDayOfWeek().getValue() % 7,
                "startTime", "00:00:00", "endTime", "23:45:00", "slotMinutes", 15)), Map.class);
        ResponseEntity<Map> q = exchange("/v1/queue/check-in", HttpMethod.POST, authedJsonBody(c.token(), Map.of(
                "patientId", p.toString(), "doctorId", c.doctor().id().toString())), Map.class);
        assertV7((String) q.getBody().get("id"));
        assertThat(su().queryForList("SELECT id::text FROM sessions WHERE staff_id = ?", String.class, c.doctor().id())).isNotEmpty().allSatisfy(Ops006ChangeSeqApiTest::assertV7);
        UUID dbDefault = inTenantTx(c.tenant().id(), () -> jdbc.queryForObject(
                "INSERT INTO vitals (tenant_id, queue_entry_id, patient_id, recorded_by) VALUES (?,?,?,?) RETURNING id", UUID.class,
                c.tenant().id(), UUID.fromString((String) q.getBody().get("id")), p, c.doctor().id()));
        assertV7(dbDefault.toString());
        // the Java generator: version, variant, and strictly increasing within one JVM even inside one millisecond
        UUID prev = UuidV7.next();
        assertV7(prev.toString());
        for (int i = 0; i < 20_000; i++) {
            UUID next = UuidV7.next();
            assertThat(next.toString()).isGreaterThan(prev.toString());
            prev = next;
        }
    }

    @Test
    void noteAutosavesWriteNoAuditRowUntilSign() {
        Clinic c = clinic("seq-g", "+919500000007");
        exchange("/v1/doctors/" + c.doctor().id() + "/working-hours", HttpMethod.POST, authedJsonBody(c.token(), Map.of(
                "dayOfWeek", java.time.LocalDate.now(java.time.ZoneOffset.UTC).getDayOfWeek().getValue() % 7,
                "startTime", "00:00:00", "endTime", "23:45:00", "slotMinutes", 15)), Map.class);
        UUID p = patient(c.token(), "+919500000107");
        String entry = (String) exchange("/v1/queue/check-in", HttpMethod.POST, authedJsonBody(c.token(), Map.of(
                "patientId", p.toString(), "doctorId", c.doctor().id().toString())), Map.class).getBody().get("id");
        for (int i = 0; i < 5; i++) {
            assertThat(exchange("/v1/clinical/notes/" + entry, HttpMethod.PATCH, authedJsonBody(c.token(), Map.of("subjective", "draft " + i)), Map.class)
                    .getStatusCode()).isEqualTo(HttpStatus.OK);
        }
        String countSql = "SELECT count(*) FROM audit_log WHERE tenant_id = ? AND entity_type = 'clinical_note'";
        assertThat(su().queryForObject(countSql, Integer.class, c.tenant().id())).isZero();
        assertThat(exchange("/v1/clinical/notes/" + entry + "/sign", HttpMethod.POST, authed(c.token()), Map.class).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(su().queryForObject(countSql, Integer.class, c.tenant().id())).isEqualTo(1);
    }

    @Test
    void theNamedRepositoriesHaveNoSelectStar() throws Exception {
        for (String f : List.of("packages/PackageRepository", "clinical/AllergyRepository", "clinical/dental/DentalRepository",
                "clinical/NoteRepository", "clinical/PrescriptionRepository")) {
            String src = Files.readString(Path.of("src/main/java/com/nabd/hms/" + f + ".java"));
            assertThat(src).as(f).doesNotContainPattern("(?i)select\\s+\\*");
        }
    }
}
