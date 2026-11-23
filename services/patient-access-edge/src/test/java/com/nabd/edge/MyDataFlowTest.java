package com.nabd.edge;

import org.junit.jupiter.api.AfterEach;
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
import static org.junit.jupiter.api.Assertions.assertTrue;

/** PLT-047: the 'my data' flow: code and date of birth first, whose data, Core decides, the chat carries the acknowledgement only. */
class MyDataFlowTest extends BookingTestBase {

    @Autowired ConversationEngine engine;
    @Autowired ContactLanguage languages;
    @Autowired ObjectMapper json;

    record Clinic(UUID tenant, String hash) {
    }

    /** Household Asha (adult, date of birth on file 14-08-1988) plus the given members. */
    private Clinic clinic(String... extra) {
        UUID t = UUID.randomUUID();
        String hash = "hash-" + UUID.randomUUID();
        List<String> m = new CopyOnWriteArrayList<>();
        m.add(FakeUpstreams.member("ref-me", "Asha", 36, "self"));
        Collections.addAll(m, extra);
        UP.households.put(hash, m);
        UP.dobOnFile.put(hash, "1988-08-14");
        languages.save(t, "whatsapp", hash, "en");
        return new Clinic(t, hash);
    }

    /** Core's privacy intake, scripted: the status it answers and the calls it saw ("body key=<Idempotency-Key>"). Every other Core call is left to the fake. */
    private List<String> core(int status, String body) {
        List<String> seen = new CopyOnWriteArrayList<>();
        UP.coreFirst = ex -> {
            if (!ex.getRequestURI().getPath().endsWith("/privacy/requests")) {
                return false;
            }
            seen.add(new String(ex.getRequestBody().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8) + " key=" + ex.getRequestHeaders().getFirst("Idempotency-Key"));
            FakeUpstreams.reply(ex, status, body);
            return true;
        };
        return seen;
    }

    @AfterEach
    void resetCore() {
        UP.coreFirst = null;
    }

    private void say(Clinic c, String text) {
        String id = FakeUpstreams.id();
        UP.inbound(id, c.hash(), text, null);
        engine.process(c.tenant(), id, "whatsapp", c.hash());
    }

    private JsonNode last() {
        return json.readTree(UP.sent.get(UP.sent.size() - 1));
    }

    private String text() {
        return last().path("text").asString();
    }

    private List<String> titles() {
        JsonNode m = last();
        List<String> t = new ArrayList<>();
        (m.has("rows") ? m.path("rows") : m.path("buttons")).forEach(b -> t.add(b.path("title").asString()));
        return t;
    }

    private void tap(Clinic c, String title) {
        JsonNode opts = last().has("rows") ? last().path("rows") : last().path("buttons");
        int i = titles().indexOf(title);
        assertTrue(i >= 0, "no option " + title + " in " + titles());
        String id = FakeUpstreams.id();
        UP.inbound(id, c.hash(), "", opts.get(i).path("id").asString());
        engine.process(c.tenant(), id, "whatsapp", c.hash());
    }

    private String code() {
        Matcher m = Pattern.compile("Your code is (\\d{6})").matcher(text());
        assertTrue(m.find(), text());
        return m.group(1);
    }

    private String state(Clinic c) {
        return superuser().queryForObject("select state from conversations where tenant_id = ?::uuid and contact_hash = ?", String.class, c.tenant().toString(), c.hash());
    }

    /** "my data", the code typed back, the right date of birth. */
    private void verify(Clinic c) {
        say(c, "My data");
        say(c, code());
        assertEquals("MYDATA_DOB", state(c));
        say(c, "14-08-1988");
    }

    @Test
    void thePatientTypesTheCodeAndTheDateOfBirthThenTheRequestIsCreatedAndAcknowledgedInChat() {
        Clinic c = clinic();
        List<String> calls = core(201, "{\"requestId\":\"" + UUID.randomUUID() + "\"}");
        say(c, "My data");
        assertEquals("MYDATA_OTP", state(c));
        assertTrue(text().contains("Your code is"), text());
        assertTrue(calls.isEmpty(), "nothing is created before the person is checked");
        String code = code();
        say(c, code.equals("000000") ? "111111" : "000000"); // a wrong code: still nothing
        assertEquals("MYDATA_OTP", state(c));
        assertTrue(calls.isEmpty());
        say(c, code);
        assertEquals("MYDATA_DOB", state(c));
        say(c, "01-01-2000"); // a wrong date of birth: still nothing
        assertEquals("MYDATA_DOB", state(c));
        assertTrue(calls.isEmpty());
        say(c, "14-08-1988");

        assertEquals("DONE", state(c));
        assertEquals(1, calls.size());
        assertTrue(calls.get(0).contains("\"personRef\":\"ref-me\"") && calls.get(0).contains("\"phoneHash\":\"" + c.hash() + "\"") && calls.get(0).contains("\"type\":\"access\""), calls.get(0));
        assertTrue(calls.get(0).contains("key=mydata:"), "idempotent on the message: a re-run asks Core for the same request");
        assertTrue(text().contains("secure link") && text().contains("72 hours"), text());
        assertFalse(text().contains("Asha") || text().contains("1988"), "the chat carries no personal or record content");
        assertFalse(superuser().queryForObject("select string_agg(regexp_replace(c::text, '[0-9a-f]{8}-[0-9a-f-]{27}|[0-9a-f]{32,}', '', 'g'), ' ') "
                + "from conversations c where tenant_id = ?::uuid and contact_hash = ?", String.class, c.tenant().toString(), c.hash()).contains("1988"), "the date of birth is never stored");
    }

