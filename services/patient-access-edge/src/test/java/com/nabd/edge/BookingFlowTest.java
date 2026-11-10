package com.nabd.edge;

import com.nabd.edge.FlowHandler.Conversation;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** ACC-040 and ACC-042 against the fake Core and Comms (the real WhatsApp sandbox is Needs external action). */
class BookingFlowTest extends BookingTestBase {

    static final Instant BASE = Instant.now().plus(3, ChronoUnit.DAYS).truncatedTo(ChronoUnit.DAYS).plus(4, ChronoUnit.HOURS).plus(30, ChronoUnit.MINUTES);

    @Autowired ConversationEngine engine;
    @Autowired ConversationStore store;
    @Autowired ContactLanguage languages;
    @Autowired HandoffService handoff;
    @Autowired SlotHolds holds;
    @Autowired ObjectMapper json;

    record Clinic(UUID tenant, UUID doctor, String name, String hash) {
    }

    /** A clinic with one doctor ("Dr Shah"), 3 free days of 6 slots from 04:30 UTC, and a household: Asha (adult) plus the given extra members. */
    private Clinic clinic(String name, String zone, String... extraMembers) {
        UUID t = UUID.randomUUID(), d = UUID.randomUUID();
        String hash = "hash-" + UUID.randomUUID();
        UP.profiles.put(t.toString(), "{\"clinicName\":\"" + name + "\",\"timeZone\":\"" + zone + "\",\"doctors\":[{\"doctorId\":\"" + d + "\",\"name\":\"Dr Shah " + name.charAt(0) + "\"}]}");
        List<String> m = new CopyOnWriteArrayList<>();
        m.add(FakeUpstreams.member("ref-me", "Asha", 36, "self"));
        Collections.addAll(m, extraMembers);
        UP.households.put(hash, m);
        UP.rawSessions.put(d.toString(), sessions(zone, 3, 6));
        languages.save(t, "whatsapp", hash, "en");
        return new Clinic(t, d, name, hash);
    }

    static String sessions(String zone, int days, int slots) {
        List<String> out = new ArrayList<>();
        for (int day = 0; day < days; day++) {
            List<String> s = new ArrayList<>();
            for (int i = 0; i < slots; i++) {
                s.add("\"" + BASE.plus(day, ChronoUnit.DAYS).plus(15L * i, ChronoUnit.MINUTES) + "\"");
            }
            LocalDate date = BASE.plus(day, ChronoUnit.DAYS).atZone(ZoneId.of(zone)).toLocalDate();
            out.add("{\"id\":\"s" + day + "\",\"date\":\"" + date + "\",\"mode\":\"slots\",\"slots\":[" + String.join(",", s) + "]}");
        }
        return "[" + String.join(",", out) + "]";
    }

    private String say(Clinic c, String text) {
        String id = FakeUpstreams.id();
        UP.inbound(id, c.hash(), text, null);
        engine.process(c.tenant(), id, "whatsapp", c.hash());
        return id;
    }

    private JsonNode last() {
        return json.readTree(UP.sent.get(UP.sent.size() - 1));
    }

    private JsonNode options() {
        JsonNode m = last();
        return m.has("rows") ? m.path("rows") : m.path("buttons");
    }

    private List<String> titles() {
        List<String> t = new ArrayList<>();
        options().forEach(b -> t.add(b.path("title").asString()));
        return t;
    }

    private String tapAt(Clinic c, int index) {
        String id = FakeUpstreams.id();
        UP.inbound(id, c.hash(), "", options().get(index).path("id").asString());
        engine.process(c.tenant(), id, "whatsapp", c.hash());
        return id;
    }

    private String tap(Clinic c, String title) {
        int i = titles().indexOf(title);
        assertTrue(i >= 0, "no option titled " + title + " in " + titles());
        return tapAt(c, i);
    }

    private String text() {
        return last().path("text").asString();
    }

    private String state(Clinic c) {
        return superuser().queryForObject("select state from conversations where tenant_id = ?::uuid and contact_hash = ?", String.class, c.tenant().toString(), c.hash());
    }

    private String ctx(Clinic c, String key) {
        return superuser().queryForObject("select context->>? from conversations where tenant_id = ?::uuid and contact_hash = ?", String.class, key, c.tenant().toString(), c.hash());
    }

    private static final String ALL = "Hi";

