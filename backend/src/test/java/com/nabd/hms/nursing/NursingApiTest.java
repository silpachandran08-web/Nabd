package com.nabd.hms.nursing;

import com.nabd.hms.support.ApiTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** E13 Nursing, Orders & Triage. NB-144/149 (clinical triage inbox, task handoff) aren't built —
 * both depend on NB-197's shared WhatsApp inbox, which doesn't exist. NB-147 (package session
 * administration by nurse) needs no new test here: it's PackageApiTest's redeem endpoint, already
 * covered there, exposed to a nurse purely via the existing "packages" RBAC grant. */
class NursingApiTest extends ApiTestBase {

    private String registerPatient(String token, String name, String phone) {
        ResponseEntity<Map> resp = exchange("/v1/patients", HttpMethod.POST, authedJsonBody(token, Map.of(
                "name", name, "phone", phone, "dob", "1990-01-01", "gender", "male")), Map.class);
        return (String) resp.getBody().get("id");
    }

    private String checkIn(String token, String patientId, UUID doctorId) {
        ResponseEntity<Map> resp = exchange("/v1/queue/check-in", HttpMethod.POST, authedJsonBody(token, Map.of(
                "patientId", patientId, "doctorId", doctorId.toString())), Map.class);
        return (String) resp.getBody().get("id");
    }

    private void moveTo(String token, String queueEntryId, String... statuses) {
        for (String s : statuses) {
            exchange("/v1/queue/" + queueEntryId + "/status", HttpMethod.PATCH, authedJsonBody(token, Map.of("status", s)), Map.class);
        }
    }

