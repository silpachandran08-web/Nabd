package com.nabd.hms.organization.golive;

import com.nabd.hms.support.ApiTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** ADM-011: only true blockers stop go-live; the rest is listed as good to have; go-live is owner-only, audited, idempotent. */
class Adm011GoLiveApiTest extends ApiTestBase {

    private final JdbcTemplate su = new JdbcTemplate(superuserDataSource());

    private record Clinic(SeededTenant tenant, SeededStaff owner, SeededStaff doctor, String token) {
    }

    /** A trialing clinic that satisfies every blocker except the ones named in skip (doctor_schedule, price_list, whatsapp_sender, dpa_signed). */
    private Clinic clinic(Set<String> skip) {
        SeededTenant t = seedTenant();
        su.update("UPDATE tenants SET status = 'trialing', tax_id = NULL, whatsapp_number = ? WHERE id = ?", skip.contains("whatsapp_sender") ? null : "+919800009999", t.id());
        UUID role = seedFullAccessRole(t.id());
        SeededStaff owner = seedStaff(t, role, "own" + UUID.randomUUID() + "@a.com", "+9198" + (10000000 + (int) (Math.random() * 89999999)), false);
        SeededStaff doctor = seedStaff(t, role, "doc" + UUID.randomUUID() + "@a.com", "+9197" + (10000000 + (int) (Math.random() * 89999999)), false);
        su.update("UPDATE staff SET is_clinician = true WHERE id = ?", doctor.id());
        inTenantTx(t.id(), () -> {
            if (!skip.contains("doctor_schedule")) {
                jdbc.update("INSERT INTO doctor_working_hours (tenant_id, doctor_id, day_of_week, start_time, end_time) VALUES (?,?,1,'09:00','13:00')", t.id(), doctor.id());
            }
            if (!skip.contains("price_list")) {
                jdbc.update("INSERT INTO charge_catalogue (tenant_id, code, name, category, base_amount) VALUES (?, 'CONS', 'Consultation', 'Consultation', 300)", t.id());
            }
        });
        if (!skip.contains("dpa_signed")) {
            su.update("INSERT INTO dpa_acceptances (tenant_id, dpa_version, signer_owner_id, method) VALUES (?, 'test-v1', ?, 'paper')", t.id(), owner.id());
        }
        return new Clinic(t, owner, doctor, loginAndGetAccessToken(owner));
    }

    private Map<?, ?> checks(Clinic c) {
        return exchange("/v1/setup/go-live/checks", HttpMethod.GET, authed(c.token()), Map.class).getBody();
    }

    private Map<?, ?> find(Object list, String code) {
        return ((List<Map<?, ?>>) list).stream().filter(m -> code.equals(m.get("code"))).findFirst().orElseThrow();
    }

    private ResponseEntity<Map> goLive(Clinic c) {
        return exchange("/v1/setup/go-live", HttpMethod.POST, authedJsonBody(c.token(), Map.of()), Map.class);
    }

    @Test
    void eachBlockerOnItsOwnBlocksGoLive_withALinkToFixIt() {
        for (String missing : List.of("doctor_schedule", "price_list", "whatsapp_sender", "dpa_signed")) {
            Clinic c = clinic(Set.of(missing));
            Map<?, ?> r = checks(c);
            List<Map<?, ?>> blockers = (List<Map<?, ?>>) r.get("blockers");
            assertThat(blockers).extracting(b -> (String) b.get("code")).containsExactly("doctor_schedule", "price_list", "whatsapp_sender", "dpa_signed");
            assertThat(blockers.stream().filter(b -> Boolean.FALSE.equals(b.get("ok"))).map(b -> (String) b.get("code"))).as("only " + missing).containsExactly(missing);
            assertThat((String) find(blockers, missing).get("fixUrl")).startsWith("/");
            ResponseEntity<Map> go = goLive(c);
            assertThat(go.getStatusCode()).as(missing).isEqualTo(HttpStatus.CONFLICT);
            assertThat(String.valueOf(go.getBody().get("type"))).endsWith("BLOCKERS_OPEN");
            assertThat(go.getBody().get("blockers")).isEqualTo(List.of(missing));
            assertThat(jdbc.queryForObject("SELECT status FROM tenants WHERE id = ?", String.class, c.tenant().id())).isEqualTo("trialing");
            assertThat(jdbc.queryForObject("SELECT setup_completed_at IS NULL FROM tenants WHERE id = ?", Boolean.class, c.tenant().id())).isTrue();
        }
    }

