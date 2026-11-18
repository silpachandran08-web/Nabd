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
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * S24 QA t1, COM-016 x COM-044 merge (cases C16-E01, C44-E01): e2's 'Book session N of M' step reads doctors through e10's
 * tenant-bound ClinicContent; Core's package answer (eligibleDoctorIds) is patient data and may be wrong. Nothing of another clinic
 * may reach the patient, and the output guard must stop a leak whichever clinic this replica served first.
 */
class S24QaCom016PackageGuardTest extends BookingTestBase {

    static final String ZONE = "Asia/Kolkata";
    static final Instant BASE = Instant.now().plus(3, ChronoUnit.DAYS).truncatedTo(ChronoUnit.DAYS).plus(5, ChronoUnit.HOURS);

    @Autowired ConversationEngine engine;
    @Autowired ContactLanguage languages;
    @Autowired ObjectMapper json;
    @Autowired MeterRegistry meters;
    @Autowired OutputGuard guard;

    /** tenant: the clinic whose staff this doctor is. The fake Core says so on every profile row (S24 fix: without it the two clinics'
     * profiles in C16-E01d are identical about the shared doctor, and nothing can tell whose he is); null = a Core that does not say. */
    record Doc(UUID id, String name, UUID tenant) {
    }

    record Clinic(UUID tenant, String name, List<Doc> doctors, String hash) {
    }

    private Clinic clinic(String name, String... doctorNames) {
        UUID t = UUID.randomUUID();
        List<Doc> docs = new ArrayList<>();
        for (int i = 0; i < doctorNames.length; i++) {
            Doc d = new Doc(UUID.randomUUID(), doctorNames[i], t);
            docs.add(d);
            UP.rawSessions.put(d.id().toString(), sessions(i));
        }
        profile(t, name, docs);
        String hash = "s24qa-" + UUID.randomUUID();
        UP.households.put(hash, new CopyOnWriteArrayList<>(List.of(FakeUpstreams.member("ref-me", "Asha", 36, "self"))));
        languages.save(t, "whatsapp", hash, "en");
        return new Clinic(t, name, docs, hash);
    }

    private void profile(UUID t, String name, List<Doc> docs) {
        StringBuilder list = new StringBuilder();
        for (Doc d : docs) {
            list.append(list.isEmpty() ? "" : ",").append("{\"doctorId\":\"").append(d.id()).append("\",\"name\":\"").append(d.name()).append('"')
                    .append(d.tenant() == null ? "" : ",\"tenantId\":\"" + d.tenant() + "\"").append('}');
        }
        UP.profiles.put(t.toString(), "{\"clinicName\":\"" + name + "\",\"timeZone\":\"" + ZONE + "\",\"doctors\":[" + list + "]}");
    }

    private static String sessions(int minute) {
        List<String> out = new ArrayList<>();
        for (int day = 0; day < 3; day++) {
            List<String> s = new ArrayList<>();
            for (int i = 0; i < 4; i++) {
                s.add("\"" + BASE.plus(day, ChronoUnit.DAYS).plus(20L * i + minute, ChronoUnit.MINUTES) + "\"");
            }
            LocalDate date = BASE.plus(day, ChronoUnit.DAYS).atZone(ZoneId.of(ZONE)).toLocalDate();
            out.add("{\"id\":\"s" + day + "\",\"date\":\"" + date + "\",\"mode\":\"slots\",\"slots\":[" + String.join(",", s) + "]}");
        }
        return "[" + String.join(",", out) + "]";
    }

    private void pkg(Clinic c, UUID instance, Doc... eligible) {
        StringBuilder ids = new StringBuilder();
        for (Doc d : eligible) {
            ids.append(ids.isEmpty() ? "" : ",").append('"').append(d.id()).append('"');
        }
        UP.packageSessions.put(c.hash(), "[{\"patientRef\":\"ref-me\",\"instanceId\":\"" + instance + "\",\"nextSession\":3,\"totalSessions\":6,\"eligibleDoctorIds\":[" + ids + "]}]");
    }