    private String orderAdministration(String token, String queueEntryId) {
        ResponseEntity<Map> resp = exchange("/v1/nursing/administration-orders", HttpMethod.POST, authedJsonBody(token, Map.of(
                "queueEntryId", queueEntryId, "drugName", "Amoxicillin", "dose", "500mg", "route", "IM", "site", "left deltoid")), Map.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return (String) resp.getBody().get("id");
    }

    // ---- NB-143: priority patients & urgent triage ----

    @Test
    void flaggingPriorityRecordsFlaggedByAndListsUnderThePriorityFilter() {
        SeededTenant tenant = seedTenant();
        UUID roleId = seedFullAccessRole(tenant.id());
        SeededStaff staff = seedStaff(tenant, roleId, "n1@a.com", "+919800020001", false);
        String token = loginAndGetAccessToken(staff);
        String patientId = registerPatient(token, "P1", "+919999960001");
        String queueEntryId = checkIn(token, patientId, staff.id());

        ResponseEntity<Map> resp = exchange("/v1/queue/" + queueEntryId + "/reorder", HttpMethod.POST, authedJsonBody(token, Map.of(
                "priority", true, "reason", "chest pain")), Map.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody().get("priorityFlaggedBy")).isEqualTo(staff.id().toString());
        assertThat(resp.getBody().get("priorityFlaggedAt")).isNotNull();
        assertThat(resp.getBody().get("priorityAcknowledgedAt")).isNull();

        ResponseEntity<List> filtered = exchange("/v1/queue?priority=true", HttpMethod.GET, authed(token), List.class);
        assertThat(filtered.getBody()).anyMatch(e -> queueEntryId.equals(((Map<?, ?>) e).get("id")));
    }

    @Test
    void acknowledgingAnUnflaggedEntryIsRejected() {
        SeededTenant tenant = seedTenant();
        UUID roleId = seedFullAccessRole(tenant.id());
        SeededStaff staff = seedStaff(tenant, roleId, "n2@a.com", "+919800020002", false);
        String token = loginAndGetAccessToken(staff);
        String patientId = registerPatient(token, "P2", "+919999960002");
        String queueEntryId = checkIn(token, patientId, staff.id());

        ResponseEntity<Map> resp = exchange("/v1/queue/" + queueEntryId + "/priority/acknowledge", HttpMethod.POST, authed(token), Map.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(resp.getBody().get("type")).asString().contains("not-flagged");
    }

    @Test
    void acknowledgingRecordsWhoAndWhenThenUnflaggingClearsEverything() {
        SeededTenant tenant = seedTenant();
        UUID roleId = seedFullAccessRole(tenant.id());
        SeededStaff staff = seedStaff(tenant, roleId, "n3@a.com", "+919800020003", false);
        String token = loginAndGetAccessToken(staff);
        String patientId = registerPatient(token, "P3", "+919999960003");
        String queueEntryId = checkIn(token, patientId, staff.id());
        exchange("/v1/queue/" + queueEntryId + "/reorder", HttpMethod.POST, authedJsonBody(token, Map.of("priority", true, "reason", "fever")), Map.class);

        ResponseEntity<Map> ack = exchange("/v1/queue/" + queueEntryId + "/priority/acknowledge", HttpMethod.POST, authed(token), Map.class);
        assertThat(ack.getBody().get("priorityAcknowledgedBy")).isEqualTo(staff.id().toString());
        assertThat(ack.getBody().get("priorityAcknowledgedAt")).isNotNull();

        ResponseEntity<Map> unflagged = exchange("/v1/queue/" + queueEntryId + "/reorder", HttpMethod.POST, authedJsonBody(token, Map.of(
                "priority", false, "reason", "resolved")), Map.class);
        assertThat(unflagged.getBody().get("priority")).isEqualTo(false);
        assertThat(unflagged.getBody().get("priorityFlaggedBy")).isNull();
        assertThat(unflagged.getBody().get("priorityAcknowledgedBy")).isNull();
    }

    // ---- NB-145: administration orders & medication administration record ----

    @Test
    void orderingAdministrationDefaultsToNotStarted() {
        SeededTenant tenant = seedTenant();
        UUID roleId = seedFullAccessRole(tenant.id());
        SeededStaff staff = seedStaff(tenant, roleId, "n4@a.com", "+919800020004", false);
        String token = loginAndGetAccessToken(staff);
        String patientId = registerPatient(token, "P4", "+919999960004");
        String queueEntryId = checkIn(token, patientId, staff.id());

        String orderId = orderAdministration(token, queueEntryId);

        ResponseEntity<List> today = exchange("/v1/nursing/administration-orders/today", HttpMethod.GET, authed(token), List.class);
        List<?> body = today.getBody();
        Map<?, ?> order = body.stream().map(o -> (Map<?, ?>) o).filter(o -> orderId.equals(o.get("id"))).findFirst().orElseThrow();
        assertThat(order.get("status")).isEqualTo("not_started");
    }

    @Test
    void administeringRequiresADifferentStaffMemberAsWitness() {
        SeededTenant tenant = seedTenant();
        UUID roleId = seedFullAccessRole(tenant.id());
        SeededStaff staff = seedStaff(tenant, roleId, "n5@a.com", "+919800020005", false);
        String token = loginAndGetAccessToken(staff);
        String patientId = registerPatient(token, "P5", "+919999960005");
        String queueEntryId = checkIn(token, patientId, staff.id());
        String orderId = orderAdministration(token, queueEntryId);

        ResponseEntity<Map> resp = exchange("/v1/nursing/administration-orders/" + orderId + "/administer", HttpMethod.POST,
                authedJsonBody(token, Map.of("witnessedByStaffId", staff.id().toString())), Map.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(resp.getBody().get("type")).asString().contains("self-witness");
    }

    @Test
    void administeringWithARealWitnessSucceedsAndIsImmutable() {
        SeededTenant tenant = seedTenant();
        UUID roleId = seedFullAccessRole(tenant.id());
        SeededStaff nurse = seedStaff(tenant, roleId, "n6@a.com", "+919800020006", false);
        SeededStaff witness = seedStaff(tenant, roleId, "n6w@a.com", "+919800020016", false);
        String token = loginAndGetAccessToken(nurse);
        String patientId = registerPatient(token, "P6", "+919999960006");
        String queueEntryId = checkIn(token, patientId, nurse.id());
        String orderId = orderAdministration(token, queueEntryId);

        ResponseEntity<Map> resp = exchange("/v1/nursing/administration-orders/" + orderId + "/administer", HttpMethod.POST,
                authedJsonBody(token, Map.of("witnessedByStaffId", witness.id().toString())), Map.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody().get("status")).isEqualTo("administered");
        assertThat(resp.getBody().get("witnessedByName")).isEqualTo("Test Staff");

        ResponseEntity<Map> again = exchange("/v1/nursing/administration-orders/" + orderId + "/administer", HttpMethod.POST,
                authedJsonBody(token, Map.of("witnessedByStaffId", witness.id().toString())), Map.class);
        assertThat(again.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(again.getBody().get("type")).asString().contains("already-recorded");
    }

    @Test
    void refusingRecordsTheReason() {
        SeededTenant tenant = seedTenant();
        UUID roleId = seedFullAccessRole(tenant.id());
        SeededStaff staff = seedStaff(tenant, roleId, "n7@a.com", "+919800020007", false);
        String token = loginAndGetAccessToken(staff);
        String patientId = registerPatient(token, "P7", "+919999960007");
        String queueEntryId = checkIn(token, patientId, staff.id());
        String orderId = orderAdministration(token, queueEntryId);

        ResponseEntity<Map> resp = exchange("/v1/nursing/administration-orders/" + orderId + "/refuse", HttpMethod.POST,
                authedJsonBody(token, Map.of("reason", "patient declined")), Map.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody().get("status")).isEqualTo("refused");
        assertThat(resp.getBody().get("refuseReason")).isEqualTo("patient declined");
    }

    // ---- NB-146: today's procedures worklist ----

    private void seedCharge(SeededTenant tenant, String code, String name, double amount, double taxRatePercent) {
        inTenantTx(tenant.id(), () -> jdbc.update(
                "INSERT INTO charge_catalogue (tenant_id, code, name, category, base_amount, tax_rate_percent) VALUES (?,?,?,?,?,?)",
                tenant.id(), code, name, "Procedure", amount, taxRatePercent));
    }

    @Test
    void orderingProcedureWithAnUnknownChargeIsRejected() {
        SeededTenant tenant = seedTenant();
        UUID roleId = seedFullAccessRole(tenant.id());
        SeededStaff staff = seedStaff(tenant, roleId, "n8@a.com", "+919800020008", false);
        String token = loginAndGetAccessToken(staff);
        String patientId = registerPatient(token, "P8", "+919999960008");
        String queueEntryId = checkIn(token, patientId, staff.id());

        ResponseEntity<Map> resp = exchange("/v1/nursing/procedure-orders", HttpMethod.POST, authedJsonBody(token, Map.of(
                "queueEntryId", queueEntryId, "chargeCode", "NOPE")), Map.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(resp.getBody().get("type")).asString().contains("charge-not-found");
    }

    @Test
    void completingAProcedurePreLoadsItIntoCheckoutAndBillingClearsIt() {
        SeededTenant tenant = seedTenant();
        UUID roleId = seedFullAccessRole(tenant.id());
        SeededStaff staff = seedStaff(tenant, roleId, "n9@a.com", "+919800020009", false);
        String token = loginAndGetAccessToken(staff);
        seedCharge(tenant, "RCT-1", "Root canal", 2500.00, 18.00);
        String patientId = registerPatient(token, "P9", "+919999960009");
        String queueEntryId = checkIn(token, patientId, staff.id());

        ResponseEntity<Map> order = exchange("/v1/nursing/procedure-orders", HttpMethod.POST, authedJsonBody(token, Map.of(
                "queueEntryId", queueEntryId, "chargeCode", "RCT-1", "prepNotes", "chair 1 ready")), Map.class);
        assertThat(order.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(order.getBody().get("status")).isEqualTo("ordered");
        assertThat(((Number) order.getBody().get("baseAmount")).doubleValue()).isEqualTo(2500.00);
        String procedureId = (String) order.getBody().get("id");

        exchange("/v1/nursing/procedure-orders/" + procedureId + "/consent", HttpMethod.POST,
                authedJsonBody(token, Map.of("signedName", "P9")), Map.class);

        ResponseEntity<Map> completed = exchange("/v1/nursing/procedure-orders/" + procedureId + "/status", HttpMethod.PATCH,
                authedJsonBody(token, Map.of("status", "completed")), Map.class);
        assertThat(completed.getBody().get("status")).isEqualTo("completed");
        assertThat(completed.getBody().get("billed")).isEqualTo(false);

        // a second status change on an already-final procedure is rejected
        ResponseEntity<Map> reopened = exchange("/v1/nursing/procedure-orders/" + procedureId + "/status", HttpMethod.PATCH,
                authedJsonBody(token, Map.of("status", "prepped")), Map.class);
        assertThat(reopened.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(reopened.getBody().get("type")).asString().contains("already-final");

        moveTo(token, queueEntryId, "waiting", "vitals_pending", "vitals_done", "in_consult", "checkout_pending");
        ResponseEntity<Map> ctx = exchange("/v1/billing/checkout/" + queueEntryId, HttpMethod.GET, authed(token), Map.class);
        List<?> pending = (List<?>) ctx.getBody().get("pendingProcedures");
        assertThat(pending).anyMatch(c -> "RCT-1".equals(((Map<?, ?>) c).get("code")));

        exchange("/v1/billing/checkout/" + queueEntryId, HttpMethod.POST, authedJsonBody(token, Map.of(
                "lineItems", List.of(Map.of("chargeCode", "RCT-1", "chargeName", "Root canal", "category", "Procedure",
                        "quantity", 1, "unitPrice", 2500.00, "taxRatePercent", 18.00)))), Map.class);

        Boolean billed = inTenantTx(tenant.id(), () -> jdbc.queryForObject(
                "SELECT billed FROM procedure_orders WHERE id = ?", Boolean.class, UUID.fromString(procedureId)));
        assertThat(billed).isTrue();
    }

    @Test
    void aProcedureCannotBeStartedWithoutRecordedConsentButCanStillBeCancelled() {
        SeededTenant tenant = seedTenant();
        UUID roleId = seedFullAccessRole(tenant.id());
        SeededStaff staff = seedStaff(tenant, roleId, "n10@a.com", "+919800020010", false);
        String token = loginAndGetAccessToken(staff);
        seedCharge(tenant, "RCT-2", "Root canal", 2500.00, 18.00);
        String patientId = registerPatient(token, "P10", "+919999960010");
        String queueEntryId = checkIn(token, patientId, staff.id());

        ResponseEntity<Map> order = exchange("/v1/nursing/procedure-orders", HttpMethod.POST, authedJsonBody(token, Map.of(
                "queueEntryId", queueEntryId, "chargeCode", "RCT-2")), Map.class);
        String procedureId = (String) order.getBody().get("id");

        ResponseEntity<Map> blocked = exchange("/v1/nursing/procedure-orders/" + procedureId + "/status", HttpMethod.PATCH,
                authedJsonBody(token, Map.of("status", "prepped")), Map.class);
        assertThat(blocked.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(blocked.getBody().get("type")).asString().contains("consent-required");

        ResponseEntity<Map> cancelled = exchange("/v1/nursing/procedure-orders/" + procedureId + "/status", HttpMethod.PATCH,
                authedJsonBody(token, Map.of("status", "cancelled")), Map.class);
        assertThat(cancelled.getBody().get("status")).isEqualTo("cancelled");
    }

    @Test
    void recordingConsentUnblocksStartingTheProcedure() {
        SeededTenant tenant = seedTenant();
        UUID roleId = seedFullAccessRole(tenant.id());
        SeededStaff staff = seedStaff(tenant, roleId, "n11@a.com", "+919800020011", false);
        String token = loginAndGetAccessToken(staff);
        seedCharge(tenant, "RCT-3", "Root canal", 2500.00, 18.00);
        String patientId = registerPatient(token, "P11", "+919999960011");
        String queueEntryId = checkIn(token, patientId, staff.id());

        ResponseEntity<Map> order = exchange("/v1/nursing/procedure-orders", HttpMethod.POST, authedJsonBody(token, Map.of(
                "queueEntryId", queueEntryId, "chargeCode", "RCT-3")), Map.class);
        String procedureId = (String) order.getBody().get("id");

        ResponseEntity<Map> consented = exchange("/v1/nursing/procedure-orders/" + procedureId + "/consent", HttpMethod.POST,
                authedJsonBody(token, Map.of("signedName", "P11")), Map.class);
        assertThat(consented.getBody().get("consentSignedName")).isEqualTo("P11");
        assertThat(consented.getBody().get("consentRecordedByName")).isEqualTo("Test Staff");
        assertThat(consented.getBody().get("consentSignedAt")).isNotNull();

        ResponseEntity<Map> prepped = exchange("/v1/nursing/procedure-orders/" + procedureId + "/status", HttpMethod.PATCH,
                authedJsonBody(token, Map.of("status", "prepped")), Map.class);
        assertThat(prepped.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(prepped.getBody().get("status")).isEqualTo("prepped");
    }

    // ---- NB-148: completed activity log ----

    @Test
    void completedActivityAggregatesVitalsAdministrationAndPriorityFlags() {
        SeededTenant tenant = seedTenant();
        UUID roleId = seedFullAccessRole(tenant.id());
        SeededStaff nurse = seedStaff(tenant, roleId, "n10@a.com", "+919800020010", false);
        SeededStaff witness = seedStaff(tenant, roleId, "n10w@a.com", "+919800020020", false);
        String token = loginAndGetAccessToken(nurse);
        String patientId = registerPatient(token, "P10", "+919999960010");
        String queueEntryId = checkIn(token, patientId, nurse.id());

        exchange("/v1/queue/" + queueEntryId + "/reorder", HttpMethod.POST, authedJsonBody(token, Map.of("priority", true, "reason", "fever")), Map.class);
        moveTo(token, queueEntryId, "waiting", "vitals_pending");
        exchange("/v1/clinical/vitals/" + queueEntryId, HttpMethod.PATCH, authedJsonBody(token, Map.of(
                "heightCm", 170.0, "weightKg", 65.0, "bpSystolic", 118, "bpDiastolic", 76,
                "pulseBpm", 70, "tempCelsius", 38.9, "spo2Percent", 97)), Map.class);
        String orderId = orderAdministration(token, queueEntryId);
        exchange("/v1/nursing/administration-orders/" + orderId + "/administer", HttpMethod.POST,
                authedJsonBody(token, Map.of("witnessedByStaffId", witness.id().toString())), Map.class);

        ResponseEntity<List> activity = exchange("/v1/nursing/activity/today", HttpMethod.GET, authed(token), List.class);

        List<String> kinds = activity.getBody().stream().map(e -> (String) ((Map<?, ?>) e).get("kind")).toList();
        assertThat(kinds).contains("vitals", "administration", "priority");
    }

    @Test
    void roleWithoutNursingGrantIsForbidden() {
        SeededTenant tenant = seedTenant();
        UUID roleId = seedRole(tenant.id(), "QueueOnly", false, fullGrant("queue"), fullGrant("patients"));
        SeededStaff staff = seedStaff(tenant, roleId, "noNursing@a.com", "+919800020099", false);
        String token = loginAndGetAccessToken(staff);

        ResponseEntity<Map> resp = exchange("/v1/nursing/administration-orders/today", HttpMethod.GET, authed(token), Map.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }
}
