package com.nabd.edge;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** COM-041: signals flag the phone, records wait for code + date of birth, booking lists no family, 3 failures lock for 24 h. */
class Com041ReverificationTest extends BookingTestBase {

    @Autowired ConversationEngine engine;
    @Autowired ContactLanguage languages;
    @Autowired PhoneVerifications phones;
    @Autowired TenantTx tx;
    @Autowired ObjectMapper json;

    record Clinic(UUID tenant, String hash) {
    }

    /** One doctor with free slots; household Asha (adult, date of birth on file 14-08-1988) plus the given members. */
    private Clinic clinic(String... extra) {
        UUID t = UUID.randomUUID(), d = UUID.randomUUID();
        String hash = "hash-" + UUID.randomUUID();
        UP.profiles.put(t.toString(), "{\"clinicName\":\"Clinic\",\"timeZone\":\"Asia/Kolkata\",\"doctors\":[{\"doctorId\":\"" + d + "\",\"name\":\"Dr Rao\"}]}");
        List<String> m = new CopyOnWriteArrayList<>();
        m.add(FakeUpstreams.member("ref-me", "Asha", 36, "self"));
        Collections.addAll(m, extra);
        UP.households.put(hash, m);
        UP.rawSessions.put(d.toString(), BookingFlowTest.sessions("Asia/Kolkata", 3, 6));
        UP.dobOnFile.put(hash, "1988-08-14");
        languages.save(t, "whatsapp", hash, "en");
        return new Clinic(t, hash);
    }

    private void deliver(Clinic c, String body) {
        String id = FakeUpstreams.id();
        UP.messages.put(id, body);
        engine.process(c.tenant(), id, "whatsapp", c.hash());
    }

    private void say(Clinic c, String text) {
        String id = FakeUpstreams.id();
        UP.inbound(id, c.hash(), text, null);
        engine.process(c.tenant(), id, "whatsapp", c.hash());
    }

    private void sayAs(Clinic c, String text, String waIdHash) {
        deliver(c, "{\"kind\":\"text\",\"text\":\"" + text + "\",\"contactHash\":\"" + c.hash() + "\",\"waIdHash\":\"" + waIdHash + "\"}");
    }

    private void changedNumberNotice(Clinic c) {
        deliver(c, "{\"kind\":\"system\",\"systemType\":\"user_changed_number\",\"text\":\"\",\"contactHash\":\"" + c.hash() + "\"}");
    }

    private JsonNode last() {
        return json.readTree(UP.sent.get(UP.sent.size() - 1));
    }

    private List<String> titles() {
        JsonNode m = last();
        List<String> t = new ArrayList<>();
        (m.has("rows") ? m.path("rows") : m.path("buttons")).forEach(b -> t.add(b.path("title").asString()));
        return t;
    }

    private void tap(Clinic c, String title) {
        JsonNode m = last();
        JsonNode opts = m.has("rows") ? m.path("rows") : m.path("buttons");
        int i = titles().indexOf(title);
        assertTrue(i >= 0, "no option " + title + " in " + titles());
        String id = FakeUpstreams.id();
        UP.inbound(id, c.hash(), "", opts.get(i).path("id").asString());
        engine.process(c.tenant(), id, "whatsapp", c.hash());
    }

    private void tapAt(Clinic c, int index) {
        JsonNode m = last();
        String id = FakeUpstreams.id();
        UP.inbound(id, c.hash(), "", (m.has("rows") ? m.path("rows") : m.path("buttons")).get(index).path("id").asString());
        engine.process(c.tenant(), id, "whatsapp", c.hash());
    }

    private String text() {
        return last().path("text").asString();
    }

    private String code() {
        Matcher m = Pattern.compile("Your code is (\\d{6})").matcher(text());
        assertTrue(m.find(), text());
        return m.group(1);
    }

    private String row(Clinic c, String column) {
        return superuser().query("select " + column + "::text from phone_verifications where tenant_id = ?::uuid and phone_hash = ?",
                (rs, i) -> rs.getString(1), c.tenant().toString(), c.hash()).stream().findFirst().orElse(null);
    }

    private String conversationState(Clinic c) {
        return superuser().queryForObject("select state from conversations where tenant_id = ?::uuid and contact_hash = ?", String.class, c.tenant().toString(), c.hash());
    }