    private void say(Clinic c, String text) {
        String id = FakeUpstreams.id();
        UP.inbound(id, c.hash(), text, null);
        engine.process(c.tenant(), id, "whatsapp", c.hash());
    }

    private List<JsonNode> sentTo(Clinic c) {
        return UP.sent.stream().map(s -> json.readTree(s)).filter(n -> c.hash().equals(n.path("contactHash").asString(""))).toList();
    }

    private JsonNode last(Clinic c) {
        List<JsonNode> got = sentTo(c);
        return got.get(got.size() - 1);
    }

    private static List<JsonNode> options(JsonNode m) {
        List<JsonNode> o = new ArrayList<>();
        (m.has("rows") ? m.path("rows") : m.path("buttons")).forEach(o::add);
        return o;
    }

    private List<String> titles(Clinic c) {
        return options(last(c)).stream().map(o -> o.path("title").asString()).toList();
    }

    private void tap(Clinic c, String title) {
        List<String> t = titles(c);
        int i = t.indexOf(title);
        assertTrue(i >= 0, "no option titled " + title + " in " + t);
        tapAt(c, i);
    }

    private void tapAt(Clinic c, int i) {
        String id = FakeUpstreams.id();
        UP.inbound(id, c.hash(), "", options(last(c)).get(i).path("id").asString());
        engine.process(c.tenant(), id, "whatsapp", c.hash());
    }

    private String state(Clinic c) {
        return superuser().queryForObject("select state from conversations where tenant_id = ?::uuid and contact_hash = ?", String.class, c.tenant().toString(), c.hash());
    }

    private double blocked() {
        return meters.find("edge_output_guard_blocked_total").counters().stream().mapToDouble(x -> x.count()).sum();
    }

    /** Everything this conversation has been sent, lower case. */
    private String shown(Clinic c) {
        return sentTo(c).toString().toLowerCase();
    }

    private static boolean leaks(String shown, Clinic other) {
        if (shown.contains(other.name().toLowerCase()) || shown.contains(other.tenant().toString())) {
            return true;
        }
        return other.doctors().stream().anyMatch(d -> shown.contains(d.id().toString()) || shown.contains(d.name().toLowerCase()));
    }

    private void serve(Clinic c) { // this replica learns what c owns (every booking start reads the profile)
        say(c, "Hi");
    }

    // C16-E01a: Core's package answer lists clinic B's doctor next to A's own: only A's doctor is offered, nothing of B is shown
    @Test
    void c16e01a_aForeignDoctorInEligibleDoctorIdsIsNeverOffered() {
        Clinic b = clinic("Qa Banyan Kids", "Dr Qa Bhatt");
        serve(b);
        Clinic a = clinic("Qa Alder Skin", "Dr Qa Anand", "Dr Qa Arora");
        UUID instance = UUID.randomUUID();
        pkg(a, instance, b.doctors().get(0), a.doctors().get(1));
        double before = blocked();
        say(a, "Hi");
        tap(a, "Book");
        tap(a, "Me");
        assertEquals(List.of("Book session 3 of 6", "A regular visit"), titles(a));
        tap(a, "Book session 3 of 6");
        String afterPkg = last(a).path("text").asString();
        tapAt(a, 0);
        tapAt(a, 0);
        String card = last(a).path("text").asString();
        tap(a, "Confirm");
        String call = UP.bookCalls.get(UP.bookCalls.size() - 1);
        boolean leak = leaks(shown(a), b);
        System.out.println("C16-E01a afterPkg='" + afterPkg + "' card='" + card + "' leakOfB=" + leak + " guardFired=" + (blocked() - before)
                + " bookedDoctorIsA2=" + call.contains(a.doctors().get(1).id().toString()) + " pkgSent=" + call.contains(instance.toString()));
        assertTrue(afterPkg.contains("Which day"), "one eligible doctor of this clinic: doctor step skipped");
        assertTrue(card.contains("Dr Qa Arora"), card);
        assertFalse(leak, "nothing of clinic B reached clinic A's patient");
        assertTrue(call.contains("\"doctorId\":\"" + a.doctors().get(1).id() + "\"") && call.contains("\"packageInstanceId\":\"" + instance + "\""), call);
    }

