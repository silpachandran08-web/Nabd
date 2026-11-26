package com.nabd.edge;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * COM-050: Menu -> My records. The list shows a type and a date per row and nothing else, a tap answers with clinic name, type, date
 * and link only, a guardian gets one button per person Core lists (and nobody Core does not list), a flagged phone is re-verified
 * before any record call, and whatever Core sends cannot put other text into a row or the link message.
 */
class Com050RecordsFlowTest extends BookingTestBase {

    private static final Pattern ITEMS = Pattern.compile("GET /patients/([^/]+)/record-items\\?(.*)");
    private static final Pattern ITEM_REF = Pattern.compile("\"itemRef\":\"([^\"]+)\"");

    @Autowired ConversationEngine engine;
    @Autowired ContactLanguage languages;
    @Autowired ButtonTokens buttons;
    @Autowired ObjectMapper json;

    /** The fake Core's records side: who a phone may see, each person's items, and the answer to a link request per item reference. */
    private final Map<String, String> subjects = new ConcurrentHashMap<>();
    private final Map<String, String> items = new ConcurrentHashMap<>();
    private final Map<String, String[]> links = new ConcurrentHashMap<>();

    record Clinic(UUID tenant, String hash, String name) {
    }

    @org.junit.jupiter.api.BeforeEach
    void resetRecords() {
        subjects.clear();
        items.clear();
        links.clear();
    }

    private Clinic clinic(String name, String... household) {
        UUID t = UUID.randomUUID();
        String hash = "c50-" + UUID.randomUUID();
        UP.profiles.put(t.toString(), "{\"clinicName\":\"" + name + "\",\"timeZone\":\"Asia/Kolkata\",\"doctors\":[]}");
        List<String> m = new CopyOnWriteArrayList<>(List.of(FakeUpstreams.member("ref-me", "Asha", 36, "self")));
        m.addAll(List.of(household));
        UP.households.put(hash, m);
        UP.dobOnFile.put(hash, "1988-08-14");
        languages.save(t, "whatsapp", hash, "en");
        subjects.put(hash, "[" + subject("ref-me", "Asha", "self") + "]");
        return new Clinic(t, hash, name);
    }

    private void scriptCore() {
        UP.coreHook = (line, body) -> {
            Matcher who = Pattern.compile("GET /households/([^/]+)/record-subjects").matcher(line);
            if (who.matches()) {
                return new String[]{"200", "{\"subjects\":" + subjects.getOrDefault(who.group(1), "[]") + "}"};
            }
            Matcher list = ITEMS.matcher(line);
            if (list.matches()) {
                String rows = items.get(list.group(1));
                return rows == null ? new String[]{"403", "{\"type\":\"NOT_IN_PROXY_SCOPE\"}"} : new String[]{rows.startsWith("5") ? rows : "200", rows.startsWith("5") ? "{}" : "{\"items\":" + rows + "}"};
            }
            if (line.equals("POST /secure-links")) {
                Matcher ref = ITEM_REF.matcher(body);
                String[] answer = ref.find() ? links.get(ref.group(1)) : null;
                return answer == null ? new String[]{"404", "{\"type\":\"not-found\"}"} : answer;
            }
            return null;
        };
    }

    private static String subject(String ref, String firstName, String relation) {
        return "{\"patientRef\":\"" + ref + "\",\"firstName\":\"" + firstName + "\",\"relation\":\"" + relation + "\"}";
    }

    private static String item(String ref, String type, String date) {
        return "{\"itemRef\":\"" + ref + "\",\"type\":\"" + type + "\",\"date\":\"" + date + "\"}";
    }

    private static String[] link(String type, String date, String url) {
        return new String[]{"201", "{\"linkUrl\":\"" + url + "\",\"expiresAt\":\"2026-10-12T11:00:00Z\",\"type\":\"" + type + "\",\"date\":\"" + date + "\"}"};
    }

    private String say(Clinic c, String text) {
        String id = FakeUpstreams.id();
        UP.inbound(id, c.hash(), text, null);
        engine.process(c.tenant(), id, "whatsapp", c.hash());
        return id;
    }

