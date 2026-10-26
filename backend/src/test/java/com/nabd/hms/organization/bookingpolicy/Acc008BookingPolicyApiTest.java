package com.nabd.hms.organization.bookingpolicy;

import com.nabd.hms.organization.api.BookingRules;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/** ACC-008: owner sets booking and queue rules, publishes them as an audited version. */
class Acc008BookingPolicyApiTest extends BookingPolicyTestSupport {

    @Autowired BookingRules rules;

    private static Map<String, Object> withFollowUp(String acceptance, int cap) {
        Map<String, Object> cfg = copy(BookingPresets.defaults());
        sub(sub(cfg, "visitTypes"), "followUp").put("acceptance", acceptance);
        sub(sub(cfg, "visitTypes"), "followUp").put("dailyCapPerDoctor", cap);
        return cfg;
    }

    @Test
    void publishingWithStepUpStoresAVersionAuditsBeforeAfterAndTheNextBookingSeesIt() throws Exception {
        Clinic c = clinic();
        assertThat(current(c.token()).getBody().get("published")).isNull(); // never published: default applies
        assertThat(inTenantTx(c.tenant().id(), () -> rules.rule(null, "followUp"))).get().extracting(BookingRules.Rule::acceptance).isEqualTo("auto");

        assertThat(putDraft(c.token(), withFollowUp("review", 25)).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(publish(c.token(), "v1").getBody().get("version")).isEqualTo(1);
        assertThat(inTenantTx(c.tenant().id(), () -> rules.rule(null, "followUp")).orElseThrow())
                .extracting(BookingRules.Rule::acceptance, BookingRules.Rule::policyVersion).containsExactly("review", 1);

        putDraft(c.token(), withFollowUp("auto", 40));
        long t0 = System.nanoTime();
        ResponseEntity<Map> pub = publish(c.token(), "follow-ups straight in");
        assertThat(pub.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(pub.getBody().get("version")).isEqualTo(2);
        assertThat(pub.getBody().get("effectiveAt")).isNotNull();
        // the next follow-up booking is decided under version 2 at once (5 s budget)
        BookingRules.Rule rule = inTenantTx(c.tenant().id(), () -> rules.rule(null, "followUp")).orElseThrow();
        assertThat((System.nanoTime() - t0) / 1_000_000_000.0).isLessThan(5.0);
        assertThat(rule.acceptance()).isEqualTo("auto");
        assertThat(rule.dailyCapPerDoctor()).isEqualTo(40);
        assertThat(rule.policyVersion()).isEqualTo(2);

        Map<String, Object> published = sub(current(c.token()).getBody(), "published");
        assertThat(published.get("version")).isEqualTo(2);
        assertThat(sub(sub(sub(published, "config"), "visitTypes"), "followUp")).containsEntry("acceptance", "auto").containsEntry("dailyCapPerDoctor", 40);
        assertThat(current(c.token()).getBody().get("draft")).isNull(); // the draft became the version

        Map<String, Object> audit = inTenantTx(c.tenant().id(), () -> jdbc.queryForMap(
                "SELECT before::text AS before, after::text AS after, actor_id FROM audit_log WHERE action = 'scheduling.booking_policy.published' ORDER BY id DESC LIMIT 1"));
        assertThat((String) audit.get("before")).contains("\"version\": 1").contains("\"review\"").contains("25");
        assertThat((String) audit.get("after")).contains("\"version\": 2").contains("\"auto\"").contains("40").contains("follow-ups straight in");
        assertThat(audit.get("actor_id").toString()).isEqualTo(c.owner().id().toString());
    }

    @Test
    void existingBookingsKeepTheirVersionAndNewOnesTakeTheNewOne() throws Exception {
        Clinic c = clinic();
        workingHoursTomorrow(c);
        String p1 = patient(c, "Pat One");
        String p2 = patient(c, "Pat Two");
        String p3 = patient(c, "Pat Three");

        String beforeAny = (String) book(c, p1, slot(c.tenant().id(), 9, 0)).getBody().get("id");
        assertThat(policyVersionOf(c.tenant().id(), beforeAny)).isNull();

        for (int v = 1; v <= 3; v++) {
            putDraft(c.token(), withFollowUp("review", 10 + v));
            publish(c.token(), "v" + v);
        }
        String underV3 = (String) book(c, p2, slot(c.tenant().id(), 10, 0)).getBody().get("id");
        assertThat(policyVersionOf(c.tenant().id(), underV3)).isEqualTo(3);

        putDraft(c.token(), withFollowUp("auto", 40));
        assertThat(publish(c.token(), "v4").getBody().get("version")).isEqualTo(4);
        assertThat(policyVersionOf(c.tenant().id(), underV3)).isEqualTo(3); // not re-decided
        assertThat(policyVersionOf(c.tenant().id(), beforeAny)).isNull();
        String underV4 = (String) book(c, p3, slot(c.tenant().id(), 11, 0)).getBody().get("id");
        assertThat(policyVersionOf(c.tenant().id(), underV4)).isEqualTo(4);
    }

    @Test
    void invalidDraftsAreRefusedWithAPointer() {
        Clinic c = clinic();
        Map<String, Object> cfg = copy(BookingPresets.defaults());
        sub(cfg, "walkIn").put("interleave", "1:7");
        ResponseEntity<Map> r = putDraft(c.token(), cfg);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(r.getBody()).containsEntry("code", "INVALID_POLICY").containsEntry("pointer", "/walkIn/interleave");

        Map<String, Object> solo = copy(BookingPresets.defaults());
        solo.put("solo", true);
        sub(solo, "checkIn").put("modes", List.of("desk"));
        r = putDraft(c.token(), solo);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(r.getBody()).containsEntry("code", "INVALID_POLICY").containsEntry("rule", "solo_requires_self_checkin");

        // solo with a self check-in mode is accepted and forces the reviewer to the doctor
        sub(solo, "checkIn").put("modes", List.of("desk", "door_qr"));
        r = putDraft(c.token(), solo);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> saved = sub(sub(r.getBody(), "draft"), "config");
        assertThat(sub(sub(saved, "visitTypes"), "new")).containsEntry("reviewerRole", "doctor");
        assertThat(current(c.token()).getBody().get("published")).isNull(); // nothing published by a draft save
    }

    @Test
    void aReceptionistMayReadButNotChangeOrPublish() throws Exception {
        Clinic c = clinic();
        putDraft(c.token(), BookingPresets.defaults());
        String desk = deskToken(c.tenant());
        assertThat(current(desk).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(exchange("/v1/booking-policy/versions", HttpMethod.GET, authed(desk), Map.class).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(putDraft(desk, BookingPresets.defaults()).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(publish(desk, null).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(applyPreset(desk, "solo").getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(sub(current(c.token()).getBody(), "draft")).isNotNull(); // still the owner's draft
    }

    @Test
    void publishNeedsAStepUpAndADraft() throws Exception {
        Clinic c = clinic();
        ResponseEntity<Map> noDraft = publish(c.token(), null);
        assertThat(noDraft.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(noDraft.getBody()).containsEntry("code", "NO_DRAFT");

        putDraft(c.token(), BookingPresets.defaults());
        ResponseEntity<Map> noStepUp = exchange("/v1/booking-policy/draft/publish", HttpMethod.POST, authedJsonBody(c.token(), Map.of()), Map.class);
        assertThat(noStepUp.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(current(c.token()).getBody().get("published")).isNull();
        assertThat(publish(c.token(), "x".repeat(201)).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST); // changeNote <= 200
    }

    @Test
    void versionHistoryShowsDiffWhoAndWhen() throws Exception {
        Clinic c = clinic();
        putDraft(c.token(), withFollowUp("review", 25));
        publish(c.token(), "first");
        putDraft(c.token(), withFollowUp("auto", 40));
        publish(c.token(), "second");

        Map<String, Object> body = exchange("/v1/booking-policy/versions", HttpMethod.GET, authed(c.token()), Map.class).getBody();
        List<Map<String, Object>> items = (List<Map<String, Object>>) body.get("items");
        assertThat(items).hasSize(2);
        assertThat(items.get(0)).containsEntry("version", 2).containsEntry("changeNote", "second").containsEntry("publishedBy", c.owner().id().toString());
        assertThat(items.get(0).get("publishedAt")).isNotNull();
        assertThat(items.get(0).get("publishedByName")).isEqualTo("Test Staff");
        List<Map<String, Object>> diff = (List<Map<String, Object>>) items.get(0).get("diff");
        assertThat(diff).extracting(d -> d.get("pointer")).containsExactlyInAnyOrder("/visitTypes/followUp/acceptance", "/visitTypes/followUp/dailyCapPerDoctor");
        assertThat(diff).filteredOn(d -> d.get("pointer").equals("/visitTypes/followUp/dailyCapPerDoctor"))
                .singleElement().satisfies(d -> assertThat(d).containsEntry("before", 25).containsEntry("after", 40));
        assertThat(((List<?>) items.get(1).get("diff"))).isNotEmpty(); // v1 against nothing: every setting
        assertThat(body.get("nextCursor")).isNull();

        Map<String, Object> page1 = exchange("/v1/booking-policy/versions?limit=1", HttpMethod.GET, authed(c.token()), Map.class).getBody();
        assertThat((List<?>) page1.get("items")).hasSize(1);
        assertThat(page1.get("nextCursor")).isEqualTo("2");
        Map<String, Object> page2 = exchange("/v1/booking-policy/versions?limit=1&cursor=2", HttpMethod.GET, authed(c.token()), Map.class).getBody();
        assertThat(((List<Map<String, Object>>) page2.get("items")).get(0)).containsEntry("version", 1);
        assertThat(page2.get("nextCursor")).isNull();
    }

    @Test
    void policiesAreTenantIsolated() throws Exception {
        Clinic a = clinic();
        Clinic b = clinic();
        putDraft(a.token(), withFollowUp("review", 25));
        publish(a.token(), "a only");
        assertThat(current(b.token()).getBody().get("published")).isNull();
        assertThat(current(b.token()).getBody().get("draft")).isNull();
        assertThat(inTenantTx(b.tenant().id(), () -> jdbc.queryForObject("SELECT count(*) FROM booking_policy_versions", Integer.class))).isZero();
        assertThat(inTenantTx(a.tenant().id(), () -> jdbc.queryForObject("SELECT count(*) FROM booking_policy_versions", Integer.class))).isEqualTo(1);
        assertThat(inTenantTx(b.tenant().id(), () -> rules.rule(null, "followUp")).orElseThrow().policyVersion()).isZero(); // B still on the default
        assertThat(inTenantTx(b.tenant().id(), () -> rules.rule(null, "walkin"))).isEqualTo(Optional.empty());
    }
}
