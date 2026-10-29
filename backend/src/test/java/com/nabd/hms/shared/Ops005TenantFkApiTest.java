package com.nabd.hms.shared;

import com.nabd.hms.common.GlobalExceptionHandler;
import com.nabd.hms.support.ApiTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** OPS-005: composite (tenant_id, x_id) foreign keys refuse another clinic's patient/staff; API answers 404, never 500. */
class Ops005TenantFkApiTest extends ApiTestBase {

    private static final int EXPECTED_KEYS = 23; // 13 patient + 7 doctor + 2 role_delegations staff + 1 queue department

    private String patient(String token, String phone) {
        ResponseEntity<Map> r = exchange("/v1/patients", HttpMethod.POST, authedJsonBody(token, Map.of(
                "name", "Fk Patient", "phone", phone, "dob", "1980-01-01", "gender", "female")), Map.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return (String) r.getBody().get("id");
    }

    private record Clinic(SeededTenant tenant, SeededStaff doctor, String token) {
    }

    private Clinic clinic(String tag, String phone) {
        SeededTenant t = seedTenant();
        SeededStaff d = seedStaff(t, seedFullAccessRole(t.id()), tag + "@fk.test", phone, false);
        return new Clinic(t, d, loginAndGetAccessToken(d));
    }

    @Test
    void everyCompositeKeyExistsAndIsValidated() {
        List<Map<String, Object>> keys = new org.springframework.jdbc.core.JdbcTemplate(superuserDataSource()).queryForList(
                "SELECT conname, convalidated FROM pg_constraint WHERE contype = 'f' AND conname ~ '^fk_.*_tenant_(patient|doctor|staff|granted_by|department)$'");
        assertThat(keys).hasSize(EXPECTED_KEYS);
        assertThat(keys).allMatch(k -> Boolean.TRUE.equals(k.get("convalidated")));
    }

    @Test
    void anotherClinicsPatientCannotBeBookedOrCheckedInOrWaitlisted() {
        Clinic a = clinic("fk-a", "+919600000001");
        Clinic b = clinic("fk-b", "+919600000002");
        String foreignPatient = patient(b.token(), "+919600000012");
        Instant slot = Instant.now().plusSeconds(86400 * 3).truncatedTo(java.time.temporal.ChronoUnit.HOURS);

        for (Object[] c : new Object[][]{
                {"/v1/appointments", Map.of("doctorId", a.doctor().id().toString(), "patientId", foreignPatient, "startTime", slot.toString())},
                {"/v1/queue/check-in", Map.of("doctorId", a.doctor().id().toString(), "patientId", foreignPatient)},
                {"/v1/waitlist", Map.of("doctorId", a.doctor().id().toString(), "patientId", foreignPatient)}}) {
            ResponseEntity<Map> r = exchange((String) c[0], HttpMethod.POST, authedJsonBody(a.token(), c[1]), Map.class);
            assertThat(r.getStatusCode()).as((String) c[0]).isEqualTo(HttpStatus.NOT_FOUND);
            assertThat((String) r.getBody().get("type")).endsWith("patient-not-found");
        }
    }

    @Test
    void theDatabaseItselfRefusesACrossTenantReference() {
        Clinic a = clinic("fk-c", "+919600000003");
        Clinic b = clinic("fk-d", "+919600000004");
        UUID foreignPatient = UUID.fromString(patient(b.token(), "+919600000014"));
        UUID ownPatient = UUID.fromString(patient(a.token(), "+919600000013"));
        String sql = "INSERT INTO chronic_conditions (tenant_id, patient_id, condition, recorded_by) VALUES (?,?,?,?)";

        assertThat(inTenantTx(a.tenant().id(), () -> jdbc.update(sql, a.tenant().id(), ownPatient, "asthma", a.doctor().id()))).isEqualTo(1);
        assertThatThrownBy(() -> inTenantTx(a.tenant().id(), () -> jdbc.update(sql, a.tenant().id(), foreignPatient, "asthma", a.doctor().id())))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("fk_chronic_conditions_tenant_patient");
        // a staff reference works the same way: tenant A's delegation cannot name tenant B's doctor
        assertThatThrownBy(() -> inTenantTx(a.tenant().id(), () -> jdbc.update(
                "INSERT INTO role_delegations (tenant_id, staff_id, delegated_role_id, granted_by, reason, expires_at) VALUES (?,?,?,?,'cover', now() + interval '1 day')",
                a.tenant().id(), b.doctor().id(), seedFullAccessRole(a.tenant().id()), a.doctor().id())))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void aTenantForeignKeyViolationMapsToProblem404NotTo500() {
        GlobalExceptionHandler h = new GlobalExceptionHandler();
        for (String[] c : new String[][]{{"fk_prescriptions_tenant_patient", "patient-not-found"}, {"fk_invoices_tenant_doctor", "doctor-not-found"},
                {"fk_role_delegations_tenant_granted_by", "staff-not-found"}, {"fk_queue_entries_tenant_department", "department-not-found"}}) {
            ProblemDetail p = h.handleIntegrity(new DataIntegrityViolationException("insert failed",
                    new SQLException("ERROR: insert or update on table violates foreign key constraint \"" + c[0] + "\"", "23503")));
            assertThat(p.getStatus()).isEqualTo(404);
            assertThat(p.getType().toString()).endsWith(c[1]);
        }
        // any other integrity failure stays the unmapped 500
        assertThat(h.handleIntegrity(new DataIntegrityViolationException("x", new SQLException("duplicate key", "23505"))).getStatus()).isEqualTo(500);
    }

    @Test
    void thePrecheckQuarantinesCrossTenantRowsAndFailsUntilTheyAreGone() throws Exception {
        Clinic a = clinic("fk-e", "+919600000005");
        Clinic b = clinic("fk-f", "+919600000006");
        UUID foreignPatient = UUID.fromString(patient(b.token(), "+919600000016"));
        String script = Files.readString(Path.of("../scripts/db/ops005-cross-tenant-precheck.sql"));
        String[] parts = script.split("-- A separate statement[^\n]*\n");
        assertThat(parts).hasSize(2);
        try (Connection c = superuserConnection(); Statement st = c.createStatement()) {
            c.setAutoCommit(false);
            try {
                st.execute("ALTER TABLE chronic_conditions DROP CONSTRAINT fk_chronic_conditions_tenant_patient");
                st.execute("INSERT INTO chronic_conditions (tenant_id, patient_id, condition, recorded_by) VALUES ('" + a.tenant().id() + "','"
                        + foreignPatient + "','asthma','" + a.doctor().id() + "')");
                st.execute(parts[0]);
                var rs = st.executeQuery("SELECT child_tenant_id, parent_tenant_id FROM cross_tenant_ref_quarantine WHERE table_name = 'chronic_conditions'");
                assertThat(rs.next()).isTrue();
                assertThat(rs.getObject(1, UUID.class)).isEqualTo(a.tenant().id());
                assertThat(rs.getObject(2, UUID.class)).isEqualTo(b.tenant().id());
                assertThatThrownBy(() -> st.execute(parts[1])).hasMessageContaining("cross-tenant reference");
            } finally {
                c.rollback();
            }
        }
        try (Connection c = superuserConnection(); Statement st = c.createStatement()) {
            st.execute(parts[0]);
            st.execute(parts[1]); // clean data: passes
        }
    }
}
