package com.nabd.edge;

import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * COM-016 against the real booking flow, fake Core and fake Comms: a property-based run over 1,000 generated conversations (seeded
 * java.util.Random, so a failure replays) proves no reply in clinic A shows another clinic's name, doctor or slot, or offers discovery;
 * then a reply that would leak another clinic is stopped by the output guard end to end.
 */
class Com016ChannelExclusivityTest extends BookingTestBase {

    private static final long SEED = 16_016L;
    private static final int CONVERSATIONS = 1_000;
    private static final String ZONE = "Asia/Kolkata";
    private static final Instant BASE = Instant.now().plus(3, ChronoUnit.DAYS).truncatedTo(ChronoUnit.DAYS).plus(4, ChronoUnit.HOURS);
    private static final String[] CLINICS = {"Sunrise Dental", "Lotus Care", "Neem Health", "Banyan Kids", "Coral Eye Centre"};
    private static final String[] DOCTORS = {"Dr Mehta", "Dr Iyer", "Dr Rao", "Dr Khan", "Dr Das", "Dr Bose", "Dr Pillai", "Dr Nair", "Dr Sen",
            "Dr Joshi", "Dr Kapoor", "Dr Menon", "Dr Reddy", "Dr Gill", "Dr Varma"};
    private static final String[] FREE_TEXT = {"hello", "hi there", "what?", "book please", "ok", "12-05-2019", "Riya", "thanks", "1", "menu"};

    @Autowired ConversationEngine engine;
    @Autowired ContactLanguage languages;
    @Autowired ButtonTokens buttons;
    @Autowired ObjectMapper json;
    @Autowired MeterRegistry meters;

    /** What one clinic owns: its name, its doctors (id -> name) and every slot start it offers (epoch seconds). */
    record Tenant(UUID id, String name, Map<UUID, String> doctors, Set<Long> slots) {
    }

    private List<Tenant> tenants(Random r) {
        List<Tenant> out = new ArrayList<>();
        int doc = 0;
        for (int k = 0; k < CLINICS.length; k++) {
            UUID t = UUID.randomUUID();
            Map<UUID, String> docs = new HashMap<>();
            Set<Long> slots = new HashSet<>();
            int n = 1 + r.nextInt(3);
            StringBuilder list = new StringBuilder();
            for (int i = 0; i < n; i++, doc++) {
                UUID d = UUID.randomUUID();
                docs.put(d, DOCTORS[doc]);
                list.append(i == 0 ? "" : ",").append("{\"doctorId\":\"").append(d).append("\",\"name\":\"").append(DOCTORS[doc]).append("\"}");
                UP.rawSessions.put(d.toString(), sessions(k * 5 + i, slots)); // a different minute per doctor: every clinic's slots are its own
            }
            UP.profiles.put(t.toString(), "{\"clinicName\":\"" + CLINICS[k] + "\",\"timeZone\":\"" + ZONE + "\",\"doctors\":[" + list + "]}");
            out.add(new Tenant(t, CLINICS[k], docs, slots));
        }
        return out;
    }

    private static String sessions(int minuteOffset, Set<Long> slots) {
        List<String> out = new ArrayList<>();
        for (int day = 0; day < 3; day++) {
            List<String> s = new ArrayList<>();
            for (int i = 0; i < 5; i++) {
                Instant at = BASE.plus(day, ChronoUnit.DAYS).plus(30L * i + minuteOffset, ChronoUnit.MINUTES);
                slots.add(at.getEpochSecond());
                s.add("\"" + at + "\"");
            }
            LocalDate date = BASE.plus(day, ChronoUnit.DAYS).atZone(ZoneId.of(ZONE)).toLocalDate();
            out.add("{\"id\":\"s" + day + "\",\"date\":\"" + date + "\",\"mode\":\"slots\",\"slots\":[" + String.join(",", s) + "]}");
        }
        return "[" + String.join(",", out) + "]";
    }

    private void send(Tenant t, String hash, String text, String payload) {
        String id = FakeUpstreams.id();
        UP.inbound(id, hash, text, payload);
        engine.process(t.id(), id, "whatsapp", hash);
    }

