package com.nabd.edge;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** COM-025: 'Settings' on WhatsApp, the language buttons, the save at Core and the confirmation in the new language. */
class SettingsFlowTest extends EdgeTestBase {

    @Autowired ConversationEngine engine;
    @Autowired ContactLanguage languages;
    @Autowired ObjectMapper json;

    UUID tenant;
    String contact;
    volatile int coreStatus;

    @BeforeEach
    void setUp() {
        tenant = UUID.randomUUID();
        contact = "c" + UUID.randomUUID().toString().replace("-", "");
        coreStatus = 200;
        UP.coreLog.clear();
        UP.coreHook = (line, body) -> line.startsWith("PATCH /households/") ? new String[]{String.valueOf(coreStatus), "{\"language\":\"hi\",\"changed\":1}"} : null;
    }

    void say(String text) {
        String id = FakeUpstreams.id();
        UP.inbound(id, contact, text, null);
        engine.process(tenant, id, "whatsapp", contact);
    }

    JsonNode last() {
        return json.readTree(UP.sent.get(UP.sent.size() - 1));
    }

    List<String> titles() {
        List<String> out = new ArrayList<>();
        JsonNode m = last();
        for (JsonNode b : m.has("rows") ? m.path("rows") : m.path("buttons")) {
            out.add(b.path("title").asString());
        }
        return out;
    }

    void tapTitle(String title) {
        JsonNode m = last();
        for (JsonNode b : m.has("rows") ? m.path("rows") : m.path("buttons")) {
            if (title.equals(b.path("title").asString())) {
                String id = FakeUpstreams.id();
                UP.inbound(id, contact, "", b.path("id").asString());
                engine.process(tenant, id, "whatsapp", contact);
                return;
            }
        }
        throw new AssertionError("no button " + title + " in " + UP.sent.get(UP.sent.size() - 1));
    }

    @Test
    void settingsShowsTheLanguagesAndATapSavesAtCoreAndConfirmsInTheNewLanguage() {
        say("Settings");
        assertTrue(last().path("text").asString().startsWith("Settings:"));
        assertEquals(List.of("English", "हिन्दी", "ಕನ್ನಡ", "తెలుగు"), titles()); // the region's offered languages (IN)

        tapTitle("हिन्दी");
        assertEquals("सहेज लिया गया। क्लिनिक के संदेश अब आपको हिन्दी में मिलेंगे।", last().path("text").asString());
        assertTrue(UP.coreLog.stream().anyMatch(l -> l.startsWith("PATCH /households/" + contact + "/notification-preferences {\"language\":\"hi\"}")), String.valueOf(UP.coreLog));
        assertEquals("hi", languages.find(tenant, "whatsapp", contact).orElseThrow(), "the bot speaks the new language too");

        say("सेटिंग्स"); // the menu is now asked in Hindi
        assertTrue(last().path("text").asString().startsWith("सेटिंग्स:"));
    }

    @Test
    void aPhoneWithoutAPatientRecordStillGetsTheBotInTheChosenLanguage_andAnOutageIsNotConfirmed() {
        coreStatus = 404;
        say("settings");
        tapTitle("తెలుగు");
        assertTrue(last().path("text").asString().contains("తెలుగు"));
        assertEquals("te", languages.find(tenant, "whatsapp", contact).orElseThrow());

        coreStatus = 503; // Core down: nothing is confirmed, the stream retries the message
        say("settings");
        int sent = UP.sent.size();
        try {
            tapTitle("English");
        } catch (RuntimeException expected) {
            // the step fails so the event is retried
        }
        assertTrue(UP.sent.stream().skip(sent).noneMatch(s -> s.contains("Saved.")));
        assertEquals("te", languages.find(tenant, "whatsapp", contact).orElseThrow());
    }
}