    // C16-E01b: every eligible doctor is another clinic's: no foreign content, hand-off, and Core is never asked to book
    @Test
    void c16e01b_onlyForeignEligibleDoctorsHandOffWithoutShowingThem() {
        Clinic b = clinic("Qa Birch Eye", "Dr Qa Bose");
        serve(b);
        Clinic a = clinic("Qa Aspen Care", "Dr Qa Ahuja", "Dr Qa Atre");
        pkg(a, UUID.randomUUID(), b.doctors().get(0));
        int calls = UP.bookCalls.size();
        say(a, "Hi");
        tap(a, "Book");
        tap(a, "Me");
        tap(a, "Book session 3 of 6");
        String text = last(a).path("text").asString();
        System.out.println("C16-E01b reply='" + text + "' state=" + state(a) + " leakOfB=" + leaks(shown(a), b) + " bookCalls+=" + (UP.bookCalls.size() - calls));
        assertFalse(leaks(shown(a), b));
        assertEquals(calls, UP.bookCalls.size());
    }

    // C16-E01c: Core is wrong twice (A's profile lists B's doctor AND the package names him): the guard stops it before any booking
    @Test
    void c16e01c_aForeignDoctorInBothProfileAndPackageIsStoppedByTheGuard() {
        Clinic b = clinic("Qa Cedar Dental", "Dr Qa Chawla");
        serve(b);
        Clinic a = clinic("Qa Cypress Clinic", "Dr Qa Chopra");
        List<Doc> wrong = new ArrayList<>(a.doctors());
        wrong.add(b.doctors().get(0));
        profile(a.tenant(), a.name(), wrong);
        pkg(a, UUID.randomUUID(), b.doctors().get(0));
        int calls = UP.bookCalls.size();
        double before = blocked();
        say(a, "Hi");
        tap(a, "Book");
        tap(a, "Me");
        tap(a, "Book session 3 of 6");
        List<String> steps = new ArrayList<>();
        steps.add(last(a).path("text").asString());
        for (int i = 0; i < 2 && !"HANDOFF".equals(state(a)) && !options(last(a)).isEmpty(); i++) {
            tapAt(a, 0);
            steps.add(last(a).path("text").asString());
        }
        boolean leakOfName = sentTo(a).toString().toLowerCase().contains(b.doctors().get(0).name().toLowerCase());
        System.out.println("C16-E01c steps=" + steps + " state=" + state(a) + " guardFired=" + (blocked() - before) + " foreignNameShown=" + leakOfName
                + " bookCalls+=" + (UP.bookCalls.size() - calls));
        assertEquals("HANDOFF", state(a));
        assertTrue(steps.contains("Let me connect you to the clinic."), steps.toString());
        assertFalse(leakOfName, "clinic B's doctor name never reached A's patient");
        assertEquals(calls, UP.bookCalls.size(), "no booking with another clinic's doctor");
    }

    // C16-E01d: the same leak, but this replica reads the leaking profile of A BEFORE it ever serves B (restart, new replica)
    @Test
    void c16e01d_theGuardStopsTheLeakWhicheverClinicWasServedFirst() {
        Clinic b = clinic("Qa Deodar Kids", "Dr Qa Dutta", "Dr Qa Dhar");
        Clinic a = clinic("Qa Dogwood Skin", "Dr Qa Desai");
        List<Doc> wrong = new ArrayList<>(a.doctors());
        wrong.add(b.doctors().get(0));
        profile(a.tenant(), a.name(), wrong);
        double before = blocked();
        serve(a);   // A first: the replica learns B's doctor as A's
        serve(b);   // then B
        // A's patient: the doctor list would show B's doctor
        say(a, "Hi");
        tap(a, "Book");
        tap(a, "Me");
        boolean aLeaked = titles(a).contains("Dr Qa Dutta");
        double afterA = blocked();
        // B's own patient picks B's own doctor
        say(b, "Hi");
        tap(b, "Book");
        tap(b, "Me");
        String bReply = last(b).path("text").asString();
        double afterB = blocked();
        System.out.println("C16-E01d aOptions=" + (aLeaked ? titles(a) : "[blocked]") + " aLeakedForeignDoctor=" + aLeaked + " guardOnA=" + (afterA - before)
                + " bReply='" + bReply + "' bState=" + state(b) + " guardOnB=" + (afterB - afterA));
        assertFalse(aLeaked, "clinic A's patient must not be offered clinic B's doctor");
        assertFalse(bReply.contains("Let me connect you"), "clinic B's own patient must not be blocked for B's own doctor");
    }