    private List<JsonNode> sentTo(String hash) {
        return UP.sent.stream().map(s -> json.readTree(s)).filter(n -> hash.equals(n.path("contactHash").asString(""))).toList();
    }

    private static List<JsonNode> options(JsonNode m) {
        List<JsonNode> o = new ArrayList<>();
        (m.has("rows") ? m.path("rows") : m.path("buttons")).forEach(o::add);
        return o;
    }

    private double blocked() {
        return meters.find("edge_output_guard_blocked_total").counters().stream().mapToDouble(c -> c.count()).sum();
    }

    // ---- criterion 1 (and 3): property-based, 1,000 generated conversations --------------------------------------------------

    @Test
    void noReplyInAClinicsConversationShowsAnotherClinicsNameDoctorOrSlot_orOffersDiscovery() {
        Random r = new Random(SEED);
        List<Tenant> all = tenants(r);
        double before = blocked();
        Map<UUID, List<String>> tokensSeen = new HashMap<>(); // a real token from another clinic's chat, replayed into this one
        int replies = 0, optionsChecked = 0;
        for (int n = 0; n < CONVERSATIONS; n++) {
            Tenant t = all.get(r.nextInt(all.size()));
            String hash = "c16-" + n + "-" + UUID.randomUUID();
            List<String> members = new CopyOnWriteArrayList<>();
            members.add(FakeUpstreams.member("ref-" + n, "Asha", 30 + r.nextInt(40), "self"));
            if (r.nextBoolean()) {
                members.add(FakeUpstreams.member("kid-" + n, "Kabir", 1 + r.nextInt(15), "dependent"));
            }
            UP.households.put(hash, members);
            if (r.nextInt(4) > 0) { // otherwise the language picker comes first
                languages.save(t.id(), "whatsapp", hash, r.nextBoolean() ? "en" : "hi");
            }
            UP.sent.clear();
            send(t, hash, "Hi", null);
            int steps = 2 + r.nextInt(6);
            for (int s = 0; s < steps; s++) {
                List<JsonNode> got = sentTo(hash);
                List<JsonNode> opts = got.isEmpty() ? List.of() : options(got.get(got.size() - 1));
                int pick = r.nextInt(100);
                List<String> foreign = tokensSeen.entrySet().stream().filter(e -> !e.getKey().equals(t.id())).flatMap(e -> e.getValue().stream()).toList();
                if (pick < 75 && !opts.isEmpty()) {
                    send(t, hash, "", opts.get(r.nextInt(opts.size())).path("id").asString());
                } else if (pick < 90 || foreign.isEmpty()) {
                    send(t, hash, FREE_TEXT[r.nextInt(FREE_TEXT.length)], null);
                } else {
                    send(t, hash, "", foreign.get(r.nextInt(foreign.size()))); // refused: the token is bound to the other clinic
                }
            }
            for (JsonNode m : sentTo(hash)) {
                replies++;
                StringBuilder shown = new StringBuilder(m.path("text").asString(""));
                for (JsonNode o : options(m)) {
                    optionsChecked++;
                    shown.append('\n').append(o.path("title").asString(""));
                    Optional<String> action = buttons.verify(o.path("id").asString(""), t.id());
                    assertTrue(action.isPresent(), "every option is signed for this clinic");
                    String a = action.get();
                    assertFalse(a.toLowerCase(Locale.ROOT).startsWith(OutputGuard.DISCOVERY_PREFIX), "discovery offered in clinic context: " + a);
                    if (a.startsWith("doc:")) {
                        assertTrue(t.doctors().containsKey(UUID.fromString(a.substring(4))), "foreign doctor offered: " + a);
                    }
                    if (a.startsWith("slot:")) {
                        assertTrue(t.slots().contains(Long.parseLong(a.substring(5))), "foreign slot offered: " + a);
                    }
                    for (Tenant other : all) {
                        if (other != t) {
                            assertFalse(a.contains(other.id().toString()), "foreign tenant id in an action");
                            other.doctors().keySet().forEach(d -> assertFalse(a.contains(d.toString()), "foreign doctor id in an action"));
                        }
                    }
                    if (r.nextInt(3) == 0) {
                        tokensSeen.computeIfAbsent(t.id(), k -> new ArrayList<>()).add(o.path("id").asString());
                    }
                }
                String text = shown.toString().toLowerCase(Locale.ROOT);
                for (Tenant other : all) {
                    if (other == t) {
                        continue;
                    }
                    assertFalse(text.contains(other.name().toLowerCase(Locale.ROOT)), "conversation " + n + " shows clinic " + other.name());
                    assertFalse(text.contains(other.id().toString()), "conversation " + n + " shows another tenant id");
                    for (String d : other.doctors().values()) {
                        assertFalse(text.contains(d.toLowerCase(Locale.ROOT)), "conversation " + n + " shows " + d + " of " + other.name());
                    }
                }
            }
        }
        assertTrue(replies > CONVERSATIONS && optionsChecked > CONVERSATIONS, "the generator really exercised the flow: " + replies + " replies, " + optionsChecked + " options");
        assertEquals(before, blocked(), "with honest content the guard never fires (no false positives over 1,000 conversations)");
    }