    @Test
    void threeWrongAnswersEndTheRequestWithoutCallingCore() {
        Clinic c = clinic();
        List<String> calls = core(201, "{\"requestId\":\"x\"}");
        say(c, "my data");
        say(c, "111111");
        say(c, "222222");
        assertEquals("MYDATA_OTP", state(c));
        say(c, "333333");
        assertEquals("DONE", state(c));
        assertTrue(text().contains("contact the clinic"), text());
        assertTrue(calls.isEmpty());
        // a fresh ask gets a fresh code and a fresh count
        say(c, "my data");
        assertEquals("MYDATA_OTP", state(c));
        String fresh = code();
        say(c, fresh.equals("000000") ? "111111" : "000000");
        say(c, fresh);
        say(c, "01-01-2001");
        say(c, "02-02-2002"); // the code's one wrong answer counts: the third wrong answer in all ends it
        assertEquals("DONE", state(c));
        assertTrue(calls.isEmpty());
    }

    @Test
    void aGuardianChoosesTheChildAndCoreRefusesAProxyWithoutFullScope() {
        Clinic c = clinic(FakeUpstreams.member("ref-aarav", "Aarav", 6, "dependent"), FakeUpstreams.member("ref-dev", "Dev", 70, "dependent"));
        List<String> calls = core(403, "{\"code\":\"PROXY_NOT_APPROVED\"}");
        verify(c);
        assertEquals("MYDATA_WHO", state(c));
        assertEquals("Whose data would you like?", text());
        assertEquals(List.of("Asha (36)", "Aarav (6)", "Dev (70)"), titles());
        assertTrue(calls.isEmpty());
        tap(c, "Dev (70)");
        assertEquals(1, calls.size());
        assertTrue(calls.get(0).contains("\"personRef\":\"ref-dev\""), calls.get(0));
        assertEquals("DONE", state(c));
        assertEquals(MyDataFlow.CONTACT, text());

        // the same household, Core allows the child (FULL guardian): acknowledged
        core(201, "{\"requestId\":\"" + UUID.randomUUID() + "\"}");
        verify(c);
        tap(c, "Aarav (6)");
        assertEquals(MyDataFlow.ACK, text());
    }

    @Test
    void aNameThatLeftTheHouseholdSinceTheListWasShownCannotBeChosen() {
        Clinic c = clinic(FakeUpstreams.member("ref-aarav", "Aarav", 6, "dependent"));
        List<String> calls = core(201, "{\"requestId\":\"x\"}");
        verify(c);
        JsonNode list = last();
        say(c, "Aarav"); // typing a name is not a tap
        assertEquals("MYDATA_WHO", state(c));
        assertTrue(calls.isEmpty());
        UP.households.get(c.hash()).removeIf(m -> m.contains("ref-aarav")); // the guardian link ended meanwhile
        String id = FakeUpstreams.id();
        UP.inbound(id, c.hash(), "", list.path("buttons").get(1).path("id").asString()); // the tap on the list shown earlier
        engine.process(c.tenant(), id, "whatsapp", c.hash());
        assertEquals("MYDATA_WHO", state(c));
        assertTrue(calls.isEmpty());
    }

    @Test
    void anOpenRequestIsAnsweredAndAnUnknownNumberGetsNoCode() {
        Clinic c = clinic();
        core(409, "{\"code\":\"OPEN_REQUEST_EXISTS\"}");
        verify(c);
        assertTrue(text().contains("already have a request"), text());

        Clinic nobody = clinic();
        UP.households.remove(nobody.hash());
        say(nobody, "my data");
        assertEquals(MyDataFlow.NO_RECORD, text());
        assertFalse(text().contains("code"), text());
    }

    @Test
    void aNumberFlaggedForReverificationIsClearedByTheSameCodeAndDateOfBirth() {
        Clinic c = clinic();
        core(201, "{\"requestId\":\"x\"}");
        UP.reverifyState.put(c.hash(), "needs_reverify");
        say(c, "hello");
        say(c, "status"); // flags the phone from Core's answer and starts the COM-041 check
        assertEquals("REVERIFY_OTP", state(c));
        say(c, "my data"); // the intent works from any step: a fresh code, the same two checks
        assertEquals("MYDATA_OTP", state(c));
        say(c, code());
        say(c, "14-08-1988");
        assertEquals("DONE", state(c));
        assertEquals(MyDataFlow.ACK, text());
        assertEquals("verified", superuser().queryForObject("select state from phone_verifications where tenant_id = ?::uuid and phone_hash = ?", String.class, c.tenant().toString(), c.hash()));
        assertTrue(UP.reverifyReports.stream().anyMatch(r -> r.startsWith(c.hash() + "|") && r.contains("\"step\":\"verified\"")));
    }
}