    private String press(Clinic c, String payload) {
        String id = FakeUpstreams.id();
        UP.inbound(id, c.hash(), "", payload);
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

    private String tap(Clinic c, String title) {
        int i = titles().indexOf(title);
        assertTrue(i >= 0, "no option titled " + title + " in " + titles());
        return press(c, options().get(i).path("id").asString());
    }

    private String text() {
        return last().path("text").asString();
    }

    private String state(Clinic c) {
        return superuser().queryForObject("select state from conversations where tenant_id = ?::uuid and contact_hash = ?", String.class, c.tenant().toString(), c.hash());
    }

    private List<String> recordCalls() {
        return UP.coreLog.stream().filter(l -> l.contains("/record-") || l.contains("/secure-links")).toList();
    }

    // ---- criterion 1: Menu -> My records, the last 10 items, type and date only ------------------------------------------------

    @Test
    void menuThenMyRecordsListsTheLastTenItemsAsTypeAndDateOnly() {
        Clinic c = clinic("Sunrise Clinic");
        scriptCore();
        String[] types = {"prescription", "invoice", "report", "visit"};
        List<String> twelve = new ArrayList<>();
        for (int i = 0; i < 12; i++) { // Core never sends these extra fields; if anything ever did, a row must still not show them
            twelve.add("{\"itemRef\":\"p" + String.format("%032x", i) + "\",\"type\":\"" + types[i % 4] + "\",\"date\":\"" + LocalDate.of(2026, 10, 12).minusDays(i)
                    + "\",\"title\":\"Amoxicillin 500 mg\",\"diagnosis\":\"Asthma\",\"test\":\"HbA1c\"}");
        }
        items.put("ref-me", "[" + String.join(",", twelve) + "]");

        say(c, "Hi");
        assertEquals(List.of("Book", "My appointment", "My records", "Talk to clinic"), titles());
        assertTrue(last().has("rows"), "four options: the menu is a list message (tap Menu, tap My records)");
        tap(c, "My records");

        assertEquals(RecordsFlow.CHOOSE, text());
        assertEquals(10, titles().size(), "the last 10 items");
        assertEquals(List.of("Prescription, 12 Oct", "Invoice, 11 Oct", "Report, 10 Oct", "Visit, 9 Oct"), titles().subList(0, 4));
        assertTrue(titles().stream().allMatch(t -> RecordsFlow.LABEL.matcher(t).matches() && t.length() <= 20), titles().toString());
        String sent = UP.sent.get(UP.sent.size() - 1);
        assertFalse(sent.contains("Amoxicillin") || sent.contains("Asthma") || sent.contains("HbA1c"), "no drug, diagnosis or test text in the list");
        assertTrue(recordCalls().stream().anyMatch(l -> l.startsWith("GET /patients/ref-me/record-items?limit=10&channel=whatsapp&phoneHash=" + c.hash())),
                "Core is asked with the verified phone: " + recordCalls());
        assertEquals("MENU", state(c), "the conversation step is untouched");

        say(c, "My Records.");
        assertEquals(10, titles().size(), "the word works too, from any step");
    }

    // ---- criterion 2: clinic name, type, date and link, nothing else -----------------------------------------------------------

    @Test
    void pickingAnItemAnswersWithClinicNameTypeDateAndTheLinkOnly() {
        Clinic c = clinic("Sunrise Clinic");
        scriptCore();
        items.put("ref-me", "[" + item("pabc", "prescription", "2026-10-12") + "," + item("igone", "invoice", "2026-10-11") + "," + item("rbad", "report", "2026-10-10") + "]");
        links.put("pabc", link("prescription", "2026-10-12", "https://patient.example.test/l/AbC_dEf-123456"));
        links.put("rbad", link("report", "2026-10-10", "https://patient.example.test/l/x Take Amoxicillin 500 mg"));
        say(c, "records");
        assertEquals(List.of("Prescription, 12 Oct", "Invoice, 11 Oct", "Report, 10 Oct"), titles());

        String messageId = tap(c, "Prescription, 12 Oct");
        assertEquals("Sunrise Clinic\nPrescription, 12 Oct\nhttps://patient.example.test/l/AbC_dEf-123456", text());
        assertEquals(0, options().size());
        String call = recordCalls().get(recordCalls().size() - 1);
        assertTrue(call.startsWith("POST /secure-links ") && call.contains("\"itemRef\":\"pabc\"") && call.contains("\"recipientPhoneHash\":\"" + c.hash() + "\"")
                && call.contains("\"channel\":\"whatsapp\""), call);
        assertTrue(call.endsWith(" key=" + UUID.nameUUIDFromBytes(("records:" + messageId).getBytes(StandardCharsets.UTF_8))), "one link per tap, replay-safe: " + call);

        say(c, "records");
        tap(c, "Invoice, 11 Oct");               // Core: gone, or not this phone's to see
        assertEquals(RecordsFlow.REFUSED, text());
        say(c, "records");
        tap(c, "Report, 10 Oct");                // an answer that is not a plain link is not passed on
        assertEquals(RecordsFlow.REFUSED, text());
        assertFalse(String.join("\n", UP.sent).contains("Amoxicillin"));
    }

    // ---- criterion 3: one tap per person Core lists; nobody Core does not list ---------------------------------------------------

    @Test
    void aGuardianSwitchesWithOneTapToEachPersonCoreListsAndToNobodyElse() {
        // the household has two children, but Core says this phone may see only one of them (and the father, by proxy)
        Clinic c = clinic("Sunrise Clinic", FakeUpstreams.member("ref-aarav", "Aarav", 6, "dependent"), FakeUpstreams.member("ref-diya", "Diya", 17, "dependent"));
        scriptCore();
        subjects.put(c.hash(), "[" + subject("ref-me", "Asha", "self") + "," + subject("ref-aarav", "Aarav", "dependent") + "," + subject("ref-ravi", "Ravi", "proxy") + "]");
        items.put("ref-me", "[" + item("p1", "prescription", "2026-10-12") + "]");
        items.put("ref-aarav", "[" + item("r2", "report", "2026-09-03") + "," + item("i3", "invoice", "2026-09-03") + "]");
        items.put("ref-ravi", "[]");

        say(c, "Hi");
        tap(c, "My records");
        assertEquals(RecordsFlow.WHO_ASK, text());
        assertEquals(List.of("Asha", "Aarav", "Ravi"), titles(), "exactly the people Core lists; Diya is not offered");
        tap(c, "Aarav");
        assertEquals(List.of("Report, 3 Sep", "Invoice, 3 Sep"), titles());
        assertTrue(recordCalls().stream().anyMatch(l -> l.startsWith("GET /patients/ref-aarav/record-items?")));
        say(c, "records");
        tap(c, "Ravi");
        assertEquals(RecordsFlow.NONE, text());

        // even a genuine button for someone out of scope gets nothing: Core refuses, the Edge shows no row
        int before = UP.sent.size();
        press(c, buttons.issue(c.tenant(), "rw:ref-diya", Duration.ofMinutes(5)));
        assertEquals(before + 1, UP.sent.size());
        assertEquals(RecordsFlow.REFUSED, text());
        assertEquals(0, options().size());
        // a forged button is dropped before any flow sees it
        press(c, "rw:ref-diya");
        assertEquals(before + 1, UP.sent.size());
    }

    @Test
    void aNumberCoreKnowsNobodyOnGetsNoListAndAPersonWithoutItemsIsToldSo() {
        Clinic c = clinic("Sunrise Clinic");
        scriptCore();
        subjects.put(c.hash(), "[]");
        say(c, "records");
        assertEquals(RecordsFlow.NOBODY, text());
        subjects.put(c.hash(), "[" + subject("ref-me", "Asha", "self") + "]");
        items.put("ref-me", "[]");
        say(c, "records");
        assertEquals(RecordsFlow.NONE, text());
    }

    // ---- criterion 4: a flagged phone is re-verified (code + date of birth) before any record call ------------------------------

    @Test
    void aPhoneFlaggedForReverificationGetsTheCodeAndDateOfBirthStepBeforeAnyRecordCall() {
        Clinic c = clinic("Sunrise Clinic");
        UP.reverifyState.put(c.hash(), "needs_reverify");
        say(c, "Hi");
        tap(c, "My records");
        assertEquals("REVERIFY_OTP", state(c));
        Matcher code = Pattern.compile("Your code is (\\d{6})").matcher(text());
        assertTrue(code.find(), text());
        press(c, buttons.issue(c.tenant(), "rg:pabc", Duration.ofMinutes(5)));   // an item button from an earlier list
        press(c, buttons.issue(c.tenant(), "rw:ref-me", Duration.ofMinutes(5))); // a person button from an earlier list
        say(c, "records");
        assertEquals("REVERIFY_OTP", state(c));
        assertTrue(UP.corePaths.stream().noneMatch(p -> p.contains("/record-") || p.contains("/secure-links")), "nothing was asked of Core's records side: " + UP.corePaths);
        assertTrue(UP.sent.stream().noneMatch(s -> s.contains("Choose a record") || s.contains("/l/")));

        code = Pattern.compile("Your code is (\\d{6})").matcher(text());
        assertTrue(code.find(), text());
        say(c, code.group(1));
        assertEquals("REVERIFY_DOB", state(c));
        say(c, "14-08-1988");
        assertTrue(text().contains("verified again"), text());
        UP.reverifyState.remove(c.hash()); // Core has the 'verified' report
        scriptCore();
        items.put("ref-me", "[" + item("p1", "prescription", "2026-10-12") + "]");
        say(c, "records");
        assertEquals(List.of("Prescription, 12 Oct"), titles());
    }

    // ---- criterion 6: the lint. A row and the link message hold a fixed type word, a date, the clinic name and a link ------------

    @Test
    void aRowIsAKnownTypeWordAndADateWhateverCoreSends() {
        for (String type : RecordsFlow.TYPES.keySet()) {
            for (LocalDate d = LocalDate.of(2026, 1, 1); d.getYear() == 2026; d = d.plusDays(1)) {
                String label = RecordsFlow.label(type, d.toString());
                assertTrue(label != null && RecordsFlow.LABEL.matcher(label).matches() && label.length() <= 20, type + " " + d + " -> " + label);
            }
        }
        assertEquals("Prescription, 12 Oct", RecordsFlow.label("prescription", "2026-10-12"));
        for (String[] bad : new String[][]{{"Amoxicillin 500 mg", "2026-10-12"}, {"prescription", "Asthma"}, {"prescription", "2026-10-12 Amoxicillin"},
                {"Prescription", "2026-10-12"}, {"lab: HbA1c", "2026-10-12"}, {"", ""}, {"report", "12 Oct"}}) {
            assertNull(RecordsFlow.label(bad[0], bad[1]), bad[0] + " / " + bad[1]);
        }

        Clinic c = clinic("Sunrise Clinic");
        scriptCore();
        items.put("ref-me", "[" + item("p1", "Amoxicillin 500 mg", "2026-10-12") + "," + item("p2", "prescription", "Asthma review") + ","
                + item("p3 Metformin", "prescription", "2026-10-11") + "," + item("r4", "report", "2026-10-10") + "]");
        subjects.put(c.hash(), "[" + subject("ref-me", "Asha", "self") + "," + subject("ref-x", "HbA1c 9.1%", "proxy") + "]");
        say(c, "records");
        assertEquals(List.of("Report, 10 Oct"), titles(), "only the well-formed row is shown; a person entry that is not a first name is not offered");
        String sent = String.join("\n", UP.sent);
        assertFalse(sent.contains("Amoxicillin") || sent.contains("Asthma") || sent.contains("Metformin") || sent.contains("HbA1c"));
    }

    /** COM-016's output guard is the last check on a records reply too: nothing of another clinic goes out. */
    @Test
    void aRecordsReplyThatWouldShowAnotherClinicIsStoppedByTheOutputGuard() {
        Clinic other = clinic("Zenith Care");
        say(other, "Hi"); // this instance now knows the other clinic's name
        Clinic c = clinic("Sunrise Clinic");
        scriptCore();
        subjects.put(c.hash(), "[" + subject("ref-me", "Asha", "self") + "," + subject("ref-z", "Zenith Care", "proxy") + "]");
        say(c, "Hi");
        tap(c, "My records");
        assertEquals(BookingCopy.t("en", "guard.fallback"), text());
        assertEquals("HANDOFF", state(c));
    }

    @Test
    void aCoreOutageFailsTheStepSoTheMessageIsRetriedInsteadOfAnsweredWithARefusal() {
        Clinic c = clinic("Sunrise Clinic");
        scriptCore();
        items.put("ref-me", "503");
        say(c, "Hi");
        assertThrows(RuntimeException.class, () -> say(c, "records"));
        assertTrue(UP.sent.stream().noneMatch(s -> s.contains(RecordsFlow.REFUSED)));
    }
}