    // ---- criterion 2: the guard end to end ----------------------------------------------------------------------------------

    @Test
    void aReplyCarryingAnotherClinicsDoctorIsBlocked_patientToldLetMeConnectYou_threadHandedToReception_alertRaised() {
        Random r = new Random(SEED + 1);
        List<Tenant> all = tenants(r);
        Tenant a = all.get(0), b = all.get(1);
        // B is served first, so this replica knows what B owns
        String hb = "c16-b-" + UUID.randomUUID();
        UP.households.put(hb, new CopyOnWriteArrayList<>(List.of(FakeUpstreams.member("ref-b", "Asha", 40, "self"))));
        languages.save(b.id(), "whatsapp", hb, "en");
        send(b, hb, "Hi", null);
        // Core (wrongly) lists B's doctor in A's profile: A's doctor list would show him
        Map.Entry<UUID, String> bDoc = b.doctors().entrySet().iterator().next();
        Map.Entry<UUID, String> aDoc = a.doctors().entrySet().iterator().next();
        UP.profiles.put(a.id().toString(), "{\"clinicName\":\"" + a.name() + "\",\"timeZone\":\"" + ZONE + "\",\"doctors\":[{\"doctorId\":\"" + aDoc.getKey()
                + "\",\"name\":\"" + aDoc.getValue() + "\"},{\"doctorId\":\"" + bDoc.getKey() + "\",\"name\":\"" + bDoc.getValue() + "\"}]}");
        String ha = "c16-a-" + UUID.randomUUID();
        UP.households.put(ha, new CopyOnWriteArrayList<>(List.of(FakeUpstreams.member("ref-a", "Asha", 40, "self"))));
        languages.save(a.id(), "whatsapp", ha, "en");
        double before = blocked();
        send(a, ha, "Hi", null);
        JsonNode menu = sentTo(ha).get(0);
        send(a, ha, "", options(menu).get(0).path("id").asString()); // Book
        send(a, ha, "", options(sentTo(ha).get(1)).get(0).path("id").asString()); // Me -> the doctor list (2 doctors)
        List<JsonNode> got = sentTo(ha);
        JsonNode last = got.get(got.size() - 1);
        assertEquals("Let me connect you to the clinic.", last.path("text").asString());
        assertTrue(options(last).isEmpty(), "no option of the blocked reply reaches the patient");
        for (JsonNode m : got) {
            assertFalse(m.toString().contains(bDoc.getValue()) || m.toString().contains(b.name()), "nothing of clinic B was sent");
        }
        assertEquals("HANDOFF", superuser().queryForObject("select state from conversations where tenant_id = ?::uuid and contact_hash = ?",
                String.class, a.id().toString(), ha), "reception takes over the thread");
        assertEquals(before + 1, blocked(), "alert metric edge_output_guard_blocked_total raised");
    }
}
