package com.nabd.hms.billing;

import com.nabd.hms.common.ModuleGrant;
import com.nabd.hms.support.ApiTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** ADM-044: GET /v1/reports/desk-summary — visits, collections by mode, unbilled visits and no-shows of the caller's clinic only. */
class Adm044DeskSummaryApiTest extends ApiTestBase {

    private record Clinic(SeededTenant tenant, SeededStaff owner, String token) {
    }

    private Clinic clinic(String email, String phone) {
        SeededTenant t = seedTenant();
        SeededStaff owner = seedStaff(t, seedFullAccessRole(t.id()), email, phone, false);
        return new Clinic(t, owner, loginAndGetAccessToken(owner));
    }

    private int born;

    /** A distinct date of birth each, so the duplicate-patient check never asks for confirmation. */
    private String patient(Clinic c, String name, String phone) {
        ResponseEntity<Map> r = exchange("/v1/patients", HttpMethod.POST, authedJsonBody(c.token(), Map.of(
                "name", name, "phone", phone, "dob", LocalDate.of(1970, 1, 1).plusDays(397L * born++).toString(), "gender", "female")), Map.class);
        assertThat(r.getStatusCode()).as("%s", r.getBody()).isEqualTo(HttpStatus.CREATED);
        return (String) r.getBody().get("id");
    }

    private String visitAtCheckout(Clinic c, String patientId) {
        ResponseEntity<Map> r = exchange("/v1/queue/check-in", HttpMethod.POST, authedJsonBody(c.token(), Map.of(
                "patientId", patientId, "doctorId", c.owner().id().toString())), Map.class);
        assertThat(r.getStatusCode().is2xxSuccessful()).as("%s", r.getBody()).isTrue();
        String q = (String) r.getBody().get("id");
        for (String s : List.of("waiting", "vitals_pending", "vitals_done", "in_consult", "checkout_pending")) {
            exchange("/v1/queue/" + q + "/status", HttpMethod.PATCH, authedJsonBody(c.token(), Map.of("status", s)), Map.class);
        }
        return q;
    }