    private List<String> reports(Clinic c) {
        return UP.reverifyReports.stream().filter(r -> r.startsWith(c.hash() + "|")).map(r -> r.substring(c.hash().length() + 1)).toList();
    }

    // ---- criterion 1 + 4: Meta's changed-number notice, then OTP + DOB before anything is served; success clears and is audited --

    @Test
    void afterMetaReportsAChangedNumberTheNextRecordsRequestNeedsCodeAndDateOfBirthAndSuccessClearsTheFlag() {
        Clinic c = clinic();
        say(c, "Hi");
        int sentBefore = UP.sent.size();
        changedNumberNotice(c);
        assertEquals(sentBefore, UP.sent.size(), "the system notice gets no answer");
        assertEquals("needs_reverify", row(c, "state"));
        assertEquals("changed_number", row(c, "reason"));
        assertTrue(reports(c).get(0).contains("\"step\":\"flagged\"") && reports(c).get(0).contains("\"reason\":\"changed_number\""), reports(c).toString());

        say(c, "Hi");
        tap(c, "My appointment");
        assertEquals("REVERIFY_OTP", conversationState(c));
        assertFalse(text().contains("person from the clinic"), "nothing is served before verification");
        String code = code();
        say(c, "status");                       // 'status' is a records request too: it cannot skip the check
        assertEquals("REVERIFY_OTP", conversationState(c));
        code = code();
        say(c, code);
        assertEquals("REVERIFY_DOB", conversationState(c));
        assertTrue(text().contains("date of birth"), text());
        say(c, "14-08-1988");
        assertEquals("verified", row(c, "state"));
        assertNotNull(row(c, "last_verified_at"));
        assertEquals("0", row(c, "failed_attempts"));
        String verified = reports(c).get(reports(c).size() - 1);
        assertTrue(verified.contains("\"step\":\"verified\"") && verified.contains("\"method\":\"otp+dob\""), verified);
        assertTrue(text().contains("verified"), text());
        tap(c, "My appointment");                // now served as before (reception answers until ACC-041's own screen)
        assertTrue(text().contains("person from the clinic"), text());
        // this clinic's conversation only, ids and hashes removed: other tests' rows and random ids can contain the digits by chance
        assertFalse(superuser().queryForObject("select string_agg(regexp_replace(c::text, '[0-9a-f]{8}-[0-9a-f-]{27}|[0-9a-f]{32,}', '', 'g'), ' ') "
                        + "from conversations c where tenant_id = ?::uuid and contact_hash = ?", String.class, c.tenant().toString(), c.hash()).contains("1988"),
                "the date of birth is never stored");
    }

    // ---- signals (integration: signals set flag) ----------------------------------------------------------------------------

    @Test
    void aDifferentWhatsAppUserIdOnTheSameNumberFlagsIt() {
        Clinic c = clinic();
        sayAs(c, "Hi", "wa-1");
        sayAs(c, "Hi", "wa-1");
        assertEquals("verified", row(c, "state"));
        sayAs(c, "Hi", "wa-2");
        assertEquals("needs_reverify", row(c, "state"));
        assertEquals("wa_id_changed", row(c, "reason"));
    }

    @Test
    void theFirstMessageAfter180DaysOfSilenceFlagsIt() {
        Clinic c = clinic();
        say(c, "Hi");
        superuser().update("update phone_verifications set last_seen_at = now() - interval '179 days' where phone_hash = ?", c.hash());
        say(c, "Hi");
        assertEquals("verified", row(c, "state"));
        superuser().update("update phone_verifications set last_seen_at = now() - interval '181 days' where phone_hash = ?", c.hash());
        say(c, "Hi");
        assertEquals("needs_reverify", row(c, "state"));
        assertEquals("silence", row(c, "reason"));
    }

    @Test
    void aNumberChangeTheDeskRecordedInCoreFlagsItWithoutReportingItBack() {
        Clinic c = clinic(FakeUpstreams.member("ref-kid", "Kiran", 5, "dependent"));
        UP.reverifyState.put(c.hash(), "needs_reverify");
        say(c, "Hi");
        tap(c, "Book");
        assertEquals(List.of("Me", "Someone else"), titles());
        assertEquals("desk_change", row(c, "reason"));
        assertTrue(reports(c).isEmpty(), "Core raised it and audited it itself");
    }

    // ---- criterion 2: booking works while pending, no family names (security: no family names before verification) -----------

