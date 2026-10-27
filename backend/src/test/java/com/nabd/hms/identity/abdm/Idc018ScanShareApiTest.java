package com.nabd.hms.identity.abdm;

import com.nabd.hms.common.ApiException;
import com.nabd.hms.identity.integration.AbdmShareGateway;
import com.nabd.hms.support.ApiTestBase;
import com.nabd.hms.support.MutableClock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Time;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

/** IDC-018: ABDM Scan & Share against a fake gateway answer (the real ABDM sandbox is Needs external action). The clock is frozen on a Tuesday noon. */
@Import(Idc018ScanShareApiTest.Fake.class)
class Idc018ScanShareApiTest extends ApiTestBase {

    static final class FakeShareGateway implements AbdmShareGateway {
        final List<String> answers = new CopyOnWriteArrayList<>();
        final AtomicBoolean down = new AtomicBoolean();

        @Override public void respond(String requestId, Integer tokenNumber) {
            if (down.get()) {
                throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "abdm-unavailable", "ABDM unavailable", "down");
            }
            answers.add(requestId + ":" + tokenNumber);
        }
    }

    static final FakeShareGateway GATEWAY = new FakeShareGateway();

    @TestConfiguration
    static class Fake {
        @Bean @Primary AbdmShareGateway fakeShareGateway() { return GATEWAY; }
        @Bean @Primary MutableClock mutableClock() { return new MutableClock(); }
    }

    @Autowired AbdmShareService share;
    @Autowired AbhaIdentifiers identifiers;
    @Autowired MutableClock clock;

    private record Clinic(SeededTenant tenant, UUID doctor, String hip) {
    }

    @BeforeEach
    void reset() {
        GATEWAY.answers.clear();
        GATEWAY.down.set(false);
        clock.setLocal("2026-03-10T12:00:00", "UTC"); // Tuesday
    }

    @AfterEach
    void realTime() {
        clock.reset();
    }

    private JdbcTemplate su() {
        return new JdbcTemplate(superuserDataSource());
    }

    /** A clinic with ABDM on, one doctor, and (open) a session covering Tuesday noon with the given cap (null = no cap). */
    private Clinic clinic(boolean open, Integer cap) {
        SeededTenant t = seedTenant();
        String hip = "HFR-" + t.slug().substring(1, 9);
        inTenantTx(t.id(), () -> jdbc.update("INSERT INTO tenant_integrations_abdm (tenant_id, enabled, hfr_id, verified_at) VALUES (?, true, ?, now())", t.id(), hip));
        SeededStaff doc = seedStaff(t, seedFullAccessRole(t.id()), "sas" + t.slug() + "@a.com", "+9195" + (10000000 + (int) (Math.random() * 89999999)), false);
        if (open) {
            inTenantTx(t.id(), () -> jdbc.update("INSERT INTO doctor_working_hours (id, tenant_id, doctor_id, day_of_week, start_time, end_time, slot_minutes, max_patients) VALUES (?,?,?,?,?,?,?,?)",
                    UUID.randomUUID(), t.id(), doc.id(), 2, Time.valueOf(LocalTime.of(9, 0)), Time.valueOf(LocalTime.of(17, 0)), 15, cap));
        }
        return new Clinic(t, doc.id(), hip);
    }

    private static AbdmShareService.Profile profile(String abha, String phone) {
        return new AbdmShareService.Profile(abha, "asha.rao@abdm", "Asha Rao", "F", 1990, 4, 12, phone);
    }

    private AbdmShareService.Outcome shareOf(Clinic c, String requestId, AbdmShareService.Profile p) {
        Optional<AbdmShareService.Outcome> o = share.handle(c.hip(), requestId, c.doctor().toString(), p);
        assertThat(o).isPresent();
        return o.get();
    }

    private int count(Clinic c, String sql) {
        return su().queryForObject(sql + " AND tenant_id = ?", Integer.class, c.tenant().id());
    }

    private void awaitAnswers(int n) throws InterruptedException {
        for (int i = 0; i < 200 && GATEWAY.answers.size() < n; i++) {
            Thread.sleep(10);
        }
        assertThat(GATEWAY.answers).hasSize(n);
    }

    @Test
    void newPatientGetsATokenAndConsentStaysPending() throws Exception {
        Clinic c = clinic(true, null);
        long t0 = System.nanoTime();
        AbdmShareService.Outcome o = shareOf(c, "req-new-1", profile("91-1234-5678-9012", "+919812345678"));
        awaitAnswers(1);
        assertThat((System.nanoTime() - t0) / 1_000_000_000.0).as("callback to ABDM answer, far below the 15 s budget").isLessThan(15.0);
        assertThat(o.token()).isEqualTo(1);
        assertThat(GATEWAY.answers).containsExactly("req-new-1:1");

        assertThat(count(c, "SELECT count(*) FROM patients WHERE name = 'Asha Rao'")).isEqualTo(1);
        assertThat(count(c, "SELECT count(*) FROM queue_entries WHERE source = 'abdm_scan_share' AND token_number = 1 AND status = 'checked_in'")).isEqualTo(1);
        assertThat(count(c, "SELECT count(*) FROM patient_identifiers WHERE verified_via = 'abdm_scan_share'")).isEqualTo(2);
        assertThat(count(c, "SELECT count(*) FROM consents WHERE true")).as("processing consent is pending: no consent row at all").isZero();
        String audit = su().queryForObject("SELECT string_agg(after::text, ' ') FROM audit_log WHERE action = 'identity.abdm_scan_share' AND tenant_id = ?", String.class, c.tenant().id());
        assertThat(audit).contains("tokenNumber").doesNotContain("91123456789012").doesNotContain("1234-5678");
    }

    @Test
    void returningPatientIsMatchedByExactAbhaAndNoDuplicateIsCreated() throws Exception {
        Clinic c = clinic(true, null);
        UUID existing = UUID.randomUUID();
        inTenantTx(c.tenant().id(), () -> {
            jdbc.update("INSERT INTO patients (id, tenant_id, name, phone, dob, gender) VALUES (?,?,?,?,?,?)", existing, c.tenant().id(), "Old Record", "+919800000001", java.sql.Date.valueOf("1990-04-12"), "female");
            identifiers.link(existing, AbhaIdentifiers.NUMBER, "91123456789012", "abdm_mobile_otp");
        });
        AbdmShareService.Outcome o = shareOf(c, "req-ret-1", profile("91-1234-5678-9012", "+919812345678"));
        assertThat(o.token()).isEqualTo(1);
        assertThat(count(c, "SELECT count(*) FROM patients WHERE true")).isEqualTo(1);
        assertThat(su().queryForObject("SELECT patient_id FROM queue_entries WHERE tenant_id = ?", UUID.class, c.tenant().id())).isEqualTo(existing);
        // a different number is not matched on a similar name or phone: it is a new patient
        shareOf(c, "req-ret-2", new AbdmShareService.Profile("91-0000-0000-0001", null, "Old Record", "F", 1990, 4, 12, "+919800000001"));
        assertThat(count(c, "SELECT count(*) FROM patients WHERE true")).isEqualTo(2);
        awaitAnswers(2);
    }

    @Test
    void aSecondScanOfTheSameQrDoesNotMintASecondToken_andARepeatedRequestIdIsAnsweredOnce() throws Exception {
        Clinic c = clinic(true, null);
        shareOf(c, "req-dup-1", profile("91-1234-5678-9012", "+919812345678"));
        AbdmShareService.Outcome again = shareOf(c, "req-dup-2", profile("91-1234-5678-9012", "+919812345678"));
        assertThat(again.token()).as("same open entry, same token").isEqualTo(1);
        AbdmShareService.Outcome replay = shareOf(c, "req-dup-1", profile("91-1234-5678-9012", "+919812345678"));
        assertThat(replay.replay()).isTrue();
        assertThat(replay.token()).isEqualTo(1);
        assertThat(count(c, "SELECT count(*) FROM queue_entries WHERE true")).isEqualTo(1);
        assertThat(count(c, "SELECT count(*) FROM abdm_share_requests WHERE true")).isEqualTo(2);
        awaitAnswers(2); // the replay is not answered again
    }

    @Test
    void closedClinicAndFullSessionAreAnsweredSeeTheDeskWithoutAnyRecord() throws Exception {
        Clinic closed = clinic(false, null);
        AbdmShareService.Outcome o = shareOf(closed, "req-closed", profile("91-1234-5678-9012", "+919812345678"));
        assertThat(o.token()).isNull();
        assertThat(o.reason()).isEqualTo("closed");
        assertThat(count(closed, "SELECT count(*) FROM patients WHERE true")).isZero();
        assertThat(count(closed, "SELECT count(*) FROM queue_entries WHERE true")).isZero();
        assertThat(count(closed, "SELECT count(*) FROM patient_identifiers WHERE true")).isZero();

        Clinic full = clinic(true, 1);
        assertThat(shareOf(full, "req-f1", profile("91-1234-5678-9012", "+919812345678")).token()).isEqualTo(1);
        AbdmShareService.Outcome second = shareOf(full, "req-f2", new AbdmShareService.Profile("91-5555-5555-5555", null, "Bina Shah", "F", 1985, 1, 1, "+919822222222"));
        assertThat(second.token()).isNull();
        assertThat(second.reason()).isEqualTo("full");
        assertThat(count(full, "SELECT count(*) FROM patients WHERE true")).as("no patient for the refused share").isEqualTo(1);
        awaitAnswers(3);
        assertThat(GATEWAY.answers).contains("req-closed:null", "req-f2:null", "req-f1:1");
    }

    @Test
    void incompleteProfileCreatesNothing_andAnUnknownCounterIsSeeTheDesk() {
        Clinic c = clinic(true, null);
        AbdmShareService.Outcome noPhone = shareOf(c, "req-inc-1", profile("91-1234-5678-9012", null));
        assertThat(noPhone.token()).isNull();
        assertThat(noPhone.reason()).isEqualTo("incomplete_profile");
        assertThat(shareOf(c, "req-inc-2", profile("12345", "+919812345678")).reason()).isEqualTo("incomplete_profile");
        assertThat(count(c, "SELECT count(*) FROM patients WHERE true")).isZero();
        Optional<AbdmShareService.Outcome> badCounter = share.handle(c.hip(), "req-inc-3", "not-a-doctor", profile("91-1234-5678-9012", "+919812345678"));
        assertThat(badCounter.get().reason()).isEqualTo("no_doctor");
    }

    @Test
    void anUnknownOrDisabledFacilityIsIgnored_andClinicsStayIsolated() {
        Clinic a = clinic(true, null);
        Clinic b = clinic(true, null);
        assertThat(share.handle("HFR-NOPE-1", "req-x", a.doctor().toString(), profile("91-1234-5678-9012", "+919812345678"))).isEmpty();
        shareOf(a, "req-iso-1", profile("91-1234-5678-9012", "+919812345678"));
        assertThat(count(b, "SELECT count(*) FROM patients WHERE true")).isZero();
        assertThat(inTenantTx(b.tenant().id(), () -> jdbc.queryForObject("SELECT count(*) FROM abdm_share_requests", Integer.class))).as("RLS: clinic B sees none of A's rows").isZero();
        assertThat(inTenantTx(a.tenant().id(), () -> jdbc.queryForObject("SELECT count(*) FROM abdm_share_requests", Integer.class))).isEqualTo(1);
        su().update("UPDATE tenant_integrations_abdm SET enabled = false WHERE tenant_id = ?", a.tenant().id());
        assertThat(share.handle(a.hip(), "req-iso-2", a.doctor().toString(), profile("91-1234-5678-9012", "+919812345678"))).as("ABDM switched off").isEmpty();
    }

    @Test
    void aGatewayThatCannotBeReachedDoesNotLoseTheToken() throws Exception {
        Clinic c = clinic(true, null);
        GATEWAY.down.set(true);
        assertThat(shareOf(c, "req-down", profile("91-1234-5678-9012", "+919812345678")).token()).isEqualTo(1);
        Thread.sleep(100);
        assertThat(GATEWAY.answers).isEmpty();
        assertThat(count(c, "SELECT count(*) FROM queue_entries WHERE source = 'abdm_scan_share'")).isEqualTo(1);
    }

    @Test
    void theShareEndpointIsReachableAnonymouslyButRefusesWithoutTheGatewaySignature() {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);
        String body = "{\"requestId\":\"r1\",\"hipId\":\"HFR-X\",\"profile\":{}}";
        assertThat(exchange("/abdm/v3/hip/patient/share", HttpMethod.POST, new HttpEntity<>(body, h), Map.class).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        h.setBearerAuth("not-a-gateway-token");
        assertThat(exchange("/abdm/v3/hip/patient/share", HttpMethod.POST, new HttpEntity<>(body, h), Map.class).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }
}
