package com.nabd.edge;

import com.nabd.edge.FlowHandler.Conversation;
import com.nabd.edge.FlowHandler.Outcome;
import com.nabd.edge.FlowHandler.State;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * COM-016.2: the last check before a bot reply is committed and sent. A reply in a clinic's conversation that carries another
 * tenant's id, one of its doctors' ids, its clinic name or one of its doctors' names (text, button titles or button actions), or
 * that offers clinic discovery, is replaced by "Let me connect you to the clinic", the thread goes to reception (HANDOFF) and an
 * alert is raised (edge_output_guard_blocked_total, alert EdgeCrossTenantReplyBlocked).
 * What a clinic owns is decided from the conversation's own clinic only (T1-BUG-5/6): the booking profile Core serves for that tenant,
 * read through ClinicContent. Never from which clinic an instance happened to serve first. A doctor the reply offers or has chosen
 * (a "doc:" option, or the "doctorId" whose days and slots it shows) must be in that profile, and a profile row that Core itself marks
 * as another clinic's staff (tenantId) is foreign. A doctor in two clinics' profiles is each clinic's own. A name the clinic itself
 * also uses (two clinics with the same doctor name or the same clinic name) is not foreign, and neither is a name made of the bot's
 * own words (a clinic called "Clinic" must not block "Talk to clinic").
 */
@Component
public class OutputGuard {

    /** Discovery of other clinics (T-369, Phase 3) belongs to the shared Nabd number with no clinic context only. */
    static final String DISCOVERY_PREFIX = "discover";
    private static final int MIN_NAME = 3;
    private static final Logger log = LoggerFactory.getLogger(OutputGuard.class);
    /** Every bot sentence in every language, lower case: a name found in it is a common word, not a clinic's mark. */
    private static final String COPY = BookingCopy.LANGUAGES.stream().flatMap(l -> BookingCopy.load(l).values().stream())
            .map(v -> v.toString().toLowerCase(Locale.ROOT)).collect(java.util.stream.Collectors.joining("\n"));

    /** One clinic's latest profile: its own ids and names, and the rows Core marks as another clinic's staff (id or name -> that clinic). */
    private record Owned(Set<String> ids, Set<String> names, Map<String, UUID> foreignIds, Map<String, UUID> foreignNames) {
    }

    // The latest profile read per clinic, replaced on every read; ownership never depends on the order of reads.
    // shortcut: another clinic's NAME or id in free text is known only for clinics this instance has read a profile of (doctors
    // offered or chosen need no such knowledge); upgrade to a Core directory feed if a free-text leak path ever appears.
    private final Map<UUID, Owned> known = new ConcurrentHashMap<>();
    private final MeterRegistry meters;
    private final ObjectProvider<ClinicContent.Provider> contents; // resolved on use: the provider itself feeds learn()

    @Autowired
    OutputGuard(MeterRegistry meters, ObjectProvider<ClinicContent.Provider> contents) {
        this.meters = meters;
        this.contents = contents;
    }

    OutputGuard(MeterRegistry meters) {
        this(meters, null);
    }

    void learn(UUID tenantId, CoreClient.Profile p) {
        Set<String> ids = new HashSet<>(), names = new HashSet<>();
        Map<String, UUID> foreignIds = new HashMap<>(), foreignNames = new HashMap<>();
        ids.add(tenantId.toString());
        name(names, p.clinicName());
        for (CoreClient.Doctor d : p.doctors()) {
            if (d.tenantId() == null || d.tenantId().equals(tenantId)) {
                ids.add(d.id().toString());
                name(names, d.name());
            } else { // Core says this row is another clinic's staff: never this clinic's, whichever profile was read first
                foreignIds.put(d.id().toString(), d.tenantId());
                Set<String> n = new HashSet<>();
                name(n, d.name());
                n.forEach(x -> foreignNames.put(x, d.tenantId()));
            }
        }
        ids.removeAll(foreignIds.keySet());
        foreignNames.keySet().removeAll(names); // a name the clinic also uses itself is not foreign
        known.put(tenantId, new Owned(Set.copyOf(ids), Set.copyOf(names), Map.copyOf(foreignIds), Map.copyOf(foreignNames)));
    }

    /** A cold instance: nothing read yet (restart, new replica). */
    void forget() {
        known.clear();
    }