    private void billAndPay(Clinic c, String queueEntryId, String method, int amount) {
        ResponseEntity<Map> inv = exchange("/v1/billing/checkout/" + queueEntryId, HttpMethod.POST, authedJsonBody(c.token(), Map.of(
                "lineItems", List.of(Map.of("override", Map.of("reasonCode", "other"), "chargeCode", "CONS", "chargeName", "Consultation",
                        "category", "Consultation", "quantity", 1, "unitPrice", amount, "taxRatePercent", 0)))), Map.class);
        assertThat(inv.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(exchange("/v1/billing/invoices/" + inv.getBody().get("id") + "/payments", HttpMethod.POST,
                authedJsonBody(c.token(), Map.of("method", method, "amount", amount)), Map.class).getStatusCode().is2xxSuccessful()).isTrue();
    }

    private int slot;

    /** Written as superuser in the first seconds of today's clinic day (seeded tenants: UTC, day start 00:00), so its time
     * has passed; one-second slots so one doctor's bookings never overlap. */
    private UUID appointment(Clinic c, String patientId, String status, UUID lateArrivalEntry) {
        UUID id = UUID.randomUUID();
        Instant start = LocalDate.now(ZoneOffset.UTC).atStartOfDay(ZoneOffset.UTC).toInstant().plusSeconds(slot++);
        try (Connection su = superuserConnection(); PreparedStatement ps = su.prepareStatement(
                "INSERT INTO appointments (id, tenant_id, patient_id, doctor_id, start_time, end_time, status, late_arrival_queue_entry_id) VALUES (?,?,?,?,?,?,?,?)")) {
            ps.setObject(1, id);
            ps.setObject(2, c.tenant().id());
            ps.setObject(3, UUID.fromString(patientId));
            ps.setObject(4, c.owner().id());
            ps.setTimestamp(5, Timestamp.from(start));
            ps.setTimestamp(6, Timestamp.from(start.plusSeconds(1)));
            ps.setString(7, status);
            ps.setObject(8, lateArrivalEntry);
            ps.executeUpdate();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        return id;
    }

    private void linkToQueueEntry(UUID appointmentId, String queueEntryId) {
        try (Connection su = superuserConnection(); PreparedStatement ps = su.prepareStatement("UPDATE queue_entries SET appointment_id = ? WHERE id = ?")) {
            ps.setObject(1, appointmentId);
            ps.setObject(2, UUID.fromString(queueEntryId));
            ps.executeUpdate();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void branchAReceptionistSeesOnlyBranchAFiguresAndBranchBDataIsAbsent() {
        Clinic a = clinic("adm044a@a.com", "+919600044001");
        Clinic b = clinic("adm044b@b.com", "+919600044002");

        // Branch A: one visit billed and paid in cash, one sent to checkout without a bill, one no-show,
        // plus bookings that are not no-shows (checked in, cancelled, late arrival)
        String paidA = visitAtCheckout(a, patient(a, "Lakshmi Varma", "+919999944001"));
        billAndPay(a, paidA, "cash", 700);
        String unbilledA = visitAtCheckout(a, patient(a, "Devika Menon", "+919999944002"));
        UUID noShowA = appointment(a, patient(a, "Farah Siddiqui", "+919999944003"), "scheduled", null);
        linkToQueueEntry(appointment(a, patient(a, "Ravi Kumar", "+919999944004"), "scheduled", null), paidA);
        appointment(a, patient(a, "Cancelled Person", "+919999944005"), "cancelled", null);
        appointment(a, patient(a, "Late Arrival", "+919999944006"), "no_show", UUID.fromString(unbilledA));

        // Branch B: the same kinds of data, which must never reach A
        String paidB = visitAtCheckout(b, patient(b, "Branchb Paid", "+919999944101"));
        billAndPay(b, paidB, "card", 4321);
        String unbilledB = visitAtCheckout(b, patient(b, "Branchb Unbilled", "+919999944102"));
        UUID noShowB = appointment(b, patient(b, "Branchb Noshow", "+919999944103"), "no_show", null);

        UUID receptionRole = seedRole(a.tenant().id(), "Receptionist", false, new ModuleGrant("queue", true, false, false, false, false, false, false));
        String reception = loginAndGetAccessToken(seedStaff(a.tenant(), receptionRole, "adm044r@a.com", "+919600044003", false));

        ResponseEntity<String> raw = exchange("/v1/reports/desk-summary", HttpMethod.GET, authed(reception), String.class);
        assertThat(raw.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(raw.getBody()).doesNotContain("Branchb", unbilledB, paidB, noShowB.toString(), b.tenant().id().toString(), "4321");

        Map<String, Object> s = exchange("/v1/reports/desk-summary", HttpMethod.GET, authed(reception), Map.class).getBody();
        assertThat(s).containsEntry("today", true).containsEntry("currency", "INR");
        assertThat((Map<String, Object>) s.get("visits")).containsEntry("total", 2);
        List<Map<String, Object>> modes = (List<Map<String, Object>>) s.get("collectionsByMode");
        assertThat(modes).extracting(m -> m.get("method")).containsExactly("cash", "card", "upi", "other");
        assertThat(((Number) modes.get(0).get("amount")).doubleValue()).isEqualTo(700.0);
        assertThat(modes.get(0)).containsEntry("transactions", 1);
        assertThat(((Number) modes.get(1).get("amount")).doubleValue()).isEqualTo(0.0);
        assertThat((List<Map<String, Object>>) s.get("unbilled")).singleElement().satisfies(u -> assertThat(u)
                .containsEntry("queueEntryId", unbilledA).containsEntry("patientName", "Devika Menon").containsEntry("doctorName", "Test Staff"));
        assertThat((List<Map<String, Object>>) s.get("noShows")).singleElement().satisfies(n -> assertThat(n)
                .containsEntry("appointmentId", noShowA.toString()).containsEntry("patientName", "Farah Siddiqui").containsEntry("doctorName", "Test Staff"));

        // the same figures as the day close it precedes
        Map<String, Object> close = exchange("/v1/billing/day-close", HttpMethod.GET, authed(a.token()), Map.class).getBody();
        assertThat(s.get("collectionsByMode")).isEqualTo(close.get("methods"));
        assertThat(s.get("unbilled")).isEqualTo(close.get("unbilled"));

        // branch B sees its own, not A's
        Map<String, Object> sb = exchange("/v1/reports/desk-summary", HttpMethod.GET, authed(b.token()), Map.class).getBody();
        assertThat((List<Map<String, Object>>) sb.get("unbilled")).singleElement().satisfies(u -> assertThat(u).containsEntry("queueEntryId", unbilledB));
        assertThat((List<Map<String, Object>>) sb.get("noShows")).singleElement().satisfies(n -> assertThat(n).containsEntry("appointmentId", noShowB.toString()));

        // another day is that day's figures: nothing yesterday
        Map<String, Object> y = exchange("/v1/reports/desk-summary?date=" + LocalDate.parse((String) s.get("date")).minusDays(1),
                HttpMethod.GET, authed(reception), Map.class).getBody();
        assertThat(y).containsEntry("today", false);
        assertThat((Map<String, Object>) y.get("visits")).containsEntry("total", 0);
        assertThat((List<?>) y.get("unbilled")).isEmpty();
        assertThat((List<?>) y.get("noShows")).isEmpty();
    }

    @Test
    void needsQueueView() {
        Clinic c = clinic("adm044c@c.com", "+919600044011");
        UUID billingOnly = seedRole(c.tenant().id(), "Cashier", false, new ModuleGrant("billing", true, true, true, false, true, false, false));
        String token = loginAndGetAccessToken(seedStaff(c.tenant(), billingOnly, "adm044d@c.com", "+919600044012", false));
        assertThat(exchange("/v1/reports/desk-summary", HttpMethod.GET, authed(token), Map.class).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }
}