    // C16-E01e (S24 fix): the package step lands on a cold instance that has never read clinic B at all: still stopped, and B is untouched
    @Test
    void c16e01e_aColdInstanceThatNeverServedTheOtherClinicStopsThePackageLeak() {
        Clinic b = clinic("Qa Fig Dental", "Dr Qa Farooqui"); // never served before the leak
        Clinic a = clinic("Qa Fern Clinic", "Dr Qa Fernandes");
        List<Doc> wrong = new ArrayList<>(a.doctors());
        wrong.add(b.doctors().get(0));
        profile(a.tenant(), a.name(), wrong);
        pkg(a, UUID.randomUUID(), b.doctors().get(0));
        guard.forget(); // restart
        int calls = UP.bookCalls.size();
        double before = blocked();
        say(a, "Hi");
        tap(a, "Book");
        tap(a, "Me");
        assertEquals(List.of("Book session 3 of 6", "A regular visit"), titles(a));
        guard.forget(); // the next step is taken by a new replica
        tap(a, "Book session 3 of 6");
        assertEquals("Let me connect you to the clinic.", last(a).path("text").asString());
        assertEquals("HANDOFF", state(a));
        assertEquals(before + 1, blocked(), "alert raised");
        assertFalse(leaks(shown(a), b), "nothing of clinic B reached clinic A's patient");
        assertEquals(calls, UP.bookCalls.size(), "no booking with another clinic's doctor");
        say(b, "Hi");
        tap(b, "Book");
        tap(b, "Me");
        assertTrue(last(b).path("text").asString().contains("Which day"), "clinic B's own patient goes on with B's own doctor: " + last(b));
        assertEquals(before + 1, blocked(), "the guard did not fire on clinic B");
    }

    // C16-E01f (S24 fix): a doctor who works at two clinics (the same id and name in both profiles) is booked from both
    @Test
    void c16e01f_aDoctorOfTwoClinicsIsBookedFromBoth() {
        Clinic a = clinic("Qa Gulmohar Skin", "Dr Qa Gupta");
        Clinic b = clinic("Qa Guava Kids", "Dr Qa Ghosh");
        UUID both = UUID.randomUUID();
        UP.rawSessions.put(both.toString(), sessions(7));
        List<Doc> da = new ArrayList<>(a.doctors()), db = new ArrayList<>(b.doctors());
        da.add(new Doc(both, "Dr Qa Gill", a.tenant())); // Core says: staff of A
        db.add(new Doc(both, "Dr Qa Gill", null));       // B's Core does not say whose row it is
        profile(a.tenant(), a.name(), da);
        profile(b.tenant(), b.name(), db);
        double before = blocked();
        serve(b);
        serve(a);
        for (Clinic c : List.of(a, b)) {
            say(c, "Hi");
            tap(c, "Book");
            tap(c, "Me");
            tap(c, "Dr Qa Gill");
            tapAt(c, 0);
            tapAt(c, c == a ? 0 : 1); // a different time in each clinic
            assertTrue(last(c).path("text").asString().contains("Dr Qa Gill"), last(c).toString());
            tap(c, "Confirm");
            assertTrue(UP.bookCalls.get(UP.bookCalls.size() - 1).contains("\"doctorId\":\"" + both + "\""));
            assertFalse("HANDOFF".equals(state(c)), "not handed to reception");
        }
        assertEquals(before, blocked(), "the guard never fired for the shared doctor");
    }

