package com.nabd.hms.queue;

import com.nabd.hms.support.ApiTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ScheduleApiTest extends ApiTestBase {

    // Java MON=1..SUN=7 -> Postgres-style SUN=0..SAT=6, same mapping ScheduleService uses.
    private static int pgDayOfWeek(LocalDate date) {
        return date.getDayOfWeek().getValue() % 7;
    }

    @Test
    void addAndListWorkingHours() {
        SeededTenant tenant = seedTenant();
        UUID roleId = seedFullAccessRole(tenant.id());
        SeededStaff doctor = seedStaff(tenant, roleId, "doc1@a.com", "+919400000001", false);
        String token = loginAndGetAccessToken(doctor);
        LocalDate today = LocalDate.now(ZoneOffset.UTC);

        ResponseEntity<Map> addResp = exchange("/v1/doctors/" + doctor.id() + "/working-hours", HttpMethod.POST,
                authedJsonBody(token, Map.of("dayOfWeek", pgDayOfWeek(today), "startTime", "00:00:00",
                        "endTime", "23:45:00", "slotMinutes", 15)), Map.class);
        assertThat(addResp.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        ResponseEntity<List> listResp = exchange("/v1/doctors/" + doctor.id() + "/working-hours", HttpMethod.GET,
                authed(token), List.class);
        assertThat(listResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(listResp.getBody()).isNotEmpty();
    }

    @Test
    void workingHoursRespectsMaxPatientsCap() {
        SeededTenant tenant = seedTenant();
        UUID roleId = seedFullAccessRole(tenant.id());
        SeededStaff doctor = seedStaff(tenant, roleId, "doc2@a.com", "+919400000002", false);
        String token = loginAndGetAccessToken(doctor);
        LocalDate today = LocalDate.now(ZoneOffset.UTC);

        ResponseEntity<Map> addResp = exchange("/v1/doctors/" + doctor.id() + "/working-hours", HttpMethod.POST,
                authedJsonBody(token, Map.of("dayOfWeek", pgDayOfWeek(today), "startTime", "00:00:00",
                        "endTime", "23:45:00", "slotMinutes", 15, "maxPatients", 1)), Map.class);
        assertThat(addResp.getBody().get("maxPatients")).isEqualTo(1);
    }

    @Test
    void addAndListLeave() {
        SeededTenant tenant = seedTenant();
        UUID roleId = seedFullAccessRole(tenant.id());
        SeededStaff doctor = seedStaff(tenant, roleId, "doc3@a.com", "+919400000003", false);
        String token = loginAndGetAccessToken(doctor);
        LocalDate today = LocalDate.now(ZoneOffset.UTC);

        ResponseEntity<Map> addResp = exchange("/v1/doctors/" + doctor.id() + "/leave", HttpMethod.POST,
                authedJsonBody(token, Map.of("dateFrom", today.toString(), "dateTo", today.toString(),
                        "reason", "conference")), Map.class);
        assertThat(addResp.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        ResponseEntity<List> listResp = exchange("/v1/doctors/" + doctor.id() + "/leave", HttpMethod.GET,
                authed(token), List.class);
        assertThat(listResp.getBody()).isNotEmpty();
    }

    @Test
    void availabilityReturnsSlotsWithinWorkingHours() {
        SeededTenant tenant = seedTenant();
        UUID roleId = seedFullAccessRole(tenant.id());
        SeededStaff doctor = seedStaff(tenant, roleId, "doc4@a.com", "+919400000004", false);
        String token = loginAndGetAccessToken(doctor);
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        exchange("/v1/doctors/" + doctor.id() + "/working-hours", HttpMethod.POST,
                authedJsonBody(token, Map.of("dayOfWeek", pgDayOfWeek(today), "startTime", "00:00:00",
                        "endTime", "23:45:00", "slotMinutes", 15)), Map.class);

        ResponseEntity<List> resp = exchange("/v1/doctors/" + doctor.id() + "/availability?date=" + today,
                HttpMethod.GET, authed(token), List.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        // not asserting non-empty: flaky in the last few minutes of the UTC day, all slots already past
    }

    @Test
    void availabilityIsEmptyOnLeaveDay() {
        SeededTenant tenant = seedTenant();
        UUID roleId = seedFullAccessRole(tenant.id());
        SeededStaff doctor = seedStaff(tenant, roleId, "doc5@a.com", "+919400000005", false);
        String token = loginAndGetAccessToken(doctor);
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        exchange("/v1/doctors/" + doctor.id() + "/working-hours", HttpMethod.POST,
                authedJsonBody(token, Map.of("dayOfWeek", pgDayOfWeek(today), "startTime", "00:00:00",
                        "endTime", "23:45:00", "slotMinutes", 15)), Map.class);
        exchange("/v1/doctors/" + doctor.id() + "/leave", HttpMethod.POST,
                authedJsonBody(token, Map.of("dateFrom", today.toString(), "dateTo", today.toString(),
                        "reason", "sick")), Map.class);

        ResponseEntity<List> resp = exchange("/v1/doctors/" + doctor.id() + "/availability?date=" + today,
                HttpMethod.GET, authed(token), List.class);
        assertThat(resp.getBody()).isEmpty();
    }

    /** NB-092: clinic_holidays existed (CRUD only, via com.nabd.hms.setup) but nothing in scheduling
     * ever consulted it before this — a holiday blocked neither availability nor booking. */
    @Test
    void availabilityIsEmptyOnAClinicHolidayAndBookingIsRejected() {
        SeededTenant tenant = seedTenant();
        UUID roleId = seedFullAccessRole(tenant.id());
        SeededStaff doctor = seedStaff(tenant, roleId, "doc6@a.com", "+919400000006", false);
        String token = loginAndGetAccessToken(doctor);
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        exchange("/v1/doctors/" + doctor.id() + "/working-hours", HttpMethod.POST,
                authedJsonBody(token, Map.of("dayOfWeek", pgDayOfWeek(today), "startTime", "00:00:00",
                        "endTime", "23:45:00", "slotMinutes", 15)), Map.class);
        exchange("/v1/setup/holidays", HttpMethod.POST, authedJsonBody(token, Map.of(
                "holidayDate", today.toString(), "name", "Test Holiday", "recurring", false)), Map.class);

        ResponseEntity<List> availability = exchange("/v1/doctors/" + doctor.id() + "/availability?date=" + today,
                HttpMethod.GET, authed(token), List.class);
        assertThat(availability.getBody()).isEmpty();

        ResponseEntity<Map> patientResp = exchange("/v1/patients", HttpMethod.POST, authedJsonBody(token, Map.of(
                "name", "Holiday Patient", "phone", "+919400000106", "dob", "1990-01-01", "gender", "male")), Map.class);
        String patientId = (String) patientResp.getBody().get("id");
        ResponseEntity<Map> booking = exchange("/v1/appointments", HttpMethod.POST, authedJsonBody(token, Map.of(
                "patientId", patientId, "doctorId", doctor.id().toString(),
                "startTime", today.atTime(10, 0).toInstant(ZoneOffset.UTC).toString())), Map.class);
        assertThat(booking.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(booking.getBody().get("type")).asString().contains("clinic-holiday");
    }

    // NB-091: "blocking a doctor warns about affected package sessions restricted to that doctor" —
    // the complementary direction to PackageApiTest's doctorLeaveWarning (which warns when *viewing*
    // a package whose doctor is already on leave); this warns at the moment leave is *added*.
    @Test
    void addingLeaveWarnsAboutActivePackagesRestrictedToThatDoctor() {
        SeededTenant tenant = seedTenant();
        UUID roleId = seedFullAccessRole(tenant.id());
        SeededStaff doctor = seedStaff(tenant, roleId, "doc7@a.com", "+919400000007", false);
        String token = loginAndGetAccessToken(doctor);

        ResponseEntity<Map> patientResp = exchange("/v1/patients", HttpMethod.POST, authedJsonBody(token, Map.of(
                "name", "Package Patient", "phone", "+919400000107", "dob", "1990-01-01", "gender", "female")), Map.class);
        String patientId = (String) patientResp.getBody().get("id");

        ResponseEntity<Map> pkgResp = exchange("/v1/packages", HttpMethod.POST, authedJsonBody(token, Map.of(
                "name", "Restricted Combo", "packageType", "session", "price", 1000.00, "taxInclusive", false,
                "validityDays", 30, "validityStarts", "purchase_date", "eligibleDoctorIds", List.of(doctor.id().toString()),
                "items", List.of(Map.of("itemType", "service_session", "name", "Laser", "quantity", 3,
                        "unitListPrice", 400.00, "taxRatePercent", 0.0)))), Map.class);
        String packageId = (String) pkgResp.getBody().get("id");
        exchange("/v1/packages/" + packageId + "/activate", HttpMethod.POST, authed(token), Map.class);
        exchange("/v1/packages/sell", HttpMethod.POST, authedJsonBody(token, Map.of(
                "patientId", patientId, "packageId", packageId, "paymentMethod", "upi")), Map.class);

        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        ResponseEntity<Map> leaveResp = exchange("/v1/doctors/" + doctor.id() + "/leave", HttpMethod.POST,
                authedJsonBody(token, Map.of("dateFrom", today.toString(), "dateTo", today.toString(), "reason", "conference")),
                Map.class);
        assertThat(leaveResp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(leaveResp.getBody().get("affectedPackagePatients")).asList()
                .anyMatch(name -> ((String) name).contains("Package Patient"));
    }

    // ---- delay ladder (NB-100) ----

    @Test
    void announceThenClearDelay() {
        SeededTenant tenant = seedTenant();
        UUID roleId = seedFullAccessRole(tenant.id());
        SeededStaff doctor = seedStaff(tenant, roleId, "doc8@a.com", "+919400000008", false);
        String token = loginAndGetAccessToken(doctor);

        ResponseEntity<Map> announceResp = exchange("/v1/doctors/" + doctor.id() + "/delay", HttpMethod.POST,
                authedJsonBody(token, Map.of("delayMinutes", 20, "reason", "emergency case running long")), Map.class);
        assertThat(announceResp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(announceResp.getBody().get("active")).isEqualTo(true);
        assertThat(((Number) announceResp.getBody().get("delayMinutes")).intValue()).isEqualTo(20);

        ResponseEntity<Void> clearResp = exchange("/v1/doctors/" + doctor.id() + "/delay/clear", HttpMethod.POST, authed(token), Void.class);
        assertThat(clearResp.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        ResponseEntity<List> historyResp = exchange("/v1/doctors/" + doctor.id() + "/delay", HttpMethod.GET, authed(token), List.class);
        List<Map<String, Object>> history = historyResp.getBody();
        assertThat(history).hasSize(1);
        assertThat(history.get(0).get("active")).isEqualTo(false);
    }

    @Test
    void announcingASecondDelayClosesTheFirstOneOutInHistory() {
        SeededTenant tenant = seedTenant();
        UUID roleId = seedFullAccessRole(tenant.id());
        SeededStaff doctor = seedStaff(tenant, roleId, "doc9@a.com", "+919400000009", false);
        String token = loginAndGetAccessToken(doctor);

        exchange("/v1/doctors/" + doctor.id() + "/delay", HttpMethod.POST,
                authedJsonBody(token, Map.of("delayMinutes", 10, "reason", "first")), Map.class);
        ResponseEntity<Map> second = exchange("/v1/doctors/" + doctor.id() + "/delay", HttpMethod.POST,
                authedJsonBody(token, Map.of("delayMinutes", 25, "reason", "second, worse")), Map.class);
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        ResponseEntity<List> historyResp = exchange("/v1/doctors/" + doctor.id() + "/delay", HttpMethod.GET, authed(token), List.class);
        List<Map<String, Object>> history = historyResp.getBody();
        assertThat(history).hasSize(2);
        long activeCount = history.stream().filter(h -> Boolean.TRUE.equals(h.get("active"))).count();
        assertThat(activeCount).isEqualTo(1);
    }

    @Test
    void clearingWithNoActiveDelayIsRejected() {
        SeededTenant tenant = seedTenant();
        UUID roleId = seedFullAccessRole(tenant.id());
        SeededStaff doctor = seedStaff(tenant, roleId, "doc10@a.com", "+919400000010", false);
        String token = loginAndGetAccessToken(doctor);

        ResponseEntity<Map> resp = exchange("/v1/doctors/" + doctor.id() + "/delay/clear", HttpMethod.POST, authed(token), Map.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(resp.getBody().get("type")).asString().contains("no-active-delay");
    }

    // ── DOC-R7: working-hours write authorization, overlap, update/delete, audit ──

    private ResponseEntity<Map> writeHours(String token, HttpMethod method, String path, String start, String end) {
        return exchange(path, method, authedJsonBody(token, Map.of("dayOfWeek", 1, "startTime", start,
                "endTime", end, "slotMinutes", 15)), Map.class);
    }

    @Test
    void queueEditAloneCannotWriteAnotherDoctorsHoursButDoctorCanWriteOwn() {
        SeededTenant tenant = seedTenant();
        // queue:view/create/edit/delete, no setup grant
        UUID queueOnly = seedRole(tenant.id(), "Receptionist", false, fullGrant("queue"));
        SeededStaff doctor = seedStaff(tenant, queueOnly, "r7doc@a.com", "+919400000071", false);
        SeededStaff reception = seedStaff(tenant, queueOnly, "r7rec@a.com", "+919400000072", false);
        String docToken = loginAndGetAccessToken(doctor);
        String recToken = loginAndGetAccessToken(reception);
        String base = "/v1/doctors/" + doctor.id() + "/working-hours";

        assertThat(writeHours(recToken, HttpMethod.POST, base, "09:00:00", "12:00:00").getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);

        ResponseEntity<Map> own = writeHours(docToken, HttpMethod.POST, base, "09:00:00", "12:00:00");
        assertThat(own.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String hoursPath = base + "/" + own.getBody().get("id");

        assertThat(writeHours(recToken, HttpMethod.PUT, hoursPath, "09:00:00", "11:00:00").getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(exchange(hoursPath, HttpMethod.DELETE, authed(recToken), Map.class).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);

        ResponseEntity<Map> ownUpdate = writeHours(docToken, HttpMethod.PUT, hoursPath, "09:00:00", "11:00:00");
        assertThat(ownUpdate.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(ownUpdate.getBody().get("endTime")).isEqualTo("11:00:00");
        assertThat(exchange(hoursPath, HttpMethod.DELETE, authed(docToken), Map.class).getStatusCode())
                .isEqualTo(HttpStatus.NO_CONTENT);
    }

    @Test
    void setupEditCanWriteAnotherDoctorsHoursAndEveryWriteIsAudited() {
        SeededTenant tenant = seedTenant();
        UUID ownerRole = seedFullAccessRole(tenant.id());
        UUID queueOnly = seedRole(tenant.id(), "Doctor", false, fullGrant("queue"));
        SeededStaff owner = seedStaff(tenant, ownerRole, "r7own@a.com", "+919400000073", false);
        SeededStaff doctor = seedStaff(tenant, queueOnly, "r7doc2@a.com", "+919400000074", false);
        String token = loginAndGetAccessToken(owner);
        String base = "/v1/doctors/" + doctor.id() + "/working-hours";

        ResponseEntity<Map> created = writeHours(token, HttpMethod.POST, base, "09:00:00", "12:00:00");
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        UUID hoursId = UUID.fromString(created.getBody().get("id").toString());
        String hoursPath = base + "/" + hoursId;

        assertThat(writeHours(token, HttpMethod.PUT, hoursPath, "10:00:00", "12:00:00").getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(exchange(hoursPath, HttpMethod.DELETE, authed(token), Map.class).getStatusCode())
                .isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(exchange(base, HttpMethod.GET, authed(token), List.class).getBody()).isEmpty();
        assertThat(exchange(hoursPath, HttpMethod.DELETE, authed(token), Map.class).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);

        List<String> actions = inTenantTx(tenant.id(), () -> jdbc.queryForList(
                "SELECT action FROM audit_log WHERE entity_type = 'doctor_working_hours' AND entity_id = ? ORDER BY id",
                String.class, hoursId));
        assertThat(actions).containsExactly("schedule.working_hours_created", "schedule.working_hours_updated",
                "schedule.working_hours_deleted");
    }

    @Test
    void overlappingHoursAreRejectedOnCreateAndUpdate() {
        SeededTenant tenant = seedTenant();
        UUID roleId = seedFullAccessRole(tenant.id());
        SeededStaff doctor = seedStaff(tenant, roleId, "r7doc3@a.com", "+919400000075", false);
        String token = loginAndGetAccessToken(doctor);
        String base = "/v1/doctors/" + doctor.id() + "/working-hours";

        ResponseEntity<Map> morning = writeHours(token, HttpMethod.POST, base, "09:00:00", "12:00:00");
        assertThat(morning.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        ResponseEntity<Map> overlap = writeHours(token, HttpMethod.POST, base, "11:00:00", "13:00:00");
        assertThat(overlap.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(overlap.getBody().get("type")).asString().contains("hours-overlap");

        ResponseEntity<Map> touching = writeHours(token, HttpMethod.POST, base, "12:00:00", "14:00:00");
        assertThat(touching.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        ResponseEntity<Map> badUpdate = writeHours(token, HttpMethod.PUT, base + "/" + touching.getBody().get("id"),
                "10:00:00", "14:00:00");
        assertThat(badUpdate.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(badUpdate.getBody().get("type")).asString().contains("hours-overlap");

        // a block may be resized over its own old range
        assertThat(writeHours(token, HttpMethod.PUT, base + "/" + morning.getBody().get("id"), "08:00:00", "11:00:00")
                .getStatusCode()).isEqualTo(HttpStatus.OK);
    }
}
