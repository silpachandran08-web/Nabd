package com.nabd.edge;

import com.nabd.edge.FlowHandler.Conversation;
import com.nabd.edge.FlowHandler.Outcome;
import com.nabd.edge.FlowHandler.State;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
 * alert is raised (edge_output_guard_blocked_total, alert EdgeCrossTenantReplyBlocked). Ids are globally unique, so an id belongs to
 * the first clinic it was learned for (a later profile listing it does not make it that clinic's). A name the clinic itself also uses
 * (two clinics with the same doctor name or the same clinic name) is not foreign, and neither is a name made of the bot's own words
 * (a clinic called "Clinic" must not block "Talk to clinic").
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

    private record Owned(Set<String> ids, Set<String> names) {
    }

    // shortcut: learned in memory from the booking profiles this replica reads (every booking start reads one), so a clinic this
    // replica has never served is not yet known; upgrade to a Core directory feed if a cross-replica leak path ever appears.
    private final Map<UUID, Owned> known = new ConcurrentHashMap<>();
    private final MeterRegistry meters;

    OutputGuard(MeterRegistry meters) {
        this.meters = meters;
    }

    void learn(UUID tenantId, CoreClient.Profile p) {
        Set<String> ids = new HashSet<>(), names = new HashSet<>();
        ids.add(tenantId.toString());
        name(names, p.clinicName());
        for (CoreClient.Doctor d : p.doctors()) {
            String id = d.id().toString();
            if (known.entrySet().stream().noneMatch(e -> !e.getKey().equals(tenantId) && e.getValue().ids().contains(id))) {
                ids.add(id);
            }
            name(names, d.name());
        }
        known.put(tenantId, new Owned(Set.copyOf(ids), Set.copyOf(names)));
    }

    /** The outcome unchanged, or the fallback hand-off when the reply would show another clinic or offer discovery. */
    Outcome check(Conversation c, Outcome o) {
        if (o.reply() == null && o.buttons().isEmpty()) {
            return o;
        }
        StringBuilder all = new StringBuilder(o.reply() == null ? "" : o.reply());
        boolean discovery = false;
        for (FlowHandler.Button b : o.buttons()) {
            all.append('\n').append(b.title()).append('\n').append(b.action());
            discovery |= b.action() != null && b.action().toLowerCase(Locale.ROOT).startsWith(DISCOVERY_PREFIX);
        }
        Optional<UUID> foreign = foreignIn(c.tenantId(), all.toString());
        if (foreign.isEmpty() && !discovery) {
            return o;
        }
        meters.counter("edge_output_guard_blocked_total", "reason", discovery ? "discovery" : "foreign_tenant").increment();
        // ids only: no reply text, no contact
        log.error("output guard blocked a reply in tenant {} conversation {}: {}", c.tenantId(), c.id(),
                foreign.map(t -> "carries content of tenant " + t).orElse("offers discovery in clinic context"));
        Map<String, String> ctx = new HashMap<>();
        String lang = c.context().get("lang");
        if (lang != null) {
            ctx.put("lang", lang);
        }
        return new Outcome(State.HANDOFF, ctx, BookingCopy.t(lang, "guard.fallback"));
    }

    /** The first other tenant whose id or name appears in the text. */
    Optional<UUID> foreignIn(UUID tenantId, String text) {
        String t = text.toLowerCase(Locale.ROOT);
        Owned own = known.getOrDefault(tenantId, new Owned(Set.of(tenantId.toString()), Set.of()));
        for (Map.Entry<UUID, Owned> e : known.entrySet()) {
            if (e.getKey().equals(tenantId)) {
                continue;
            }
            for (String id : e.getValue().ids()) {
                if (t.contains(id)) {
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