    // C16-E02g (round 2, record only): TODAY's Core sends no tenantId on profile rows (InternalBookingProfileController.Doctor = doctorId, name).
    // Clinic B is served first, then A's profile wrongly lists B's doctor: what A's patient sees on the doctor list and on the package path.
    // Disclosed decision (s24-fix-guard.md), so nothing is asserted about block or pass: the behaviour is printed for the report.
    @Test
    void c16e02g_todaysCoreShapeWithoutTenantIdOnProfileRows_recorded() {
        Clinic b = clinic("Qa Hazel Dental", "Dr Qa Hegde");
        Clinic a = clinic("Qa Holly Clinic", "Dr Qa Hora");
        List<Doc> honestB = b.doctors().stream().map(d -> new Doc(d.id(), d.name(), null)).toList();
        List<Doc> wrongA = new ArrayList<>(a.doctors().stream().map(d -> new Doc(d.id(), d.name(), null)).toList());
        wrongA.add(honestB.get(0));
        profile(b.tenant(), b.name(), honestB);
        profile(a.tenant(), a.name(), wrongA);
        guard.forget();
        serve(b); // the true owner is known to this instance before the leak (the case e10's guard caught on the doctor list)
        double before = blocked();
        say(a, "Hi");
        tap(a, "Book");
        tap(a, "Me");
        List<String> list = "HANDOFF".equals(state(a)) ? List.of() : titles(a);
        boolean listLeak = list.contains("Dr Qa Hegde");
        double afterList = blocked();
        // the package path, a fresh patient of A whose package names B's doctor as the only eligible one
        Clinic a2 = new Clinic(a.tenant(), a.name(), a.doctors(), "s24qa-" + UUID.randomUUID());
        UP.households.put(a2.hash(), new CopyOnWriteArrayList<>(List.of(FakeUpstreams.member("ref-me", "Asha", 36, "self"))));
        languages.save(a2.tenant(), "whatsapp", a2.hash(), "en");
        pkg(a2, UUID.randomUUID(), honestB.get(0));
        say(a2, "Hi");
        tap(a2, "Book");
        tap(a2, "Me");
        tap(a2, "Book session 3 of 6");
        List<String> steps = new ArrayList<>();
        steps.add(last(a2).path("text").asString());
        for (int i = 0; i < 2 && !"HANDOFF".equals(state(a2)) && !options(last(a2)).isEmpty(); i++) {
            tapAt(a2, 0);
            steps.add(last(a2).path("text").asString().replace('\n', ' '));
        }
        boolean pkgLeak = sentTo(a2).toString().toLowerCase().contains("dr qa hegde");
        System.out.println("C16-E02g noTenantIdOnRows doctorList=" + list + " listShowsForeignDoctor=" + listLeak + " guardOnList=" + (afterList - before)
                + " | packagePath steps=" + steps + " state=" + state(a2) + " foreignNameShown=" + pkgLeak + " guardOnPackage=" + (blocked() - afterList));
        assertTrue(state(a) != null && state(a2) != null, "no crash");
    }

    // C44-E01b: the package is used up between the offer and the tap: no package is sent to Core, the regular choice follows
    @Test
    void c44e01b_aPackageUsedUpAfterTheOfferIsNotBooked() {
        Clinic a = clinic("Qa Elm Physio", "Dr Qa Ein", "Dr Qa Eapen");
        UUID instance = UUID.randomUUID();
        pkg(a, instance, a.doctors().get(0));
        say(a, "Hi");
        tap(a, "Book");
        tap(a, "Me");
        UP.packageSessions.put(a.hash(), "[]"); // last session redeemed at the desk meanwhile
        tap(a, "Book session 3 of 6");
        List<String> t = titles(a);
        System.out.println("C44-E01b after used-up tap options=" + t);
        assertEquals(List.of("Dr Qa Ein", "Dr Qa Eapen"), t, "the regular doctor list (both doctors), not a package booking");
        tap(a, "Dr Qa Eapen");
        tapAt(a, 0);
        tapAt(a, 0);
        tap(a, "Confirm");
        String call = UP.bookCalls.get(UP.bookCalls.size() - 1);
        assertFalse(call.contains(instance.toString()), call);
    }
}