    @Test
    void whileReverificationIsPendingBookingWorksButNoFamilyMemberIsListedOrAdded() {
        Clinic c = clinic(FakeUpstreams.member("ref-aarav", "Aarav", 6, "dependent"), FakeUpstreams.member("ref-meera", "Meera", 4, "dependent"));
        changedNumberNotice(c);
        int before = UP.sent.size();
        say(c, "Hi");
        tap(c, "Book");
        assertEquals(List.of("Me", "Someone else"), titles());
        tap(c, "Me");
        tapAt(c, 0);
        tapAt(c, 0);
        tap(c, "Confirm");
        assertTrue(text().contains("confirmed"), text());
        assertEquals(1, UP.bookCalls.size());
        assertTrue(UP.bookCalls.get(0).contains("\"patientRef\":\"ref-me\""));
        assertTrue(UP.sent.subList(before, UP.sent.size()).stream().noneMatch(s -> s.contains("Aarav") || s.contains("Meera")), "no family names");
        assertEquals("needs_reverify", row(c, "state"), "booking does not verify the number");
    }

    // ---- criterion 3: 3 failures lock for 24 h, desk alerted and audited; then booking still works ------------------------------

    @Test
    void threeFailedAttemptsLockRecordsFor24HoursAndTellCoreToAlertTheDeskAndAudit() {
        Clinic c = clinic(FakeUpstreams.member("ref-kid", "Kiran", 5, "dependent"));
        changedNumberNotice(c);
        say(c, "Hi");
        tap(c, "My appointment");
        String code = code();
        say(c, "000000".equals(code) ? "111111" : "000000");        // 1: wrong code
        assertTrue(text().contains("not right"), text());
        say(c, code);                                                // the right code still works after one miss
        say(c, "01-01-1970");                                        // 2: wrong date of birth
        assertTrue(text().contains("does not match"), text());
        say(c, "not a date");                                        // unreadable: asked again, not counted
        assertEquals("2", row(c, "failed_attempts"));
        say(c, "02-02-1972");                                        // 3: locked
        assertEquals(Reverification.LOCKED_TEXT, text());
        assertEquals("locked", row(c, "state"));
        Long hours = superuser().queryForObject("select round(extract(epoch from locked_until - now()) / 3600) from phone_verifications where phone_hash = ?", Long.class, c.hash());
        assertEquals(24L, hours);
        List<String> r = reports(c);
        assertTrue(r.get(r.size() - 3).contains("\"step\":\"failed\"") && r.get(r.size() - 3).contains("\"attempt\":1"), r.toString());
        assertTrue(r.get(r.size() - 2).contains("\"attempt\":2"), r.toString());
        assertTrue(r.get(r.size() - 1).contains("\"step\":\"locked\"") && r.get(r.size() - 1).contains("\"attempt\":3")
                && r.get(r.size() - 1).contains("\"lockedUntil\""), r.toString());

        say(c, "status");
        assertEquals(Reverification.LOCKED_TEXT, text(), "records stay locked");
        say(c, "Hi");
        tap(c, "Book");
        assertEquals(List.of("Me", "Someone else"), titles(), "booking still works while locked, without family names");

        superuser().update("update phone_verifications set locked_until = now() - interval '1 second' where phone_hash = ?", c.hash());
        say(c, "status");
        assertEquals("REVERIFY_OTP", conversationState(c), "after 24 h the number can try again");
        assertEquals("0", row(c, "failed_attempts"));
    }

    @Test
    void aFailedAttemptReRunAfterAVersionConflictCountsOnce() {
        Clinic c = clinic();
        changedNumberNotice(c);
        phones.fail(c.tenant(), c.hash(), "msg-1");
        phones.fail(c.tenant(), c.hash(), "msg-1");
        assertEquals("1", row(c, "failed_attempts"));
        assertEquals(1, reports(c).stream().filter(r -> r.contains("\"step\":\"failed\"")).count());
    }

    @Test
    void phoneVerificationsAreTenantIsolated() {
        Clinic a = clinic();
        changedNumberNotice(a);
        UUID other = UUID.randomUUID();
        assertEquals(0, tx.<Integer>run(other, (j, s) -> j.queryForObject("select count(*) from phone_verifications where phone_hash = ?", Integer.class, a.hash())).intValue());
        assertEquals(1, tx.<Integer>run(a.tenant(), (j, s) -> j.queryForObject("select count(*) from phone_verifications where phone_hash = ?", Integer.class, a.hash())).intValue());
    }
}
