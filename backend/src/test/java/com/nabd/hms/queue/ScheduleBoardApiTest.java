package com.nabd.hms.queue;

import com.nabd.hms.support.ApiTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Clinic Operations → Schedule, end to end through the API on an Indian clinic's clock. */
class ScheduleBoardApiTest extends ApiTestBase {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    @Test
    @SuppressWarnings("unchecked")
    void dayGridShowsBookingsLeaveAndFreeSlotsInTheClinicsZone() {
        SeededTenant tenant = seedTenant();
        jdbc.update("UPDATE tenants SET timezone = 'Asia/Kolkata' WHERE id = ?", tenant.id());
        UUID role = seedFullAccessRole(tenant.id());
        SeededStaff asha = seedStaff(tenant, role, "asha@a.com", "+919700000001", false);
        SeededStaff bala = seedStaff(tenant, role, "bala@a.com", "+919700000002", false);
        inTenantTx(tenant.id(), () -> {
            jdbc.update("UPDATE staff SET name = 'Dr. Asha' WHERE id = ?", asha.id());
            jdbc.update("UPDATE staff SET name = 'Dr. Bala' WHERE id = ?", bala.id());
        });
        String token = loginAndGetAccessToken(asha);
        LocalDate day = LocalDate.now(IST).plusDays(3);
        int dow = day.getDayOfWeek().getValue() % 7;
        for (String[] h : List.of(new String[]{"09:00:00", "11:00:00"}, new String[]{"16:00:00", "17:00:00"})) {
            exchange("/v1/doctors/" + asha.id() + "/working-hours", HttpMethod.POST, authedJsonBody(token, Map.of(
                    "dayOfWeek", dow, "startTime", h[0], "endTime", h[1], "slotMinutes", 30)), Map.class);
        }
        exchange("/v1/doctors/" + bala.id() + "/working-hours", HttpMethod.POST, authedJsonBody(token, Map.of(
                "dayOfWeek", dow, "startTime", "09:00:00", "endTime", "12:00:00", "slotMinutes", 30)), Map.class);
        exchange("/v1/doctors/" + bala.id() + "/leave", HttpMethod.POST, authedJsonBody(token, Map.of(
                "dateFrom", day.toString(), "dateTo", day.toString(), "reason", "Conference")), Map.class);
        String patient = (String) exchange("/v1/patients", HttpMethod.POST, authedJsonBody(token, Map.of(
                "name", "Kavya Reddy", "phone", "+919999961001", "dob", "1990-01-01", "gender", "female")), Map.class)
                .getBody().get("id");
        ResponseEntity<Map> booked = exchange("/v1/appointments", HttpMethod.POST, authedJsonBody(token, Map.of(
                "patientId", patient, "doctorId", asha.id().toString(), "isFollowUp", true,
                "startTime", day.atTime(LocalTime.of(10, 0)).atZone(IST).toInstant().toString())), Map.class);
        assertThat(booked.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        inTenantTx(tenant.id(), () -> jdbc.update(
                "INSERT INTO clinic_holidays (tenant_id, holiday_date, name) VALUES (?,?,'Onam')", tenant.id(), java.sql.Date.valueOf(day.plusDays(1))));

        Map<String, Object> body = exchange("/v1/schedule/day?date=" + day, HttpMethod.GET, authed(token), Map.class).getBody();
        assertThat(body.get("timezone")).isEqualTo("Asia/Kolkata");
        assertThat(body.get("holiday")).isNull();
        List<Map<String, Object>> doctors = (List<Map<String, Object>>) body.get("doctors");
        assertThat(doctors).extracting(d -> d.get("name")).containsExactly("Dr. Asha", "Dr. Bala");
        assertThat(doctors.get(1)).containsEntry("onLeave", true).containsEntry("leaveReason", "Conference");

        List<Map<String, Object>> sessions = (List<Map<String, Object>>) body.get("sessions");
        assertThat(sessions).extracting(s -> s.get("label") + " " + s.get("start") + "-" + s.get("end"))
                .containsExactly("Morning 09:00-12:00", "Evening 16:00-17:00");
        Map<String, Map<String, Object>> am = rows(sessions.get(0));
        Map<String, Object> tenOClock = cell(am, "10:00", 0);
        assertThat(tenOClock.get("kind")).isEqualTo("booked");
        Map<String, Object> booking = ((List<Map<String, Object>>) tenOClock.get("bookings")).get(0);
        assertThat(booking).containsEntry("patientName", "Kavya Reddy").containsEntry("visitType", "follow_up")
                .containsEntry("time", "10:00").containsEntry("status", "scheduled");
        assertThat(cell(am, "09:00", 0).get("kind")).isEqualTo("available");
        assertThat(cell(am, "09:00", 0).get("slotStart")).isEqualTo(day.atTime(9, 0).atZone(IST).toInstant().toString());
        assertThat(cell(am, "11:00", 0).get("kind")).isEqualTo("break");
        assertThat(cell(am, "09:00", 1).get("kind")).isEqualTo("leave");
        // Asha: 09:00, 09:30, 10:30 free in the morning (10:00 booked) + 16:00, 16:30 = 5
        assertThat((Map<String, Object>) body.get("counts")).containsEntry("appointments", 1).containsEntry("freeSlots", 5);

        // holiday next day: grid still drawn, but nothing is bookable
        Map<String, Object> holiday = exchange("/v1/schedule/day?date=" + day.plusDays(1), HttpMethod.GET, authed(token), Map.class).getBody();
        assertThat(holiday.get("holiday")).isEqualTo("Onam");

        // week from `day`: day 0 has the booking and free slots, day 1 is the holiday
        Map<String, Object> week = exchange("/v1/schedule/week?start=" + day, HttpMethod.GET, authed(token), Map.class).getBody();
        List<Map<String, Object>> days = (List<Map<String, Object>>) week.get("days");
        assertThat(days).hasSize(7);
        assertThat(days.get(0).get("sessions")).isEqualTo(List.of(
                Map.of("label", "Morning", "booked", 1, "free", 3), Map.of("label", "Evening", "booked", 0, "free", 2)));
        assertThat(days.get(1).get("holiday")).isEqualTo("Onam");
    }

    @Test
    @SuppressWarnings("unchecked")
    void todaysWalkInAppearsWithItsToken() {
        SeededTenant tenant = seedTenant();
        SeededStaff doc = seedStaff(tenant, seedFullAccessRole(tenant.id()), "wd@a.com", "+919700000011", false);
        String token = loginAndGetAccessToken(doc);
        String patient = (String) exchange("/v1/patients", HttpMethod.POST, authedJsonBody(token, Map.of(
                "name", "Arjun Nair", "phone", "+919999961011", "dob", "1990-01-01", "gender", "male")), Map.class)
                .getBody().get("id");
        ResponseEntity<Map> checkIn = exchange("/v1/queue/check-in", HttpMethod.POST, authedJsonBody(token, Map.of(
                "patientId", patient, "doctorId", doc.id().toString())), Map.class);
        assertThat(checkIn.getStatusCode().is2xxSuccessful()).isTrue();

        Map<String, Object> body = exchange("/v1/schedule/day", HttpMethod.GET, authed(token), Map.class).getBody();
        assertThat(body.get("today")).isEqualTo(true);
        List<Map<String, Object>> bookings = ((List<Map<String, Object>>) body.get("sessions")).stream()
                .flatMap(s -> ((List<Map<String, Object>>) s.get("rows")).stream())
                .flatMap(r -> ((List<Map<String, Object>>) r.get("cells")).stream())
                .flatMap(c -> ((List<Map<String, Object>>) c.get("bookings")).stream()).toList();
        assertThat(bookings).singleElement().satisfies(b -> assertThat(b)
                .containsEntry("visitType", "walk_in").containsEntry("patientName", "Arjun Nair")
                .containsEntry("token", checkIn.getBody().get("tokenNumber")));
        assertThat((Map<String, Object>) body.get("counts")).containsEntry("walkIns", 1);
    }

    @Test
    void scheduleNeedsQueueView() {
        SeededTenant tenant = seedTenant();
        UUID role = seedRole(tenant.id(), "Pharmacist", false, fullGrant("pharmacy"));
        String token = loginAndGetAccessToken(seedStaff(tenant, role, "ph@a.com", "+919700000021", false));
        assertThat(exchange("/v1/schedule/day", HttpMethod.GET, authed(token), Map.class).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(exchange("/v1/schedule/week", HttpMethod.GET, authed(token), Map.class).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Map<String, Object>> rows(Map<String, Object> session) {
        Map<String, Map<String, Object>> out = new java.util.LinkedHashMap<>();
        ((List<Map<String, Object>>) session.get("rows")).forEach(r -> out.put((String) r.get("time"), r));
        return out;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> cell(Map<String, Map<String, Object>> rows, String time, int doctor) {
        return ((List<Map<String, Object>>) rows.get(time).get("cells")).get(doctor);
    }
}