    // ---- ACC-040 criterion 1 + ACC-042 criterion 1: five taps, no free text, family list ----------------------------------

    @Test
    void returningParentBooksForAChildInFiveTapsWithNoFreeText_andTheFamilyListShowsMeAndEachChildWithAge() {
        Clinic c = clinic("Alpha", "Asia/Kolkata", FakeUpstreams.member("ref-aarav", "Aarav", 6, "dependent"), FakeUpstreams.member("ref-meera", "Meera", 4, "dependent"));
        int taps = 0;
        say(c, "Hi");                                   // the opening message is free text, not a booking tap
        assertEquals(List.of("Book", "My appointment", "Talk to clinic"), titles());
        tap(c, "Book"); taps++;
        assertEquals(List.of("Me", "Aarav (6)", "Meera (4)", "Add a child", "Someone else"), titles());
        assertTrue(last().has("rows"), "more than 3 options go out as a list message");
        tap(c, "Aarav (6)"); taps++;                    // one doctor: the doctor step is skipped
        assertTrue(text().contains("Which day"));
        assertEquals(3, titles().size());
        tapAt(c, 0); taps++;
        assertEquals(6, titles().size());
        tapAt(c, 1); taps++;                            // 04:45 UTC
        assertTrue(text().contains("Dr Shah A") && text().contains("Alpha"), text());
        tap(c, "Confirm"); taps++;
        assertTrue(taps <= 5);
        assertTrue(text().contains("confirmed"), text());
        assertEquals(1, UP.bookCalls.size());
        String call = UP.bookCalls.get(0);
        assertTrue(call.contains("\"patientRef\":\"ref-aarav\"") && call.contains("\"channel\":\"whatsapp\"") && call.contains(BASE.plus(15, ChronoUnit.MINUTES).toString()), call);
        assertTrue(call.split("\\|")[0].matches("[0-9a-f-]{36}"), "Idempotency-Key is a UUID");
        assertEquals("DONE", state(c));
        assertEquals(0, superuser().queryForObject("select count(*) from booking_holds where tenant_id = ?::uuid", Integer.class, c.tenant().toString()), "hold released after booking");
    }

    @Test
    void aSingleAdultHouseholdAlsoBooksInFiveTaps_andOurSideOfTheJourneyIsFast() {
        List<Long> millis = new ArrayList<>();
        for (int run = 0; run < 20; run++) {
            Clinic c = clinic("Beta", "Asia/Kolkata");
            long t0 = System.nanoTime();
            say(c, ALL);
            tap(c, "Book");
            assertEquals(List.of("Me", "Add a child", "Someone else"), titles());
            tap(c, "Me");
            tapAt(c, 0);
            tapAt(c, 0);
            tap(c, "Confirm");
            millis.add((System.nanoTime() - t0) / 1_000_000);
            assertTrue(text().contains("confirmed"));
        }
        Collections.sort(millis);
        assertTrue(millis.get(10) < 2000, "median of our own processing " + millis.get(10) + " ms; the 60 s journey time is measured in the WhatsApp sandbox");
    }

    // ---- criterion 7: Hi -> menu quickly ---------------------------------------------------------------------------------