    /** True when every doctor the reply offers or has chosen is in the conversation's own clinic profile (read again before saying no). */
    private boolean ownDoctors(Conversation c, Set<String> doctors) {
        Owned own = known.get(c.tenantId());
        if ((own == null || !own.ids().containsAll(doctors)) && contents != null) {
            contents.getObject().of(c).profile(); // teaches learn() what Core serves for this clinic now
            own = known.get(c.tenantId());
        }
        return own != null && own.ids().containsAll(doctors);
    }

    /** The outcome unchanged, or the fallback hand-off when the reply would show another clinic or offer discovery. */
    Outcome check(Conversation c, Outcome o) {
        if (o.reply() == null && o.buttons().isEmpty()) {
            return o;
        }
        StringBuilder all = new StringBuilder(o.reply() == null ? "" : o.reply());
        boolean discovery = false;
        Set<String> doctors = new HashSet<>(); // offered ("doc:" options) or chosen (the package path skips the list: the id is only in the context)
        if (o.context() != null && o.context().get("doctorId") != null) {
            doctors.add(o.context().get("doctorId").toLowerCase(Locale.ROOT));
        }
        for (FlowHandler.Button b : o.buttons()) {
            all.append('\n').append(b.title()).append('\n').append(b.action());
            discovery |= b.action() != null && b.action().toLowerCase(Locale.ROOT).startsWith(DISCOVERY_PREFIX);
            if (b.action() != null && b.action().startsWith("doc:")) {
                doctors.add(b.action().substring(4).toLowerCase(Locale.ROOT));
            }
        }
        boolean notOurs = !doctors.isEmpty() && !ownDoctors(c, doctors);
        Optional<UUID> foreign = foreignIn(c.tenantId(), all.toString());
        if (foreign.isEmpty() && !notOurs && !discovery) {
            return o;
        }
        meters.counter("edge_output_guard_blocked_total", "reason", discovery ? "discovery" : "foreign_tenant").increment();
        // ids only: no reply text, no contact
        log.error("output guard blocked a reply in tenant {} conversation {}: {}", c.tenantId(), c.id(),
                foreign.map(t -> "carries content of tenant " + t).orElse(notOurs ? "names a doctor that is not in this clinic's profile" : "offers discovery in clinic context"));
        Map<String, String> ctx = new HashMap<>();
        String lang = c.context().get("lang");
        if (lang != null) {
            ctx.put("lang", lang);
        }
        return new Outcome(State.HANDOFF, ctx, BookingCopy.t(lang, "guard.fallback"));
    }

    /** The first other tenant whose id or name appears in the text (a row Core marks as another clinic's staff first). */
    Optional<UUID> foreignIn(UUID tenantId, String text) {
        String t = text.toLowerCase(Locale.ROOT);
        Owned own = known.getOrDefault(tenantId, new Owned(Set.of(tenantId.toString()), Set.of(), Map.of(), Map.of()));
        for (Map.Entry<String, UUID> e : own.foreignIds().entrySet()) {
            if (t.contains(e.getKey())) {
                return Optional.of(e.getValue());
            }
        }
        for (Map.Entry<String, UUID> e : own.foreignNames().entrySet()) {
            if (containsWord(t, e.getKey())) {
                return Optional.of(e.getValue());
            }
        }
        for (Map.Entry<UUID, Owned> e : known.entrySet()) {
            if (e.getKey().equals(tenantId)) {
                continue;
            }
            for (String id : e.getValue().ids()) {
                if (!own.ids().contains(id) && t.contains(id)) { // a doctor in both profiles is this clinic's own too
                    return Optional.of(e.getKey());
                }
            }
            for (String n : e.getValue().names()) {
                if (!own.names().contains(n) && containsWord(t, n)) {
                    return Optional.of(e.getKey());
                }
            }
        }
        return Optional.empty();
    }

    private static void name(Set<String> names, String n) {
        String s = n == null ? "" : n.strip().toLowerCase(Locale.ROOT);
        if (s.length() >= MIN_NAME && !containsWord(COPY, s)) {
            names.add(s);
        }
    }

    /** needle as a whole word or phrase: "Alpha" is not found in "Alphabet". */
    static boolean containsWord(String hay, String needle) {
        for (int i = hay.indexOf(needle); i >= 0; i = hay.indexOf(needle, i + 1)) {
            int end = i + needle.length();
            if ((i == 0 || !Character.isLetterOrDigit(hay.charAt(i - 1))) && (end == hay.length() || !Character.isLetterOrDigit(hay.charAt(end)))) {
                return true;
            }
        }
        return false;
    }
}