    @Test
    void pendingInvitesAndEverythingElseAreGoodToHave_goLiveSucceedsAndTenantIsActive() {
        Clinic c = clinic(Set.of());
        UUID role = seedFullAccessRole(c.tenant().id());
        inTenantTx(c.tenant().id(), () -> {
            for (int i = 0; i < 4; i++) {
                jdbc.update("INSERT INTO staff (id, tenant_id, role_id, email, name, mobile_phone, status) VALUES (?,?,?,?,?,?,'invited')",
                        UUID.randomUUID(), c.tenant().id(), role, "inv" + i + "-" + UUID.randomUUID() + "@a.com", "Invitee " + i, "+91980000700" + i);
            }
        });
        Map<?, ?> r = checks(c);
        Map<?, ?> invites = find(r.get("niceToHave"), "staff_invited");
        assertThat(invites.get("ok")).isEqualTo(false);
        assertThat((String) invites.get("note")).contains("4 of 6");
        assertThat(find(r.get("niceToHave"), "patients_imported").get("ok")).isEqualTo(false);
        assertThat(find(r.get("niceToHave"), "waiting_room_tv").get("ok")).isEqualTo(false);
        assertThat(find(r.get("niceToHave"), "tax_id").get("ok")).isEqualTo(false);

        // a poster is not available before there is a link to print
        assertThat(exchange("/v1/setup/go-live/poster", HttpMethod.GET, authed(c.token()), Map.class).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

        ResponseEntity<Map> go = goLive(c);
        assertThat(go.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(go.getBody().get("liveAt")).isNotNull();
        assertThat((String) go.getBody().get("bookingUrl")).endsWith("/b/" + c.tenant().slug());
        assertThat(go.getBody().get("qrPosterUrl")).isEqualTo("/v1/setup/go-live/poster");

        Map<String, Object> row = su.queryForMap("SELECT status, setup_completed_at, go_live_by FROM tenants WHERE id = ?", c.tenant().id());
        assertThat(row.get("status")).isEqualTo("active");
        assertThat(row.get("setup_completed_at")).isNotNull();
        assertThat(row.get("go_live_by")).isEqualTo(c.owner().id());
        Map<String, Object> ev = su.queryForMap("SELECT changed_by, changed_by_staff FROM tenant_lifecycle_events WHERE tenant_id = ? AND to_status = 'active'", c.tenant().id());
        assertThat(ev.get("changed_by")).isNull();
        assertThat(ev.get("changed_by_staff")).isEqualTo(c.owner().id());
        assertThat(inTenantTx(c.tenant().id(), () -> jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE action = 'setup.went_live'", Long.class))).isEqualTo(1L);

        // the booking link and the printable poster are available afterwards
        ResponseEntity<byte[]> poster = exchange("/v1/setup/go-live/poster", HttpMethod.GET, authed(c.token()), byte[].class);
        assertThat(poster.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(new String(poster.getBody(), 0, 5)).isEqualTo("%PDF-");
        assertThat(new String(poster.getBody(), java.nio.charset.StandardCharsets.ISO_8859_1)).contains("Book your visit").contains("/b/" + c.tenant().slug());

        // a repeat answers the same and writes nothing more
        ResponseEntity<Map> again = goLive(c);
        assertThat(again.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(again.getBody().get("liveAt")).isEqualTo(go.getBody().get("liveAt"));
        assertThat(inTenantTx(c.tenant().id(), () -> jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE action = 'setup.went_live'", Long.class))).isEqualTo(1L);
    }

    @Test
    void ksaClinicWithoutZatcaPhase2_isGoodToHaveWithANote_andDoesNotBlock() {
        Clinic c = clinic(Set.of());
        su.update("UPDATE tenants SET region_code = 'SA', region = 'SA', tax_id = NULL WHERE id = ?", c.tenant().id());
        Map<?, ?> r = checks(c);
        Map<?, ?> zatca = find(r.get("niceToHave"), "einvoice_phase2");
        assertThat(zatca.get("ok")).isEqualTo(false);
        assertThat((String) zatca.get("note")).contains("phase-1 QR invoices work meanwhile");
        assertThat((String) find(r.get("niceToHave"), "tax_id").get("note")).containsIgnoringCase("VAT");
        assertThat(((List<Map<?, ?>>) r.get("blockers")).stream().allMatch(b -> Boolean.TRUE.equals(b.get("ok")))).isTrue();
        assertThat(goLive(c).getStatusCode()).isEqualTo(HttpStatus.OK);

        // an Indian clinic has no such item, and its tax label is GST
        Clinic in = clinic(Set.of());
        Map<?, ?> ri = checks(in);
        assertThat(((List<Map<?, ?>>) ri.get("niceToHave")).stream().map(m -> (String) m.get("code"))).doesNotContain("einvoice_phase2");
        assertThat((String) find(ri.get("niceToHave"), "tax_id").get("note")).contains("GSTIN");
    }

    @Test
    void onlyTheOwnerCanGoLive_andViewersOnlyReadTheChecks() {
        Clinic c = clinic(Set.of());
        UUID clerkRole = seedRole(c.tenant().id(), "Clerk", false, fullGrant("setup"));
        SeededStaff clerk = seedStaff(c.tenant(), clerkRole, "clerk" + UUID.randomUUID() + "@a.com", "+919800000808", false);
        String clerkToken = loginAndGetAccessToken(clerk);
        ResponseEntity<Map> r = exchange("/v1/setup/go-live", HttpMethod.POST, authedJsonBody(clerkToken, Map.of()), Map.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(String.valueOf(r.getBody().get("type"))).endsWith("NOT_OWNER");
        assertThat(jdbc.queryForObject("SELECT status FROM tenants WHERE id = ?", String.class, c.tenant().id())).isEqualTo("trialing");

        UUID noSetup = seedRole(c.tenant().id(), "Nurse", false, fullGrant("queue"));
        SeededStaff nurse = seedStaff(c.tenant(), noSetup, "nurse" + UUID.randomUUID() + "@a.com", "+919800000809", false);
        assertThat(exchange("/v1/setup/go-live/checks", HttpMethod.GET, authed(loginAndGetAccessToken(nurse)), Map.class).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void anotherClinicsDataNeverCountsForThisOne() {
        Clinic ready = clinic(Set.of());
        Clinic bare = clinic(Set.of("doctor_schedule", "price_list"));
        Map<?, ?> r = checks(bare);
        assertThat(find(r.get("blockers"), "doctor_schedule").get("ok")).isEqualTo(false);
        assertThat(find(r.get("blockers"), "price_list").get("ok")).isEqualTo(false);
        assertThat(find(checks(ready).get("blockers"), "doctor_schedule").get("ok")).isEqualTo(true);
    }
}