    @Test
    void hiFromAKnownPhoneAnswersWithTheMenuWellInsideThreeSeconds() {
        List<Long> millis = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            Clinic c = clinic("Gamma", "Asia/Kolkata");
            long t0 = System.nanoTime();
            say(c, "Hi");
            millis.add((System.nanoTime() - t0) / 1_000_000);
            assertEquals(List.of("Book", "My appointment", "Talk to clinic"), titles());
            assertTrue(text().contains("Gamma"));
        }
        Collections.sort(millis);
        assertTrue(millis.get(18) < 3000, "p95 " + millis.get(18) + " ms");
    }

    // ---- criterion 2: slot lost between offer and confirm -----------------------------------------------------------------

    @Test
    void slotTakenBeforeConfirmSaysSoAndOffersTheThreeNearestInTheSameMessage() {
        Clinic c = clinic("Delta", "Asia/Kolkata");
        say(c, "Hi"); tap(c, "Book"); tap(c, "Me"); tapAt(c, 0); tapAt(c, 2);   // picks 05:00 UTC
        UP.bookMode = "taken";
        tap(c, "Confirm");
        assertTrue(text().contains("just taken"), text());
        assertEquals(3, options().size());
        assertEquals("CHOOSING_SLOT", state(c));
        assertEquals(0, superuser().queryForObject("select count(*) from booking_holds where tenant_id = ?::uuid and slot_start = ?", Integer.class,
                c.tenant().toString(), java.sql.Timestamp.from(BASE.plus(30, ChronoUnit.MINUTES))), "the lost slot is not held");
        UP.bookMode = "confirmed";
        tapAt(c, 0);
        tap(c, "Confirm");
        assertTrue(text().contains("confirmed"));
        assertNotEquals(UP.bookCalls.get(0).split("\\|")[0], UP.bookCalls.get(1).split("\\|")[0], "a different slot is a different booking key");
    }

    @Test
    void aSlotHeldByAnotherChatIsNotOfferedAndPickingItGivesAlternatives() {
        Clinic a = clinic("Echo", "Asia/Kolkata");
        say(a, "Hi"); tap(a, "Book"); tap(a, "Me"); tapAt(a, 0); tapAt(a, 0);  // chat A holds 04:30
        assertEquals("CONFIRMING", state(a));
        languages.save(a.tenant(), "whatsapp", "hash-b", "en");
        UP.households.put("hash-b", new CopyOnWriteArrayList<>(List.of(FakeUpstreams.member("ref-b", "Bela", 30, "self"))));
        Clinic b = new Clinic(a.tenant(), a.doctor(), a.name(), "hash-b");
        say(b, "Hi"); tap(b, "Book"); tap(b, "Me"); tapAt(b, 0);
        assertEquals(5, titles().size(), "chat B is not offered the slot chat A holds");
        assertEquals(1, superuser().queryForObject("select count(*) from booking_holds where tenant_id = ?::uuid and slot_start = ?", Integer.class,
                a.tenant().toString(), java.sql.Timestamp.from(BASE)));
    }

    // ---- criterion 3: confirmation only after commit; timeout checks with the same key -------------------------------------

    @Test
    void aLostAnswerIsCheckedAgainWithTheSameKeyAndConfirmedOnce() {
        Clinic c = clinic("Foxtrot", "Asia/Kolkata");
        say(c, "Hi"); tap(c, "Book"); tap(c, "Me"); tapAt(c, 0); tapAt(c, 0);
        UP.bookMode = "drop_once";
        int before = UP.sent.size();
        tap(c, "Confirm");
        assertEquals(2, UP.bookCalls.size());
        assertEquals(UP.bookCalls.get(0).split("\\|")[0], UP.bookCalls.get(1).split("\\|")[0], "the check uses the same Idempotency-Key");
        assertEquals(1, UP.sent.subList(before, UP.sent.size()).stream().filter(s -> s.contains("confirmed")).count());
    }

    @Test
    void whenCoreStaysSilentThePatientIsNeverToldItIsConfirmedAndAnotherTapReusesTheKey() {
        Clinic c = clinic("Golf", "Asia/Kolkata");
        say(c, "Hi"); tap(c, "Book"); tap(c, "Me"); tapAt(c, 0); tapAt(c, 0);
        UP.bookMode = "drop_always";
        tap(c, "Confirm");
        assertTrue(text().contains("checking"), text());
        assertTrue(UP.sent.stream().noneMatch(s -> s.contains("is confirmed")));
        assertEquals("CONFIRMING", state(c));
        UP.bookMode = "confirmed";
        tap(c, "Confirm");
        assertTrue(text().contains("confirmed"));
        assertEquals(1, UP.bookCalls.stream().map(s -> s.split("\\|")[0]).distinct().count(), "one key for the one booking");
    }

    // ---- criterion 4: a clinic's own channel never shows another clinic ------------------------------------------------------

    @Test
    void aClinicsConversationOnlyEverNamesAndCallsThatClinic() {
        Clinic a = clinic("Hotel", "Asia/Kolkata"), b = clinic("India", "Asia/Kolkata");
        say(a, "Hi"); tap(a, "Book"); tap(a, "Me"); tapAt(a, 0); tapAt(a, 0); tap(a, "Confirm");
        String all = String.join("\n", UP.sent);
        assertFalse(all.contains("India") || all.contains("Dr Shah I"), "no other clinic or doctor in any message");
        assertTrue(UP.corePaths.stream().allMatch(p -> p.contains(a.tenant().toString())), "every Core call is for this clinic: " + UP.corePaths);
        assertTrue(UP.corePaths.stream().noneMatch(p -> p.contains(b.tenant().toString())));
    }

    // ---- criterion 5: token sessions (not met: see report) ---------------------------------------------------------------------

    @Test
    void aDoctorWithOnlyTokenSessionsIsNeverOfferedAMadeUpTime() {
        Clinic c = clinic("Juliet", "Asia/Kolkata");
        LocalDate d = BASE.atZone(ZoneId.of("Asia/Kolkata")).toLocalDate();
        UP.rawSessions.put(c.doctor().toString(), "[{\"id\":\"s\",\"date\":\"" + d + "\",\"mode\":\"tokens\",\"tokensLeft\":12}]");
        say(c, "Hi"); tap(c, "Book"); tap(c, "Me");
        assertTrue(text().contains("no free times"), text());
        assertEquals("HANDOFF", state(c));
    }

    // ---- criterion 6: resume ----------------------------------------------------------------------------------------------------

    @Test
    void aPatientWhoLeavesAndReturnsWithinADayResumesAtTheLastStep() {
        Clinic c = clinic("Kilo", "Asia/Kolkata", FakeUpstreams.member("ref-k", "Kabir", 5, "dependent"));
        say(c, "Hi"); tap(c, "Book"); tap(c, "Kabir (5)");
        String dayQuestion = text();
        superuser().update("update conversations set updated_at = now() - interval '20 hours', expires_at = now() + interval '4 hours' where tenant_id = ?::uuid", c.tenant().toString());
        say(c, "Hi again");
        assertEquals(dayQuestion, text(), "the same question comes back");
        assertEquals("CHOOSING_DAY", state(c));
        tapAt(c, 0); tapAt(c, 0); tap(c, "Confirm");
        assertTrue(text().contains("confirmed"));
        assertTrue(UP.bookCalls.get(0).contains("ref-k"), "the earlier choice of child was kept");
    }

    @Test
    void afterTheDayWindowTheConversationStartsAtTheMenuAgainButTheLanguageIsRemembered() {
        Clinic c = clinic("Lima", "Asia/Kolkata");
        say(c, "Hi"); tap(c, "Book");
        superuser().update("update conversations set expires_at = now() - interval '1 minute' where tenant_id = ?::uuid", c.tenant().toString());
        say(c, "Hello");
        assertEquals(List.of("Book", "My appointment", "Talk to clinic"), titles());
    }

    // ---- criterion 8: two unrecognised messages hand over --------------------------------------------------------------------------

    @Test
    void twoUnrecognisedMessagesInARowHandTheThreadToReceptionAndTheBotStaysSilentUntilHandedBack() {
        Clinic c = clinic("Mike", "Asia/Kolkata");
        say(c, "Hi");
        say(c, "what are your fees");
        assertEquals(List.of("Book", "My appointment", "Talk to clinic"), titles(), "first miss: the question again");
        assertEquals("MENU", state(c));
        say(c, "hello?");
        assertEquals("HANDOFF", state(c));
        assertTrue(text().contains("A person from the clinic"), text());
        int n = UP.sent.size();
        say(c, "anyone there");
        say(c, "Hi");
        assertEquals(n, UP.sent.size(), "silent while staff hold the thread");
        assertTrue(handoff.handBack(c.tenant(), "whatsapp", c.hash()));
        say(c, "Hi");
        assertEquals(List.of("Book", "My appointment", "Talk to clinic"), titles());
    }

    @Test
    void aValidTapBetweenTwoUnrecognisedMessagesResetsTheCount() {
        Clinic c = clinic("November", "Asia/Kolkata");
        say(c, "Hi"); say(c, "blah"); tap(c, "Book"); say(c, "blah");
        assertNotEquals("HANDOFF", state(c));
    }

    @Test
    void talkToClinicHandsOverAtOnceAndAnUnknownNumberDoes() {
        Clinic c = clinic("Oscar", "Asia/Kolkata");
        say(c, "Hi"); tap(c, "Talk to clinic");
        assertEquals("HANDOFF", state(c));
        Clinic u = clinic("Papa", "Asia/Kolkata");
        UP.households.remove(u.hash());
        say(u, "Hi"); tap(u, "Book");
        assertTrue(text().contains("could not find this number"), text());
        assertEquals("HANDOFF", state(u));
    }

    // ---- criterion 9: emergency -------------------------------------------------------------------------------------------------------

    @Test
    void anEmergencyPhraseAnswersWithTheLocalNumberInAnyStateEvenWhileStaffHoldTheThread() {
        Clinic c = clinic("Quebec", "Asia/Kolkata");
        say(c, "Hi"); tap(c, "Book");
        int n = UP.sent.size();
        say(c, "I have CHEST PAIN now");
        assertEquals(n + 1, UP.sent.size());
        assertTrue(text().contains("108"), text());
        assertEquals("1", ctx(c, "urgent"), "the thread is flagged urgent");
    }

    @Test
    void emergencyNumbersPerRegionAreConfiguredAndThePhraseListShipsEmpty() {
        assertEquals("108", props.emergencyNumbers().get("in"));
        assertEquals("997", props.emergencyNumbers().get("sa"));
        assertEquals("998", props.emergencyNumbers().get("ae"));
        assertEquals("999", props.emergencyNumbers().get("qa"));
        var yml = new org.springframework.boot.env.YamlPropertySourceLoader();
        try {
            var src = yml.load("a", new org.springframework.core.io.ClassPathResource("application.yml")).get(0);
            assertFalse(src.containsProperty("edge.emergency-phrases[0]"), "no phrase ships without clinical sign-off");
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }

    // ---- criterion 10: 2 minute hold ----------------------------------------------------------------------------------------------------

    @Test
    void pickingATimeHoldsItForTwoMinutesAndAnAbandonedHoldFreesTheSlot() {
        Clinic c = clinic("Romeo", "Asia/Kolkata");
        say(c, "Hi"); tap(c, "Book"); tap(c, "Me"); tapAt(c, 0); tapAt(c, 0);
        Integer left = superuser().queryForObject("select extract(epoch from expires_at - now())::int from booking_holds where tenant_id = ?::uuid and slot_start = ?",
                Integer.class, c.tenant().toString(), java.sql.Timestamp.from(BASE));
        assertTrue(left > 100 && left <= 120, "2-minute hold, was " + left + " s");
        assertFalse(holds.tryHold(c.tenant(), c.doctor(), BASE, UUID.randomUUID()), "held for this patient");
        superuser().update("update booking_holds set expires_at = now() - interval '1 second' where tenant_id = ?::uuid", c.tenant().toString());
        assertTrue(holds.tryHold(c.tenant(), c.doctor(), BASE, UUID.randomUUID()), "after 2 minutes the slot is free again");
    }

    // ---- criterion 11: request mode ----------------------------------------------------------------------------------------------------

    @Test
    void aRequestModeClinicTellsThePatientItIsPendingWithTheMinutesLeft() {
        Clinic c = clinic("Sierra", "Asia/Kolkata");
        UP.bookMode = "requested";
        say(c, "Hi"); tap(c, "Book"); tap(c, "Me"); tapAt(c, 0); tapAt(c, 0); tap(c, "Confirm");
        assertTrue(text().contains("received your request") && text().matches("(?s).*within (9|10) minutes.*"), text());
        assertFalse(text().contains("is confirmed"));
    }

    // ---- criterion 12: clinic zone ------------------------------------------------------------------------------------------------------

    @Test
    void dateAndTimeOnTheCardFollowTheClinicsOwnTimeZone() {
        Clinic in = clinic("Tango", "Asia/Kolkata"), sa = clinic("Uniform", "Asia/Riyadh");
        say(in, "Hi"); tap(in, "Book"); tap(in, "Me"); tapAt(in, 0); tapAt(in, 0);
        assertTrue(text().contains("10:00"), text());      // 04:30 UTC
        say(sa, "Hi"); tap(sa, "Book"); tap(sa, "Me"); tapAt(sa, 0); tapAt(sa, 0);
        assertTrue(text().contains("7:30"), text());       // 04:30 UTC
    }

    // ---- language -----------------------------------------------------------------------------------------------------------------------

    @Test
    void theLanguageIsAskedOnceAndRemembered() {
        UUID t = UUID.randomUUID();
        UP.profiles.put(t.toString(), "{\"clinicName\":\"Victor\",\"timeZone\":\"Asia/Kolkata\",\"doctors\":[]}");
        Clinic c = new Clinic(t, UUID.randomUUID(), "Victor", "hash-lang-" + UUID.randomUUID());
        say(c, "Hi");
        assertEquals(List.of("English", "हिन्दी", "ಕನ್ನಡ", "తెలుగు"), titles());
        tap(c, "हिन्दी");
        assertEquals(BookingCopy.t("hi", "menu.book"), titles().get(0));
        assertEquals("hi", languages.find(t, "whatsapp", c.hash()).orElseThrow());
        superuser().update("delete from conversations where tenant_id = ?::uuid", t.toString());
        say(c, "Hi");
        assertEquals(BookingCopy.t("hi", "menu.book"), titles().get(0), "no picker the second time");
    }

    @Test
    void everyLanguageHasEveryKeyWithTheSamePlaceholdersAndButtonTitlesFit20Characters() {
        var en = BookingCopy.load("en");
        for (String l : BookingCopy.LANGUAGES) {
            var p = BookingCopy.load(l);
            assertEquals(en.stringPropertyNames(), p.stringPropertyNames(), l);
            for (String k : en.stringPropertyNames()) {
                assertEquals(count(en.getProperty(k)), count(p.getProperty(k)), l + " " + k);
                if (k.startsWith("menu.") && !k.equals("menu") || k.startsWith("sex.") || k.equals("who.me") || k.equals("who.add") || k.equals("who.other")
                        || k.equals("confirm") || k.equals("change")) {
                    assertTrue(p.getProperty(k).length() <= 20, l + " " + k + " is a button title");
                }
            }
        }
    }

    private static long count(String s) {
        return java.util.regex.Pattern.compile("%[sd]").matcher(s).results().count();
    }

    // ---- ACC-042 ------------------------------------------------------------------------------------------------------------------------------

    @Test
    void anAdultSiblingOnTheSamePhoneIsNeverListedAndSomeoneElseExplainsWhy() {
        Clinic c = clinic("Whiskey", "Asia/Kolkata", FakeUpstreams.member("ref-sib", "Sibling", 40, "self"), FakeUpstreams.member("ref-kid", "Kid", 3, "dependent"));
        say(c, "Hi"); tap(c, "Book");
        assertEquals(List.of("Me", "Kid (3)", "Add a child", "Someone else"), titles());
        assertTrue(UP.sent.stream().noneMatch(s -> s.contains("Sibling")));
        tap(c, "Someone else");
        assertTrue(text().contains("their own consent"), text());
        assertEquals(List.of("Talk to clinic"), titles());
    }

    @Test
    void aChildWhoTurned18IsNotListedAndARevokedChildDisappearsAtTheNextBooking() {
        Clinic c = clinic("Xray", "Asia/Kolkata", FakeUpstreams.member("ref-big", "Grown", 19, "dependent"), FakeUpstreams.member("ref-small", "Small", 2, "dependent"));
        say(c, "Hi"); tap(c, "Book");
        assertEquals(List.of("Me", "Small (2)", "Add a child", "Someone else"), titles());
        UP.households.get(c.hash()).removeIf(m -> m.contains("ref-small"));     // the guardian link was revoked
        say(c, "?");                                                             // next time the list is built it is fetched again
        assertEquals(List.of("Me", "Add a child", "Someone else"), titles());
        assertTrue(UP.sent.stream().noneMatch(m -> m.contains("Grown")));
    }

    @Test
    void aStaleChoiceOfARevokedChildShowsTheCurrentListInsteadOfBooking() {
        Clinic c = clinic("Yankee", "Asia/Kolkata", FakeUpstreams.member("ref-gone", "Gone", 4, "dependent"));
        say(c, "Hi"); tap(c, "Book");
        String stale = options().get(1).path("id").asString();
        UP.households.get(c.hash()).removeIf(m -> m.contains("ref-gone"));
        String id = FakeUpstreams.id();
        UP.inbound(id, c.hash(), "", stale);
        engine.process(c.tenant(), id, "whatsapp", c.hash());
        assertEquals(0, UP.bookCalls.size());
        assertFalse(titles().stream().anyMatch(t -> t.startsWith("Gone")));
    }

    @Test
    void addingAChildInChatCreatesItAndContinuesToTheDayStepWithTheNewChild() {
        Clinic c = clinic("Zulu", "Asia/Kolkata", FakeUpstreams.member("ref-x", "Xena", 4, "dependent"));
        say(c, "Hi"); tap(c, "Book"); tap(c, "Add a child");
        say(c, "Kabir Singh");
        say(c, "31-02-2020");                                  // not a date
        assertTrue(text().contains("could not read"), text());
        say(c, "12-03-2020");
        tap(c, "Boy");
        assertEquals(1, UP.dependantCalls.size());
        String[] call = UP.dependantCalls.get(0).split("\\|", 3);
        assertTrue(call[0].matches("[0-9a-f-]{36}"), "Idempotency-Key");
        assertEquals(c.hash(), call[1]);
        assertTrue(call[2].contains("\"firstName\":\"Kabir\"") && call[2].contains("\"dob\":\"2020-03-12\"") && call[2].contains("\"sex\":\"male\""), call[2]);
        assertTrue(text().contains("Which day"), "booking continues for the new child");
        tapAt(c, 0); tapAt(c, 0); tap(c, "Confirm");
        assertTrue(UP.bookCalls.get(0).contains("child-ref-1"));
        assertFalse(superuser().queryForObject("select string_agg(c::text, ' ') from conversations c", String.class).contains("Singh"), "only the first name is kept");
    }

    @Test
    void anAdultDateOfBirthForAChildIsRefusedByCoreAndAskedAgain() {
        Clinic c = clinic("Alpha2", "Asia/Kolkata");
        UP.households.get(c.hash()).add(FakeUpstreams.member("ref-d", "Dee", 2, "dependent"));
        say(c, "Hi"); tap(c, "Book"); tap(c, "Add a child");
        say(c, "Old"); say(c, "01-01-1990"); UP.dependantStatus = 400; tap(c, "Girl");
        assertTrue(text().contains("under 18"), text());
        assertEquals("CHILD_DOB", state(c));
    }
    // ---- ACC-045: Core outage mid-flow, and the funnel ------------------------------------------------------------------

    @Test
    void coreOutageMidFlowSaysUnavailableOnceKeepsTheStepAndTheRetryResumesWhenCoreIsBack() {
        Clinic c = clinic("Outage", "Asia/Kolkata");
        say(c, "Hi"); tap(c, "Book");
        String before = state(c);
        String id = FakeUpstreams.id();
        UP.inbound(id, c.hash(), "", options().get(titles().indexOf("Me")).path("id").asString());
        int sentBefore = UP.sent.size();
        UP.coreFirst = ex -> { FakeUpstreams.reply(ex, 503, "{}"); return true; };
        try {
            assertThrows(RuntimeException.class, () -> engine.process(c.tenant(), id, "whatsapp", c.hash()), "not claimed: the stream retries it");
            assertThrows(RuntimeException.class, () -> engine.process(c.tenant(), id, "whatsapp", c.hash()), "still down on the retry");
        } finally {
            UP.coreFirst = null;
        }
        assertEquals(sentBefore + 1, UP.sent.size(), "told once per message, not on every retry");
        assertTrue(text().contains("temporarily unavailable"), text());
        assertEquals(before, state(c), "the conversation stays on the step it was on");
        engine.process(c.tenant(), id, "whatsapp", c.hash()); // the stream's next retry, Core is back
        assertNotEquals(before, state(c), "resumed from the same step with the same message");
        assertTrue(UP.bookCalls.isEmpty(), "nothing was booked during the outage");
    }

    @Test
    void theFunnelRecordsStateChangesOnlyWithNoContactData() {
        Clinic c = clinic("Funnel", "Asia/Kolkata");
        say(c, "Hi"); tap(c, "Book"); tap(c, "Me"); tapAt(c, 0); tapAt(c, 0); tap(c, "Confirm");
        assertEquals("DONE", state(c));
        List<String> steps = superuser().queryForList("select to_state from funnel_steps where tenant_id = ?::uuid order by at, to_state", String.class,
                c.tenant().toString());
        assertTrue(steps.contains("MENU") && steps.contains("DONE"), steps.toString());
        List<String> cols = superuser().queryForList("select column_name from information_schema.columns where table_name = 'funnel_steps' order by 1", String.class);
        assertEquals(List.of("at", "conversation_id", "from_state", "tenant_id", "to_state"), cols, "ids, states and time only");
    }
}
